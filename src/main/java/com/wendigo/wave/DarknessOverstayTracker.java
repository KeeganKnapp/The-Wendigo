package com.wendigo.wave;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import com.wendigo.sound.WendigoSounds;
import com.wendigo.spatial.DarkSpotScanner;

/**
 * Punishes lingering too long in darkness (light &lt;= DarkSpotScanner.MAX_DARK_LIGHT, the same bar
 * the wendigo's own spawn/despawn scanning uses) - "you've been in the dark too long, time to die".
 * Separate mechanic from the normal spawn-count-triggered wave system (that one escalates on how many
 * times the wendigo has ever spawned on this player; this one punishes one continuous darkness stay,
 * reset the instant the player steps into real light), though it reuses WendigoManager's wave
 * lifecycle to actually act on it: if no wendigo is currently active, it spawns a fresh hardcoded
 * ambush (WendigoManager.triggerDarknessAmbush); if one already is, it interrupts whatever that
 * wendigo is currently doing and redirects it instead (WendigoManager.overrideIntoChaseUntilLight,
 * on a much shorter fixed threshold - the threat is already right there).
 */
public final class DarknessOverstayTracker {
	private static final int SAMPLE_INTERVAL_TICKS = 20; // once/sec, matches WendigoProgressionTracker
	// Below this stage-derived percent, staying in darkness never escalates into the ambush - the
	// periodic warning noise (see WARNING_NOISE_INTERVAL_TICKS) is independent of this threshold
	// entirely, gated on the wendigo already being active on this player instead (see isActiveOn).
	private static final int AMBUSH_MIN_PERCENT = 20;
	// Each 20%-band gets a random threshold within its own shrinking 5-second window, counting down
	// as stage climbs: 20-39% -> 15-20s, 40-59% -> 10-15s, 60-79% -> 5-10s, 80%+ -> 0-5s. Rolled once
	// per continuous darkness stay (see rolledThresholdTicks), not re-rolled while already dark.
	// Explicit design call: this scaling is meant to stay (unlike per-step tier gating on the
	// hardcoded plan's own content, e.g. spawn spot eligibility, which is NOT severity-scaled here -
	// see triggerDarknessAmbush's bypassTierGating=true) - this is "the rule for when it's allowed
	// to run", not a limitation on the plan itself, and stage is meant to affect it.
	private static final int THRESHOLD_BAND_SECONDS = 5;
	private static final int THRESHOLD_MAX_SECONDS = 20;
	// How often the "get out of the dark" noise repeats while continuously in darkness - only while
	// the wendigo is genuinely active on this specific player right now (see isActiveOn's own call
	// site below), the user's own explicit request: no noise from just sitting in the dark alone.
	private static final int WARNING_NOISE_INTERVAL_TICKS = 100; // 5s
	// If a wendigo is already active when the darkness-overstay condition is met, it overrides into
	// internal.chase_until_light instead of trying to spawn a second one - a much shorter fixed
	// threshold than the tiered spawn-a-fresh-ambush one, since the threat is already right there,
	// nothing to wait on a spawn for.
	private static final int OVERRIDE_THRESHOLD_TICKS = 100; // 5s

	private final WendigoProgressionTracker progressionTracker;
	private final WendigoManager wendigoManager;
	// How long (in ticks) each player has been continuously in darkness - removed entirely the
	// instant they step into real light, not just paused, so a fresh stay always starts the clock
	// (and re-rolls the threshold) from zero.
	private final Map<UUID, Integer> darkTicks = new HashMap<>();
	private final Map<UUID, Integer> rolledThresholdTicks = new HashMap<>();
	private int ticksSinceSample;

	public DarknessOverstayTracker(WendigoProgressionTracker progressionTracker, WendigoManager wendigoManager) {
		this.progressionTracker = progressionTracker;
		this.wendigoManager = wendigoManager;
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
			// Same reasoning as WendigoProgressionTracker/WendigoManager's own tick hooks - this fires
			// every real server tick regardless of /tick freeze, but this is world state that should
			// only advance while the player's level is actually ticking.
			if (player.isSpectator() || !player.level().tickRateManager().runsNormally()) {
				continue;
			}
			UUID id = player.getUUID();
			boolean inDark = player.level().getMaxLocalRawBrightness(player.blockPosition()) <= DarkSpotScanner.MAX_DARK_LIGHT;
			if (!inDark) {
				this.darkTicks.remove(id);
				this.rolledThresholdTicks.remove(id);
				continue;
			}
			int ticks = this.darkTicks.merge(id, SAMPLE_INTERVAL_TICKS, Integer::sum);
			// The user's own explicit request: only play this "still lingering in the dark" warning
			// while the wendigo is genuinely active on THIS player right now, not just because
			// they're sitting in darkness generally (with no wendigo around at all, or one that's
			// busy on someone else in a multiplayer group).
			if (ticks % WARNING_NOISE_INTERVAL_TICKS < SAMPLE_INTERVAL_TICKS && this.wendigoManager.isActiveOn(player)) {
				WendigoSounds.play(player.level(), player, WendigoSounds.Type.AMBIENT);
			}
			int percent = this.progressionTracker.percentOf(player);
			if (percent < AMBUSH_MIN_PERCENT) {
				continue;
			}
			boolean alreadyActive = this.wendigoManager.hasActiveWave(player.level());
			int thresholdTicks = alreadyActive
				? OVERRIDE_THRESHOLD_TICKS
				: this.rolledThresholdTicks.computeIfAbsent(id, k -> rollThresholdTicks(percent));
			if (ticks >= thresholdTicks) {
				this.darkTicks.remove(id);
				this.rolledThresholdTicks.remove(id);
				if (alreadyActive) {
					this.wendigoManager.overrideIntoChaseUntilLight(player.level(), player);
				} else {
					this.wendigoManager.triggerDarknessAmbush(player.level(), player);
				}
			}
		}
	}

	/** See the class-level band comment above for the exact seconds-per-band table this implements.
	 * At 100% - the relationship as established as it can ever get - the roll is skipped entirely:
	 * the very first dark tick sampled is already past threshold, so the ambush fires immediately
	 * rather than waiting out even the 0-5s band's own possible upper end. */
	private static int rollThresholdTicks(int percent) {
		if (percent >= 100) {
			return 0;
		}
		int bandIndex = Math.min(Math.clamp(percent, 20, 100) / 20, 4) - 1; // 0..3
		int maxSeconds = THRESHOLD_MAX_SECONDS - bandIndex * THRESHOLD_BAND_SECONDS;
		int minSeconds = maxSeconds - THRESHOLD_BAND_SECONDS;
		int seconds = minSeconds + ThreadLocalRandom.current().nextInt(THRESHOLD_BAND_SECONDS + 1);
		return seconds * SAMPLE_INTERVAL_TICKS;
	}
}
