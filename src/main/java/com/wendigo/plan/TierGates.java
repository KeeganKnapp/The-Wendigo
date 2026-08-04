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
	 * listed here (posture.stare, control.*, timing.wait, movement.approach_band,
	 * internal.despawn_move) means always allowed - control flow, staring, and mandatory wave
	 * cleanup are never gated (movement.approach_band's own band choice is separately gated by
	 * SchemaBuilder.isBandAllowed/filterApproachBand, not here - same split spawn_at's bands use). */
	static int minPercentFor(String actionType) {
		return switch (actionType) {
			case "movement.approach", "movement.retreat_to_dark",
				"movement.retreat_with_fallback", "movement.reposition", "movement.hold",
				"combat.break_torch", "sound.ambient_cue" -> 20;
			case "memory.store_dark_location" -> 40;
			// movement.drop shares combat.lunge_attack's own 60% tier - the user's own explicit
			// pairing: spot_above (see SchemaBuilder.isBandAllowed, gated the same 60% here) + drop +
			// lunge is meant to become available together, an overhead alternative to a ground-level
			// lunge, not a strictly-better tactic reserved for its own higher bar. combat.chase's own
			// 80% pairing (spot_above, drop, chase) unlocks naturally once chase itself does, needing
			// no separate threshold for the combination.
			case "combat.lunge_attack", "movement.drop" -> 60;
			case "combat.chase" -> 80;
			// Stage 5's own representativePercent (WendigoProgressionTracker.STAGE_PERCENTS[5]) exactly
			// - the user's own explicit "only available to stage 5" request. Stage 4 tops out at 70, so
			// this threshold is only ever reachable by stage 5 specifically, same precedent combat.chase's
			// 80 already sets (also unreachable below stage 5, just less precisely-chosen a value).
			case "combat.teleport_behind" -> 90;
			default -> 0;
		};
	}

	/** Minimum severity percent at which this sound.ambient_cue value is allowed - "chase" matches
	 * combat.lunge_attack's own unlock (the earliest action it could reasonably announce); "flee"/
	 * "stare"/"ambient" have nothing stricter to match (movement.retreat_with_fallback and
	 * sound.ambient_cue itself both already unlock at 20%, and posture.stare isn't gated at all), so
	 * they fall to the same floor sound.ambient_cue's own action-type gate already enforces. */
	static int minPercentForCue(String cue) {
		return switch (cue) {
			case "chase" -> 60;
			default -> 20; // "flee", "stare", "ambient"
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
