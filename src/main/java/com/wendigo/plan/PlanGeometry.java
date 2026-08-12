package com.wendigo.plan;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;

import com.wendigo.entity.WendigoEntity;
import com.wendigo.spatial.DarkSpotScanner;

/** Spatial helper for movement primitives that still need a plain dark-spot scan (movement.
 * retreat_to_dark, memory.store_dark_location) - movement.reposition itself (the only other former
 * caller here, relative-to-reference-point tactical stepping) was fully absorbed into
 * movement.approach_spot and removed entirely. */
final class PlanGeometry {
	private PlanGeometry() {
	}

	/** Darkest standable spot within radius of self, biased away from the nearest player if there is
	 * one - see DarkSpotScanner.findDarkestAwayFrom. Every caller of this is picking a place to flee
	 * to, not just anywhere dark, so heading toward/past the player it's meant to be getting away
	 * from defeats the point. */
	static BlockPos findDarkSpot(WendigoEntity self, double radius) {
		Player player = Targeting.nearestPlayer(self);
		BlockPos avoid = player != null ? player.blockPosition() : null;
		return DarkSpotScanner.findDarkestAwayFrom(self.level(), self.blockPosition(), radius, avoid);
	}
}
