package com.wendigo.entity;

import com.nyfaria.awcapi.entity.movement.AdvancedPathFinder;
import com.nyfaria.awcapi.entity.movement.ClimberPathNavigator;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;

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
}
