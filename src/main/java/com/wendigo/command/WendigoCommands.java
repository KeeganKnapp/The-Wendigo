package com.wendigo.command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;

import com.wendigo.WendigoMod;
import com.wendigo.entity.WendigoEntity;

/**
 * Debug-only commands for exercising the LLM/plan-execution subsystem: {@code llmtest} proves
 * the round trip to the API and prints the raw parsed plan, {@code plantest} injects a
 * hand-written plan straight into the nearest WendigoEntity's PlanRunner (bypassing the API and
 * the wave system entirely), {@code wave} forces WendigoManager to start a real wave (LLM call
 * included) targeting a given player immediately, and {@code wavetest} does the same but reads a
 * hand-authored plan from a JSON file instead of calling the LLM - free, repeatable iteration on
 * spawn/despawn behavior. All bypass the normal cooldown/severity gating, since waiting on real
 * y<0 dwell time isn't practical to test with.
 */
public final class WendigoCommands {
	private static final String DEFAULT_SCENARIO =
		"A player is standing 6 blocks away in a dimly lit cave. The wendigo is currently idle.";

	private static final String DEFAULT_TEST_PLAN_FILE = "test-plan.json";
	private static final String DEFAULT_TEST_PLAN_CONTENT = """
		{
		  "spawn_at": "spot_a",
		  "plan": [
		    { "type": "posture.stare", "enabled": true },
		    { "type": "timing.wait", "duration": "short" },
		    { "type": "movement.approach", "speed": "slow" }
		  ],
		  "despawn_at": "spot_d"
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
							StringArgumentType.getString(ctx, "file"))))));
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
		WendigoMod.llmClient.requestPlan(systemPrompt, scenario)
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
