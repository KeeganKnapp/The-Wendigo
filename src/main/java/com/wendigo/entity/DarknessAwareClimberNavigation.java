package com.wendigo.entity;

import com.nyfaria.awcapi.entity.Orientation;
import com.nyfaria.awcapi.entity.movement.AdvancedPathFinder;
import com.nyfaria.awcapi.entity.movement.ClimberPathNavigator;
import com.nyfaria.awcapi.entity.movement.DirectionalPathPoint;

import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.Vec3;

/**
 * Wall/ceiling-aware navigation for WendigoEntity - the wendigo's only navigation, used by every
 * movement primitive (see PlanRunner). Built on AWCAPI's own ClimberPathNavigator, constructed
 * exactly the way Nyf's Spiders' reference implementation does it (verified via javap -c against
 * its SpiderMixin: {@code new ClimberPathNavigator(mob, level, false)} then
 * {@code setCanFloat(true)}), with DarknessAwareClimberNodeEvaluator swapped in so the same
 * light-cost malus the wendigo's pathfinding has always applied still holds once wall/ceiling
 * routes are possible too.
 *
 * <p>{@link #createAdvancedPathFinder} is the real override point, not createPathFinder -
 * AdvancedGroundPathNavigator's own createPathFinder(int) is {@code protected final} (verified via
 * javap) and just delegates to this, re-deriving its inherited nodeEvaluator field from whatever
 * AdvancedPathFinder this method returns.
 */
public class DarknessAwareClimberNavigation extends ClimberPathNavigator<WendigoEntity> {
	private DarknessAwareClimberNodeEvaluator darknessNodeEvaluator;

	public DarknessAwareClimberNavigation(WendigoEntity mob, Level level) {
		super(mob, level, false);
		this.setCanFloat(true);
		// See DarknessAwareNavigation's old equivalent comment: GroundPathNavigation.createPath
		// silently redirects any target sitting under a solid ceiling to a position above it
		// (vanilla's anti-tunneling logic for surface mobs) unless this is set - inherited from the
		// same GroundPathNavigation ancestor, still needed for a mob that lives in caves.
		this.setCanPathToTargetsBelowSurface(true);
	}

	@Override
	protected AdvancedPathFinder createAdvancedPathFinder(int maxVisitedNodes) {
		this.darknessNodeEvaluator = new DarknessAwareClimberNodeEvaluator();
		this.darknessNodeEvaluator.setCanPassDoors(true);
		this.darknessNodeEvaluator.setCanPathWalls(true);
		this.darknessNodeEvaluator.setCanPathCeiling(true);
		return new AdvancedPathFinder(this.darknessNodeEvaluator, maxVisitedNodes);
	}

	/** See DarknessMalus.lightTolerant - lets the two into-the-light primitives opt out. */
	public void setLightTolerant(boolean lightTolerant) {
		this.darknessNodeEvaluator.setLightTolerant(lightTolerant);
	}

	/** See DarknessMalus.severityPercent/HARD_BLOCK_MAX_PERCENT. */
	public void setSeverityPercent(int severityPercent) {
		this.darknessNodeEvaluator.setSeverityPercent(severityPercent);
	}

	/**
	 * Real bug found via javap against ClimberPathNavigator.moveTo(Entity, double) (used by every
	 * chase/lunge/approach primitive that tracks a Player directly rather than a fixed BlockPos - see
	 * PlanRunner's own moveTo(target, speed) call sites): when its own createPath(entity, 0) attempt
	 * comes back null, it does NOT fail - it stores the raw target into a private targetPosition
	 * field and unconditionally RETURNS TRUE, claiming navigation started. That fallback is only ever
	 * acted on by ClimberPathNavigator's own tick() while useVanillaBehaviour is true (confirmed via
	 * javap on both the constructor - our third arg, false, is stored straight into that field - and
	 * tick() itself, which gates the whole branch on it) - which this navigation is NOT constructed
	 * with (see class doc: {@code super(mob, level, false)}, matching the reference SpiderMixin
	 * implementation). The net effect: on whatever fraction of calls hit that null-path case, the
	 * wendigo silently never receives a real path to follow at all, PlanRunner reads the true return
	 * value and thinks movement started fine, and WendigoDebug's own path-particle trail keeps
	 * drawing whatever real Path object was already sitting in getPath() from the PREVIOUS
	 * successful move (that dead branch never touches the path field either) - exactly the reported
	 * "path is visible but he never starts moving" symptom, and exactly why it only happens
	 * sometimes, not always. Overridden here to call createPath ourselves and, on the same null
	 * result, fall back to the coordinate-based moveTo (which goes through the real moveTo(Path,
	 * double) pipeline, no useVanillaBehaviour gate involved) instead of AWCAPI's dead branch -
	 * everything else (the common, real-path case) behaves identically to the un-overridden method,
	 * same first call either way.
	 */
	@Override
	public boolean moveTo(Entity entity, double speedModifier) {
		Path path = this.createPath(entity, 0);
		if (path != null) {
			return this.moveTo(path, speedModifier);
		}
		return this.moveTo(entity.getX(), entity.getY(), entity.getZ(), speedModifier);
	}

	/** Root-caused via bytecode diff against Stormie's Spiders' own vendored fork of this exact
	 * class (win.demistorm.stormiespiders...AdvancedClimberPathNavigator, decompiled with
	 * Vineflower - a direct fork of Nyf's Spiders/AWCAPI, not a from-scratch implementation): AWCAPI
	 * 1.0.1's own followThePath() (confirmed via decompile - this override is a byte-for-byte copy
	 * of it, see the loop below) can permanently deadlock the SAME node index. Its lookahead loop
	 * only ever advances nextNodeIndex when isOnSameSideAsTarget is true - the entity's CURRENT
	 * getGroundDirection() already matching the target node's own attachment side - but right at a
	 * wall-to-ceiling (or any cross-surface) transition, the entity can't be on the new side without
	 * first moving there, and can't move there without the index advancing: a genuine chicken-and-egg
	 * deadlock, matching this session's own live PATH_NODES captures (a healthy 37-node path stalls
	 * mid-traversal, and the NEXT repath - a brand new search from that same stuck position - comes
	 * back a degenerate single node with empty pathableSides). Stormie's own published changelog logs
	 * fixing "spiders randomly pausing while in chase" and "stuck detection is now more robust" -
	 * both a direct match for what's captured here.
	 * <p>
	 * The fix ported from their fork (their AdvancedClimberPathNavigator.followThePath(), same
	 * shape): if the main lookahead loop above ends without advancing the index at all, and the
	 * entity is nonetheless already within ordinary arrival distance of the CURRENT (not lookahead)
	 * node, force the index forward anyway - bypassing the same-side check that deadlocked it. Still
	 * gated on genuine physical proximity (maxDistanceToWaypoint, the same tolerance the main loop
	 * itself uses), not a blind skip - this only breaks the deadlock once the entity has actually
	 * arrived where the stuck node says it should be. */
	@Override
	protected void followThePath() {
		Vec3 pos = this.getTempMobPos();
		this.maxDistanceToWaypoint = this.mob.getBbWidth() > 0.75F ? this.mob.getBbWidth() / 2.0F
			: 0.75F - this.mob.getBbWidth() / 2.0F;
		float maxDistanceToWaypointY = Math.max(1.0F,
			this.mob.getBbHeight() > 0.75F ? this.mob.getBbHeight() / 2.0F : 0.75F - this.mob.getBbHeight() / 2.0F);
		int sizeX = Mth.ceil(this.mob.getBbWidth());
		int sizeY = Mth.ceil(this.mob.getBbHeight());
		int sizeZ = sizeX;
		Orientation orientation = this.climber.getOrientation();
		Vec3 upVector = orientation.getGlobal(this.mob.yRot, -90.0F);
		this.verticalFacing = Direction.getApproximateNearest((float) upVector.x, (float) upVector.y,
			(float) upVector.z);

		int nodeIndexBeforeLoop = this.path.getNextNodeIndex();
		boolean advanced = false;
		for (int i = 4; i >= 0; i--) {
			if (this.path.getNextNodeIndex() + i < this.path.getNodeCount()) {
				Node currentTarget = this.path.getNode(this.path.getNextNodeIndex() + i);
				double dx = Math.abs(currentTarget.x + (int) (this.mob.getBbWidth() + 1.0F) * 0.5F - this.mob.getX());
				double dy = Math.abs(currentTarget.y - this.mob.getY());
				double dz = Math.abs(currentTarget.z + (int) (this.mob.getBbWidth() + 1.0F) * 0.5F - this.mob.getZ());
				boolean isWaypointInReach = dx < this.maxDistanceToWaypoint && dy < maxDistanceToWaypointY
					&& dz < this.maxDistanceToWaypoint;
				boolean isOnSameSideAsTarget;
				if (!this.canFloat()
					|| currentTarget.type != PathType.WATER && currentTarget.type != PathType.WATER_BORDER
						&& currentTarget.type != PathType.LAVA) {
					if (currentTarget instanceof DirectionalPathPoint directionalPoint) {
						Direction targetSide = directionalPoint.getPathSide();
						isOnSameSideAsTarget = targetSide == null
							|| this.climber.getGroundDirection().getLeft() == targetSide;
					} else {
						isOnSameSideAsTarget = true;
					}
				} else {
					isOnSameSideAsTarget = true;
				}

				if (isOnSameSideAsTarget && (isWaypointInReach || i == 0
					&& this.mob.getNavigation().canCutCorner(this.path.getNextNode().type)
					&& this.isNextTargetInLine(pos, sizeX, sizeY, sizeZ, 1 + i))) {
					this.path.setNextNodeIndex(this.path.getNextNodeIndex() + 1 + i);
					advanced = true;
					break;
				}
			}
		}

		if (!advanced && this.path.getNextNodeIndex() == nodeIndexBeforeLoop
			&& nodeIndexBeforeLoop < this.path.getNodeCount() - 1) {
			Node currentNode = this.path.getNode(nodeIndexBeforeLoop);
			double cdx = Math.abs(currentNode.x + (int) (this.mob.getBbWidth() + 1.0F) * 0.5F - this.mob.getX());
			double cdy = Math.abs(currentNode.y - this.mob.getY());
			double cdz = Math.abs(currentNode.z + (int) (this.mob.getBbWidth() + 1.0F) * 0.5F - this.mob.getZ());
			if (cdx < this.maxDistanceToWaypoint && cdy < maxDistanceToWaypointY && cdz < this.maxDistanceToWaypoint) {
				this.path.setNextNodeIndex(nodeIndexBeforeLoop + 1);
			}
		}

		this.doStuckDetection(pos);
	}

	/** Private in AWCAPI's own AdvancedClimberPathNavigator, so followThePath's override above can't
	 * call the inherited one directly - copied unchanged (confirmed via decompile) rather than
	 * reimplemented, since it has no dependency on anything this class needed to change. */
	private boolean isNextTargetInLine(Vec3 pos, int sizeX, int sizeY, int sizeZ, int offset) {
		if (this.path.getNextNodeIndex() + offset >= this.path.getNodeCount()) {
			return false;
		}
		Vec3 currentTarget = Vec3.atBottomCenterOf(this.path.getNextNodePos());
		if (!pos.closerThan(currentTarget, 2.0)) {
			return false;
		}
		Vec3 nextTarget = Vec3.atBottomCenterOf(this.path.getNodePos(this.path.getNextNodeIndex() + offset));
		Vec3 targetDir = nextTarget.subtract(currentTarget);
		Vec3 currentDir = pos.subtract(currentTarget);
		return targetDir.dot(currentDir) > 0.0;
	}
}
