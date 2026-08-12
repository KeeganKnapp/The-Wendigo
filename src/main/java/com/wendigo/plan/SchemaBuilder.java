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

	/** A fresh, filtered copy of the base schema for this severity - safe to mutate further, callers
	 * never share the returned instance. caveScale is no longer read by anything in here (distance is
	 * entirely a runtime concern now - see SemanticBands.actionSearchMinDistance - not a schema-time
	 * one), kept in the signature since every real caller already resolves a live CaveScale anyway and
	 * a future filter might reasonably want it again. */
	public static JsonObject forSeverity(int severityPercent, CaveScale caveScale) {
		JsonObject schema = JsonParser.parseString(BASE_SCHEMA_JSON).getAsJsonObject();
		JsonObject defs = schema.getAsJsonObject("$defs");

		filterActionStepUnion(defs, severityPercent);
		filterSoundCue(defs, severityPercent);
		filterStareBand(defs, severityPercent);
		filterTeleportType(defs, severityPercent);
		filterApproachType(defs, severityPercent);

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

	/** combat.teleport's own "destination" field, restricted to exactly
	 * TierGates.teleportTypesUnlocked(stage) - the action type is always available (see
	 * TierGates.minPercentFor's own comment - not listed there at all), so this is the actual gating
	 * mechanism for this whole capability. No-op if the def isn't present (already stripped some other
	 * way, or the base schema changed) - same defensive shape filterSoundCue already uses. */
	private static void filterTeleportType(JsonObject defs, int severityPercent) {
		if (!defs.has("combat_teleport")) {
			return;
		}
		JsonObject destinationProperty = defs.getAsJsonObject("combat_teleport").getAsJsonObject("properties").getAsJsonObject("destination");
		List<String> allowed = TierGates.teleportTypesUnlocked(TierGates.stageFor(severityPercent));
		JsonArray kept = new JsonArray();
		for (JsonElement value : destinationProperty.getAsJsonArray("enum")) {
			if (allowed.contains(value.getAsString())) {
				kept.add(value);
			}
		}
		destinationProperty.add("enum", kept);
	}

	/** movement.approach_spot's own "destination" field, restricted to exactly
	 * TierGates.approachTypesUnlocked(stage) - same shape as filterTeleportType right above, its own
	 * separate (looser) type-unlock table. No-op if the def isn't present, same defensive shape every
	 * other filter* method here already uses. */
	private static void filterApproachType(JsonObject defs, int severityPercent) {
		if (!defs.has("movement_approach_spot")) {
			return;
		}
		JsonObject destinationProperty = defs.getAsJsonObject("movement_approach_spot").getAsJsonObject("properties").getAsJsonObject("destination");
		List<String> allowed = TierGates.approachTypesUnlocked(TierGates.stageFor(severityPercent));
		JsonArray kept = new JsonArray();
		for (JsonElement value : destinationProperty.getAsJsonArray("enum")) {
			if (allowed.contains(value.getAsString())) {
				kept.add(value);
			}
		}
		destinationProperty.add("enum", kept);
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
