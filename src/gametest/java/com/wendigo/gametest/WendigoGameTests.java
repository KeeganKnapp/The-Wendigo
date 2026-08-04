package com.wendigo.gametest;

import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.datafixers.util.Pair;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import com.wendigo.WendigoMod;
import com.wendigo.entity.ModEntities;
import com.wendigo.entity.WendigoEntity;
import com.wendigo.spatial.DarkSpotScanner;
import com.wendigo.wave.WendigoProgressionData;
import com.wendigo.wave.WendigoProgressionTracker;

/**
 * Headless regression scenarios for the wendigo mod, run via `./gradlew runGameTest` - no real
 * client ever connects (see makeMockPlayer, used throughout for a controllable fake player).
 *
 * <p>Instantiated by Fabric Loader itself (see src/gametest/resources/fabric.mod.json's own
 * "fabric-gametest" entrypoint, which points at this class) - needs a public no-arg constructor,
 * and every @GameTest method needs to be a genuine instance method, not static, confirmed live
 * (Fabric's own validation for this entrypoint type rejects both a private constructor and a
 * static test method, the opposite of vanilla's own GameTest convention).
 */
public final class WendigoGameTests {
	@GameTest
	public void pipelineSmokeTest(GameTestHelper helper) {
		helper.succeed();
	}

	/** First real assertion against actual mod code - proves the harness can construct/inspect a
	 * genuine WendigoEntity, not just run an empty scenario. See ModEntities.init's own comment for
	 * why 50 (not vanilla Enderman's 40). */
	@GameTest
	public void wendigoHasFiftyMaxHealth(GameTestHelper helper) {
		WendigoEntity wendigo = helper.spawn(ModEntities.WENDIGO, new BlockPos(1, 1, 1));
		helper.assertTrue(wendigo.getMaxHealth() == 50.0F,
			"expected 50 max health, got " + wendigo.getMaxHealth());
		helper.succeed();
	}

	/** This entity is a WendigoManager-owned singleton, never written to chunk NBT and never
	 * subject to vanilla's own distance-based despawn heuristic - see WendigoEntity's own
	 * shouldBeSaved/removeWhenFarAway overrides and their doc comments for the real bug (a stray
	 * duplicate on server restart) this guards against. */
	@GameTest
	public void wendigoNeverPersistsOrAutoDespawns(GameTestHelper helper) {
		WendigoEntity wendigo = helper.spawn(ModEntities.WENDIGO, new BlockPos(1, 1, 1));
		helper.assertTrue(!wendigo.shouldBeSaved(), "expected shouldBeSaved() == false");
		helper.assertTrue(!wendigo.removeWhenFarAway(1000.0), "expected removeWhenFarAway() == false");
		helper.succeed();
	}

	/** forceGrabNow (WendigoManager's own unconditional grab_distance override) should mount an
	 * ordinary player with no spear defense - the baseline case the spear-defense test below is
	 * meant to contrast against. */
	@GameTest
	public void forceGrabNowMountsAnOrdinaryPlayer(GameTestHelper helper) {
		WendigoEntity wendigo = helper.spawn(ModEntities.WENDIGO, new BlockPos(1, 1, 1));
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		wendigo.forceGrabNow(player);
		helper.assertTrue(wendigo.isForcingRide(), "expected isForcingRide() == true after an ordinary grab");
		helper.assertTrue(player.getVehicle() == wendigo, "expected the player to actually be riding the wendigo");
		helper.succeed();
	}

	/** The user's own explicit trap mechanic: a player holding any tier of spear (see
	 * PlanRunner.isPlayerDefendingWithSpear), actively using it (isUsingItem(), the vanilla charge-
	 * and-hold pose - startUsingItem here is the direct server-side equivalent of what a real
	 * client's use-item packet would trigger), and facing the wendigo should repel the grab instead
	 * of getting caught - the wendigo takes damage and is NOT mounted. Never live-tested with a real
	 * player before this. */
	@GameTest
	public void spearDefenseRepelsTheGrabAndDamagesTheWendigo(GameTestHelper helper) {
		WendigoEntity wendigo = helper.spawn(ModEntities.WENDIGO, new BlockPos(1, 1, 1));
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		player.teleportTo(wendigo.getX() + 1.0, wendigo.getY(), wendigo.getZ());
		// getVisualEyePosition(), not getEyePosition() - isPlayerDefendingWithSpear's own facing check
		// (PlanPredicates.isLookingAtSelf) targets the rig's real visual head position now, not
		// vanilla's own (deliberately much lower) default eye height - see that method's own doc
		// comment for the suffocation bug that happened the one time this got flipped the other way.
		player.lookAt(EntityAnchorArgument.Anchor.EYES, wendigo.getVisualEyePosition());
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WOODEN_SPEAR));
		player.startUsingItem(InteractionHand.MAIN_HAND);
		float healthBeforeGrab = wendigo.getHealth();

		wendigo.forceGrabNow(player);

		helper.assertTrue(!wendigo.isForcingRide(), "expected the spear defense to repel the grab, not land it");
		helper.assertTrue(player.getVehicle() != wendigo, "expected the player to never be mounted");
		helper.assertTrue(wendigo.getHealth() < healthBeforeGrab,
			"expected the wendigo to take spear damage (was " + healthBeforeGrab + ", now " + wendigo.getHealth() + ")");
		helper.succeed();
	}

	/** Direct check of DarkSpotScanner.findLiveBandPosition3D (PlanRunner.tickOrbit's replacement for
	 * the old flood-based findLiveBandPosition - see that method's own doc comment for the bug this
	 * fixes): proves a ceiling position (normal=DOWN) is actually discoverable and lands well above
	 * the player, distinct from a floor position (normal=UP) landing at/near the player's own level -
	 * the old flood-based method routinely failed to even seed a ceiling search from a floor-standing
	 * position, which this test would have caught. Builds its own small sealed stone room (floor,
	 * ceiling, four walls) rather than relying on the default test platform's own bounds/lighting, so
	 * the ceiling height here is known and the room is guaranteed dark. */
	@GameTest
	public void findLiveBandPosition3DFindsBothFloorAndCeiling(GameTestHelper helper) {
		int roomHeight = 9;
		for (int x = 0; x <= 6; x++) {
			for (int z = 0; z <= 6; z++) {
				helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
				helper.setBlock(new BlockPos(x, roomHeight, z), Blocks.STONE);
			}
		}
		for (int y = 1; y < roomHeight; y++) {
			for (int x = 0; x <= 6; x++) {
				helper.setBlock(new BlockPos(x, y, 0), Blocks.STONE);
				helper.setBlock(new BlockPos(x, y, 6), Blocks.STONE);
			}
			for (int z = 0; z <= 6; z++) {
				helper.setBlock(new BlockPos(0, y, z), Blocks.STONE);
				helper.setBlock(new BlockPos(6, y, z), Blocks.STONE);
			}
		}

		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		// helper.setBlock above placed the room in TEST-STRUCTURE-relative coordinates (like
		// helper.spawn always does internally) - unlike those two, Entity.teleportTo has no concept
		// of a test structure at all and takes plain absolute world coordinates, so the relative room
		// position has to be converted via helper.absolutePos explicitly here or the player ends up
		// nowhere near the room this test just built.
		BlockPos playerAbsolute = helper.absolutePos(new BlockPos(3, 1, 3));
		player.teleportTo(playerAbsolute.getX() + 0.5, playerAbsolute.getY(), playerAbsolute.getZ() + 0.5);
		BlockPos playerPos = player.blockPosition();

		// maxDistance generous relative to the room's own 8-block floor-to-ceiling gap (straight-up
		// distance alone is already 8) - leaves real horizontal slack for a sampled point to land
		// anywhere across the room's footprint and still fall inside the band. The room's own
		// footprint (7x7) is small relative to a full sphere of radius up to 12, so any single
		// findLiveBandPosition3D call has a real chance of exhausting its 80 samples without one
		// landing inside the room at all (a sample missing the room isn't a bug - see the method's own
		// doc comment, it's not flood-guaranteed reachable/discoverable, by design) - retry a handful
		// of times, same as any other randomized-search caller in this codebase already does
		// (findUnwatchedPosition's own UNWATCHED_POSITION_ATTEMPTS), rather than requiring a single
		// call to succeed against a small test room.
		BlockPos ceiling = null;
		BlockPos floor = null;
		for (int attempt = 0; attempt < 20 && (ceiling == null || floor == null); attempt++) {
			if (ceiling == null) {
				ceiling = DarkSpotScanner.findLiveBandPosition3D(helper.getLevel(), playerPos, 0.0, 12.0, Direction.DOWN);
			}
			if (floor == null) {
				floor = DarkSpotScanner.findLiveBandPosition3D(helper.getLevel(), playerPos, 0.0, 12.0, Direction.UP);
			}
		}

		helper.assertTrue(ceiling != null, "expected a ceiling position to be found");
		helper.assertTrue(floor != null, "expected a floor position to be found");
		helper.assertTrue(ceiling.getY() > playerPos.getY() + 2,
			"expected the ceiling pick to sit well above the player, got " + ceiling);
		helper.assertTrue(floor.getY() <= playerPos.getY() + 1,
			"expected the floor pick to sit at/near the player's own floor level, got " + floor);
		helper.succeed();
	}

	/** /wendigo plantest's own entry point (debugInjectPlan -> PlanRunner.startRaw), exercised end
	 * to end with the simplest possible plan body: a bare control.despawn, bypassing tier gating (as
	 * debugInjectPlan always does), so it should resolve as an immediate, same-tick vanish rather
	 * than a real flee - see WendigoManager's own control.despawn description. Proves the whole
	 * JSON-plan -> PlanRunner -> action-resolution pipeline actually runs, not just entity
	 * construction. */
	@GameTest
	public void injectedDespawnOnlyPlanCompletesImmediately(GameTestHelper helper) {
		WendigoEntity wendigo = helper.spawn(ModEntities.WENDIGO, new BlockPos(1, 1, 1));
		JsonObject despawnStep = new JsonObject();
		despawnStep.addProperty("type", "control.despawn");
		JsonArray steps = new JsonArray();
		steps.add(despawnStep);
		JsonObject plan = new JsonObject();
		plan.add("plan", steps);
		plan.add("global_rules", new JsonArray());

		wendigo.debugInjectPlan(plan);

		helper.succeedWhen(() -> helper.assertTrue(wendigo.isWaveComplete(),
			"expected the injected despawn-only plan to complete"));
	}

	/** combat.teleport_behind (stage 5 only outside debug - debugInjectPlan bypasses tier gating the
	 * same way every other injected plan does, see startRaw) - proves the whole
	 * DarkSpotScanner.findNearestUnwatchedDarkSpot -> PlanRunner teleport pipeline actually relocates
	 * the entity somewhere new, not just resolves the action as a no-op. */
	@GameTest
	public void injectedTeleportBehindPlanRelocatesTheEntity(GameTestHelper helper) {
		WendigoEntity wendigo = helper.spawn(ModEntities.WENDIGO, new BlockPos(5, 1, 5));
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		player.teleportTo(1.5, 1.0, 1.5);
		BlockPos startPos = wendigo.blockPosition();

		JsonObject teleportStep = new JsonObject();
		teleportStep.addProperty("type", "combat.teleport_behind");
		JsonArray steps = new JsonArray();
		steps.add(teleportStep);
		JsonObject plan = new JsonObject();
		plan.add("plan", steps);
		plan.add("global_rules", new JsonArray());

		wendigo.debugInjectPlan(plan);

		helper.succeedWhen(() -> {
			helper.assertTrue(wendigo.isWaveComplete(), "expected the injected teleport_behind plan to complete");
			helper.assertTrue(!wendigo.blockPosition().equals(startPos),
				"expected combat.teleport_behind to actually relocate the entity, not leave it in place");
		});
	}

	/** movement.approach_band(spot_above) chained into movement.drop (both unlocked at 60% outside
	 * debug - debugInjectPlan bypasses tier gating) - proves WendigoEntity.forceDetach actually causes
	 * a real physical fall, not just a no-op or a purely cosmetic state flip, when used the way the
	 * user's own request actually suggests pairing it (spot_above, then drop). Deliberately NOT a raw
	 * teleport-spawn onto the ceiling followed by a bare drop - live-debugged that setup into a real,
	 * separate bug: a spawn that never did any genuine climbing movement never gets AWCAPI's own
	 * ClimberComponent.attachmentNormal/orientation to converge away from its plain-floor default
	 * (confirmed via a debug log showing normal.y still reading ~1.0 while resting flush against a
	 * ceiling), and forceDetach's push - correctly computed from the OTHER, always-accurate
	 * getGroundDirection() geometric probe - then fights AWCAPI's own internal re-attachment snap
	 * inside travelOnGround, which uses that same stale orientation and pulls the entity right back to
	 * the exact same resting position. A real climb (this test's own first leg) gives AWCAPI's normal
	 * per-tick attachment search every chance to converge correctly before the drop ever runs, matching
	 * how this action is actually meant to be reached in a real plan. Own sealed stone room for the
	 * same guaranteed-dark, known-geometry reasons the other tests above need one. */
	@GameTest(maxTicks = 600)
	public void injectedSpotAboveThenDropPlanReachesCeilingThenFalls(GameTestHelper helper) {
		int roomHeight = 9;
		for (int x = 0; x <= 6; x++) {
			for (int z = 0; z <= 6; z++) {
				helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
				helper.setBlock(new BlockPos(x, roomHeight, z), Blocks.STONE);
			}
		}
		for (int y = 1; y < roomHeight; y++) {
			for (int x = 0; x <= 6; x++) {
				helper.setBlock(new BlockPos(x, y, 0), Blocks.STONE);
				helper.setBlock(new BlockPos(x, y, 6), Blocks.STONE);
			}
			for (int z = 0; z <= 6; z++) {
				helper.setBlock(new BlockPos(0, y, z), Blocks.STONE);
				helper.setBlock(new BlockPos(6, y, z), Blocks.STONE);
			}
		}

		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		BlockPos playerAbsolute = helper.absolutePos(new BlockPos(3, 1, 3));
		player.teleportTo(playerAbsolute.getX() + 0.5, playerAbsolute.getY(), playerAbsolute.getZ() + 0.5);

		WendigoEntity wendigo = helper.spawn(ModEntities.WENDIGO, new BlockPos(3, 1, 1));
		double startY = wendigo.getY();

		JsonObject approachStep = new JsonObject();
		approachStep.addProperty("type", "movement.approach_band");
		approachStep.addProperty("band", "spot_above");
		approachStep.addProperty("speed", "fast");
		JsonObject dropStep = new JsonObject();
		dropStep.addProperty("type", "movement.drop");
		JsonArray steps = new JsonArray();
		steps.add(approachStep);
		steps.add(dropStep);
		JsonObject plan = new JsonObject();
		plan.add("plan", steps);
		plan.add("global_rules", new JsonArray());

		wendigo.debugInjectPlan(plan);

		boolean[] reachedCeiling = {false};
		helper.succeedWhen(() -> {
			if (wendigo.getY() > startY + 3.0) {
				reachedCeiling[0] = true;
			}
			helper.assertTrue(reachedCeiling[0], "expected the wendigo to reach up near the ceiling first");
			helper.assertTrue(wendigo.isWaveComplete(), "expected the injected spot_above + drop plan to complete");
			helper.assertTrue(wendigo.getY() < startY + 2.0,
				"expected movement.drop to cause a real fall back down after reaching the ceiling - ended at y="
					+ wendigo.getY());
		});
	}

	/** movement.approach_band(spot_above) (unlocked at 60% outside debug - debugInjectPlan bypasses
	 * tier gating) - proves the "spot_above" band actually resolves to a real ceiling position above
	 * the player and paths there, not just a no-op. Own sealed stone room for the same
	 * guaranteed-dark, known-geometry reasons the other tests above need one. */
	@GameTest(maxTicks = 400)
	public void injectedApproachSpotAbovePlanReachesTheCeiling(GameTestHelper helper) {
		int roomHeight = 9;
		for (int x = 0; x <= 6; x++) {
			for (int z = 0; z <= 6; z++) {
				helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
				helper.setBlock(new BlockPos(x, roomHeight, z), Blocks.STONE);
			}
		}
		for (int y = 1; y < roomHeight; y++) {
			for (int x = 0; x <= 6; x++) {
				helper.setBlock(new BlockPos(x, y, 0), Blocks.STONE);
				helper.setBlock(new BlockPos(x, y, 6), Blocks.STONE);
			}
			for (int z = 0; z <= 6; z++) {
				helper.setBlock(new BlockPos(0, y, z), Blocks.STONE);
				helper.setBlock(new BlockPos(6, y, z), Blocks.STONE);
			}
		}

		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		BlockPos playerAbsolute = helper.absolutePos(new BlockPos(3, 1, 3));
		player.teleportTo(playerAbsolute.getX() + 0.5, playerAbsolute.getY(), playerAbsolute.getZ() + 0.5);

		WendigoEntity wendigo = helper.spawn(ModEntities.WENDIGO, new BlockPos(3, 1, 1));
		double startY = wendigo.getY();

		JsonObject approachStep = new JsonObject();
		approachStep.addProperty("type", "movement.approach_band");
		approachStep.addProperty("band", "spot_above");
		approachStep.addProperty("speed", "fast");
		JsonArray steps = new JsonArray();
		steps.add(approachStep);
		JsonObject plan = new JsonObject();
		plan.add("plan", steps);
		plan.add("global_rules", new JsonArray());

		wendigo.debugInjectPlan(plan);

		helper.succeedWhen(() -> {
			helper.assertTrue(wendigo.isWaveComplete(), "expected the injected spot_above approach plan to complete");
			helper.assertTrue(wendigo.getY() > startY + 3.0,
				"expected movement.approach_band(spot_above) to actually reach up near the ceiling - started at y="
					+ startY + ", ended at y=" + wendigo.getY());
		});
	}

	/** Regression test for a real server crash pulled from the logs: a control.while step missing its
	 * "body" field entirely (schema marks it required, but a live LLM generation violated that anyway
	 * - same class of issue max_iterations already has a defensive fallback for) threw a
	 * NullPointerException out of whileBodyHasApproach's own getAsJsonArray("body").iterator() the
	 * instant this step was reached, crashing the whole server (see the crash report's "Ticking
	 * entity" stack trace). PlanRunner now substitutes a harmless placeholder body instead - this just
	 * proves the plan resolves cleanly (reaches the control.despawn right after it) rather than
	 * crashing. */
	@GameTest
	public void controlWhileMissingBodyDoesNotCrash(GameTestHelper helper) {
		WendigoEntity wendigo = helper.spawn(ModEntities.WENDIGO, new BlockPos(1, 1, 1));
		JsonObject condition = new JsonObject();
		condition.addProperty("type", "predicate.player_unreachable");
		JsonObject whileStep = new JsonObject();
		whileStep.addProperty("type", "control.while");
		whileStep.add("condition", condition);
		whileStep.addProperty("max_iterations", "few");
		// Deliberately no "body" property at all - the exact malformed shape from the crash log.
		JsonObject despawnStep = new JsonObject();
		despawnStep.addProperty("type", "control.despawn");
		JsonArray steps = new JsonArray();
		steps.add(whileStep);
		steps.add(despawnStep);
		JsonObject plan = new JsonObject();
		plan.add("plan", steps);
		plan.add("global_rules", new JsonArray());

		wendigo.debugInjectPlan(plan);

		helper.succeedWhen(() -> helper.assertTrue(wendigo.isWaveComplete(),
			"expected the plan to resolve past the malformed control.while and complete, not crash"));
	}

	/** Full carry-flee-then-drop cycle (see PlanRunner.startCarryFlee/finishCarryFlee) - forceGrabNow
	 * catches an ordinary player, and the carry should resolve on its own flat 5-15s timer (see
	 * CARRY_FLEE_MIN_TICKS/MAX_TICKS) regardless of whether it ever reaches its live-band flee
	 * target, ending with the player actually dismounted. Never live-tested end to end with a real
	 * player before this - real playtesting found bugs at almost every stage of this exact sequence
	 * earlier this session (never dropping, instant re-grab, etc.), all supposedly fixed; this is the
	 * regression test for that whole chain. maxTicks generous (400 = 20s) to comfortably clear the
	 * worst-case 15s timer. */
	@GameTest(maxTicks = 400)
	public void forcedGrabEventuallyReleasesThePlayerOnItsOwn(GameTestHelper helper) {
		WendigoEntity wendigo = helper.spawn(ModEntities.WENDIGO, new BlockPos(1, 1, 1));
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		wendigo.forceGrabNow(player);
		helper.assertTrue(wendigo.isForcingRide(), "expected the grab to land first");

		helper.succeedWhen(() -> {
			helper.assertTrue(!wendigo.isForcingRide(), "expected the carry-flee timer to eventually release the rider");
			helper.assertTrue(player.getVehicle() != wendigo, "expected the player to actually be dismounted, not just forcingRide flipped");
		});
	}

	/** Confirms the confirmed-with-the-user stage-to-spawn-count mapping: spawns 1-2 -> stage 1,
	 * 3 -> stage 2, 4 -> stage 3, 5 -> stage 4, 6+ -> stage 5 (permanent). completedRuns is
	 * 0-indexed (how many are already fully done), so stageFor(0) and stageFor(1) both answer "what
	 * stage does the NEXT run belong to" for someone with 0 or 1 completed runs respectively. */
	@GameTest
	public void progressionStageForMapsSpawnCountsCorrectly(GameTestHelper helper) {
		helper.assertTrue(WendigoProgressionTracker.stageFor(0) == 1, "expected 0 completed runs -> stage 1");
		helper.assertTrue(WendigoProgressionTracker.stageFor(1) == 1, "expected 1 completed run -> stage 1");
		helper.assertTrue(WendigoProgressionTracker.stageFor(2) == 2, "expected 2 completed runs -> stage 2");
		helper.assertTrue(WendigoProgressionTracker.stageFor(3) == 3, "expected 3 completed runs -> stage 3");
		helper.assertTrue(WendigoProgressionTracker.stageFor(4) == 4, "expected 4 completed runs -> stage 4");
		helper.assertTrue(WendigoProgressionTracker.stageFor(5) == 5, "expected 5 completed runs -> stage 5");
		helper.assertTrue(WendigoProgressionTracker.stageFor(100) == 5, "expected 100 completed runs -> stage 5 (permanent)");
		helper.succeed();
	}

	/** Full run lifecycle: starting a run doesn't touch completedRuns, hitting the stage's goal
	 * (stage 1 = 10 successful stares, see WendigoProgressionTracker's own goal table) makes
	 * isGoalMet true, and completeRun both advances completedRuns and correctly moves a player from
	 * their first completed run (spawn 1, still stage 1 - spawns 1-2 both are) into their third run
	 * (spawn 3, stage 2). */
	@GameTest
	public void progressionRunCompletesOnceGoalMetAndAdvancesStage(GameTestHelper helper) {
		WendigoProgressionTracker tracker = new WendigoProgressionTracker();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		helper.assertTrue(tracker.stageOf(player) == 1, "expected a fresh player to start at stage 1");

		tracker.startRun(player);
		helper.assertTrue(tracker.completedRunsOf(player) == 0, "expected starting a run to not touch completedRuns");
		helper.assertTrue(!tracker.isGoalMet(player), "expected stage 1's 10-stare goal to not be met with 0 progress");
		tracker.addProgress(player, 10);
		helper.assertTrue(tracker.isGoalMet(player), "expected stage 1's 10-stare goal to be met at 10 progress");
		tracker.completeRun(player);
		helper.assertTrue(tracker.completedRunsOf(player) == 1, "expected completedRuns to advance to 1");
		helper.assertTrue(tracker.stageOf(player) == 1, "expected the 2nd run to still be stage 1");

		tracker.startRun(player);
		tracker.addProgress(player, 10);
		tracker.completeRun(player);
		helper.assertTrue(tracker.completedRunsOf(player) == 2, "expected completedRuns to advance to 2");
		helper.assertTrue(tracker.stageOf(player) == 2, "expected the 3rd run to be stage 2");
		helper.succeed();
	}

	/** The core "cosmetic despawn doesn't end the run" guarantee: startRun called again on a player
	 * with an already-active run (simulating a resume after the entity was discarded/relocated) must
	 * NOT reset their progress - see startRun's own computeIfAbsent. Progress made before and after
	 * the simulated pause both count toward the same goal. */
	@GameTest
	public void progressionPartialProgressPersistsAcrossASimulatedPause(GameTestHelper helper) {
		WendigoProgressionTracker tracker = new WendigoProgressionTracker();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();

		tracker.startRun(player);
		tracker.addProgress(player, 6);
		helper.assertTrue(!tracker.isGoalMet(player), "expected 6/10 progress to not meet stage 1's goal");
		helper.assertTrue(tracker.completedRunsOf(player) == 0, "expected completedRuns unaffected by partial progress");

		// Simulate a cosmetic despawn-and-resume: startRun again on the same still-active run.
		tracker.startRun(player);
		helper.assertTrue(!tracker.isGoalMet(player), "expected the resume to preserve, not reset, existing progress");
		tracker.addProgress(player, 4);
		helper.assertTrue(tracker.isGoalMet(player), "expected the resumed run's combined progress (6+4=10) to meet the goal");
		helper.succeed();
	}

	/** selectTarget's own priority rule: a player with an already-active (unfinished) run always
	 * wins over fresh eligibility, bypassing the 2000-tick timer entirely - confirmed here by a
	 * player whose run just started (eligibilityTicks nowhere near 2000) still getting selected as
	 * a resume candidate the instant they're back below y=0. */
	@GameTest
	public void progressionSelectTargetPrioritizesResumeOverFreshEligibility(GameTestHelper helper) {
		WendigoProgressionTracker tracker = new WendigoProgressionTracker();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		player.teleportTo(player.getX(), -10.0, player.getZ());
		tracker.startRun(player);

		WendigoProgressionTracker.TargetSelection selection = tracker.selectTarget(helper.getLevel());
		helper.assertTrue(selection != null, "expected a resumable player below y=0 to be selected");
		helper.assertTrue(selection.target() == player, "expected the resumable player to be the target");
		helper.assertTrue(selection.isResume(), "expected isResume() to be true for an active-run player");
		helper.succeed();
	}

	/** Stage 5's own kill-tracking: health saved between spawns is restored on the next spawn (the
	 * user's own explicit fix for "he heals back to full by teleporting away"), and a genuine kill
	 * (endStage5Hunt) resets it back to full so the next hunt starts fresh rather than resuming
	 * dead-on-arrival. */
	@GameTest
	public void progressionStage5HealthPersistsAndResetsOnKill(GameTestHelper helper) {
		WendigoProgressionTracker tracker = new WendigoProgressionTracker();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();

		helper.assertTrue(tracker.stage5HealthOf(player, 50.0F) == 50.0F, "expected full health with nothing saved yet");
		tracker.saveStage5Health(player, 22.0F);
		helper.assertTrue(tracker.stage5HealthOf(player, 50.0F) == 22.0F, "expected the saved health to be restored");
		tracker.endStage5Hunt(player);
		helper.assertTrue(tracker.stage5HealthOf(player, 50.0F) == 50.0F, "expected a kill to reset health back to full");
		helper.succeed();
	}

	/** completedRuns' own persistence codec (see WendigoProgressionData) round-trips a real map
	 * through NBT exactly like the game's own SavedDataStorage will on save/load - the user's own
	 * explicit "make the amount of runs persistent between server starts and stops" request. */
	@GameTest
	public void progressionDataCodecRoundTripsCompletedRuns(GameTestHelper helper) {
		UUID id = UUID.randomUUID();
		WendigoProgressionData original = new WendigoProgressionData();
		original.completedRuns().put(id, 4);

		Tag encoded = WendigoProgressionData.CODEC.encodeStart(NbtOps.INSTANCE, original).getOrThrow();
		Pair<WendigoProgressionData, Tag> decoded = WendigoProgressionData.CODEC.decode(NbtOps.INSTANCE, encoded).getOrThrow();
		Map<UUID, Integer> roundTripped = decoded.getFirst().completedRuns();
		helper.assertTrue(roundTripped.get(id) != null && roundTripped.get(id) == 4,
			"expected completedRuns to round-trip through the persistence codec unchanged");
		helper.succeed();
	}

	/** The real singleton tracker (WendigoMod.progressionTracker, the only one ever .register()'d -
	 * see WendigoProgressionTracker.onServerStarted) actually writes completedRuns through into the
	 * overworld's own SavedData the moment a run completes, not just its own in-memory map - proving
	 * the exact hook the game's normal save cycle will pick up and write to disk. Doesn't simulate an
	 * actual server restart (GameTest has no way to do that), but confirms the write-through wiring
	 * a restart's own load (SERVER_STARTED -> computeIfAbsent) depends on is really in place. */
	@GameTest
	public void progressionSingletonWritesThroughToOverworldSavedData(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		WendigoMod.progressionTracker.setRunsForTesting(player, 3);
		WendigoProgressionData data = helper.getLevel().getServer().overworld().getDataStorage().computeIfAbsent(WendigoProgressionData.TYPE);
		Integer persisted = data.completedRuns().get(player.getUUID());
		helper.assertTrue(persisted != null && persisted == 3,
			"expected the real singleton's setRunsForTesting to write through into the overworld's SavedData");
		helper.succeed();
	}

	/** Live bug report: the wendigo was seen sitting in orbit mode still visibly staring (facing the
	 * player, eyes glowing). Root cause: PlanRunner.completeWave's own visual-stare-lock reset only
	 * covers a NORMAL plan-driven wave end - WendigoManager's forced-backstop wave-end path
	 * (checkForcedWaveEnd, e.g. a hard timeout) relocates straight into a fresh startOrbit call
	 * without ever going through completeWave, so isStaring() could survive all the way into orbit.
	 * Fixed by moving the reset into startOrbit/startReturnToOrbit themselves - the two entry points
	 * every "back to idle" path funnels through regardless of how the wave actually ended. Doesn't
	 * need to go through a real forced-backstop scenario to prove the fix; setStaring(true) followed
	 * directly by startOrbit is exactly the invariant that must hold no matter what set it. */
	@GameTest
	public void startOrbitAlwaysClearsAVisualStareLock(GameTestHelper helper) {
		WendigoEntity wendigo = helper.spawn(ModEntities.WENDIGO, new BlockPos(1, 1, 1));
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		player.teleportTo(3.5, 1.0, 3.5);

		wendigo.setStaring(true);
		helper.assertTrue(wendigo.isStaring(), "expected setStaring(true) to actually take effect before the real check");

		wendigo.startOrbit(player);

		helper.assertTrue(!wendigo.isStaring(), "expected startOrbit to always clear a leftover visual stare lock");
		helper.succeed();
	}
}
