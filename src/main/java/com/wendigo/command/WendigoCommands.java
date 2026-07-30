package com.wendigo.command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.wendigo.WendigoMod;
import com.wendigo.debug.WendigoDebug;
import com.wendigo.entity.ModEntities;
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
 * path particles for whatever wave is currently active, plus Night Vision for the duration (removed
 * when toggled back off). {@code aggression get/set} reads or
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
			.then(Commands.literal("orbit")
				.executes(ctx -> forceOrbit(ctx.getSource(), ctx.getSource().getPlayerOrException()))
				.then(Commands.argument("target", EntityArgument.player())
					.executes(ctx -> forceOrbit(ctx.getSource(), EntityArgument.getPlayer(ctx, "target")))))
			.then(Commands.literal("headtest")
				.executes(ctx -> spawnHeadTrackingTest(ctx.getSource())))
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
	// Far past any realistic debug session length (~14 hours) so it never flicker-warns near
	// expiring - toggling debug back off removes it explicitly instead of ever letting it run out.
	private static final int DEBUG_NIGHT_VISION_DURATION_TICKS = 999999;

	private static int toggleDebug(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		boolean nowEnabled = WendigoDebug.toggle(player);
		if (nowEnabled) {
			player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, DEBUG_NIGHT_VISION_DURATION_TICKS, 0, true, true));
		} else {
			player.removeEffect(MobEffects.NIGHT_VISION);
		}
		source.sendSystemMessage(Component.literal("[wendigo] Debug mode " + (nowEnabled ? "enabled" : "disabled") + "."));
		return 1;
	}

	// headtest's tunnel: floor (y-1), one wall (z-1, 5 tall), ceiling (y+5) - open corridor at z=baseZ
	// (where the lineup stands) plus a second open column at z=baseZ+1 (where the tester walks past,
	// so both floor and wall/ceiling attachments are visible from the same walk-through) - both
	// forcibly cleared to air first in case the rig lands inside existing terrain.
	private static final int HEADTEST_SLOT_SPACING = 3;
	private static final int HEADTEST_TUNNEL_HEIGHT = 5;
	// Standard Minecraft yaw convention (verified against WendigoVisual.lookAtPlayerYaw's own -90
	// offset): 0=south, 90=west, 180=north, 270=east.
	private static final float YAW_SOUTH = 0f;
	private static final float YAW_WEST = 90f;
	private static final float YAW_NORTH = 180f;
	private static final float YAW_EAST = 270f;

	/** One lineup slot's spawn parameters - see spawnHeadTrackingTest's own comment for why the yaw
	 * sweep matters (it's exactly the axis the E/W head-flip bug turned out to depend on). */
	private record HeadTestSlot(String label, Direction normal, boolean chasing, float yaw, boolean lowCeiling) {}

	private static final List<HeadTestSlot> HEADTEST_SLOTS = List.of(
		// Open headroom, standing - the REST_POSE whole-body-turn codepath (only reachable when NOT
		// crawling), kept as a baseline distinct from every other slot below.
		new HeadTestSlot("floor, standing, staring", Direction.UP, false, YAW_SOUTH, false),
		// Forced into a 2-tall alcove (see buildHeadTestTunnel) so these actually exercise the
		// crawl-pose headLookExtra codepath instead of standing pose ignoring it - a stationary,
		// zero-velocity, open-headroom "chasing" dummy would otherwise never leave Pose.STANDING and
		// silently test nothing (isMoving() reads false, and normal open floor headroom means
		// hasStandingClearanceHere() reads true - see updatePose).
		new HeadTestSlot("floor, crawling, chasing, facing south", Direction.UP, true, YAW_SOUTH, true),
		new HeadTestSlot("floor, crawling, chasing, facing west", Direction.UP, true, YAW_WEST, true),
		new HeadTestSlot("floor, crawling, chasing, facing north", Direction.UP, true, YAW_NORTH, true),
		new HeadTestSlot("floor, crawling, chasing, facing east", Direction.UP, true, YAW_EAST, true),
		// Wall/ceiling attachment already forces crawl pose unconditionally (isRestingOnFloor()
		// false), so no low-ceiling carving is needed for these - every yaw here is the body's own
		// rotation while stuck to the SAME wall/ceiling face, not a different compass wall.
		new HeadTestSlot("wall, chasing, facing south", Direction.SOUTH, true, YAW_SOUTH, false),
		new HeadTestSlot("wall, chasing, facing west", Direction.SOUTH, true, YAW_WEST, false),
		new HeadTestSlot("wall, chasing, facing north", Direction.SOUTH, true, YAW_NORTH, false),
		new HeadTestSlot("wall, chasing, facing east", Direction.SOUTH, true, YAW_EAST, false),
		new HeadTestSlot("ceiling, chasing, facing south", Direction.DOWN, true, YAW_SOUTH, false),
		new HeadTestSlot("ceiling, chasing, facing west", Direction.DOWN, true, YAW_WEST, false),
		new HeadTestSlot("ceiling, chasing, facing north", Direction.DOWN, true, YAW_NORTH, false),
		new HeadTestSlot("ceiling, chasing, facing east", Direction.DOWN, true, YAW_EAST, false)
	);

	/** Spawns a stationary lineup of wendigos (floor/wall/ceiling, every compass yaw, plus one
	 * diagonal-corner incline) so head-tracking can be inspected directly by walking past them,
	 * instead of reasoning about it from code alone - see WendigoEntity.setDebugForceChasing for how
	 * "chasing" is faked without a real plan/target. Builds its own small tunnel a few blocks in
	 * front of the caller so it works regardless of nearby terrain.
	 *
	 * <p>The yaw sweep (south/west/north/east) per surface exists because the reported bug ("head
	 * tracks correctly facing north/south, flips 180 facing east/west") turned out to depend on
	 * exactly that axis - see computeClimbingHeadLookYaw's own doc comment for the root cause
	 * (fromOrientationAndYaw's handedness fix inverts the effective yaw direction, which happens to
	 * be a no-op at yaw 0/180 and a full flip at yaw +/-90). The one incline slot is a genuinely
	 * diagonal attachment normal (not achievable via nudgeTowardAttachedSurface's Direction overload)
	 * - see spawnHeadTestWendigoOnCorner. */
	private static int spawnHeadTrackingTest(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		ServerLevel level = source.getLevel();

		BlockPos playerPos = player.blockPosition();
		int baseX = playerPos.getX() + 3;
		int baseY = playerPos.getY();
		int baseZ = playerPos.getZ();
		int inclineSlotIndex = HEADTEST_SLOTS.size();
		int length = HEADTEST_SLOT_SPACING * inclineSlotIndex + 2;

		buildHeadTestTunnel(level, baseX, baseY, baseZ, length);
		carveLowCeiling(level, baseX, baseY, baseZ, HEADTEST_SLOTS);
		int inclineX = baseX + HEADTEST_SLOT_SPACING * inclineSlotIndex;
		buildInclineCorner(level, inclineX, baseY, baseZ);

		for (int i = 0; i < HEADTEST_SLOTS.size(); i++) {
			HeadTestSlot slot = HEADTEST_SLOTS.get(i);
			int x = baseX + HEADTEST_SLOT_SPACING * i;
			int y = switch (slot.normal()) {
				case SOUTH -> baseY + 2;
				case DOWN -> baseY + 4;
				default -> baseY;
			};
			spawnHeadTestWendigo(level, x, y, baseZ, slot.yaw(), slot.normal(), slot.chasing(), slot.label());
		}
		spawnHeadTestWendigoOnCorner(level, inclineX, baseY, baseZ, "incline corner, chasing");

		player.teleportTo(baseX - 2 + 0.5, baseY, baseZ + 1.5);

		source.sendSystemMessage(Component.literal("[wendigo] Head-tracking test rig built at "
			+ baseX + "," + baseY + "," + baseZ + " - walk down the open column at z=" + (baseZ + 1)
			+ " (+X direction) past " + (HEADTEST_SLOTS.size() + 1) + " dummies: a standing floor "
			+ "baseline, four crawling-floor/wall/ceiling groups each swept through south/west/north/"
			+ "east facing, and one diagonal incline corner at the far end. Every head should turn to "
			+ "track you as you pass, regardless of which way its body is facing."));
		return 1;
	}

	private static void buildHeadTestTunnel(ServerLevel level, int baseX, int baseY, int baseZ, int length) {
		for (int x = baseX - 1; x <= baseX + length; x++) {
			level.setBlockAndUpdate(new BlockPos(x, baseY - 1, baseZ), Blocks.STONE.defaultBlockState());
			level.setBlockAndUpdate(new BlockPos(x, baseY + HEADTEST_TUNNEL_HEIGHT, baseZ), Blocks.STONE.defaultBlockState());
			for (int y = baseY; y < baseY + HEADTEST_TUNNEL_HEIGHT; y++) {
				level.setBlockAndUpdate(new BlockPos(x, y, baseZ - 1), Blocks.STONE.defaultBlockState());
				level.setBlockAndUpdate(new BlockPos(x, y, baseZ), Blocks.AIR.defaultBlockState());
				level.setBlockAndUpdate(new BlockPos(x, y, baseZ + 1), Blocks.AIR.defaultBlockState());
			}
		}
	}

	/** Drops a solid ceiling to baseY+2 (a genuine 2-tall gap - short of the 3-tall clearance
	 * DarkSpotScanner.hasStandingClearance requires) directly above every slot marked lowCeiling, but
	 * only over the lineup column (z=baseZ, where each dummy's own blockPosition actually sits) -
	 * the tester's walking column (baseZ+1) stays the full 5 tall throughout so nobody has to crouch
	 * through the whole rig just to reach the later slots. */
	private static void carveLowCeiling(ServerLevel level, int baseX, int baseY, int baseZ, List<HeadTestSlot> slots) {
		for (int i = 0; i < slots.size(); i++) {
			if (!slots.get(i).lowCeiling()) {
				continue;
			}
			int x = baseX + HEADTEST_SLOT_SPACING * i;
			level.setBlockAndUpdate(new BlockPos(x, baseY + 2, baseZ), Blocks.STONE.defaultBlockState());
		}
	}

	/** A single step block whose top face and south face meet in a convex, outward-facing corner -
	 * see spawnHeadTestWendigoOnCorner. The rest of that column was already cleared to air by
	 * buildHeadTestTunnel, so placing just this one block is enough to expose the corner. */
	private static void buildInclineCorner(ServerLevel level, int x, int baseY, int baseZ) {
		level.setBlockAndUpdate(new BlockPos(x, baseY, baseZ), Blocks.STONE.defaultBlockState());
	}

	private static void spawnHeadTestWendigo(ServerLevel level, int x, int y, int z, float yaw, Direction normal,
			boolean chasing, String label) {
		WendigoEntity wendigo = new WendigoEntity(ModEntities.WENDIGO, level);
		wendigo.snapTo(x + 0.5, y, z + 0.5, yaw, 0f);
		wendigo.syncPoseToSpawnPosition();
		wendigo.nudgeTowardAttachedSurface(normal);
		if (chasing) {
			wendigo.setDebugForceChasing(true);
		} else {
			wendigo.setStaring(true);
		}
		level.addFreshEntity(wendigo);
		WendigoMod.LOGGER.info("[wendigo] headtest spawned {} at {},{},{} yaw={}", label, x, y, z, yaw);
	}

	/** Spawns on the outward top/south corner of buildInclineCorner's step block - a genuinely
	 * diagonal attachment (normal roughly halfway between UP and SOUTH, an "ascending incline" rather
	 * than the ceiling-side diagonal WendigoVisual's CLIMB_SURFACE_OFFSET_BLOCKS comment already
	 * describes live-testing on, e.g. an overhang's underside). No Direction value describes that, so
	 * this positions the dummy just outside the corner and nudges it diagonally (down-and-north, into
	 * the corner) instead - AWCAPI's own collision smoothing (see the constructor's
	 * setCollisionsInclusionRange/setCollisionsSmoothingRange) resolves the blended normal from the
	 * real block geometry, the same mechanism a live convex-corner climb would go through. */
	private static void spawnHeadTestWendigoOnCorner(ServerLevel level, int stepX, int baseY, int baseZ, String label) {
		WendigoEntity wendigo = new WendigoEntity(ModEntities.WENDIGO, level);
		double x = stepX + 0.5;
		double y = baseY + 1.0;
		double z = baseZ + 1.3;
		wendigo.snapTo(x, y, z, YAW_SOUTH, 0f);
		wendigo.syncPoseToSpawnPosition();
		wendigo.nudgeTowardAttachedSurface(new Vec3(0.0, -1.0, -1.0));
		wendigo.setDebugForceChasing(true);
		level.addFreshEntity(wendigo);
		WendigoMod.LOGGER.info("[wendigo] headtest spawned {} at {},{},{}", label, x, y, z);
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

	/** Forces the nearest existing WendigoEntity into orbit mode around target (default: the sender)
	 * - see PlanRunner.startOrbit. Deliberately standalone (doesn't touch WendigoManager/WaveState -
	 * that persistence/lifecycle wiring is a separate step) so the orbit primitive itself can be
	 * tested against a manually-spawned entity (e.g. via /wendigo plantest) before the automatic
	 * spawn/despawn/orbit-transition machinery around it exists. */
	private static int forceOrbit(CommandSourceStack source, ServerPlayer target) throws CommandSyntaxException {
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
		nearest.startOrbit(target);
		source.sendSystemMessage(Component.literal("[wendigo] Entity " + nearest.getId()
			+ " now orbiting " + target.getGameProfile().name()));
		return 1;
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
