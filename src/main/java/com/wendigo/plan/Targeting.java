package com.wendigo.plan;

import net.minecraft.world.entity.player.Player;

import com.wendigo.entity.WendigoEntity;

/** Single-player targeting: whoever's nearest, re-resolved on demand rather than cached. */
final class Targeting {
	private Targeting() {
	}

	static Player nearestPlayer(WendigoEntity self) {
		return self.level().getNearestPlayer(self, SemanticBands.NEAREST_PLAYER_RADIUS);
	}
}
