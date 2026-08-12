package com.wendigo.entity;

import com.nyfaria.awcapi.entity.movement.AdvancedWalkNodeProcessor;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;

/**
 * AWCAPI's climbing-aware neighbor generation (floor, wall, and ceiling nodes already unified into
 * one graph by the superclass), with the same light/cobweb cost malus the wendigo's ordinary
 * pathfinding always applied layered on top (see DarknessMalus) - so a climbing route still
 * prefers darkness the same way a floor-only route always did. Supersedes the old floor-only
 * DarknessNodeEvaluator/DarknessAwareNavigation pair entirely - this is the wendigo's only
 * navigation now (see WendigoEntity.createNavigation), not a second one it switches into.
 */
public class DarknessAwareClimberNodeEvaluator extends AdvancedWalkNodeProcessor {
	private final DarknessMalus malus = new DarknessMalus();

	/**
	 * Real live bug, root-caused via AWCAPI's own real source (github.com/Nyfaria/
	 * AdvancedWallClimberAPI, com.nyfaria.awcapi.entity.movement.AdvancedWalkNodeProcessor - no
	 * public API for this, so confirmed against the actual GitHub source directly, not guessed from
	 * behavior): {@code alwaysAllowDiagonals} defaults to {@code true} in the superclass, with no
	 * setter exposed at all - which makes {@code allowDiagonalPathOptions} return true unconditionally
	 * for every diagonal candidate, completely skipping its own corner-cutting guard (normally:
	 * only allow a diagonal if at least one of the two straight-line neighbors it cuts between is
	 * actually open, not flatly blocked). The user's own detailed live report matches exactly: the
	 * wendigo would freeze at a mineshaft vertical-shaft plank-brace corner where only ONE of the two
	 * corner-adjacent cells was genuinely open - a node existed there (is3DPathing's own diagonal
	 * check in {@code isSuitablePoint} treats "one side open, one side blocked" as valid on its own,
	 * intentionally, for climbing around a real convex block edge) but nothing actually connected it
	 * to a REACHABLE path, since the guard that would normally reject an illegitimate corner cut
	 * never ran at all. {@code alwaysAllowDiagonals} is {@code protected}, not private, so a subclass
	 * (this one) can set it directly even across packages - restoring the upstream guard without
	 * touching AWCAPI's own jar. The debug spider (also AWCAPI-based) never showed this: it's
	 * floor-only (no wall/ceiling climbing enabled), so it never reaches the is3DPathing vertical-
	 * diagonal branch this bug lives in at all, regardless of alwaysAllowDiagonals' own value.
	 */
	public DarknessAwareClimberNodeEvaluator() {
		this.alwaysAllowDiagonals = false;
	}

	/** See DarknessMalus.lightTolerant. */
	public void setLightTolerant(boolean lightTolerant) {
		this.malus.setLightTolerant(lightTolerant);
	}

	/** See DarknessMalus.severityPercent/HARD_BLOCK_MAX_PERCENT. */
	public void setSeverityPercent(int severityPercent) {
		this.malus.setSeverityPercent(severityPercent);
	}

	@Override
	public void prepare(PathNavigationRegion region, Mob mob) {
		super.prepare(region, mob);
		this.malus.prepare(mob);
	}

	@Override
	public void done() {
		this.malus.done();
		super.done();
	}

	/**
	 * The old DarknessNodeEvaluator's rail-downgrade fix, correctly re-targeted this time - a
	 * follow-up to this class's own earlier note (right above, in an older revision) that flagged
	 * getPathTypeOfMob as the WRONG override point without yet finding the RIGHT one. Confirmed via
	 * AWCAPI's own real GitHub source (same fetch technique as the alwaysAllowDiagonals fix above):
	 * AdvancedWalkNodeProcessor has its OWN equivalent of vanilla WalkNodeEvaluator's rail-downgrade
	 * heuristic (a RAIL tile silently becomes UNPASSABLE_RAIL - malus -1.0F, effectively BLOCKED -
	 * unless the searching mob's own CURRENT position, or the block below it, is ALSO a rail tile),
	 * but it lives inside THIS overload specifically - the one {@code getDirectionalPathNodeTypeCached}
	 * (the real hot path every neighbor lookup in getNeighbors/getSafePoints actually goes through)
	 * calls - not inside getPathTypeOfMob, which AWCAPI routes through a DIFFERENT internal path that
	 * never actually gets hit during ordinary node search. Overriding getPathTypeOfMob (what the old
	 * vanilla-shaped fix did, and what this class's own prior revision correctly avoided repeating
	 * blindly) would have compiled fine and done precisely nothing. packNodeType/unpackNodeType
	 * themselves are package-private in the real source (confirmed via javap - no access modifier),
	 * so their exact bit layout (top 32 bits = PathType.ordinal(), bottom 32 bits = the packed
	 * pathable-sides bitmask, both confirmed against the real source) is replicated here directly
	 * rather than called, since a cross-package subclass can't reach them.
	 * <p>
	 * WIDENED from the first pass (which only un-downgraded UNPASSABLE_RAIL back to RAIL) - the
	 * user's own live "he still gets stuck" follow-up plus explicit "just like air" ask. RAIL and
	 * WALKABLE both already carry the same 0.0F default malus (confirmed via javap against PathType's
	 * own static initializer), so cost was never actually the remaining problem; the gap is that
	 * plenty of OTHER logic in this evaluator (e.g. isSuitablePoint's is3DPathing diagonal-corner
	 * check: {@code open1 = pathNodeType1 == PathType.OPEN || pathNodeType1 == PathType.WALKABLE})
	 * explicitly special-cases WALKABLE/OPEN by exact type, not by malus - a node merely typed RAIL
	 * (rather than WALKABLE) still reads as neither, which could still tighten diagonal
	 * connectivity around a rail sitting near a corner. Forcing straight to WALKABLE - not just
	 * undoing the downgrade - makes a rail tile genuinely indistinguishable from ordinary floor to
	 * every downstream check in this class, not merely non-blocked. Backed up by
	 * WendigoEntity's own constructor now ALSO setting getPathfindingMalus(RAIL/UNPASSABLE_RAIL) to
	 * 0.0F explicitly (the same established fix this codebase already used for FENCE/WATER) - pure
	 * defense in depth in case some other, not-yet-found code path reads either type without going
	 * through this override at all.
	 */
	@Override
	protected long getDirectionalPathNodeType(PathfindingContext context, int x, int y, int z, Mob entity,
			int xSize, int ySize, int zSize, boolean canBreakDoorsIn, boolean canEnterDoorsIn) {
		long packed = super.getDirectionalPathNodeType(context, x, y, z, entity, xSize, ySize, zSize, canBreakDoorsIn, canEnterDoorsIn);
		PathType type = PathType.values()[(int) (packed >> 32)];
		if (type != PathType.RAIL && type != PathType.UNPASSABLE_RAIL) {
			return packed;
		}
		return ((long) PathType.WALKABLE.ordinal() << 32) | (packed & 0xFFFFFFFFL);
	}

	@Override
	public int getNeighbors(Node[] neighbors, Node node) {
		int count = super.getNeighbors(neighbors, node);
		return this.malus.apply(this.mob, neighbors, count);
	}
}
