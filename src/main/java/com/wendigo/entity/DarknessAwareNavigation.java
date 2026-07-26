package com.wendigo.entity;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.PathFinder;

/**
 * Ground navigation that costs light level into every route (see DarknessNodeEvaluator) instead
 * of the vanilla WalkNodeEvaluator, which has no concept of light at all. This is the wendigo's
 * only navigation today - used by every movement primitive. A future dash-style action that's
 * meant to cross light on purpose would need its own separate handling, not a second navigation
 * instance; nothing like that exists yet.
 */
public class DarknessAwareNavigation extends GroundPathNavigation {
	// Assigned by createPathFinder, which - per Java's construction order - runs during the
	// super(mob, level) call above, before this field would otherwise be initialized; the direct
	// assignment inside createPathFinder makes that safe regardless.
	private DarknessNodeEvaluator darknessNodeEvaluator;

	public DarknessAwareNavigation(Mob mob, Level level) {
		super(mob, level);
		// GroundPathNavigation.createPath silently redirects any target sitting under a solid
		// ceiling to a position above it (vanilla's anti-tunneling logic for surface mobs) unless
		// this is set - it looks only at the target column, not where the mob itself is standing.
		// For a mob that lives in caves, that meant every movement target (torches, despawn spots,
		// everything) was being redirected above the cave ceiling, often into sealed rock with no
		// real path back down - the actual cause of "can't pathfind" failures and the occasional
		// lucky-but-wrong partial path toward whatever was heuristically closest to that redirected,
		// unreachable target instead.
		this.setCanPathToTargetsBelowSurface(true);
	}

	@Override
	protected PathFinder createPathFinder(int maxVisitedNodes) {
		this.darknessNodeEvaluator = new DarknessNodeEvaluator();
		this.nodeEvaluator = this.darknessNodeEvaluator;
		this.nodeEvaluator.setCanPassDoors(true);
		return new PathFinder(this.nodeEvaluator, maxVisitedNodes);
	}

	/** See DarknessNodeEvaluator.lightTolerant - lets the two into-the-light primitives opt out. */
	public void setLightTolerant(boolean lightTolerant) {
		this.darknessNodeEvaluator.setLightTolerant(lightTolerant);
	}
}
