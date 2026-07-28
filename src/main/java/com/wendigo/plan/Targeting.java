package com.wendigo.plan;

import java.util.List;

import net.minecraft.world.entity.player.Player;

import com.wendigo.entity.WendigoEntity;

/** Player targeting: whoever's nearest for movement/distance purposes, re-resolved on demand rather
 * than cached. See nearbyPlayers for the multiplayer stare-detection case, where any player within
 * range should be able to trigger a look-based check, not just the single nearest one. */
final class Targeting {
	private Targeting() {
	}

	static Player nearestPlayer(WendigoEntity self) {
		return self.level().getNearestPlayer(self, SemanticBands.NEAREST_PLAYER_RADIUS);
	}

	/** Every player within the same radius nearestPlayer uses, not just the closest one - stare
	 * detection is meant to be "global FOV" in multiplayer: any player looking at the wendigo counts,
	 * regardless of who the wave is actually targeting/chasing. */
	static List<? extends Player> nearbyPlayers(WendigoEntity self) {
		double radius = SemanticBands.NEAREST_PLAYER_RADIUS;
		double radiusSq = radius * radius;
		return self.level().players().stream()
			.filter(player -> self.distanceToSqr(player) <= radiusSq)
			.toList();
	}
}
