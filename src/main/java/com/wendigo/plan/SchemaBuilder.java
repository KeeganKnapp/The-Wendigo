package com.wendigo.plan;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

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
	 * further, callers never share the returned instance. */
	public static JsonObject forSeverity(int severityPercent, CaveScale caveScale) {
		JsonObject schema = JsonParser.parseString(BASE_SCHEMA_JSON).getAsJsonObject();
		JsonObject defs = schema.getAsJsonObject("$defs");

		filterActionStepUnion(defs, severityPercent);
		filterSoundCue(defs, severityPercent);
		filterApproachBand(defs, severityPercent, caveScale);
		filterStareBand(defs, severityPercent);
		filterTeleportBand(defs, severityPercent);
		filterTeleportInViewBand(defs, severityPercent);
		filterTeleportEyelineBand(defs, severityPercent);

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
	 * Whether this distance-band value is allowed at this severity/cave scale - single source of
	 * truth for movement.approach_band's own schema filter below (previously also backed spawn_at,
	 * removed - see this class's own history/the plan that removed it: the wendigo no longer
	 * teleports to a chosen band before a plan starts, it just starts from wherever it already is).
	 * - close_as_possible is reserved for a tight/mineshaft-like cave only, any severity - there's
	 *   nowhere far to go anyway in a cramped space, so even a low-severity encounter there can start
	 *   close; a normal/massive cave never offers it.
	 * - close/medium/far/farther unlock progressively closer as severity climbs (farther at 20%, far
	 *   at 40%, medium at 60%, close at 80%) - the wendigo is allowed to appear progressively
	 *   bolder/closer the more established this player's relationship with it already is. farthest
	 *   shares farther's own 20% floor - there's no tier below that one to gate against.
	 * - no_players_looking: always allowed - the safe, unwatched default.
	 * - spot_above: same 60% floor as combat.lunge_attack/movement.drop - the three are meant to
	 *   unlock together (see TierGates.minPercentFor's own comment), spot_above + drop + lunge being
	 *   the tier-60 pairing the user's own request calls out explicitly.
	 */
	public static boolean isBandAllowed(String band, int severityPercent, CaveScale caveScale) {
		return switch (band) {
			case "close_as_possible" -> caveScale == CaveScale.TIGHT;
			case "close" -> severityPercent >= 80;
			case "medium" -> severityPercent >= 60;
			case "far" -> severityPercent >= 40;
			case "farther", "farthest" -> severityPercent >= 20;
			case "spot_above" -> severityPercent >= 60;
			default -> true; // "no_players_looking"
		};
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

	/** movement_approach_band's own "band" field, filtered by isBandAllowed - a pre-existing gap in
	 * the old labeled-spot system (movement.approach_spot's own "spot" enum was never
	 * severity-filtered) closed as a natural side effect of a much earlier rewrite rather than carried
	 * forward. no_players_looking is always kept regardless of severity (isBandAllowed's own default
	 * case already returns true for it unconditionally). */
	private static void filterApproachBand(JsonObject defs, int severityPercent, CaveScale caveScale) {
		if (!defs.has("movement_approach_band")) {
			return;
		}
		JsonObject bandProperty = defs.getAsJsonObject("movement_approach_band").getAsJsonObject("properties").getAsJsonObject("band");
		JsonArray kept = new JsonArray();
		for (JsonElement value : bandProperty.getAsJsonArray("enum")) {
			if (isBandAllowed(value.getAsString(), severityPercent, caveScale)) {
				kept.add(value);
			}
		}
		bandProperty.add("enum", kept);
	}

	/** combat.teleport_to_band's own "band" field, restricted to exactly TierGates.
	 * teleportDestinationBands(stage) - unlike every other band-enum filter above, this one isn't a
	 * CaveScale/torch-availability policy check reused from isBandAllowed, it's a flat stage lookup:
	 * the action type is always available (see TierGates.minPercentFor's own comment - not listed
	 * there at all), so this is the actual gating mechanism for this whole capability. No-op if the
	 * def isn't present (already stripped some other way, or the base schema changed) - same
	 * defensive shape filterApproachBand/filterSoundCue already use. */
	private static void filterTeleportBand(JsonObject defs, int severityPercent) {
		if (!defs.has("combat_teleport_to_band")) {
			return;
		}
		JsonObject bandProperty = defs.getAsJsonObject("combat_teleport_to_band").getAsJsonObject("properties").getAsJsonObject("band");
		List<String> allowed = TierGates.teleportDestinationBands(TierGates.stageFor(severityPercent));
		JsonArray kept = new JsonArray();
		for (JsonElement value : bandProperty.getAsJsonArray("enum")) {
			if (allowed.contains(value.getAsString())) {
				kept.add(value);
			}
		}
		bandProperty.add("enum", kept);
	}

	/** combat.teleport_in_view's own "band" field, restricted to exactly TierGates.
	 * teleportInViewBands(stage) - same shape as filterTeleportBand right above, now both cumulative
	 * lists (stage 1 is the one exception - see teleportInViewBands' own doc comment). No-op if the
	 * def isn't present, same defensive shape every other filter* method here already uses. */
	private static void filterTeleportInViewBand(JsonObject defs, int severityPercent) {
		if (!defs.has("combat_teleport_in_view")) {
			return;
		}
		JsonObject bandProperty = defs.getAsJsonObject("combat_teleport_in_view").getAsJsonObject("properties").getAsJsonObject("band");
		List<String> allowed = TierGates.teleportInViewBands(TierGates.stageFor(severityPercent));
		JsonArray kept = new JsonArray();
		for (JsonElement value : bandProperty.getAsJsonArray("enum")) {
			if (allowed.contains(value.getAsString())) {
				kept.add(value);
			}
		}
		bandProperty.add("enum", kept);
	}

	/** combat.teleport_to_eyeline's own "band" field, restricted to exactly TierGates.
	 * teleportEyelineBands(stage) - same shape as filterTeleportInViewBand right above (they currently
	 * happen to compute identical lists, but read from their own separate TierGates method - see that
	 * method's own doc comment for why they're kept distinct rather than shared). No-op if the def
	 * isn't present, same defensive shape every other filter* method here already uses. */
	private static void filterTeleportEyelineBand(JsonObject defs, int severityPercent) {
		if (!defs.has("combat_teleport_to_eyeline")) {
			return;
		}
		JsonObject bandProperty = defs.getAsJsonObject("combat_teleport_to_eyeline").getAsJsonObject("properties").getAsJsonObject("band");
		List<String> allowed = TierGates.teleportEyelineBands(TierGates.stageFor(severityPercent));
		JsonArray kept = new JsonArray();
		for (JsonElement value : bandProperty.getAsJsonArray("enum")) {
			if (allowed.contains(value.getAsString())) {
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
