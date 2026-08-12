package com.wendigo.plan;

import java.util.ArrayList;
import java.util.List;

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
	 * movement.retreat_with_fallback, sound.ambient_cue, internal.despawn_move, combat.teleport) means
	 * always allowed - control flow, staring, and mandatory wave cleanup are never gated.
	 * movement.approach_spot/combat.teleport are always available as action TYPES, same "offer it
	 * always, gate the destination instead" precedent this codebase already settled on for the old
	 * teleport actions - the real gating for both is entirely in which destination type(s)
	 * approachTypesUnlocked/teleportTypesUnlocked even offer for the current stage. sound.breathe
	 * shares movement.approach's own 20% floor - the user's own explicit reversal of an earlier "allow
	 * sounds in stage 1" call specifically for breathe (sound.ambient_cue itself stays ungated, see
	 * minPercentForCue's own comment): stage 1 is meant to stay the faintest, most-deniable tier, and a
	 * deliberate, diegetic breathing noise right next to the player reads as too concrete a presence
	 * for that. */
	static int minPercentFor(String actionType) {
		return switch (actionType) {
			case "movement.approach", "movement.retreat_to_dark",
				"movement.hold", "combat.break_torch", "sound.breathe" -> 20;
			case "memory.store_dark_location" -> 40;
			// movement.drop shares combat.lunge_attack's own 60% tier - the user's own explicit
			// pairing: spot_above/the approach/teleport "above" destination type (see
			// approachTypesUnlocked/teleportTypesUnlocked, gated the same 60% here) + drop + lunge is
			// meant to become available together, an overhead alternative to a ground-level lunge, not
			// a strictly-better tactic reserved for its own higher bar. combat.chase's own 80% pairing
			// (above, drop, chase) unlocks naturally once chase itself does, needing no separate
			// threshold for the combination.
			case "combat.lunge_attack", "movement.drop" -> 60;
			case "combat.chase" -> 80;
			default -> 0;
		};
	}

	/** Maps a representative severity percent to its 1-5 stage bucket - the same boundaries
	 * WendigoManager.buildSystemPrompt's own stage-narrative selection already uses (severityPercent
	 * < 20/40/60/80 or not), and the same buckets WendigoProgressionTracker.STAGE_PERCENTS'
	 * representative values (10/30/50/70/90) each land squarely inside. Backs
	 * teleportTypesUnlocked/approachTypesUnlocked below - both need the same stage number. */
	static int stageFor(int severityPercent) {
		if (severityPercent < 20) {
			return 1;
		}
		if (severityPercent < 40) {
			return 2;
		}
		if (severityPercent < 60) {
			return 3;
		}
		if (severityPercent < 80) {
			return 4;
		}
		return 5;
	}

	/** combat.teleport's own destination type(s), by stage - CUMULATIVE (each stage keeps everything
	 * unlocked earlier, plus whatever's new this stage, so the offered menu only ever grows). Distance
	 * no longer varies by stage at all (see SemanticBands.actionSearchMinDistance - cave scale drives
	 * that now, not severity), so this is the ENTIRE severity-gating story for this action: in_view/
	 * eyeline/ahead unlock together at stage 1 (the flagship reveal tools, real variety for what's
	 * otherwise almost the whole of what stage 1 can do), torch adds at stage 3, above at stage 4
	 * (pairs with movement.drop's own 60% unlock - see minPercentFor), behind - the single most
	 * dramatic blind-spot ambush - reserved for stage 5 alone. First pass, flagged as the least
	 * mechanically-derived part of this whole redesign - adjust by feel once live-tested. */
	static List<String> teleportTypesUnlocked(int stage) {
		List<String> types = new ArrayList<>(List.of("in_view", "eyeline", "ahead"));
		if (stage >= 3) {
			types.add("torch");
		}
		if (stage >= 4) {
			types.add("above");
		}
		if (stage >= 5) {
			types.add("behind");
		}
		return types;
	}

	/** movement.approach_spot's own destination type(s), by stage - same cumulative shape as
	 * teleportTypesUnlocked, but looser: walking somewhere unwatched is a much smaller ask than
	 * instantly blinking there, so this reaches its full menu well before teleport does - in_view/
	 * eyeline/ahead/unwatched all unlock together at stage 1 (unwatched is the standard "reposition
	 * before the rest of a plan runs" first step, no reason to gate it at all), behind adds at stage 2
	 * (still much earlier than teleport's own stage-5 reservation), above at stage 4 (matches
	 * teleport's own pairing with movement.drop's 60% unlock - reaching a ceiling spot without the
	 * ability to safely drop afterward isn't very useful). No torch - that's teleport-only, approaching
	 * a torch first isn't a distinct need once combat.break_torch already targets the nearest one from
	 * wherever the wendigo ends up. */
	static List<String> approachTypesUnlocked(int stage) {
		List<String> types = new ArrayList<>(List.of("in_view", "eyeline", "ahead", "unwatched"));
		if (stage >= 2) {
			types.add("behind");
		}
		if (stage >= 4) {
			types.add("above");
		}
		return types;
	}

	/** Minimum severity percent at which this sound.ambient_cue value is allowed - "chase" matches
	 * combat.lunge_attack's own unlock (the earliest action it could reasonably announce); "flee"/
	 * "stare"/"ambient" have nothing to gate against anymore (sound.ambient_cue itself is no longer
	 * action-type-gated at all - see minPercentFor's own comment on the user's own explicit "allow
	 * sounds in stage 1" request - and posture.stare isn't gated either, movement.retreat_with_fallback
	 * is available at every stage too), so they're allowed from 0%. */
	static int minPercentForCue(String cue) {
		return switch (cue) {
			case "chase" -> 60;
			default -> 0; // "flee", "stare", "ambient"
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
