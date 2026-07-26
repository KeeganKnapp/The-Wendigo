package com.wendigo.plan;

import com.google.gson.JsonObject;

/**
 * Deterministic severity-tier action whitelist. Replaces trusting the LLM to self-restrict via
 * prose tier rules alone - real testing showed that fails outright (combat.lunge_attack and
 * combat.break_torch both appeared in generated plans at 1-2% severity, despite the prompt
 * explicitly saying "no torches, no traps, nothing else" below 20%). A disallowed step is treated
 * like any other unmet precondition in this engine (see PlanRunner.startAction) - skipped, never a
 * wave-ending error.
 *
 * Also the single source of truth SchemaBuilder reads from to remove not-yet-unlocked options from
 * the schema sent to the model in the first place, rather than only catching them after the fact -
 * minPercentFor/minPercentForCue/SUDDEN_DESPAWN_MAX_PERCENT are package-visible specifically for
 * that reuse, so the two enforcement layers can't silently drift apart from each other.
 *
 * Numbers here are the enforcement backbone for WendigoManager.SYSTEM_PROMPT's tier descriptions;
 * keep the two in sync by hand - this package has no dependency on the prompt text, same tradeoff
 * already accepted elsewhere in this codebase (e.g. DarkSpotScanner's light-band constants).
 */
final class TierGates {
	// control.despawn (vanish-in-place) is inverted relative to everything else here - allowed
	// BELOW this percent (or when no despawn candidates exist at all, a runtime-only condition
	// SchemaBuilder can't see at schema-build time) rather than at/above a minimum. See
	// PlanRunner.isSuddenDespawnAllowed, the actual runtime enforcement this mirrors.
	static final int SUDDEN_DESPAWN_MAX_PERCENT = 20;

	private TierGates() {
	}

	/** Minimum severity percent (0-100) at which this action type is allowed to run at all. Not
	 * listed here (posture.stare, control.*, timing.wait, movement.approach_spot,
	 * internal.despawn_move) means always allowed - control flow, staring, and mandatory wave
	 * cleanup are never gated. */
	static int minPercentFor(String actionType) {
		return switch (actionType) {
			case "movement.approach", "movement.approach_dim_spot", "movement.retreat_to_dark",
				"movement.retreat_with_fallback", "movement.reposition", "movement.hold",
				"combat.break_torch", "sound.ambient_cue" -> 20;
			case "memory.store_dark_location" -> 40;
			case "combat.lunge_attack" -> 60;
			case "combat.chase" -> 80;
			default -> 0;
		};
	}

	/** Minimum severity percent at which this sound.ambient_cue value is allowed. */
	static int minPercentForCue(String cue) {
		return switch (cue) {
			case "jumpscare" -> 60;
			case "caught" -> 40;
			default -> 20; // "ambience"
		};
	}

	static boolean isAllowed(JsonObject step, int severityPercent) {
		String type = step.get("type").getAsString();
		if (severityPercent < minPercentFor(type)) {
			return false;
		}
		if ("sound.ambient_cue".equals(type)) {
			return severityPercent >= minPercentForCue(step.get("cue").getAsString());
		}
		return true;
	}
}
