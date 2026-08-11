package com.wendigo.gametest;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.mojang.datafixers.util.Pair;

import io.netty.channel.embedded.EmbeddedChannel;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import com.wendigo.WendigoMod;
import com.wendigo.block.SnuffedWallTorchBlock;
import com.wendigo.block.WendigoBlocks;
import com.wendigo.entity.ModEntities;
import com.wendigo.entity.WendigoEntity;
import com.wendigo.plan.PlanPredicates;
import com.wendigo.spatial.DarkSpotScanner;
import com.wendigo.spatial.LightSourceScanner;
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
	/** GameTestHelper.makeMockServerPlayerInLevel()'s own player (GameTestHelper$2, confirmed via
	 * decompile) unconditionally overrides gameMode() to always return GameType.CREATIVE, regardless
	 * of the underlying ServerPlayerGameMode's real state - which makes isCreative() permanently true
	 * for every player that helper produces, now indistinguishable from a genuinely creative player as
	 * far as PlanRunner.beginForcedRide's own creative-immunity check is concerned (confirmed live -
	 * grab tests using that helper started failing the instant that check was added, and neither
	 * ServerPlayer.setGameMode(SURVIVAL) nor reading getAbilities().instabuild could tell the
	 * difference either - both come back creative-flavored too). Replicates that same helper's own
	 * join sequence (GameProfile, CommonListenerCookie, embedded Connection, PlayerList.placeNewPlayer
	 * - same call sequence, confirmed via decompiling makeMockServerPlayerInLevel itself) with a
	 * plain, un-overridden ServerPlayer instead, so gameMode() genuinely reflects
	 * ServerPlayerGameMode's own real state - reachable this way, unlike through the hardcoded
	 * override, even though it turns out (confirmed live via a temporary debug assertion) the
	 * GameTestServer's own configured default gametype is ALSO creative, same as a real creative-
	 * default world's would be: PlayerList.placeNewPlayer applies the server's own default gametype to
	 * every freshly-joined player with no saved data, same as it would for a real player connecting
	 * for the first time - the anonymous subclass's hardcoded override was never the only source of
	 * "creative," just the one making it unconditional and un-overridable afterward too. An explicit
	 * setGameMode(SURVIVAL) below actually sticks for a plain ServerPlayer, since gameMode() genuinely
	 * reads the field it just wrote instead of a hardcoded constant. */
	private static ServerPlayer makeSurvivalMockPlayer(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		GameProfile profile = new GameProfile(UUID.randomUUID(), "test-mock-player");
		CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
		ServerPlayer player = new ServerPlayer(level.getServer(), level, profile, ClientInformation.createDefault());
		Connection connection = new Connection(PacketFlow.SERVERBOUND);
		new EmbeddedChannel(connection);
		level.getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
		player.setGameMode(GameType.SURVIVAL);
		return player;
	}

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
		// Not makeMockServerPlayerInLevel() - see makeSurvivalMockPlayer's own doc comment: that
		// helper's own player unconditionally reports GameType.CREATIVE, which now makes
		// PlanRunner.beginForcedRide's creative-immunity check treat it as un-grabbable, defeating the
		// entire point of this "ordinary player" baseline test.
		ServerPlayer player = makeSurvivalMockPlayer(helper);
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

	/** Direct check of DarkSpotScanner.findLiveBandPositionInView (combat.teleport_in_view's own
	 * resolver) - findLiveBandPosition3D's own test above already covers the shared band+dark
	 * sampling machinery, so this only needs to confirm the one thing this method adds on top: the
	 * resolved spot is one the player is actually facing toward, not just any live-resolved dark spot
	 * in band. Same 7x7 sealed room shape as findLiveBandPosition3DFindsBothFloorAndCeiling right
	 * above, but via succeedWhen (retried every tick) rather than a same-tick manual retry loop -
	 * live-confirmed that a freshly-placed room's own lighting hasn't actually settled dark yet on the
	 * very tick helper.setBlock runs (MAX_DARK_LIGHT's own check reads stale/lit values that tick
	 * regardless of how many same-tick attempts are made), so this needs to wait real ticks for the
	 * light engine to catch up, not just resample the same not-yet-dark room repeatedly. padding is
	 * bumped past the @GameTest default (1) as cheap extra insurance - this file's default empty
	 * structure is only 8x8x8 (see the bundled fabric-gametest-api-v1 jar's own empty.snbt), smaller
	 * than several of this file's own built rooms including this one. */
	@GameTest(padding = 20)
	public void findLiveBandPositionInViewFindsAFacingSpot(GameTestHelper helper) {
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
		BlockPos playerPos = player.blockPosition();

		helper.succeedWhen(() -> {
			BlockPos inView = DarkSpotScanner.findLiveBandPositionInView(helper.getLevel(), player, 0.0, 5.0);
			helper.assertTrue(inView != null, "expected an in-view position to be found");
			double distance = Math.sqrt(inView.distSqr(playerPos));
			helper.assertTrue(distance <= 5.0,
				"expected the in-view pick to fall within the sampled band, got distance " + distance);
		});
	}

	/** Direct check of DarkSpotScanner.findLiveBandPositionEyeline (combat.teleport_to_eyeline's own
	 * resolver) - same shape as findLiveBandPositionInViewFindsAFacingSpot right above (shares the
	 * exact same underlying sampling machinery, see findLiveBandPositionFacing), the only difference
	 * being the much tighter EYELINE_ALIGNMENT_DEGREES threshold (14 degrees vs in-view's 60) - this
	 * only needs to confirm a resolved spot still actually satisfies that tighter alignment, not
	 * re-prove the shared band/dark sampling covered elsewhere. succeedWhen (not a same-tick loop) for
	 * the same "freshly-placed room isn't dark yet" reason documented on that test; the tighter cone
	 * also means fewer of each tick's 80 samples land inside it, so this may take a few more real
	 * ticks to succeed than the in-view version does, which succeedWhen already tolerates - maxTicks
	 * bumped well past the @GameTest default for exactly that reason (a 14-degree cone only has
	 * ~1/17th the solid angle a 60-degree one does, so a lot more retries can genuinely be needed
	 * before one of each tick's 80 samples lands both in-cone and on a valid dark floor column). */
	@GameTest(padding = 20, maxTicks = 400)
	public void findLiveBandPositionEyelineFindsATightlyAlignedSpot(GameTestHelper helper) {
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
		BlockPos playerPos = player.blockPosition();
		// Unlike findLiveBandPositionInViewFindsAFacingSpot's own 60-degree cone (wide enough to
		// tolerate whatever the mock player's own default rotation happens to be), a 14-degree cone
		// is narrow enough that leaving rotation to chance made this flaky in practice - look dead
		// level straight down the room's own open Z-axis instead, a known-good direction with plenty
		// of valid dark floor cells actually inside the cone.
		player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(helper.absolutePos(new BlockPos(3, 1, 6))));

		helper.succeedWhen(() -> {
			BlockPos eyeline = DarkSpotScanner.findLiveBandPositionEyeline(helper.getLevel(), player, 0.0, 5.0);
			helper.assertTrue(eyeline != null, "expected an eyeline position to be found");
			double distance = Math.sqrt(eyeline.distSqr(playerPos));
			helper.assertTrue(distance <= 5.0,
				"expected the eyeline pick to fall within the sampled band, got distance " + distance);
		});
	}

	/** Direct check of DarkSpotScanner.findCeilingSpotAbovePlayer (combat.teleport_to_band's own new
	 * "above_player" resolver) - the direct-above probe this method tries first is a deterministic
	 * straight-line scan, not randomized, so once the room's own lighting has actually settled dark
	 * (see findLiveBandPositionInViewFindsAFacingSpot's own note on why a same-tick check isn't
	 * reliable - a fresh helper.setBlock room reads stale/lit for a few real ticks before the light
	 * engine catches up, this method's own MAX_DARK_LIGHT check included) it succeeds on the very
	 * first tick that's true, hence succeedWhen rather than a manual retry loop. Room height is the
	 * minimum that still lands a candidate at/above the [10,30] window's own 10-block floor (12:
	 * ceiling solid at 12, open candidate at 11, 10 above the player's own y=1). padding is bumped
	 * past the @GameTest default (1) as cheap extra insurance - see that same note for why. */
	@GameTest(padding = 20)
	public void findCeilingSpotAbovePlayerFindsAHighDarkCeiling(GameTestHelper helper) {
		int roomHeight = 12;
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
		BlockPos playerPos = player.blockPosition();

		helper.succeedWhen(() -> {
			BlockPos ceilingSpot = DarkSpotScanner.findCeilingSpotAbovePlayer(helper.getLevel(), playerPos);
			helper.assertTrue(ceilingSpot != null, "expected a ceiling spot above the player to be found");
			double heightAbove = ceilingSpot.getY() - playerPos.getY();
			helper.assertTrue(heightAbove >= 10.0 && heightAbove <= 30.0,
				"expected the ceiling pick to sit 10-30 blocks above the player, got height " + heightAbove);
			helper.assertTrue(ceilingSpot.getX() == playerPos.getX() && ceilingSpot.getZ() == playerPos.getZ(),
				"expected the ceiling pick to prefer landing directly above the player when unobstructed, got " + ceilingSpot);
		});
	}

	/** combat.teleport_in_view(band=close) via debugInjectPlan - proves the dispatch case itself is
	 * wired correctly (right JSON field names, right resolver call, falls through to a resolved
	 * action instead of hitting startAction's own "unknown action type" fallback) end to end through
	 * PlanRunner, the thing a pure DarkSpotScanner unit test can't catch on its own. Deliberately
	 * doesn't also assert the entity actually relocated, unlike injectedTeleportToBandPlanRelocatesTheEntity
	 * right above - that would need a room genuinely large enough to contain the whole
	 * TELEPORT_IN_VIEW_LADDER cascade's own worst-case farthest band (up to 64 blocks), which doesn't
	 * fit this file's established small-room convention (see findCeilingSpotAbovePlayerFindsAHighDarkCeiling's
	 * own note on why going bigger already caused real cross-test corruption); the resolver's own
	 * correctness (including the actual relocation) is already independently covered by
	 * findLiveBandPositionInViewFindsAFacingSpot above. A clean no-op still completes the wave (see
	 * this action's own schema description), so isWaveComplete alone is still a meaningful assertion
	 * either way. padding bumped past the @GameTest default for the same reason as the two direct
	 * DarkSpotScanner tests above - see findLiveBandPositionInViewFindsAFacingSpot's own note. */
	@GameTest(padding = 20)
	public void injectedTeleportInViewPlanCompletes(GameTestHelper helper) {
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

		WendigoEntity wendigo = helper.spawn(ModEntities.WENDIGO, new BlockPos(3, 1, 4));
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		BlockPos playerAbsolute = helper.absolutePos(new BlockPos(3, 1, 3));
		player.teleportTo(playerAbsolute.getX() + 0.5, playerAbsolute.getY(), playerAbsolute.getZ() + 0.5);

		JsonObject teleportStep = new JsonObject();
		teleportStep.addProperty("type", "combat.teleport_in_view");
		teleportStep.addProperty("band", "close");
		JsonArray steps = new JsonArray();
		steps.add(teleportStep);
		JsonObject plan = new JsonObject();
		plan.add("plan", steps);
		plan.add("global_rules", new JsonArray());

		wendigo.debugInjectPlan(plan);

		helper.succeedWhen(() -> {
			helper.assertTrue(wendigo.isWaveComplete(), "expected the injected teleport_in_view plan to complete");
		});
	}

	/** combat.teleport_to_eyeline(band=close) via debugInjectPlan - same dispatch-wiring proof as
	 * injectedTeleportInViewPlanCompletes right above, for the new sibling action instead. Same
	 * reasoning for not also asserting relocation (findLiveBandPositionEyelineFindsATightlyAlignedSpot
	 * already covers the resolver directly; a resolve failure is still a clean no-op that completes
	 * the wave regardless, per this action's own schema description). */
	@GameTest(padding = 20)
	public void injectedTeleportToEyelinePlanCompletes(GameTestHelper helper) {
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

		WendigoEntity wendigo = helper.spawn(ModEntities.WENDIGO, new BlockPos(3, 1, 4));
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		BlockPos playerAbsolute = helper.absolutePos(new BlockPos(3, 1, 3));
		player.teleportTo(playerAbsolute.getX() + 0.5, playerAbsolute.getY(), playerAbsolute.getZ() + 0.5);

		JsonObject teleportStep = new JsonObject();
		teleportStep.addProperty("type", "combat.teleport_to_eyeline");
		teleportStep.addProperty("band", "close");
		JsonArray steps = new JsonArray();
		steps.add(teleportStep);
		JsonObject plan = new JsonObject();
		plan.add("plan", steps);
		plan.add("global_rules", new JsonArray());

		wendigo.debugInjectPlan(plan);

		helper.succeedWhen(() -> {
			helper.assertTrue(wendigo.isWaveComplete(), "expected the injected teleport_to_eyeline plan to complete");
		});
	}

	/** combat.teleport_ahead via debugInjectPlan - same dispatch-wiring proof as
	 * injectedTeleportInViewPlanCompletes/injectedTeleportToEyelinePlanCompletes above, for the new
	 * path-prediction action instead. The mock player here is stationary (no real velocity from
	 * makeMockServerPlayerInLevel/teleportTo alone), so this specifically exercises
	 * resolvePredictedPathSpot's own "not moving -> null" branch and the teleportInViewFallback path
	 * that follows it - see injectedTeleportAheadPlanCompletesWhenPlayerIsMoving right below for the
	 * predicted-path branch instead. Same "don't also assert relocation" scope as the other two
	 * dispatch tests - a resolve failure anywhere in the fallback chain is still a clean no-op that
	 * completes the wave. */
	@GameTest(padding = 20)
	public void injectedTeleportAheadPlanCompletesWhenPlayerIsStationary(GameTestHelper helper) {
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

		WendigoEntity wendigo = helper.spawn(ModEntities.WENDIGO, new BlockPos(3, 1, 4));
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		BlockPos playerAbsolute = helper.absolutePos(new BlockPos(3, 1, 3));
		player.teleportTo(playerAbsolute.getX() + 0.5, playerAbsolute.getY(), playerAbsolute.getZ() + 0.5);

		JsonObject teleportStep = new JsonObject();
		teleportStep.addProperty("type", "combat.teleport_ahead");
		JsonArray steps = new JsonArray();
		steps.add(teleportStep);
		JsonObject plan = new JsonObject();
		plan.add("plan", steps);
		plan.add("global_rules", new JsonArray());

		wendigo.debugInjectPlan(plan);

		helper.succeedWhen(() -> {
			helper.assertTrue(wendigo.isWaveComplete(), "expected the injected teleport_ahead plan to complete");
		});
	}

	/** Same dispatch-wiring proof as injectedTeleportAheadPlanCompletesWhenPlayerIsStationary right
	 * above, but the mock player is given a real horizontal velocity first - exercises
	 * resolvePredictedPathSpot's own extrapolation branch (and, since this file's small test rooms
	 * can't fit the full TELEPORT_AHEAD_LOOKAHEAD_DISTANCE/SEARCH_RADIUS, very likely still falls
	 * through to teleportInViewFallback once the prediction search itself comes up empty - the point
	 * of this test is proving that whole chain doesn't crash when a real direction actually gets
	 * extrapolated, not asserting which branch wins). */
	@GameTest(padding = 20)
	public void injectedTeleportAheadPlanCompletesWhenPlayerIsMoving(GameTestHelper helper) {
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

		WendigoEntity wendigo = helper.spawn(ModEntities.WENDIGO, new BlockPos(3, 1, 4));
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		BlockPos playerAbsolute = helper.absolutePos(new BlockPos(3, 1, 3));
		player.teleportTo(playerAbsolute.getX() + 0.5, playerAbsolute.getY(), playerAbsolute.getZ() + 0.5);
		player.setDeltaMovement(0.2, 0.0, 0.0);

		JsonObject teleportStep = new JsonObject();
		teleportStep.addProperty("type", "combat.teleport_ahead");
		JsonArray steps = new JsonArray();
		steps.add(teleportStep);
		JsonObject plan = new JsonObject();
		plan.add("plan", steps);
		plan.add("global_rules", new JsonArray());

		wendigo.debugInjectPlan(plan);

		helper.succeedWhen(() -> {
			helper.assertTrue(wendigo.isWaveComplete(), "expected the injected teleport_ahead plan to complete");
		});
	}

	/** Engine-level coverage for control.re_evaluate's own hold/splice mechanics - PlanRunner.
	 * resumeFromReEvaluate is called directly here rather than exercising the real WendigoManager/
	 * LlmClient round trip (no real LLM call is possible from a GameTest). Confirms: (1) the action
	 * genuinely HOLDS (isReEvaluateRequested() reads true, the wave does NOT complete) until something
	 * resolves it, matching a real indeterminate wait rather than a fixed timeout; (2) once resolved via
	 * resumeFromReEvaluate, the spliced sub-plan actually runs and the wave completes; (3) the final
	 * outcome().planShape() reads as one continuous sequence - the original plan's own steps, then
	 * "control.re_evaluate", then the sub-plan's own steps - the user's own explicit "connect the plan
	 * and subplan at the re-evaluate" request. maxTicks bumped past vanilla's tiny default - the
	 * leading posture.stare step (needed now so control.re_evaluate doesn't skip itself as a no-op,
	 * see the meaningful-action gating this file's own comment above describes) holds for a real 60-tick
	 * minimum (STARE_MIN_HOLD_TICKS) before control.re_evaluate even starts. */
	@GameTest(padding = 20, maxTicks = 120)
	public void reEvaluateHoldsThenSplicesSubPlanIntoPlanShape(GameTestHelper helper) {
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

		WendigoEntity wendigo = helper.spawn(ModEntities.WENDIGO, new BlockPos(3, 1, 1));
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		BlockPos playerAbsolute = helper.absolutePos(new BlockPos(3, 1, 3));
		player.teleportTo(playerAbsolute.getX() + 0.5, playerAbsolute.getY(), playerAbsolute.getZ() + 0.5);

		// A meaningful action has to run before control.re_evaluate now (see
		// PlanRunner.meaningfulActionCompletedThisWave's own doc comment - the user's own explicit
		// "don't allow re-evaluate before a meaningful action" request) or it resolves as an instant
		// no-op instead of genuinely holding - posture.stare is the simplest real action available
		// (instantaneous, no repositioning, see this file's own comment on the next test down).
		JsonObject stareStep = new JsonObject();
		stareStep.addProperty("type", "posture.stare");
		stareStep.addProperty("enabled", true);
		JsonObject reEvaluateStep = new JsonObject();
		reEvaluateStep.addProperty("type", "control.re_evaluate");
		JsonArray steps = new JsonArray();
		steps.add(stareStep);
		steps.add(reEvaluateStep);
		JsonObject plan = new JsonObject();
		plan.add("plan", steps);
		plan.add("global_rules", new JsonArray());

		wendigo.debugInjectPlan(plan);

		boolean[] resumed = {false};
		helper.onEachTick(() -> {
			if (resumed[0]) {
				return;
			}
			// Confirms the hold is genuine - if this ever saw isWaveComplete()==true before the
			// re-evaluate got resolved, control.re_evaluate would be resolving itself with no sub-plan,
			// exactly the bug this test exists to catch.
			helper.assertTrue(!wendigo.isWaveComplete(), "expected the wave to still be holding on control.re_evaluate, not already complete");
			if (wendigo.isReEvaluateRequested()) {
				resumed[0] = true;
				JsonObject subStep = new JsonObject();
				subStep.addProperty("type", "control.none");
				JsonArray subSteps = new JsonArray();
				subSteps.add(subStep);
				JsonObject subPlan = new JsonObject();
				subPlan.addProperty("previous_encounter_recap", "");
				subPlan.add("plan", subSteps);
				subPlan.addProperty("despawn_band", "far");
				subPlan.add("global_rules", new JsonArray());
				wendigo.resumeFromReEvaluate(subPlan);
			}
		});

		helper.succeedWhen(() -> {
			helper.assertTrue(resumed[0], "expected control.re_evaluate to actually request a sub-plan");
			helper.assertTrue(wendigo.isWaveComplete(), "expected the spliced sub-plan to run to completion");
			List<String> shape = wendigo.getOutcome().planShape();
			helper.assertTrue(shape.equals(List.of("posture.stare", "control.re_evaluate", "control.none")),
				"expected planShape to read [posture.stare, control.re_evaluate, control.none] (original spliced with sub-plan), got " + shape);
		});
	}

	/** posture.stare is a plain instantaneous toggle - no repositioning logic of any kind runs before
	 * it (the earlier obstruction-avoidance mechanic that used to live here was removed outright per
	 * the user's own explicit request, in favor of playerDirection context + control.re_evaluate
	 * letting the MODEL react to a bad stare instead of the engine mechanically working around it).
	 * Regression coverage: the wendigo's position must stay UNCHANGED once an injected posture.stare
	 * plan completes. maxTicks bumped past vanilla's tiny default - every fresh stare now holds for a
	 * real 60-tick minimum (STARE_MIN_HOLD_TICKS) before the plan can finish, universal as of the
	 * user's own later "make sure it's 3 seconds minimum... unless something else happens" broadening. */
	@GameTest(padding = 20, maxTicks = 120)
	public void staresWithoutRepositioningWhenAlreadyVisible(GameTestHelper helper) {
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

		WendigoEntity wendigo = helper.spawn(ModEntities.WENDIGO, new BlockPos(3, 1, 4));
		BlockPos spawnPos = wendigo.blockPosition();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		BlockPos playerAbsolute = helper.absolutePos(new BlockPos(3, 1, 3));
		player.teleportTo(playerAbsolute.getX() + 0.5, playerAbsolute.getY(), playerAbsolute.getZ() + 0.5);

		JsonObject stareStep = new JsonObject();
		stareStep.addProperty("type", "posture.stare");
		stareStep.addProperty("enabled", true);
		JsonArray steps = new JsonArray();
		steps.add(stareStep);
		JsonObject plan = new JsonObject();
		plan.add("plan", steps);
		plan.add("global_rules", new JsonArray());

		wendigo.debugInjectPlan(plan);

		helper.succeedWhen(() -> {
			helper.assertTrue(wendigo.isWaveComplete(), "expected the injected posture.stare plan to complete");
			// Distance tolerance, not exact position equality - now that every fresh stare holds for a
			// real 60-tick minimum (STARE_MIN_HOLD_TICKS), the entity sits idle (a plain timing.wait,
			// no navigation of any kind - see holdStep) for long enough that ordinary physics settling
			// can nudge it a single block, which isn't the deliberate multi-block repositioning this
			// test actually exists to catch (the old removed obstruction-avoidance mechanic).
			double driftSqr = wendigo.blockPosition().distSqr(spawnPos);
			helper.assertTrue(driftSqr <= 2.0,
				"expected no deliberate repositioning when already visible - started at " + spawnPos
					+ ", ended at " + wendigo.blockPosition());
		});
	}

	/** Diagnostic, not a strict pass/fail check (always succeeds as long as it runs without crashing) -
	 * the user's own explicit request for a way to manually verify PlanPredicates.hasLineOfSightToSelf's
	 * new head-only ring (see its own doc comment - simplified down from an earlier wide, posture-aware,
	 * whole-body grid to a small ring right at getVisualEyePosition()) actually tracks the wendigo's
	 * REAL head position correctly in both poses, not just "some point on the body." Logs
	 * PlanPredicates.isLookingAtSelf(player, wendigo, "dead_stare") - the same head-position check both
	 * stare detection and stare obstruction now share - every 10 ticks via WendigoMod.LOGGER, readable
	 * from the server console/log during a real /gametest run. The player starts looking directly at
	 * the wendigo's own current head position (expect true early on), then at the halfway point turns
	 * to face away (expect false afterward) - a real state transition to actually see reflected in the
	 * log, not just one constant value the whole time. See stareHeadVisibilityPrintsWhileCrawling right
	 * below for the crawling-pose sibling - same shape, a short-ceiling room instead of an open one. */
	@GameTest(padding = 20, maxTicks = 120)
	public void stareHeadVisibilityPrintsWhileStanding(GameTestHelper helper) {
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

		WendigoEntity wendigo = helper.spawn(ModEntities.WENDIGO, new BlockPos(3, 1, 4));
		// Plenty of standing clearance here (roomHeight=9) - syncPoseToSpawnPosition resolves to
		// STANDING immediately, bypassing updatePose's own debounce, and stays STANDING every
		// subsequent tick since the room's own geometry (the only thing updatePose re-checks while the
		// entity never moves/navigates here) never changes.
		wendigo.syncPoseToSpawnPosition();

		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		BlockPos playerAbsolute = helper.absolutePos(new BlockPos(3, 1, 3));
		player.teleportTo(playerAbsolute.getX() + 0.5, playerAbsolute.getY(), playerAbsolute.getZ() + 0.5);
		player.lookAt(EntityAnchorArgument.Anchor.EYES, wendigo.getVisualEyePosition());

		logHeadVisibilityEveryTenTicks(helper, wendigo, player, "standing");
	}

	/** Crawling-pose sibling of stareHeadVisibilityPrintsWhileStanding right above - same diagnostic
	 * shape, just a short-ceiling room (2 blocks of interior clearance, one short of
	 * DarkSpotScanner.hasStandingClearance's own 3-block requirement) so syncPoseToSpawnPosition
	 * resolves to CRAWLING instead, and stays that way for the same "static geometry, no movement"
	 * reason the standing version does. */
	@GameTest(padding = 20, maxTicks = 120)
	public void stareHeadVisibilityPrintsWhileCrawling(GameTestHelper helper) {
		int roomHeight = 3;
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

		WendigoEntity wendigo = helper.spawn(ModEntities.WENDIGO, new BlockPos(3, 1, 4));
		wendigo.syncPoseToSpawnPosition();
		helper.assertTrue(wendigo.isCrawling(), "expected the 2-block-clearance room to force crawling pose");

		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		BlockPos playerAbsolute = helper.absolutePos(new BlockPos(3, 1, 3));
		player.teleportTo(playerAbsolute.getX() + 0.5, playerAbsolute.getY(), playerAbsolute.getZ() + 0.5);
		player.lookAt(EntityAnchorArgument.Anchor.EYES, wendigo.getVisualEyePosition());

		logHeadVisibilityEveryTenTicks(helper, wendigo, player, "crawling");
	}

	/** Shared tick-driven logging loop for the two head-visibility diagnostics above - prints
	 * PlanPredicates.isLookingAtSelf(player, wendigo, "dead_stare") every 10 ticks via WendigoMod.LOGGER,
	 * flips the player to face away from the wendigo at the halfway point (tick 50 of the 100-tick
	 * observation window) so the log shows a real true-to-false transition instead of one constant
	 * value, then succeeds once the window's over - always, this is an observation tool, not an
	 * assertion (read the log to actually verify the behavior). The "away" direction is computed live,
	 * relative to the wendigo's own current position at tick 50 (player-minus-wendigo, extended further
	 * past the player) rather than a fixed room-relative offset - the fixed version genuinely pointed
	 * the wrong way for this file's own room layout (player-then-wendigo along the same +Z axis), which
	 * a first run of this exact test caught by producing a nonsensical true/false pattern. */
	private static void logHeadVisibilityEveryTenTicks(GameTestHelper helper, WendigoEntity wendigo, ServerPlayer player, String poseLabel) {
		helper.onEachTick(() -> {
			long tick = helper.getTick();
			if (tick == 50) {
				Vec3 awayDirection = player.position().subtract(wendigo.position()).normalize();
				player.lookAt(EntityAnchorArgument.Anchor.EYES, player.position().add(awayDirection.scale(10.0)));
			}
			if (tick % 10 == 0) {
				boolean staringAtHead = PlanPredicates.isLookingAtSelf(player, wendigo, "dead_stare");
				WendigoMod.LOGGER.info("[stare-head-visibility] pose={} tick={} isCrawling={} headY={} staringAtHead={}",
					poseLabel, tick, wendigo.isCrawling(), String.format("%.2f", wendigo.getVisualEyePosition().y), staringAtHead);
			}
		});
		helper.runAfterDelay(100, helper::succeed);
	}

	/** sound.breathe via debugInjectPlan - proves the dispatch case itself is wired correctly (right
	 * JSON field name, falls through to WendigoSounds.playBreathe instead of hitting startAction's own
	 * "unknown action type" fallback) and that a player standing well within BREATHE_SUCCESS_RADIUS
	 * (16 blocks) of the wendigo at the moment it plays gets counted as a successful breathe - see
	 * PlanRunner.EncounterOutcome.successfulBreatheCount, read here via WendigoEntity.getOutcome()
	 * (public, backs WendigoManager's own goal-progress feed - see PlanRunner.outcome's own doc
	 * comment for why it's safe to read mid-wave, not just after completion). */
	@GameTest
	public void injectedBreathePlanCountsAsSuccessfulWhenClose(GameTestHelper helper) {
		WendigoEntity wendigo = helper.spawn(ModEntities.WENDIGO, new BlockPos(1, 1, 1));
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		BlockPos playerAbsolute = helper.absolutePos(new BlockPos(1, 1, 2));
		player.teleportTo(playerAbsolute.getX() + 0.5, playerAbsolute.getY(), playerAbsolute.getZ() + 0.5);

		JsonObject breatheStep = new JsonObject();
		breatheStep.addProperty("type", "sound.breathe");
		JsonArray steps = new JsonArray();
		steps.add(breatheStep);
		JsonObject plan = new JsonObject();
		plan.add("plan", steps);
		plan.add("global_rules", new JsonArray());

		wendigo.debugInjectPlan(plan);

		helper.succeedWhen(() -> {
			helper.assertTrue(wendigo.isWaveComplete(), "expected the injected sound.breathe plan to complete");
			helper.assertTrue(wendigo.getOutcome().successfulBreatheCount() == 1,
				"expected the breathe (player 1 block away) to count as successful, got "
					+ wendigo.getOutcome().successfulBreatheCount());
		});
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

	/** User's own explicit request: confirm withdrewInstantly (renamed from vanishedCleanly - see
	 * PlanRunner.completeWave) reliably reads true for the exact case it's supposed to represent, and
	 * doesn't flip to false when it shouldn't. Same bare control.despawn plan as
	 * injectedDespawnOnlyPlanCompletesImmediately right above (debugInjectPlan bypasses tier gating,
	 * so isSuddenDespawnAllowed's own severity check never redirects this into a real walked flee -
	 * see that method's own doc comment) - the one case completeWave(true) is supposed to cover every
	 * time. Checked via WendigoEntity.getOutcome() (public, backs WendigoManager's own
	 * EncounterHistory.record call), not by reaching into PlanRunner directly. */
	@GameTest
	public void injectedDespawnOnlyPlanWithdrawsInstantly(GameTestHelper helper) {
		WendigoEntity wendigo = helper.spawn(ModEntities.WENDIGO, new BlockPos(1, 1, 1));
		JsonObject despawnStep = new JsonObject();
		despawnStep.addProperty("type", "control.despawn");
		JsonArray steps = new JsonArray();
		steps.add(despawnStep);
		JsonObject plan = new JsonObject();
		plan.add("plan", steps);
		plan.add("global_rules", new JsonArray());

		wendigo.debugInjectPlan(plan);

		helper.succeedWhen(() -> {
			helper.assertTrue(wendigo.isWaveComplete(), "expected the injected despawn-only plan to complete");
			helper.assertTrue(wendigo.getOutcome().withdrewInstantly(),
				"expected a bare control.despawn (tier gating bypassed) to resolve as an instant withdrawal, "
					+ "not a walked/carried one");
		});
	}

	/** combat.teleport_to_band(band=behind_player) - debugInjectPlan bypasses SchemaBuilder's own
	 * stage-filtered "band" enum (see TierGates.teleportDestinationBands) the same way every other
	 * injected plan bypasses ordinary tier gating, but NOT the separate runtime source-band
	 * precondition (TierGates.teleportSourceBands) - that's a real physical fact about the wendigo's
	 * own current position, not a policy gate, so it still applies even here. A fresh helper.spawn()
	 * defaults severityPercent to 0 (stage 1, allowed source bands just ["close"] - see
	 * TierGates.stageFor/teleportSourceBands), so this test's own 5-block start distance is
	 * deliberately chosen to already satisfy that precondition rather than needing to fight it.
	 * Proves the whole DarkSpotScanner.findNearestUnwatchedDarkSpot -> PlanRunner teleport pipeline
	 * actually relocates the entity somewhere new, not just resolves the action as a no-op. */
	@GameTest
	public void injectedTeleportToBandPlanRelocatesTheEntity(GameTestHelper helper) {
		WendigoEntity wendigo = helper.spawn(ModEntities.WENDIGO, new BlockPos(5, 1, 5));
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		player.teleportTo(1.5, 1.0, 1.5);
		BlockPos startPos = wendigo.blockPosition();

		JsonObject teleportStep = new JsonObject();
		teleportStep.addProperty("type", "combat.teleport_to_band");
		teleportStep.addProperty("band", "behind_player");
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

	/** combat.teleport_to_band(above_player) chained into movement.drop - the exact "raw teleport-spawn
	 * onto the ceiling followed by a bare drop" setup the test right above's own doc comment describes
	 * as a real, separate, live-debugged bug. This test is what the user's own live "he can't actually
	 * drop from the ceiling" report was actually hitting: the walked spot_above path right above already
	 * worked and had coverage; this teleport path didn't and lacked any. Two real, separate bugs found
	 * and fixed chasing this down (see PlanRunner's combat.teleport_to_band case and WendigoEntity.
	 * forceDetach's own updated doc comments for the full account):
	 * <p>
	 * 1. nudgeTowardAttachedSurface used to unconditionally push Direction.UP (its own no-op floor
	 * convention) after EVERY teleport, regardless of which band actually resolved - so a ceiling
	 * landing via above_player never got the corrective push needed to make AWCAPI recognize a real
	 * ceiling collision at all, and just fell straight back off under ordinary gravity. Fixed: pass
	 * Direction.DOWN (the ceiling convention) specifically when resolvedBand is "above_player".
	 * <p>
	 * 2. Even once genuinely attached (confirmed live via getGroundSide() flipping to UP a few ticks
	 * after landing), forceDetach()'s own no-op guard (getGroundSide()==DOWN) could read a spurious
	 * false positive right after a teleport-created attachment, silently skipping the whole detach with
	 * no visible effect - explained several earlier fix attempts all measurably changing nothing before
	 * this was finally caught via a live diagnostic dump. Fixed: cross-check against
	 * hasRealFloorNearby() (the same "is this floor reading actually real" distinction
	 * recoverFromSpuriousAttachmentLoss already draws elsewhere in this class).
	 * <p>
	 * Known remaining limitation, NOT fixed here: once a real detach does begin, the entity can still
	 * fall only partway before AWCAPI's own ground-detection spuriously locks it back in place a couple
	 * blocks below the ceiling instead of reaching the room's real floor - the same general "ceiling
	 * flip" reliability class this class's own restingOnFloorRaw/recoverFromSpuriousAttachmentLoss
	 * comments already document fighting elsewhere, evidently not fully solved by either of those. This
	 * test only asserts what's now reliably true (reaches the ceiling, genuinely attaches, and
	 * movement.drop causes SOME real downward movement rather than silently no-op'ing) rather than a
	 * guaranteed full fall back to the floor - the walked spot_above path above remains the reliable
	 * choice for a real plan that needs the fall to complete. Room height/padding match
	 * findCeilingSpotAbovePlayerFindsAHighDarkCeiling's own established minimum for actually landing a
	 * candidate inside above_player's own [10,30] window (12: ceiling solid at 12, open candidate at
	 * 11, 10 above the player's own y=1), and the plan injection is delayed (see that same test's own
	 * "stale/lit" note) so the room's own lighting has genuinely settled dark before above_player's
	 * live resolution ever runs - this file's own established convention for any test that needs a
	 * real above_player resolution, not just this test's own room. */
	@GameTest(maxTicks = 600, padding = 20)
	public void injectedTeleportAbovePlayerThenDropPlanReachesCeilingThenMoves(GameTestHelper helper) {
		int roomHeight = 12;
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

		JsonObject teleportStep = new JsonObject();
		teleportStep.addProperty("type", "combat.teleport_to_band");
		teleportStep.addProperty("band", "above_player");
		JsonObject dropStep = new JsonObject();
		dropStep.addProperty("type", "movement.drop");
		JsonArray steps = new JsonArray();
		steps.add(teleportStep);
		steps.add(dropStep);
		JsonObject plan = new JsonObject();
		plan.add("plan", steps);
		plan.add("global_rules", new JsonArray());

		// See this test's own doc comment - the room's lighting hasn't actually settled dark yet on the
		// very tick helper.setBlock runs, and above_player's own live resolution needs it to have.
		helper.runAfterDelay(40, () -> wendigo.debugInjectPlan(plan));

		double[] peakY = {startY};
		helper.onEachTick(() -> peakY[0] = Math.max(peakY[0], wendigo.getY()));

		boolean[] reachedCeiling = {false};
		helper.succeedWhen(() -> {
			if (wendigo.getY() > startY + 3.0) {
				reachedCeiling[0] = true;
			}
			helper.assertTrue(reachedCeiling[0], "expected combat.teleport_to_band(above_player) to reach up near the ceiling first");
			helper.assertTrue(wendigo.isWaveComplete(), "expected the injected teleport_to_band(above_player) + drop plan to complete");
			helper.assertTrue(wendigo.getY() < peakY[0] - 1.0,
				"expected movement.drop to cause at least some real downward movement off the ceiling (not silently no-op) "
					+ "- peak y=" + peakY[0] + " ended at y=" + wendigo.getY());
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
	 * catches an ordinary player, and the carry should resolve on its own flat, stage-scaled timer (see
	 * rideFairChanceTicks/carryFleeReleaseTick - 2-7s depending on severityPercent) regardless of
	 * whether it ever reaches its live-band flee target, ending with the player actually dismounted.
	 * Never live-tested end to end with a real player before this - real playtesting found bugs at
	 * almost every stage of this exact sequence earlier this session (never dropping, instant re-grab,
	 * etc.), all supposedly fixed; this is the regression test for that whole chain. maxTicks generous
	 * (400 = 20s) to comfortably clear the worst case regardless of whatever severityPercent this test
	 * entity happens to default to. */
	@GameTest(maxTicks = 400)
	public void forcedGrabEventuallyReleasesThePlayerOnItsOwn(GameTestHelper helper) {
		WendigoEntity wendigo = helper.spawn(ModEntities.WENDIGO, new BlockPos(1, 1, 1));
		// See forceGrabNowMountsAnOrdinaryPlayer's own comment - makeMockServerPlayerInLevel()'s
		// player is unconditionally creative, which would skip the grab entirely now.
		ServerPlayer player = makeSurvivalMockPlayer(helper);
		wendigo.forceGrabNow(player);
		helper.assertTrue(wendigo.isForcingRide(), "expected the grab to land first");

		helper.succeedWhen(() -> {
			helper.assertTrue(!wendigo.isForcingRide(), "expected the carry-flee timer to eventually release the rider");
			helper.assertTrue(player.getVehicle() != wendigo, "expected the player to actually be dismounted, not just forcingRide flipped");
		});
	}

	/** Confirms the stage-to-spawn-count mapping: spawns 1-2 -> stage 1, 3 -> stage 2, 4 -> stage 3,
	 * 5 -> stage 4, 6+ -> stage 5 (permanent) - reverted back to giving stage 1 both spawns 1 and 2,
	 * the user's own explicit "revert to two runs in stage 1" request (undoing an earlier same-session
	 * "cut stage 1 to only be the first run" change). completedRuns is 0-indexed (how many are already
	 * fully done), so stageFor(n) answers "what stage does the NEXT run belong to" for someone with n
	 * completed runs. */
	@GameTest
	public void progressionStageForMapsSpawnCountsCorrectly(GameTestHelper helper) {
		helper.assertTrue(WendigoProgressionTracker.stageFor(0) == 1, "expected 0 completed runs -> stage 1");
		helper.assertTrue(WendigoProgressionTracker.stageFor(1) == 1, "expected 1 completed run -> still stage 1");
		helper.assertTrue(WendigoProgressionTracker.stageFor(2) == 2, "expected 2 completed runs -> stage 2");
		helper.assertTrue(WendigoProgressionTracker.stageFor(3) == 3, "expected 3 completed runs -> stage 3");
		helper.assertTrue(WendigoProgressionTracker.stageFor(4) == 4, "expected 4 completed runs -> stage 4");
		helper.assertTrue(WendigoProgressionTracker.stageFor(5) == 5, "expected 5 completed runs -> stage 5 (permanent)");
		helper.assertTrue(WendigoProgressionTracker.stageFor(100) == 5, "expected 100 completed runs -> stage 5 (permanent)");
		helper.succeed();
	}

	/** Full run lifecycle: starting a run doesn't touch completedRuns, hitting the stage's goal
	 * (stage 1 = 4 successful stares AND 4 sound cues AND 1 successful breathe, all three axes
	 * independently, see WendigoProgressionTracker's own STAGE_GOALS/STAGE1_SOUND_GOAL/BREATHE_GOAL)
	 * makes isGoalMet true, and completeRun both advances completedRuns and correctly moves a player
	 * from their first completed run (spawn 1, stage 1) into their second run (spawn 2, still stage 1 -
	 * stage 1 covers the first two runs, see stageFor's own doc comment) and then their third run
	 * (spawn 3, stage 2). */
	@GameTest
	public void progressionRunCompletesOnceGoalMetAndAdvancesStage(GameTestHelper helper) {
		WendigoProgressionTracker tracker = new WendigoProgressionTracker();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		helper.assertTrue(tracker.stageOf(player) == 1, "expected a fresh player to start at stage 1");

		tracker.startRun(player);
		helper.assertTrue(tracker.completedRunsOf(player) == 0, "expected starting a run to not touch completedRuns");
		helper.assertTrue(!tracker.isGoalMet(player), "expected stage 1's compound goal to not be met with 0 progress");
		tracker.addProgress(player, 4);
		helper.assertTrue(!tracker.isGoalMet(player),
			"expected 4/4 stares alone, with 0 sound cues and 0 breathes, to not meet stage 1's AND goal");
		tracker.addSecondaryProgress(player, 4);
		helper.assertTrue(!tracker.isGoalMet(player),
			"expected stares+noises alone, with 0 breathes, to not meet stage 1's AND goal");
		tracker.addBreatheProgress(player, 1);
		helper.assertTrue(tracker.isGoalMet(player),
			"expected stage 1's 4-stares-AND-4-noises-AND-1-breathe goal to be met once all three clear");
		tracker.completeRun(player);
		helper.assertTrue(tracker.completedRunsOf(player) == 1, "expected completedRuns to advance to 1");
		helper.assertTrue(tracker.stageOf(player) == 1, "expected the 2nd run to still be stage 1");

		tracker.startRun(player);
		tracker.addProgress(player, 4);
		tracker.addSecondaryProgress(player, 4);
		tracker.addBreatheProgress(player, 1);
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
		tracker.addProgress(player, 3);
		tracker.addSecondaryProgress(player, 2);
		helper.assertTrue(!tracker.isGoalMet(player), "expected 3/4 stares, 2/4 noises to not meet stage 1's compound goal");
		helper.assertTrue(tracker.completedRunsOf(player) == 0, "expected completedRuns unaffected by partial progress");

		// Simulate a cosmetic despawn-and-resume: startRun again on the same still-active run.
		tracker.startRun(player);
		helper.assertTrue(!tracker.isGoalMet(player), "expected the resume to preserve, not reset, existing progress");
		tracker.addProgress(player, 1);
		tracker.addSecondaryProgress(player, 2);
		helper.assertTrue(!tracker.isGoalMet(player),
			"expected stares+noises alone, with 0 breathes, to still not meet the compound goal");
		tracker.addBreatheProgress(player, 1);
		helper.assertTrue(tracker.isGoalMet(player),
			"expected the resumed run's combined progress (3+1=4 stares, 2+2=4 noises) plus a breathe to meet the goal");
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

	/** LightSourceScanner.snuffByWendigo's own torch branch - proves it replaces a real torch with the
	 * MATCHING one of WendigoBlocks' 8 registered snuffed-block instances (not always the same one
	 * regardless of family - the user's own explicit correction mid-design), preserves FACING for the
	 * wall case, and reads light level 0 afterward. Deliberately uses copper/redstone (not the plain
	 * family) so a bug that always fell back to the plain torch instance would actually be caught. */
	@GameTest
	public void snuffByWendigoConvertsATorchToItsMatchingSnuffedBlock(GameTestHelper helper) {
		BlockPos standingPos = new BlockPos(1, 2, 1);
		BlockPos wallPos = new BlockPos(3, 2, 1);
		helper.setBlock(standingPos, Blocks.COPPER_TORCH);
		helper.setBlock(wallPos, Blocks.REDSTONE_WALL_TORCH.defaultBlockState()
			.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));

		ServerLevel level = helper.getLevel();
		LightSourceScanner.snuffByWendigo(level, helper.absolutePos(standingPos), null);
		LightSourceScanner.snuffByWendigo(level, helper.absolutePos(wallPos), null);

		BlockState standingResult = helper.getBlockState(standingPos);
		BlockState wallResult = helper.getBlockState(wallPos);
		helper.assertTrue(standingResult.getBlock() == WendigoBlocks.SNUFFED_COPPER_TORCH,
			"expected the copper torch to snuff into SNUFFED_COPPER_TORCH specifically, got " + standingResult.getBlock());
		helper.assertTrue(standingResult.getLightEmission() == 0, "expected the snuffed torch to emit no light");
		helper.assertTrue(wallResult.getBlock() == WendigoBlocks.SNUFFED_REDSTONE_WALL_TORCH,
			"expected the redstone wall torch to snuff into SNUFFED_REDSTONE_WALL_TORCH specifically, got " + wallResult.getBlock());
		helper.assertTrue(wallResult.getValue(SnuffedWallTorchBlock.FACING) == Direction.EAST,
			"expected the wall torch's FACING to survive snuffing, got " + wallResult.getValue(SnuffedWallTorchBlock.FACING));
		helper.succeed();
	}

	/** Right-clicking a snuffed torch with flint and steel relights it back into its OWN original real
	 * family (not always a plain torch) - the user's own explicit correction. BlockState.useItemOn is
	 * the public dispatcher for Block's own protected useItemOn override (SnuffedTorchBlock/
	 * SnuffedWallTorchBlock aren't in this package, so their own useItemOn isn't directly callable). */
	@GameTest
	public void snuffedTorchRelightsBackToItsOwnFamily(GameTestHelper helper) {
		BlockPos wallPos = new BlockPos(3, 2, 1);
		helper.setBlock(wallPos, WendigoBlocks.SNUFFED_COPPER_WALL_TORCH.defaultBlockState()
			.setValue(SnuffedWallTorchBlock.FACING, Direction.SOUTH));

		ServerLevel level = helper.getLevel();
		BlockPos absoluteWallPos = helper.absolutePos(wallPos);
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		ItemStack flintAndSteel = new ItemStack(Items.FLINT_AND_STEEL);
		BlockHitResult hitResult = new BlockHitResult(Vec3.atCenterOf(absoluteWallPos), Direction.SOUTH, absoluteWallPos, false);

		level.getBlockState(absoluteWallPos).useItemOn(flintAndSteel, level, player, InteractionHand.MAIN_HAND, hitResult);

		BlockState relit = helper.getBlockState(wallPos);
		helper.assertTrue(relit.getBlock() == Blocks.COPPER_WALL_TORCH,
			"expected flint and steel to relight the snuffed copper wall torch back into a real copper wall torch, got " + relit.getBlock());
		helper.assertTrue(relit.getValue(BlockStateProperties.HORIZONTAL_FACING) == Direction.SOUTH,
			"expected FACING to survive relighting, got " + relit.getValue(BlockStateProperties.HORIZONTAL_FACING));
		helper.succeed();
	}

	/** LightSourceScanner.snuffByWendigo's own candle branch - a real vanilla candle, not a substitute
	 * block, just gets its own LIT property flipped off in place (see AbstractCandleBlock - vanilla
	 * already gives light level 0 and free flint-and-steel relighting for this state, nothing else to
	 * build). */
	@GameTest
	public void snuffByWendigoTogglesACandleOffInPlace(GameTestHelper helper) {
		BlockPos pos = new BlockPos(1, 2, 1);
		helper.setBlock(pos, Blocks.CANDLE.defaultBlockState().setValue(BlockStateProperties.LIT, true));

		LightSourceScanner.snuffByWendigo(helper.getLevel(), helper.absolutePos(pos), null);

		BlockState result = helper.getBlockState(pos);
		helper.assertTrue(result.getBlock() == Blocks.CANDLE, "expected the same real candle block, not a substitute");
		helper.assertTrue(!result.getValue(BlockStateProperties.LIT), "expected the candle to be snuffed to LIT=false");
		helper.assertTrue(result.getLightEmission() == 0, "expected the snuffed candle to emit no light");
		helper.succeed();
	}

	/** The narrowed-scope regression check: a lantern must NOT be treated as snuffable, even though it
	 * still counts as an ordinary breakable light source (isBreakableLightSource/findLightSources,
	 * unchanged) for the prompt's own torch-count context. */
	@GameTest
	public void findSnuffableLightSourcesIgnoresLanterns(GameTestHelper helper) {
		BlockPos originPos = new BlockPos(3, 2, 3);
		BlockPos lanternPos = new BlockPos(4, 2, 3);
		helper.setBlock(originPos, Blocks.STONE);
		helper.setBlock(lanternPos, Blocks.LANTERN);

		var snuffable = LightSourceScanner.findSnuffableLightSources(helper.getLevel(),
			helper.absolutePos(originPos), 10.0, 5);

		helper.assertTrue(snuffable.isEmpty(), "expected a lantern to never be returned as a snuffable target, got " + snuffable);
		helper.succeed();
	}
}
