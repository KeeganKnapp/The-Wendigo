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
	// spawn_at's "on_torch" option isn't an action_step, so it isn't covered by TierGates at all -
	// this is its own, separate gate, requiring BOTH the top severity tier AND a tight/mineshaft-like
	// cave (see CaveScaleScanner) - spawning on a torch and destroying it reads as a cramped, sudden
	// ambush, which doesn't fit a massive open cavern. See PlanRunner/WendigoManager for the
	// spawn-resolution side, and WendigoManager.spawnWave for the matching defensive re-check.
	private static final int SPAWN_ON_TORCH_MIN_PERCENT = 80;

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
		filterSpawnAt(schema, severityPercent, caveScale);
		filterDespawnAt(schema, caveScale);

		return schema;
	}

	/**
	 * Whether this spawn_at value is allowed at this severity/cave scale - single source of truth
	 * for both the schema filter below and WendigoManager's matching defensive re-check.
	 * - spot_a (nearest scanned spot, see WaveContext) is reserved for a tight/mineshaft-like cave
	 *   only, any severity - there's nowhere far to spawn anyway in a cramped space, so even a low-
	 *   severity encounter there can start close; a normal/massive cave never offers it.
	 * - spot_b/c/d/e/f unlock progressively closer as severity climbs (e/f at 20%, d at 40%, c at
	 *   60%, b at 80%) - the wendigo is allowed to appear progressively bolder/closer the more
	 *   established this player's relationship with it already is.
	 * - on_torch: SPAWN_ON_TORCH_MIN_PERCENT AND a tight cave (see the field's own comment).
	 * - no_players_looking: always allowed - the safe, unwatched default.
	 */
	public static boolean isSpawnSpotAllowed(String label, int severityPercent, CaveScale caveScale) {
		return switch (label) {
			case "spot_a" -> caveScale == CaveScale.TIGHT;
			case "spot_b" -> severityPercent >= 80;
			case "spot_c" -> severityPercent >= 60;
			case "spot_d" -> severityPercent >= 40;
			case "spot_e", "spot_f" -> severityPercent >= 20;
			case "on_torch" -> severityPercent >= SPAWN_ON_TORCH_MIN_PERCENT && caveScale == CaveScale.TIGHT;
			default -> true; // "no_players_looking"
		};
	}

	/** Whether this despawn_at value is allowed at this cave scale - not tiered by severity at all,
	 * unlike spawn_at; spot_a/spot_b are reserved for a tight cave same as their spawn_at rule, every
	 * other scanned spot is a valid despawn point at any severity. */
	public static boolean isDespawnSpotAllowed(String label, CaveScale caveScale) {
		return switch (label) {
			case "spot_a", "spot_b" -> caveScale == CaveScale.TIGHT;
			default -> true; // spot_c, spot_d, spot_e, spot_f
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

	private static void filterSpawnAt(JsonObject schema, int severityPercent, CaveScale caveScale) {
		JsonObject spawnAt = schema.getAsJsonObject("properties").getAsJsonObject("spawn_at");
		JsonArray kept = new JsonArray();
		for (JsonElement value : spawnAt.getAsJsonArray("enum")) {
			if (isSpawnSpotAllowed(value.getAsString(), severityPercent, caveScale)) {
				kept.add(value);
			}
		}
		spawnAt.add("enum", kept);
	}

	private static void filterDespawnAt(JsonObject schema, CaveScale caveScale) {
		JsonObject despawnAt = schema.getAsJsonObject("properties").getAsJsonObject("despawn_at");
		JsonArray kept = new JsonArray();
		for (JsonElement value : despawnAt.getAsJsonArray("enum")) {
			if (isDespawnSpotAllowed(value.getAsString(), caveScale)) {
				kept.add(value);
			}
		}
		despawnAt.add("enum", kept);
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
