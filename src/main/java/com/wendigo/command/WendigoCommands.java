package com.wendigo.command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;

import com.wendigo.WendigoMod;
import com.wendigo.debug.WendigoDebug;
import com.wendigo.entity.WendigoEntity;
import com.wendigo.plan.SchemaBuilder;
import com.wendigo.spatial.CaveScaleScanner;

/**
 * Debug-only commands for exercising the LLM/plan-execution subsystem: {@code llmtest} proves
 * the round trip to the API and prints the raw parsed plan, {@code plantest} injects a
 * hand-written plan straight into the nearest WendigoEntity's PlanRunner (bypassing the API and
 * the wave system entirely), {@code wave} forces WendigoManager to start a real wave (LLM call
 * included) targeting a given player immediately, and {@code wavetest} does the same but reads a
 * hand-authored plan from a JSON file instead of calling the LLM - free, repeatable iteration on
 * spawn/despawn behavior. All bypass the normal cooldown/severity gating, since waiting on real
 * y&lt;0 dwell time isn't practical to test with. {@code debug} toggles the sender's own debug
 * session (see com.wendigo.debug.WendigoDebug) - chat commentary plus scanned-spot/dim-spot/live-
 * path particles for whatever wave is currently active. {@code aggression get/set} reads or
 * directly overrides a player's dweller severity, for jumping straight to a given tier instead of
 * grinding real time below y=0. {@code reset} discards the current wave and its cooldown so a fresh
 * {@code wave}/{@code wavetest} can fire right away instead of waiting for the current one to finish.
 */
public final class WendigoCommands {
	private static final String DEFAULT_SCENARIO =
		"A player is standing 6 blocks away in a dimly lit cave. The wendigo is currently idle.";

	private static final String DEFAULT_TEST_PLAN_FILE = "test-plan.json";
	private static final String DEFAULT_TEST_PLAN_CONTENT = """
	{
	"spawn_at": "spot_a",
	"plan": [
	{ "type": "movement.approach_dim_spot", "speed": "slow" },
	{ "type": "posture.stare", "enabled": true },
	{
	"type": "control.while",
	"condition": { "type": "predicate.player_distance", "comparator": "farther_than", "distance": "lunge_distance" },
	"body": [
	{ "type": "timing.wait", "duration": "short" }
	],
	"max_iterations": "many"
	},
	{ "type": "posture.stare", "enabled": false },
	{ "type": "combat.lunge_attack", "speed": "fast" },
	{ "type": "movement.retreat_with_fallback", "speed": "fast" }
	]
	}
	""";

	private WendigoCommands() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
			dispatcher.register(build()));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> build() {
		return Commands.literal("wendigo")
			.requires(source -> Commands.LEVEL_GAMEMASTERS.check(source.permissions()))
			.then(Commands.literal("llmtest")
				.executes(ctx -> runTest(ctx.getSource(), DEFAULT_SCENARIO))
				.then(Commands.argument("scenario", StringArgumentType.greedyString())
					.executes(ctx -> runTest(ctx.getSource(), StringArgumentType.getString(ctx, "scenario")))))
			.then(Commands.literal("plantest")
				.then(Commands.argument("json", StringArgumentType.greedyString())
					.executes(ctx -> injectPlan(ctx.getSource(), StringArgumentType.getString(ctx, "json")))))
			.then(Commands.literal("wave")
				.executes(ctx -> forceWave(ctx.getSource(), ctx.getSource().getPlayerOrException()))
				.then(Commands.argument("target", EntityArgument.player())
					.executes(ctx -> forceWave(ctx.getSource(), EntityArgument.getPlayer(ctx, "target")))))
			.then(Commands.literal("wavetest")
				.executes(ctx -> forceWaveTest(ctx.getSource(), ctx.getSource().getPlayerOrException(), DEFAULT_TEST_PLAN_FILE))
				.then(Commands.argument("target", EntityArgument.player())
					.executes(ctx -> forceWaveTest(ctx.getSource(), EntityArgument.getPlayer(ctx, "target"), DEFAULT_TEST_PLAN_FILE))
					.then(Commands.argument("file", StringArgumentType.string())
						.executes(ctx -> forceWaveTest(ctx.getSource(), EntityArgument.getPlayer(ctx, "target"),
							StringArgumentType.getString(ctx, "file"))))))
			.then(Commands.literal("debug")
				.executes(ctx -> toggleDebug(ctx.getSource())))
			.then(Commands.literal("reset")
				.executes(ctx -> resetForTesting(ctx.getSource())))
			.then(Commands.literal("aggression")
				.then(Commands.literal("get")
					.executes(ctx -> getAggression(ctx.getSource(), ctx.getSource().getPlayerOrException()))
					.then(Commands.argument("target", EntityArgument.player())
						.executes(ctx -> getAggression(ctx.getSource(), EntityArgument.getPlayer(ctx, "target")))))
				.then(Commands.literal("set")
					.then(Commands.argument("target", EntityArgument.player())
						.then(Commands.argument("value", IntegerArgumentType.integer(0))
							.executes(ctx -> setAggression(ctx.getSource(), EntityArgument.getPlayer(ctx, "target"),
								IntegerArgumentType.getInteger(ctx, "value")))))));
	}

	/**
	 * Toggles the sender's own debug session: chat commentary of what the wendigo is doing/hits
	 * trouble with (see PlanRunner#debugSay), plus particles for the active wave's scanned dark
	 * spots (colored per spot_a..d), their dim spots (same color, darker), and the wendigo's live
	 * path (white) - see com.wendigo.debug.WendigoDebug.
	 */
	private static int toggleDebug(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		boolean nowEnabled = WendigoDebug.toggle(player);
		source.sendSystemMessage(Component.literal("[wendigo] Debug mode " + (nowEnabled ? "enabled" : "disabled") + "."));
		return 1;
	}

	/** Reports a player's current dweller severity ("aggression") - the same number/cap the LLM sees each request. */
	private static int getAggression(CommandSourceStack source, ServerPlayer target) {
		if (WendigoMod.severityTracker == null) {
			source.sendFailure(Component.literal("Severity tracker isn't initialized."));
			return 0;
		}
		int severity = WendigoMod.severityTracker.severityOf(target);
		int cap = WendigoMod.severityTracker.severityCap();
		int percent = cap > 0 ? 100 * severity / cap : 0;
		source.sendSystemMessage(Component.literal("[wendigo] " + target.getGameProfile().name() + " aggression: "
			+ severity + "/" + cap + " (" + percent + "%)"));
		return severity;
	}

	/** Directly sets a player's dweller severity ("aggression") - jump straight to a tier for testing
	 * instead of grinding real time below y=0. Clamped to [0, cap] by PlayerSeverityTracker. */
	private static int setAggression(CommandSourceStack source, ServerPlayer target, int value) {
		if (WendigoMod.severityTracker == null) {
			source.sendFailure(Component.literal("Severity tracker isn't initialized."));
			return 0;
		}
		WendigoMod.severityTracker.setSeverity(target, value);
		int actual = WendigoMod.severityTracker.severityOf(target);
		source.sendSystemMessage(Component.literal("[wendigo] Set " + target.getGameProfile().name() + " aggression to "
			+ actual + "/" + WendigoMod.severityTracker.severityCap()));
		return actual;
	}

	/** Discards the current level's active/pending wave (if any) and zeroes its cooldown, so a fresh
	 * /wendigo wave can fire immediately instead of waiting for the current encounter to finish
	 * naturally or for the post-wave cooldown to lapse afterward. */
	private static int resetForTesting(CommandSourceStack source) {
		if (WendigoMod.wendigoManager == null) {
			source.sendFailure(Component.literal("Wendigo manager isn't initialized."));
			return 0;
		}
		WendigoMod.wendigoManager.resetForTesting(source.getLevel());
		source.sendSystemMessage(Component.literal("[wendigo] Reset - a new /wendigo wave can fire immediately."));
		return 1;
	}

	/** Forces WendigoManager to start a wave targeting the given player right now, bypassing cooldown/severity checks. */
	private static int forceWave(CommandSourceStack source, ServerPlayer target) {
		if (WendigoMod.wendigoManager == null) {
			source.sendFailure(Component.literal("Wendigo manager isn't initialized."));
			return 0;
		}
		WendigoMod.wendigoManager.forceWave(source.getLevel(), target);
		source.sendSystemMessage(Component.literal("[wendigo] Forced a wave targeting " + target.getGameProfile().name()));
		return 1;
	}

	/**
	 * Same as {@link #forceWave}, but skips the LLM entirely and runs a hand-authored plan from
	 * config/&lt;file&gt; (default {@value #DEFAULT_TEST_PLAN_FILE}) through the real spawn/despawn
	 * lifecycle - edit the file and re-run the command to iterate without spending API calls.
	 */
	private static int forceWaveTest(CommandSourceStack source, ServerPlayer target, String fileName) {
		if (WendigoMod.wendigoManager == null) {
			source.sendFailure(Component.literal("Wendigo manager isn't initialized."));
			return 0;
		}
		JsonObject plan;
		try {
			plan = loadTestPlan(fileName);
		} catch (IOException e) {
			source.sendFailure(Component.literal("[wendigo] Failed to read " + fileName + ": " + e.getMessage()));
			return 0;
		} catch (JsonParseException | IllegalStateException e) {
			source.sendFailure(Component.literal("[wendigo] " + fileName + " isn't valid JSON: " + e.getMessage()));
			return 0;
		}
		WendigoMod.wendigoManager.forceWaveWithPlan(source.getLevel(), target, plan);
		source.sendSystemMessage(Component.literal("[wendigo] Forced a wave from " + fileName + " targeting " + target.getGameProfile().name()));
		return 1;
	}

	/** Loads a hand-authored plan from config/&lt;fileName&gt;, writing a starter example on first use. */
	private static JsonObject loadTestPlan(String fileName) throws IOException {
		Path path = FabricLoader.getInstance().getConfigDir().resolve(fileName);
		if (!Files.exists(path)) {
			Files.createDirectories(path.getParent());
			Files.writeString(path, DEFAULT_TEST_PLAN_CONTENT);
		}
		return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
	}

	/** Feeds a raw plan JSON straight to the nearest WendigoEntity, e.g. {@code /wendigo plantest {"plan":[{"type":"control.none"}]}}. */
	private static int injectPlan(CommandSourceStack source, String json) {
		var level = source.getLevel();
		var pos = source.getPosition();
		WendigoEntity nearest = level
			.getEntitiesOfClass(WendigoEntity.class, new AABB(pos, pos).inflate(100))
			.stream()
			.findFirst()
			.orElse(null);
		if (nearest == null) {
			source.sendFailure(Component.literal("No WendigoEntity within 100 blocks."));
			return 0;
		}
		JsonObject plan = JsonParser.parseString(json).getAsJsonObject();
		nearest.debugInjectPlan(plan);
		source.sendSystemMessage(Component.literal("[wendigo] Injected plan into entity " + nearest.getId()));
		return 1;
	}

	private static int runTest(CommandSourceStack source, String scenario) {
		if (WendigoMod.llmClient == null) {
			source.sendFailure(Component.literal("Wendigo LLM client isn't initialized."));
			return 0;
		}

		source.sendSystemMessage(Component.literal("[wendigo] Asking the LLM for a plan..."));

		String systemPrompt = "You control a wendigo, a shadow-dwelling stalker creature in Minecraft. "
			+ "Given the scenario, choose a short plan (1-6 steps) using only the provided action schema.";

		var server = source.getServer();
		// Raw connectivity test, not tied to any player/severity - full unfiltered schema (TIGHT
		// unlocks spot_a too, torchSpawnAvailable=true unlocks spawn_on_torch too, maximizing schema
		// coverage for this test).
		WendigoMod.llmClient.requestPlan(systemPrompt, scenario, SchemaBuilder.forSeverity(100, CaveScaleScanner.CaveScale.TIGHT, true))
			.whenComplete((plan, error) -> server.execute(() -> {
				if (error != null) {
					source.sendFailure(Component.literal("[wendigo] LLM request failed: " + error.getMessage()));
					WendigoMod.LOGGER.error("LLM test command failed", error);
				} else {
					source.sendSystemMessage(Component.literal("[wendigo] Plan: " + plan));
				}
			}));

		return 1;
	}
}
