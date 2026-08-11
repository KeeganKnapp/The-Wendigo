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
	 * listed here (posture.stare, control.*, timing.wait, movement.approach_band,
	 * movement.retreat_with_fallback, sound.ambient_cue, internal.despawn_move) means always allowed -
	 * control flow, staring, and mandatory wave cleanup are never gated (movement.approach_band's own
	 * band choice is separately gated by SchemaBuilder.isBandAllowed/filterApproachBand, not here).
	 * movement.retreat_with_fallback deliberately moved out of the 20% group it used to share with
	 * everything else here - the user's own explicit correction: stage 1 (under 20%) used to have
	 * control.despawn schema-forced as its ONLY possible ending (nothing else offered at all), which
	 * read as the engine hard-requiring an instant vanish rather than that just being the
	 * recommended, in-character choice. A real, visible flee is now offered as an alternative ending
	 * at every stage including 1 - STAGE_UNDER_20's own prompt text (WendigoManager) still steers
	 * toward control.despawn as the typical pick for this stage's "barely a presence" feel, but
	 * doesn't hard-require it anymore. sound.ambient_cue similarly moved out - the user's own explicit
	 * "allow sounds in stage 1" request, feeding stage 1's own new "4 stares AND 4 noises" compound
	 * goal (see WendigoProgressionTracker) - minPercentForCue below still gates the individual cue
	 * VALUES, not just this action type. */
	static int minPercentFor(String actionType) {
		return switch (actionType) {
			case "movement.approach", "movement.retreat_to_dark",
				"movement.reposition", "movement.hold",
				"combat.break_torch" -> 20;
			case "memory.store_dark_location" -> 40;
			// movement.drop shares combat.lunge_attack's own 60% tier - the user's own explicit
			// pairing: spot_above (see SchemaBuilder.isBandAllowed, gated the same 60% here) + drop +
			// lunge is meant to become available together, an overhead alternative to a ground-level
			// lunge, not a strictly-better tactic reserved for its own higher bar. combat.chase's own
			// 80% pairing (spot_above, drop, chase) unlocks naturally once chase itself does, needing
			// no separate threshold for the combination.
			case "combat.lunge_attack", "movement.drop" -> 60;
			case "combat.chase" -> 80;
			// combat.teleport_to_band/combat.teleport_in_view/combat.teleport_to_eyeline deliberately
			// NOT listed - the user's own explicit "offer the teleport always" request, once
			// combat.teleport_to_band's own band progression replaced the old stage-5-only
			// combat.teleport_behind, later extended to teleport_in_view and now teleport_to_eyeline on
			// the same reasoning. Always allowed as an action TYPE; the real gating is entirely in which
			// band value(s) SchemaBuilder's own filter* methods even offer for the current stage (see
			// teleportDestinationBands/teleportInViewBands/teleportEyelineBands), plus
			// combat.teleport_to_band's own additional runtime source-band precondition (see
			// teleportSourceBands - the only one of the three with one) - "the source + destination
			// gating offer enough for good horror rules" (the user's own words), no third gate needed.
			default -> 0;
		};
	}

	/** Maps a representative severity percent to its 1-5 stage bucket - the same boundaries
	 * WendigoManager.buildSystemPrompt's own stage-narrative selection already uses (severityPercent
	 * < 20/40/60/80 or not), and the same buckets WendigoProgressionTracker.STAGE_PERCENTS'
	 * representative values (10/30/50/70/90) each land squarely inside. Backs
	 * combat.teleport_to_band's own stage-scaled band progression below - both the destination
	 * band(s) SchemaBuilder offers and the source band PlanRunner requires at runtime need the same
	 * stage number, so this is the one shared definition both read from. */
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

	/** combat.teleport_to_band's own destination band(s), by stage - CUMULATIVE (the user's own
	 * explicit correction to an earlier non-cumulative design): each stage keeps every band that
	 * unlocked at an earlier stage, plus whatever's new this stage, so the offered menu only ever
	 * grows. Per-stage new unlocks, same groupings as the original locked design: farthest at 1,
	 * farther at 2, far at 3, medium+close together at 4 (a real choice between an ordinary
	 * positioning teleport and the unwatched blind-spot flavor), behind_player+above_player+torch
	 * together at 5 (the three most dramatic options - the original blind-spot ambush, the ceiling
	 * drop-in point, and the plain nearest-torch relocation that replaced spawn_on_torch). Stage 5
	 * ends up with all 8 possible values. */
	static List<String> teleportDestinationBands(int stage) {
		List<String> bands = new ArrayList<>();
		bands.add("farthest");
		if (stage >= 2) {
			bands.add("farther");
		}
		if (stage >= 3) {
			bands.add("far");
		}
		if (stage >= 4) {
			bands.add("medium");
			bands.add("close");
		}
		if (stage >= 5) {
			bands.add("behind_player");
			bands.add("above_player");
			bands.add("torch");
		}
		return bands;
	}

	/** combat.teleport_in_view's own destination band(s), by stage - CUMULATIVE like
	 * teleportDestinationBands above, same farthest-at-1-down-to-close-at-5 per-stage new-unlock
	 * progression ("its like the other scalings we've done," the user's own words) - EXCEPT stage 1,
	 * which gets a deliberate one-time exception: every band at once, not just farthest, per the
	 * user's own explicit "allow in front of player teleporting at all distances for stage 1 only"
	 * request - real variety for the single reveal-and-vanish beat that's otherwise almost the whole
	 * of what stage 1 can do (see WendigoManager's STAGE_UNDER_20 prompt text). Stages 2+ compute
	 * their own cumulative list from the ordinary progression as if stage 1 had only contributed
	 * farthest - they do NOT inherit stage 1's own full special-case set, or every later stage would
	 * trivially also have all 5 and the progression would mean nothing. Unlike
	 * teleportDestinationBands, this has no matching runtime source-band precondition - an in-view
	 * teleport is meant to be reachable for a deliberate reveal regardless of the wendigo's own
	 * current position. */
	static List<String> teleportInViewBands(int stage) {
		if (stage == 1) {
			return List.of("farthest", "farther", "far", "medium", "close");
		}
		List<String> bands = new ArrayList<>();
		bands.add("farthest");
		if (stage >= 2) {
			bands.add("farther");
		}
		if (stage >= 3) {
			bands.add("far");
		}
		if (stage >= 4) {
			bands.add("medium");
		}
		if (stage >= 5) {
			bands.add("close");
		}
		return bands;
	}

	/** combat.teleport_to_eyeline's own destination band(s), by stage - identical shape to
	 * teleportInViewBands right above (same cumulative farthest-at-1-to-close-at-5 progression, same
	 * stage-1 "every band at once" exception), a separate method rather than reusing that one directly
	 * since the two abilities are conceptually distinct (broadly in view vs genuinely along the
	 * player's current gaze - see DarkSpotScanner.EYELINE_ALIGNMENT_DEGREES) even though their band
	 * gating happens to be identical - the user's own explicit "normal distance gating like we do with
	 * other teleportation options, except for stage 1 which has access to all distance bands" request
	 * for this new ability, mirroring teleport_in_view's own established pattern rather than inventing
	 * a new one. Like teleportInViewBands, no matching runtime source-band precondition - reachable for
	 * a reveal regardless of the wendigo's own current position. */
	static List<String> teleportEyelineBands(int stage) {
		if (stage == 1) {
			return List.of("farthest", "farther", "far", "medium", "close");
		}
		List<String> bands = new ArrayList<>();
		bands.add("farthest");
		if (stage >= 2) {
			bands.add("farther");
		}
		if (stage >= 3) {
			bands.add("far");
		}
		if (stage >= 4) {
			bands.add("medium");
		}
		if (stage >= 5) {
			bands.add("close");
		}
		return bands;
	}

	/** combat.teleport_to_band's own best-to-worst fallback order, the user's own explicit list -
	 * "teleport_above, teleport_behind, close, medium, far, etc... thats the order of best to worst".
	 * When the model's chosen band fails to resolve live (PlanRunner.startAction's own
	 * combat.teleport_to_band case), the engine walks forward through this list from wherever that
	 * choice sits - never backward toward a "better" option the model wasn't even offered at the
	 * current stage - trying each successive (worse, i.e. safer/farther) option until one resolves or
	 * the list runs out. farther/farthest tacked on past what the user listed by name ("etc") to
	 * complete the ladder, matching teleportDestinationBands' own full band vocabulary. torch (the
	 * spawn_on_torch replacement, stage 5 only) sits just below above_player/behind_player - a
	 * dramatic, stage-5-exclusive option, but a plain relocation rather than either of those two's own
	 * blind-spot/overhead ambush flavor, so it reads as one notch less dramatic. */
	static final List<String> TELEPORT_BAND_PRIORITY = List.of(
		"above_player", "behind_player", "torch", "close", "medium", "far", "farther", "farthest");

	/** combat.teleport_in_view's own fallback order - "if a spot can't be found at that distance in
	 * view then just try outward from there," the user's own words: a SEPARATE, smaller ladder from
	 * TELEPORT_BAND_PRIORITY above (this ability has no above_player/behind_player equivalents), and
	 * cascaded in the OPPOSITE direction - starting from the model's chosen band and walking toward
	 * the FARTHEST end (never toward close), since "outward" here means farther from the player, not
	 * safer/worse the way TELEPORT_BAND_PRIORITY's own ordering means it. */
	static final List<String> TELEPORT_IN_VIEW_LADDER = List.of("close", "medium", "far", "farther", "farthest");

	/** combat.teleport_to_eyeline's own fallback order - identical shape to TELEPORT_IN_VIEW_LADDER
	 * right above, same "search outward from the model's own choice toward farthest" reasoning. */
	static final List<String> TELEPORT_EYELINE_LADDER = List.of("close", "medium", "far", "farther", "farthest");

	/** combat.teleport_to_band's own runtime-only precondition (PlanRunner checks this against the
	 * wendigo's OWN current live distance-band from the player, at the moment the step actually
	 * executes - not schema-enforceable, since it depends on live position, not just severity):
	 * CUMULATIVE like teleportDestinationBands above (the user's own explicit correction) - the SAME
	 * ladder as that method, walked in the OPPOSITE direction (close unlocks at stage 1, growing to
	 * farthest by stage 5), and now a set-membership check rather than one exact-match value per
	 * stage: the wendigo's current position just needs to be in ANY band unlocked so far this stage,
	 * not exactly the newest one. An early-stage wendigo can only use its (safe, distant) teleport as
	 * an escape while already close to the player; a stage-5 wendigo can pull it off from any of the 5
	 * ordinary bands, matching how rich stage 5's own destination menu has become. No stage-5
	 * "behind_player" special case here, unlike the destination side - there's no equivalent "teleport
	 * away from the player" concept for a source band. */
	static List<String> teleportSourceBands(int stage) {
		List<String> bands = new ArrayList<>();
		bands.add("close");
		if (stage >= 2) {
			bands.add("medium");
		}
		if (stage >= 3) {
			bands.add("far");
		}
		if (stage >= 4) {
			bands.add("farther");
		}
		if (stage >= 5) {
			bands.add("farthest");
		}
		return bands;
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
