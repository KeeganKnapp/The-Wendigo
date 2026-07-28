package com.wendigo.plan;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.wendigo.spatial.CaveScaleScanner.CaveScale;
import com.wendigo.spatial.DarkSpotScanner;

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
	 * - spot_a (nearest scanned spot, see WaveContext) is reserved for a tight/mineshaft-like cave
	 *   only, any severity - there's nowhere far to spawn anyway in a cramped space, so even a low-
	 *   severity encounter there can start close; a normal/massive cave never offers it.
	 * - spot_b/c/d/e/f unlock progressively closer as severity climbs (e/f at 20%, d at 40%, c at
	 *   60%, b at 80%) - the wendigo is allowed to appear progressively bolder/closer the more
	 *   established this player's relationship with it already is. Some of these may be torch-linked
	 *   rather than genuinely dark (see DarkSpotScanner.WaveSpot) - invisible here, spawn_at doesn't
	 *   distinguish, only WendigoManager.spawnWave's resolution side reads that.
	 * - no_players_looking: always allowed - the safe, unwatched default.
	 * - spawn_on_torch: unlocks on the exact same schedule as spot_b/c/d/e/f, just measured against
	 *   the separate torch-spawn-candidate pool instead of a labeled spot - see minTorchSpawnDistance.
	 *   torchSpawnAvailable is the caller's answer to "does at least one candidate clear that
	 *   distance" (a world fact WaveContext/the caller computed, not a policy choice re-derived here).
	 *   At 80%+ this lines up with combat.chase also being unlocked - see the prompt guidance in
	 *   WendigoManager for the "commit to a hunt" framing that only applies at that top tier; below
	 *   80% it's offered as an ordinary spawn location choice, no forced follow-up.
	 */
	public static boolean isSpawnSpotAllowed(String label, int severityPercent, CaveScale caveScale, boolean torchSpawnAvailable) {
		return switch (label) {
			case "spot_a" -> caveScale == CaveScale.TIGHT;
			case "spot_b" -> severityPercent >= 80;
			case "spot_c" -> severityPercent >= 60;
			case "spot_d" -> severityPercent >= 40;
			case "spot_e", "spot_f" -> severityPercent >= 20;
			case "spawn_on_torch" -> torchSpawnAvailable && severityPercent >= 20;
			default -> true; // "no_players_looking"
		};
	}

	/** The minimum distance-from-player a spawn_on_torch candidate must clear to be usable at this
	 * severity - the exact same progressive tiers spot_b(16)/c(24)/d(32)/e(40) unlock at (see
	 * DarkSpotScanner.spotDistanceThreshold), just applied to the separate torch-spawn-candidate pool
	 * instead of a labeled spot: 20-39% needs e-or-further, 40-59% d-or-further, 60-79% c-or-further,
	 * 80%+ b-or-further. Below 20%, nothing qualifies (matches spot_e/f's own floor - there's no tier
	 * below that one to fall back to). Public so WendigoManager's resolution side filters the same
	 * candidate pool this schema check reasons about, not just gates the option's visibility. */
	public static double minTorchSpawnDistance(int severityPercent) {
		if (severityPercent >= 80) {
			return DarkSpotScanner.spotDistanceThreshold(1); // spot_b's own threshold
		}
		if (severityPercent >= 60) {
			return DarkSpotScanner.spotDistanceThreshold(2); // spot_c's own threshold
		}
		if (severityPercent >= 40) {
			return DarkSpotScanner.spotDistanceThreshold(3); // spot_d's own threshold
		}
		if (severityPercent >= 20) {
			return DarkSpotScanner.spotDistanceThreshold(4); // spot_e's own threshold
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
	 * once it's available, "jumpscare"/"caught" still shouldn't be selectable before their own
	 * higher thresholds. */
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
			if (isSpawnSpotAllowed(value.getAsString(), severityPercent, caveScale, torchSpawnAvailable)) {
				kept.add(value);
			}
		}
		spawnAt.add("enum", kept);
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
