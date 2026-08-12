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
import com.wendigo.debug.StareHeadTest;
import com.wendigo.debug.WendigoDebug;
import com.wendigo.debug.WendigoDebugItems;
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
 * when toggled back off), plus a one-time grant of the three WendigoDebugItems testing tools (bug
 * bookmark, stage-5 spawner, tracked-spider summoner). {@code runs get/set} reads or
 * directly overrides a player's completed-run count (which stage that puts them at), for jumping
 * straight to a given stage instead of grinding out real encounters. {@code startrun} skips the
 * 2000-tick eligibility wait for a fresh or already-active run - still needs the target to actually
 * be under y=0 for it to pick up. {@code reset} discards the current wave and its cooldown so a fresh
 * {@code wave}/{@code wavetest} can fire right away instead of waiting for the current one to finish.
 * {@code summon all/base/eyes} spawns a stationary, staring dummy in front of the caller with
 * only the requested rig layer (or all of them, stacked normally) given real items, for inspecting
 * how each texture lines up on the model in isolation. {@code headoffset} (no args) reports the
 * current standing rest-pose head-yaw correction override, {@code headoffset <degrees>} sets it, and
 * {@code headoffset reset} clears it back to the default - live-tunable so the right value can be
 * found by eye instead of a rebuild-and-restart per guess. {@code trackheadoffset} is the same idea
 * for the separate active-tracking (stare/chase, crawl pose, floor) head-yaw correction instead, and
 * {@code climbheadoffset} the same again for active-tracking while climbing (wall/ceiling/slope).
 * {@code crawlpitch}/{@code climbpitch} toggle the flat-floor/climbing crawl-tracking head pitch
 * negation the same way.
 */
public final class WendigoCommands {
	private static final String DEFAULT_SCENARIO =
		"A player is standing 6 blocks away in a dimly lit cave. The wendigo is currently idle.";

	private static final String DEFAULT_TEST_PLAN_FILE = "test-plan.json";
	private static final String DEFAULT_TEST_PLAN_CONTENT = """
	{
	"plan": [
	{ "type": "movement.approach_spot", "destination": "unwatched", "speed": "slow" },
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
			.then(Commands.literal("verbose")
				.executes(ctx -> toggleVerbose(ctx.getSource())))
			.then(Commands.literal("orbit")
				.executes(ctx -> forceOrbit(ctx.getSource(), ctx.getSource().getPlayerOrException()))
				.then(Commands.argument("target", EntityArgument.player())
					.executes(ctx -> forceOrbit(ctx.getSource(), EntityArgument.getPlayer(ctx, "target")))))
			.then(Commands.literal("headtest")
				.executes(ctx -> spawnHeadTrackingTest(ctx.getSource()))
				.then(Commands.literal("stare")
					.executes(ctx -> spawnStareHeadTest(ctx.getSource()))
					.then(Commands.literal("stop")
						.executes(ctx -> stopStareHeadTest(ctx.getSource())))))
			.then(Commands.literal("summon")
				.then(Commands.literal("all")
					.executes(ctx -> spawnTexturePreview(ctx.getSource(), WendigoEntity.TexturePreviewMode.ALL, "all")))
				.then(Commands.literal("base")
					.executes(ctx -> spawnTexturePreview(ctx.getSource(), WendigoEntity.TexturePreviewMode.BASE_ONLY, "base")))
				.then(Commands.literal("eyes")
					.executes(ctx -> spawnTexturePreview(ctx.getSource(), WendigoEntity.TexturePreviewMode.EYES_ONLY, "eyes"))))
			.then(Commands.literal("reset")
				.executes(ctx -> resetForTesting(ctx.getSource())))
			.then(Commands.literal("headoffset")
				.executes(ctx -> reportHeadOffset(ctx.getSource()))
				.then(Commands.literal("reset")
					.executes(ctx -> resetHeadOffset(ctx.getSource())))
				.then(Commands.argument("degrees", IntegerArgumentType.integer(-360, 360))
					.executes(ctx -> setHeadOffset(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "degrees")))))
			.then(Commands.literal("trackheadoffset")
				.executes(ctx -> reportTrackHeadOffset(ctx.getSource()))
				.then(Commands.literal("reset")
					.executes(ctx -> resetTrackHeadOffset(ctx.getSource())))
				.then(Commands.argument("degrees", IntegerArgumentType.integer(-360, 360))
					.executes(ctx -> setTrackHeadOffset(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "degrees")))))
			.then(Commands.literal("climbheadoffset")
				.executes(ctx -> reportClimbTrackHeadOffset(ctx.getSource()))
				.then(Commands.literal("reset")
					.executes(ctx -> resetClimbTrackHeadOffset(ctx.getSource())))
				.then(Commands.argument("degrees", IntegerArgumentType.integer(-360, 360))
					.executes(ctx -> setClimbTrackHeadOffset(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "degrees")))))
			.then(Commands.literal("crawlpitch")
				.executes(ctx -> toggleCrawlPitch(ctx.getSource())))
			.then(Commands.literal("climbpitch")
				.executes(ctx -> toggleClimbPitch(ctx.getSource())))
			.then(Commands.literal("runs")
				.then(Commands.literal("get")
					.executes(ctx -> getRuns(ctx.getSource(), ctx.getSource().getPlayerOrException()))
					.then(Commands.argument("target", EntityArgument.player())
						.executes(ctx -> getRuns(ctx.getSource(), EntityArgument.getPlayer(ctx, "target")))))
				.then(Commands.literal("set")
					.then(Commands.argument("target", EntityArgument.player())
						.then(Commands.argument("value", IntegerArgumentType.integer(0))
							.executes(ctx -> setRuns(ctx.getSource(), EntityArgument.getPlayer(ctx, "target"),
								IntegerArgumentType.getInteger(ctx, "value")))))))
			.then(Commands.literal("startrun")
				.executes(ctx -> startRun(ctx.getSource(), ctx.getSource().getPlayerOrException()))
				.then(Commands.argument("target", EntityArgument.player())
					.executes(ctx -> startRun(ctx.getSource(), EntityArgument.getPlayer(ctx, "target")))));
	}

	/**
	 * Toggles the sender's own debug session: chat commentary of what the wendigo is doing/hits
	 * trouble with (see PlanRunner#debugSay), plus a particle trail for the wendigo's live path
	 * (white) - see com.wendigo.debug.WendigoDebug.
	 */
	// Far past any realistic debug session length (~14 hours) so it never flicker-warns near
	// expiring - toggling debug back off removes it explicitly instead of ever letting it run out.
	private static final int DEBUG_NIGHT_VISION_DURATION_TICKS = 999999;

	private static int toggleDebug(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		boolean nowEnabled = WendigoDebug.toggle(player);
		if (nowEnabled) {
			player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, DEBUG_NIGHT_VISION_DURATION_TICKS, 0, true, true));
			WendigoDebugItems.giveDebugItems(player);
		} else {
			player.removeEffect(MobEffects.NIGHT_VISION);
		}
		source.sendSystemMessage(Component.literal("[wendigo] Debug mode " + (nowEnabled ? "enabled" : "disabled") + "."));
		return 1;
	}

	/** See WendigoDebug.verboseEnabled's own doc comment - the per-tick diagnostic dumps (WDIAG,
	 * PATH_NODES, CHASE_REPATH, NEARBY_SURFACES, FLOOR_COLUMN, ONGROUND_TRANSITION) and PlanRunner's
	 * own high-volume chat commentary, both quieted by default now. Server-wide, not per-player, same
	 * as crawlpitch/climbpitch above - unlike the main debug toggle, this doesn't gate whether a
	 * session exists at all, just how much it says once one does. */
	private static int toggleVerbose(CommandSourceStack source) {
		boolean nowEnabled = !WendigoDebug.verboseEnabled();
		WendigoDebug.setVerbose(nowEnabled);
		source.sendSystemMessage(Component.literal("[wendigo] Verbose debug output " + (nowEnabled ? "enabled." : "disabled.")));
		return 1;
	}

	/** Live-overrides the standing rest-pose head-yaw correction (see WendigoVisual's onTick) so the
	 * right value can be found by eye - stand a wendigo in front of you (e.g. `/wendigo summon all`),
	 * make it stare (`/wendigo plantest {"plan":[{"type":"posture.stare","enabled":true}]}` or just
	 * approach it), then run this repeatedly with different values until the face reads correctly.
	 * Server-wide, not per-player (same scope as every other WendigoDebug toggle). */
	private static int setHeadOffset(CommandSourceStack source, int degrees) {
		WendigoDebug.setStandingHeadYawOverride(degrees);
		source.sendSystemMessage(Component.literal("[wendigo] Standing head-yaw override set to " + degrees + " degrees."));
		return degrees;
	}

	private static int resetHeadOffset(CommandSourceStack source) {
		WendigoDebug.clearStandingHeadYawOverride();
		source.sendSystemMessage(Component.literal("[wendigo] Standing head-yaw override cleared - back to the default."));
		return 1;
	}

	private static int reportHeadOffset(CommandSourceStack source) {
		float current = WendigoDebug.getStandingHeadYawOverride();
		source.sendSystemMessage(Component.literal("[wendigo] Standing head-yaw override: "
			+ (Float.isNaN(current) ? "none (using the default)" : (current + " degrees"))));
		return 1;
	}

	/** Same idea as {@link #setHeadOffset}/{@link #resetHeadOffset}/{@link #reportHeadOffset}, for the
	 * active-tracking (stare/chase, crawl pose, on the floor) head-yaw correction instead - see
	 * WendigoVisual.onTick's own headLookYawDelta computation. Stand a wendigo in front of you and
	 * make it chase/stare (e.g. `/wendigo headtest stare` or a real chase) to dial this one in, since
	 * it only applies while actively tracking, unlike the standing-rest-pose override above. */
	private static int setTrackHeadOffset(CommandSourceStack source, int degrees) {
		WendigoDebug.setTrackingHeadYawOverride(degrees);
		source.sendSystemMessage(Component.literal("[wendigo] Active-tracking head-yaw override set to " + degrees + " degrees."));
		return degrees;
	}

	private static int resetTrackHeadOffset(CommandSourceStack source) {
		WendigoDebug.clearTrackingHeadYawOverride();
		source.sendSystemMessage(Component.literal("[wendigo] Active-tracking head-yaw override cleared - back to the default."));
		return 1;
	}

	private static int reportTrackHeadOffset(CommandSourceStack source) {
		float current = WendigoDebug.getTrackingHeadYawOverride();
		source.sendSystemMessage(Component.literal("[wendigo] Active-tracking head-yaw override: "
			+ (Float.isNaN(current) ? "none (using the default)" : (current + " degrees"))));
		return 1;
	}

	/** Same idea again, for the CLIMBING (wall/ceiling/slope) active-tracking head-yaw correction -
	 * see WendigoVisual.onTick's climbTrackingCorrectionDegrees. Get a wendigo staring/chasing while
	 * attached to a wall, ceiling, or slope to dial this one in - unlike the other two head-yaw
	 * overrides, there's no confirmed-correct value here yet, only a confirmed-wrong default (0). */
	private static int setClimbTrackHeadOffset(CommandSourceStack source, int degrees) {
		WendigoDebug.setClimbTrackingHeadYawOverride(degrees);
		source.sendSystemMessage(Component.literal("[wendigo] Climbing active-tracking head-yaw override set to " + degrees + " degrees."));
		return degrees;
	}

	private static int resetClimbTrackHeadOffset(CommandSourceStack source) {
		WendigoDebug.clearClimbTrackingHeadYawOverride();
		source.sendSystemMessage(Component.literal("[wendigo] Climbing active-tracking head-yaw override cleared - back to the default (0)."));
		return 1;
	}

	private static int reportClimbTrackHeadOffset(CommandSourceStack source) {
		float current = WendigoDebug.getClimbTrackingHeadYawOverride();
		source.sendSystemMessage(Component.literal("[wendigo] Climbing active-tracking head-yaw override: "
			+ (Float.isNaN(current) ? "none (using the default, 0)" : (current + " degrees"))));
		return 1;
	}

	/** Flips whether the flat-floor crawl-tracking head pitch is negated (see WendigoVisual.onTick's
	 * headLookPitch computation) - lets the current negation be A/B tested live against the plain,
	 * un-negated value instead of another rebuild-and-restart guess. */
	private static int toggleCrawlPitch(CommandSourceStack source) {
		boolean nowInverted = !WendigoDebug.isFloorCrawlPitchInverted();
		WendigoDebug.setFloorCrawlPitchInverted(nowInverted);
		source.sendSystemMessage(Component.literal("[wendigo] Flat-floor crawl head pitch is now "
			+ (nowInverted ? "inverted (current code default)." : "NOT inverted (raw computeHeadLookPitch value).")));
		return 1;
	}

	/** Same idea as {@link #toggleCrawlPitch}, for the climbing (wall/ceiling attachment) case. */
	private static int toggleClimbPitch(CommandSourceStack source) {
		boolean nowInverted = !WendigoDebug.isClimbPitchInverted();
		WendigoDebug.setClimbPitchInverted(nowInverted);
		source.sendSystemMessage(Component.literal("[wendigo] Climbing head pitch is now "
			+ (nowInverted ? "inverted (current code default)." : "NOT inverted (raw computeHeadLookPitch value).")));
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

	private static WendigoEntity spawnHeadTestWendigo(ServerLevel level, int x, int y, int z, float yaw, Direction normal,
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
		return wendigo;
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

	/** /wendigo headtest stare - the user's own explicit "we need the test to also actually be a
	 * command that we can summon dummies with so I can look at their head and see if our head
	 * obstruction + stare logic is correct" request: an in-game, walk-around-able sibling of
	 * WendigoGameTests' own stareHeadVisibilityPrintsWhileStanding/WhileCrawling. Spawns two
	 * stationary dummies (standing in the open, crawling under a dropped low ceiling - same
	 * buildHeadTestTunnel/carveLowCeiling rig spawnHeadTrackingTest already uses, just two slots
	 * instead of the full lineup) and hands them to StareHeadTest.start, which then keeps the
	 * caller's own action bar updated with live PlanPredicates.isLookingAtSelf(..., "dead_stare")
	 * results for each one every 10 ticks - walk around, behind cover, etc. and watch it update. */
	private static final List<HeadTestSlot> STARE_HEADTEST_SLOTS = List.of(
		new HeadTestSlot("stare test - standing", Direction.UP, true, YAW_SOUTH, false),
		new HeadTestSlot("stare test - crawling", Direction.UP, true, YAW_SOUTH, true));

	private static int spawnStareHeadTest(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		ServerLevel level = source.getLevel();

		BlockPos playerPos = player.blockPosition();
		int baseX = playerPos.getX() + 3;
		int baseY = playerPos.getY();
		int baseZ = playerPos.getZ();
		int length = HEADTEST_SLOT_SPACING * STARE_HEADTEST_SLOTS.size() + 1;

		buildHeadTestTunnel(level, baseX, baseY, baseZ, length);
		carveLowCeiling(level, baseX, baseY, baseZ, STARE_HEADTEST_SLOTS);

		WendigoEntity standing = spawnHeadTestWendigo(level, baseX, baseY, baseZ,
			STARE_HEADTEST_SLOTS.get(0).yaw(), STARE_HEADTEST_SLOTS.get(0).normal(),
			STARE_HEADTEST_SLOTS.get(0).chasing(), STARE_HEADTEST_SLOTS.get(0).label());
		WendigoEntity crawling = spawnHeadTestWendigo(level, baseX + HEADTEST_SLOT_SPACING, baseY, baseZ,
			STARE_HEADTEST_SLOTS.get(1).yaw(), STARE_HEADTEST_SLOTS.get(1).normal(),
			STARE_HEADTEST_SLOTS.get(1).chasing(), STARE_HEADTEST_SLOTS.get(1).label());

		StareHeadTest.start(player, standing, crawling);
		player.teleportTo(baseX - 2 + 0.5, baseY, baseZ + 1.5);

		source.sendSystemMessage(Component.literal("[wendigo] Stare head-visibility test rig built at "
			+ baseX + "," + baseY + "," + baseZ + " - a standing dummy and (" + HEADTEST_SLOT_SPACING
			+ " blocks over) a crawling one. Your action bar now shows live dead_stare status for each "
			+ "as you look around/walk behind cover - run '/wendigo headtest stare stop' to end it."));
		return 1;
	}

	private static int stopStareHeadTest(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		boolean wasActive = StareHeadTest.stop(player);
		source.sendSystemMessage(Component.literal(wasActive
			? "[wendigo] Stare head-visibility test session ended."
			: "[wendigo] No active stare head-visibility test session to stop."));
		return 1;
	}

	// How far in front of the player (along their own facing) a texture-preview dummy spawns.
	private static final double TEXTURE_PREVIEW_SPAWN_DISTANCE = 3.0;

	/** Spawns one stationary, staring wendigo a few blocks in front of the caller with only the
	 * requested rig layer(s) given real items - see WendigoEntity.TexturePreviewMode's own comment.
	 * "all" is the normal stacked appearance; "base"/"eyes" isolate one texture at a time so it can be
	 * inspected without the other layer drawn over it. Forces staring on so the whole rig turns to
	 * face the caller and the eyes layer (which only lights up while staring/chasing) is actually
	 * visible rather than reading as pitch black. */
	private static int spawnTexturePreview(CommandSourceStack source, WendigoEntity.TexturePreviewMode mode, String label)
			throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		ServerLevel level = source.getLevel();
		Vec3 look = player.getLookAngle();
		Vec3 spawnPos = player.position().add(look.x * TEXTURE_PREVIEW_SPAWN_DISTANCE, 0.0, look.z * TEXTURE_PREVIEW_SPAWN_DISTANCE);
		float yaw = player.getYRot() + 180.0f;

		WendigoEntity wendigo = new WendigoEntity(ModEntities.WENDIGO, level);
		wendigo.snapTo(spawnPos.x, spawnPos.y, spawnPos.z, yaw, 0f);
		wendigo.syncPoseToSpawnPosition();
		wendigo.setTexturePreviewMode(mode);
		wendigo.setStaring(true);
		level.addFreshEntity(wendigo);

		source.sendSystemMessage(Component.literal("[wendigo] Spawned a \"" + label + "\"-texture preview at "
			+ spawnPos.toString() + " - /kill it (or let it despawn) when you're done looking."));
		return 1;
	}

	/** Reports a player's current completed-run count and the stage/percent that puts them at - the
	 * same stage the LLM sees each request. */
	private static int getRuns(CommandSourceStack source, ServerPlayer target) {
		if (WendigoMod.progressionTracker == null) {
			source.sendFailure(Component.literal("Progression tracker isn't initialized."));
			return 0;
		}
		int completedRuns = WendigoMod.progressionTracker.completedRunsOf(target);
		int stage = WendigoMod.progressionTracker.stageOf(target);
		int percent = WendigoMod.progressionTracker.representativePercent(stage);
		source.sendSystemMessage(Component.literal("[wendigo] " + target.getGameProfile().name() + " completed runs: "
			+ completedRuns + " (stage " + stage + ", " + percent + "%)"));
		return completedRuns;
	}

	/** Directly sets a player's completed-run count - jump straight to a stage for testing instead of
	 * grinding out real encounters. Clears any in-progress run (debug jump, no partial-progress
	 * preservation expected) - see WendigoProgressionTracker.setRunsForTesting. */
	private static int setRuns(CommandSourceStack source, ServerPlayer target, int value) {
		if (WendigoMod.progressionTracker == null) {
			source.sendFailure(Component.literal("Progression tracker isn't initialized."));
			return 0;
		}
		WendigoMod.progressionTracker.setRunsForTesting(target, value);
		int actual = WendigoMod.progressionTracker.completedRunsOf(target);
		int stage = WendigoMod.progressionTracker.stageOf(target);
		source.sendSystemMessage(Component.literal("[wendigo] Set " + target.getGameProfile().name() + " completed runs to "
			+ actual + " (stage " + stage + ")"));
		return actual;
	}

	/** Marks the target immediately eligible for a run - skips WendigoProgressionTracker's own
	 * 2000-tick-under-y=0 eligibility wait entirely (see startRun's own doc comment: it resets
	 * eligibilityTicks and, unless a run was already active, opens a fresh ActiveRun on the spot),
	 * without bypassing anything else real about how a run actually starts. Doesn't force a spawn by
	 * itself - selectTarget still requires the target to genuinely be under y=0 before
	 * tryEnterOrbit's own next scheduled attempt (up to ~1s later) picks them up, same "can't follow
	 * back above ground" rule that's load-bearing everywhere else in this codebase, deliberately not
	 * bypassed just for this debug convenience. If a run was already active on them, this is a no-op
	 * beyond resetting their eligibility timer - existing progress is left untouched either way. */
	private static int startRun(CommandSourceStack source, ServerPlayer target) {
		if (WendigoMod.progressionTracker == null) {
			source.sendFailure(Component.literal("Progression tracker isn't initialized."));
			return 0;
		}
		boolean isFreshRun = WendigoMod.progressionTracker.startRun(target);
		int stage = WendigoMod.progressionTracker.stageOf(target);
		source.sendSystemMessage(Component.literal("[wendigo] " + (isFreshRun ? "Started a fresh" : "Marked eligible for the already-active")
			+ " run on " + target.getGameProfile().name() + " (stage " + stage + ") - it'll pick up as soon as they're below y=0."));
		return 1;
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
		// unlocks close_as_possible too, maximizing schema coverage for this test).
		WendigoMod.llmClient.requestPlan(systemPrompt, scenario, SchemaBuilder.forSeverity(100, CaveScaleScanner.CaveScale.TIGHT))
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
