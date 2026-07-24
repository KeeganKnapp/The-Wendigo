package com.wendigo.wave;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Tracks cumulative time each player has spent below y=0, world-wide, never resetting when they
 * resurface - this is the wendigo's targeting signal (whoever's been in the depths longest,
 * total) and, longer-term, a severity value meant to gate escalating behavior once a "defeat the
 * dweller" progression exists. Deliberately not a reward for spelunking: it only ever goes up.
 * In-memory only - resets on server restart.
 */
public final class PlayerSeverityTracker {
	// Sampled once per second (not every tick) so the cap represents a sane amount of wall-clock
	// time rather than raw ticks.
	private static final int SAMPLE_INTERVAL_TICKS = 20;

	private final WendigoWaveConfig config;
	private final Map<UUID, Integer> secondsUnderY0 = new HashMap<>();
	private int ticksSinceSample;

	public PlayerSeverityTracker(WendigoWaveConfig config) {
		this.config = config;
	}

	public void register() {
		ServerTickEvents.END_SERVER_TICK.register(this::onEndServerTick);
	}

	private void onEndServerTick(MinecraftServer server) {
		this.ticksSinceSample++;
		if (this.ticksSinceSample < SAMPLE_INTERVAL_TICKS) {
			return;
		}
		this.ticksSinceSample = 0;

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player.isSpectator() || player.getY() >= 0) {
				continue;
			}
			this.secondsUnderY0.merge(player.getUUID(), 1, (current, one) -> Math.min(this.config.severityCap, current + one));
		}
	}

	public int severityOf(ServerPlayer player) {
		return this.secondsUnderY0.getOrDefault(player.getUUID(), 0);
	}

	/** Highest-severity player currently below y=0 in this level, or null if nobody qualifies right now. */
	public ServerPlayer mostSevereEligiblePlayer(ServerLevel level) {
		ServerPlayer best = null;
		int bestSeverity = -1;
		for (ServerPlayer player : level.players()) {
			if (player.isSpectator() || player.getY() >= 0) {
				continue;
			}
			int severity = severityOf(player);
			if (severity > bestSeverity) {
				bestSeverity = severity;
				best = player;
			}
		}
		return best;
	}
}
