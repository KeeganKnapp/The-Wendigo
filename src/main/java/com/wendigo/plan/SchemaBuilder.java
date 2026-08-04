package com.wendigo.plan;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.wendigo.spatial.CaveScaleScanner.CaveScale;

/**
 * Builds a per-request WendigoActionPlan schema with anything not yet unlocked for the given
 * severity percent removed entirely, not just caught after the fact - TierGates remains the
 * runtime backstop (for /wendigo wavetest's hand-authored plans, and defense-in-depth generally),
 * but for a real API call this is now the primary enforcement layer: the model literally can't
 * select a disallowed option, which is stronger than "the engine silently skips it if it does",
 * and removing unused $defs shrinks what's actually sent at low severity, when most of the schema
 * doesn't apply yet anyway. Reads the exact same thresholds TierGates uses at runtime (package-
 * visible there specifically for this reuse) so the two can't silently drift apart.
 */
public final class SchemaBuilder {
	private static final String BASE_SCHEMA_JSON = readBaseSchema();

	private SchemaBuilder() {
	}

	/** A fresh, filtered copy of the base schema for this severity/cave scale - safe to mutate
	 * further, callers never share the returned instance. torchSpawnAvailable is whether
	 * WaveContext actually found at least one reachable torch-adjacent spawn_on_torch candidate this
	 * request - a world fact, kept separate from the severity policy check in isSpawnSpotAllowed. */
	public static JsonObject forSeverity(int severityPercent, CaveScale caveScale, boolean torchSpawnAvailable) {
		JsonObject schema = JsonParser.parseString(BASE_SCHEMA_JSON).getAsJsonObject();
		JsonObject defs = schema.getAsJsonObject("$defs");

		filterActionStepUnion(defs, severityPercent);
		filterSoundCue(defs, severityPercent);
		filterSpawnAt(schema, severityPercent, caveScale, torchSpawnAvailable);
		filterApproachBand(defs, severityPercent, caveScale, torchSpawnAvailable);
		filterStareBand(defs, severityPercent);

		return schema;
	}

	// Above this severity, a stare only counts as "noticed" if the player is actually looking
	// toward the wendigo (in_view) or dead-on (dead_stare) - corner_of_eye (the widest, ~60 degree
	// band) is dropped entirely. Below this, a barely-established relationship with the player
	// plausibly still gets "made" by the faintest peripheral glimpse; once it's established enough
	// to be bold (40%+), that bar tightens to something a player would actually recognize as looking
	// at it.
	private static final int STARE_BAND_NARROW_MIN_PERCENT = 40;

	/** Removes "corner_of_eye" from predicate.player_looking_at_self/predicate.player_undetected's
	 * band enum at/above STARE_BAND_NARROW_MIN_PERCENT - both predicate defs stay present regardless
	 * of severity (TierGates never gates predicates, only action_step types), only the band choice
	 * narrows. */
	private static void filterStareBand(JsonObject defs, int severityPercent) {
		if (severityPercent < STARE_BAND_NARROW_MIN_PERCENT) {
			return;
		}
		for (String defName : new String[] {"predicate_player_looking_at_self", "predicate_player_undetected"}) {
			if (!defs.has(defName)) {
				continue;
			}
			JsonObject bandProperty = defs.getAsJsonObject(defName).getAsJsonObject("properties").getAsJsonObject("band");
			JsonArray kept = new JsonArray();
			for (JsonElement value : bandProperty.getAsJsonArray("enum")) {
				if (!"corner_of_eye".equals(value.getAsString())) {
					kept.add(value);
				}
			}
			bandProperty.add("enum", kept);
		}
	}

	/**
	 * Whether this spawn_at value is allowed at this severity/cave scale - single source of truth
	 * for both the schema filter below and WendigoManager's matching defensive re-check.
	 * - close_as_possible is reserved for a tight/mineshaft-like cave only, any severity - there's
	 *   nowhere far to go anyway in a cramped space, so even a low-severity encounter there can start
	 *   close; a normal/massive cave never offers it.
	 * - close/medium/far/farther unlock progressively closer as severity climbs (farther at 20%, far
	 *   at 40%, medium at 60%, close at 80%) - the wendigo is allowed to appear progressively
	 *   bolder/closer the more established this player's relationship with it already is. farthest
	 *   shares farther's own 20% floor - there's no tier below that one to gate against.
	 * - no_players_looking: always allowed - the safe, unwatched default.
	 * - spawn_on_torch: unlocks on the exact same schedule as close/medium/far/farther, just measured
	 *   against a live torch scan instead of a distance band directly - see minTorchSpawnDistance.
	 *   torchSpawnAvailable is the caller's answer to "does at least one live torch clear that
	 *   distance" (a world fact the caller computed, not a policy choice re-derived here). At 80%+
	 *   this lines up with combat.chase also being unlocked - see the prompt guidance in
	 *   WendigoManager for the "commit to a hunt" framing that only applies at that top tier; below
	 *   80% it's offered as an ordinary spawn location choice, no forced follow-up.
	 * - spot_above: same 60% floor as combat.lunge_attack/movement.drop - the three are meant to
	 *   unlock together (see TierGates.minPercentFor's own comment), spot_above + drop + lunge being
	 *   the tier-60 pairing the user's own request calls out explicitly.
	 */
	public static boolean isBandAllowed(String band, int severityPercent, CaveScale caveScale, boolean torchSpawnAvailable) {
		return switch (band) {
			case "close_as_possible" -> caveScale == CaveScale.TIGHT;
			case "close" -> severityPercent >= 80;
			case "medium" -> severityPercent >= 60;
			case "far" -> severityPercent >= 40;
			case "farther", "farthest" -> severityPercent >= 20;
			case "spawn_on_torch" -> torchSpawnAvailable && severityPercent >= 20;
			case "spot_above" -> severityPercent >= 60;
			default -> true; // "no_players_looking"
		};
	}

	/** The minimum distance-from-player a spawn_on_torch candidate must clear to be usable at this
	 * severity - the exact same progressive tiers close/medium/far/farther unlock at (see
	 * PositionBands.distanceMin), just applied to a live torch scan instead of a distance band
	 * directly: 20-39% needs farther-or-further, 40-59% far-or-further, 60-79% medium-or-further,
	 * 80%+ close-or-further. Below 20%, nothing qualifies (matches farther/farthest's own floor -
	 * there's no tier below that one to fall back to). Public so WendigoManager's resolution side
	 * filters the same live torch scan this schema check reasons about, not just gates the option's
	 * visibility. */
	public static double minTorchSpawnDistance(int severityPercent) {
		if (severityPercent >= 80) {
			return PositionBands.distanceMin("close");
		}
		if (severityPercent >= 60) {
			return PositionBands.distanceMin("medium");
		}
		if (severityPercent >= 40) {
			return PositionBands.distanceMin("far");
		}
		if (severityPercent >= 20) {
			return PositionBands.distanceMin("farther");
		}
		return Double.POSITIVE_INFINITY;
	}

	/** Removes any action_step $ref (and its now-unreferenced $def body) whose action type isn't
	 * unlocked yet - covers plan bodies, control.if/control.while branches, and global_rule actions
	 * all at once, since they all just point at this same union. */
	private static void filterActionStepUnion(JsonObject defs, int severityPercent) {
		JsonArray union = defs.getAsJsonObject("action_step").getAsJsonArray("anyOf");
		JsonArray kept = new JsonArray();
		for (JsonElement element : union) {
			String defName = refName(element.getAsJsonObject().get("$ref").getAsString());
			String actionType = defs.getAsJsonObject(defName).getAsJsonObject("properties")
				.getAsJsonObject("type").get("const").getAsString();
			boolean allowed = "control.despawn".equals(actionType)
				? severityPercent < TierGates.SUDDEN_DESPAWN_MAX_PERCENT
				: severityPercent >= TierGates.minPercentFor(actionType);
			if (allowed) {
				kept.add(element);
			} else {
				defs.remove(defName);
			}
		}
		defs.getAsJsonObject("action_step").add("anyOf", kept);
	}

	/** Narrower than a whole-action-type removal - sound.ambient_cue itself may already be gone
	 * (below its own 20% action-type gate, in which case there's nothing left to narrow here), but
	 * once it's available, "chase" still shouldn't be selectable before its own higher threshold
	 * (see TierGates.minPercentForCue). */
	private static void filterSoundCue(JsonObject defs, int severityPercent) {
		if (!defs.has("sound_ambient_cue")) {
			return;
		}
		JsonObject cueProperty = defs.getAsJsonObject("sound_ambient_cue").getAsJsonObject("properties").getAsJsonObject("cue");
		JsonArray kept = new JsonArray();
		for (JsonElement cue : cueProperty.getAsJsonArray("enum")) {
			if (severityPercent >= TierGates.minPercentForCue(cue.getAsString())) {
				kept.add(cue);
			}
		}
		cueProperty.add("enum", kept);
	}

	private static void filterSpawnAt(JsonObject schema, int severityPercent, CaveScale caveScale, boolean torchSpawnAvailable) {
		JsonObject spawnAt = schema.getAsJsonObject("properties").getAsJsonObject("spawn_at");
		JsonArray kept = new JsonArray();
		for (JsonElement value : spawnAt.getAsJsonArray("enum")) {
			if (isBandAllowed(value.getAsString(), severityPercent, caveScale, torchSpawnAvailable)) {
				kept.add(value);
			}
		}
		spawnAt.add("enum", kept);
	}

	/** movement_approach_band's own "band" field gets the exact same isBandAllowed filtering
	 * spawn_at's enum does - a pre-existing gap in the old labeled-spot system (movement.approach_spot's
	 * own "spot" enum was never severity-filtered, only spawn_at was, so a model could technically
	 * request approach_spot on a label it was never offered as a spawn choice) closed as a natural
	 * side effect of this rewrite rather than carried forward. spawn_on_torch doesn't apply here (a
	 * spawn_at-only special value, not a distance band - torches are for arriving already exposed,
	 * not for retreating from view mid-plan) and stays excluded if present. no_players_looking DOES
	 * apply here now (unlike spawn_on_torch) - see PlanRunner's own movement.approach_band handling -
	 * and, like spawn_at's own no_players_looking, is always kept regardless of severity (isBandAllowed's
	 * own default case already returns true for it unconditionally, same as it does for spawn_at). */
	private static void filterApproachBand(JsonObject defs, int severityPercent, CaveScale caveScale, boolean torchSpawnAvailable) {
		if (!defs.has("movement_approach_band")) {
			return;
		}
		JsonObject bandProperty = defs.getAsJsonObject("movement_approach_band").getAsJsonObject("properties").getAsJsonObject("band");
		JsonArray kept = new JsonArray();
		for (JsonElement value : bandProperty.getAsJsonArray("enum")) {
			String band = value.getAsString();
			if (!"spawn_on_torch".equals(band) && isBandAllowed(band, severityPercent, caveScale, torchSpawnAvailable)) {
				kept.add(value);
			}
		}
		bandProperty.add("enum", kept);
	}

	private static String refName(String ref) {
		return ref.substring(ref.lastIndexOf('/') + 1);
	}

	private static String readBaseSchema() {
		try (InputStream in = SchemaBuilder.class.getResourceAsStream("/llm/action_schema.json")) {
			if (in == null) {
				throw new IllegalStateException("Missing bundled resource /llm/action_schema.json");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new RuntimeException("Failed to load action schema resource", e);
		}
	}
}
