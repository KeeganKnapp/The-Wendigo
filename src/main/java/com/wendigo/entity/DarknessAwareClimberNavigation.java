package com.wendigo.entity;

import com.nyfaria.awcapi.entity.movement.AdvancedPathFinder;
import com.nyfaria.awcapi.entity.movement.ClimberPathNavigator;

import net.minecraft.world.level.Level;

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
}
