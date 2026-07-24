package com.wendigo.wave;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Engine-scanned context for one wave: the target player, their severity, and labeled dark-spot candidates. */
public final class WaveContext {
	private static final String[] SPOT_LABELS = {"spot_a", "spot_b", "spot_c", "spot_d"};

	private final ServerPlayer player;
	private final int severity;
	private final int severityCap;
	private final List<BlockPos> spots; // nearest -> furthest, index-aligned with SPOT_LABELS

	public WaveContext(ServerPlayer player, int severity, int severityCap, List<BlockPos> spots) {
		this.player = player;
		this.severity = severity;
		this.severityCap = severityCap;
		this.spots = spots;
	}

	public ServerPlayer player() {
		return this.player;
	}

	/** Resolves a schema spot label (e.g. "spot_b") to its scanned position, or null if not found. */
	public BlockPos resolve(String label) {
		for (int i = 0; i < SPOT_LABELS.length; i++) {
			if (SPOT_LABELS[i].equals(label) && i < this.spots.size()) {
				return this.spots.get(i);
			}
		}
		return null;
	}

	/** Renders this context as the user-prompt text the LLM sees alongside the action schema. */
	public String toPromptText() {
		ServerLevel level = this.player.level();
		BlockPos playerPos = this.player.blockPosition();

		StringBuilder sb = new StringBuilder();
		sb.append("Target player: ").append(this.player.getGameProfile().name()).append(". ");
		sb.append("Dweller severity for this player: ").append(this.severity).append("/").append(this.severityCap)
			.append(" (cumulative time spent below y=0 - higher means this has been going on longer). ");
		sb.append("Scanned dark spots near the player, nearest to furthest:\n");
		for (int i = 0; i < this.spots.size(); i++) {
			BlockPos spot = this.spots.get(i);
			double distance = Math.sqrt(spot.distSqr(playerPos));
			int light = level.getMaxLocalRawBrightness(spot);
			sb.append(String.format("- %s: %.0f blocks away, light level %d%n", SPOT_LABELS[i], distance, light));
		}
		return sb.toString();
	}
}
