package com.wendigo.wave;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.level.ServerPlayer;

import com.wendigo.plan.PlanRunner;

/**
 * Per-player memory of the wendigo's last real encounter with them - a narrative-continuity signal
 * for the LLM prompt (see WaveContext.toPromptText), distinct from WendigoProgressionTracker's
 * stage-derived percent. That number tells the model roughly how bold to be; this tells it what
 * actually happened last time, so it can react ("that didn't land, try something else") instead of
 * re-deriving a plan from the number alone every request - see the plan-shape collapse this was
 * built to address (real testing converged on one near-identical template regardless of severity).
 *
 * Not persisted across server restarts, and deliberately not updated by debug-forced waves
 * (/wendigo wave, /wendigo wavetest) - see WendigoManager - so testing/showcase content never
 * pollutes what the model is told about real encounters.
 */
public final class EncounterHistory {
	private final Map<UUID, Entry> byPlayer = new HashMap<>();

	public record Entry(List<String> planShape, boolean hitLanded, boolean reachedDeadStare,
			boolean vanishedCleanly, int waveCount, int endTick) {
	}

	public void record(ServerPlayer player, PlanRunner.EncounterOutcome outcome, int nowTick) {
		Entry previous = this.byPlayer.get(player.getUUID());
		int waveCount = (previous != null ? previous.waveCount() : 0) + 1;
		this.byPlayer.put(player.getUUID(), new Entry(outcome.planShape(), outcome.hitLanded(),
			outcome.reachedDeadStare(), outcome.vanishedCleanly(), waveCount, nowTick));
	}

	/** Null if the wendigo hasn't had a real encounter with this player yet this session. */
	public Entry of(ServerPlayer player) {
		return this.byPlayer.get(player.getUUID());
	}
}
