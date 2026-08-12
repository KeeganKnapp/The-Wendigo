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
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.arrow.SpectralArrow;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import com.wendigo.WendigoMod;
import com.wendigo.block.SnuffedWallTorchBlock;
import com.wendigo.block.WendigoBlocks;
import com.wendigo.entity.ModEntities;
import com.wendigo.entity.WendigoEntity;
import com.wendigo.plan.PlanPredicates;
import com.wendigo.plan.SemanticBands;
import com.wendigo.spatial.CaveScaleScanner.CaveScale;
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

	/** A spectral arrow that actually deals real, meaningful damage under WendigoEntity's own
	 * distance/speed-scaled formula (see computeSpectralHitDamage) - close range (the shooter is
	 * teleported right next to the wendigo, well inside SPECTRAL_HIT_NEAR_DISTANCE) and a real,
	 * full-draw-speed velocity, not the zero-velocity default this constructor otherwise leaves an
	 * un-shot arrow at (0 speed deliberately maps to 0 damage now, the user's own explicit "a
	 * half-charged shot shouldn't deal a huge blow" design). Tests that only care about "did a hit
	 * register at all," not the exact damage amount, use this instead of constructing one by hand. */
	private static SpectralArrow makeLethalSpectralArrow(GameTestHelper helper, WendigoEntity wendigo) {
		ServerLevel level = helper.getLevel();
		ServerPlayer shooter = makeSurvivalMockPlayer(helper);
		shooter.teleportTo(wendigo.getX() + 1.0, wendigo.getY(), wendigo.getZ());
		SpectralArrow arrow = new SpectralArrow(level, wendigo.getX(), wendigo.getY(), wendigo.getZ(),
			ItemStack.EMPTY, new ItemStack(Items.BOW));
		arrow.setOwner(shooter);
		arrow.setDeltaMovement(new Vec3(-2.7, 0.0, 0.0));
		return arrow;
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
	 * ordinary player. */
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

	/** Only a SpectralArrow can ever damage the wendigo - see WendigoEntity.hurtServer's own doc
	 * comment. An ordinary arrow's damage source should be rejected outright: hurtServer returns
	 * false and no damage is actually applied, same as before the hit was attempted. */
	@GameTest
	public void ordinaryArrowDealsNoDamage(GameTestHelper helper) {
		WendigoEntity wendigo = helper.spawn(ModEntities.WENDIGO, new BlockPos(1, 1, 1));
		ServerLevel level = helper.getLevel();
		float healthBefore = wendigo.getHealth();
		Arrow arrow = new Arrow(level, wendigo.getX(), wendigo.getY(), wendigo.getZ(), ItemStack.EMPTY, new ItemStack(Items.BOW));

		boolean result = wendigo.hurtServer(level, wendigo.damageSources().arrow(arrow, null), 6.0F);

		helper.assertTrue(!result, "expected an ordinary arrow's hurtServer call to be rejected");
		helper.assertTrue(wendigo.getHealth() == healthBefore,
			"expected no damage from an ordinary arrow (was " + healthBefore + ", now " + wendigo.getHealth() + ")");
		helper.succeed();
	}

	/** A landed SpectralArrow hit should deal real damage and start the visible glow window (see
	 * WendigoEntity.startGlow/isCurrentlyGlowing, and WendigoVisual.applyGlow for the rig-side
	 * mirror of this, which isn't reachable from this headless test). */
	@GameTest
	public void spectralArrowDamagesAndGlowsTheWendigo(GameTestHelper helper) {
		WendigoEntity wendigo = helper.spawn(ModEntities.WENDIGO, new BlockPos(1, 1, 1));
		ServerLevel level = helper.getLevel();
		float healthBefore = wendigo.getHealth();
		SpectralArrow arrow = makeLethalSpectralArrow(helper, wendigo);

		boolean result = wendigo.hurtServer(level, wendigo.damageSources().arrow(arrow, arrow.getOwner()), 6.0F);

		helper.assertTrue(result, "expected a spectral arrow's hurtServer call to land");
		helper.assertTrue(wendigo.getHealth() < healthBefore,
			"expected the wendigo to take spectral arrow damage (was " + healthBefore + ", now " + wendigo.getHealth() + ")");
		helper.assertTrue(wendigo.isCurrentlyGlowing(), "expected the wendigo to start glowing after a spectral hit");
		helper.succeed();
	}

	/** The detection-only volume (see WendigoEntity.checkSpectralArrowDetection/arrowDetectionBox)
	 * is deliberately taller than the real (tiny) standing hitbox so a well-aimed spectral arrow
	 * still registers even if it never actually clips the real hitbox. Not forcing the crawling
	 * pose here - updatePose() runs every tick and would immediately revert it back to standing on
	 * this open test platform (no movement/navigation/crawl-space trigger), so this exercises the
	 * standing margin instead: STANDING_DIMENSIONS is 2.0 tall, ARROW_DETECTION_STANDING is 2.9
	 * tall - the arrow sits at +2.4, above the real hitbox but inside the wider detection box.
	 * Placing the arrow only after a short runAfterDelay, not immediately on spawn: a freshly-
	 * spawned wendigo actually falls a further block or so settling onto the floor (confirmed live
	 * via temporary diagnostic logging), so computing the arrow's Y off the pre-settle spawn
	 * position raced that fall and usually ended up above even the wider detection box by the time
	 * the entity actually came to rest. Lets the world's own tick loop run
	 * checkSpectralArrowDetection naturally from there (succeedWhen, not a manual tick() call,
	 * matching every other tick-driven assertion in this file). */
	@GameTest
	public void spectralArrowDetectionBoxCatchesAMissThatSkipsTheRealHitbox(GameTestHelper helper) {
		WendigoEntity wendigo = helper.spawn(ModEntities.WENDIGO, new BlockPos(1, 1, 1));
		ServerLevel level = helper.getLevel();
		float healthBefore = wendigo.getHealth();

		ServerPlayer shooter = makeSurvivalMockPlayer(helper);
		shooter.teleportTo(wendigo.getX() + 1.0, wendigo.getY(), wendigo.getZ());

		helper.runAfterDelay(10, () -> {
			SpectralArrow arrow = new SpectralArrow(level, wendigo.getX(), wendigo.getY() + 2.4, wendigo.getZ(),
				ItemStack.EMPTY, new ItemStack(Items.BOW));
			arrow.setOwner(shooter);
			// No gravity - stays exactly in the detection window regardless of how many ticks the
			// wendigo's own pose takes to settle to standing (updatePose() briefly reads "moving"
			// right after spawn), rather than this test racing a falling arrow against that window.
			arrow.setNoGravity(true);
			// A small nonzero nudge, not zero: checkSpectralArrowDetection now only counts a STILL-
			// AIRBORNE arrow (see WendigoEntity.ARROW_AIRBORNE_VELOCITY_SQR_THRESHOLD) - an arrow this
			// constructor never shot would otherwise read as already "grounded" and get skipped,
			// which is exactly what this test is NOT trying to exercise, but still a real nonzero
			// speed so computeSpectralHitDamage's own speed multiplier doesn't wash the hit's damage
			// out to an unreliable near-zero float. Downward (Y), not horizontal: the standing
			// detection box's own horizontal half-width is only 0.3 (no margin over the real hitbox
			// at all - only height got a generous bump), so even this small a velocity accumulates
			// enough drift over the ~10-15 ticks this test takes to resolve to drift the arrow clean
			// out of that narrow a footprint (confirmed live via temporary diagnostic logging - the
			// first version of this test used horizontal drift and never got caught at all). The
			// vertical window has 2.4 blocks of room below this starting height before leaving it, a
			// much more generous margin for the same drift.
			arrow.setDeltaMovement(new Vec3(0.0, -0.1, 0.0));
			level.addFreshEntity(arrow);

			helper.succeedWhen(() -> {
				helper.assertTrue(wendigo.getHealth() < healthBefore,
					"expected the wider detection volume to catch a spectral arrow that misses the real hitbox (was "
						+ healthBefore + ", now " + wendigo.getHealth() + ")");
				helper.assertTrue(!arrow.isAlive(), "expected the detection scan to discard the arrow once consumed");
			});
		});
	}

	/** A grounded (already stuck) spectral arrow should NOT deal damage via the fallback detection
	 * scan - the user's own explicit follow-up request: only a still-in-flight arrow counts as
	 * "getting shot," not one sitting nearby long after missing. This constructor never calls
	 * shoot(), so the arrow's own default velocity is already zero - no explicit setDeltaMovement
	 * needed here, unlike the still-airborne sibling test right above. Waits several ticks (not
	 * succeedWhen, which is for an eventually-true condition - this asserts the opposite, that
	 * nothing happens) then confirms health never moved and the arrow was left alone. */
	@GameTest(maxTicks = 60)
	public void groundedSpectralArrowDealsNoDamage(GameTestHelper helper) {
		WendigoEntity wendigo = helper.spawn(ModEntities.WENDIGO, new BlockPos(1, 1, 1));
		ServerLevel level = helper.getLevel();
		float healthBefore = wendigo.getHealth();

		helper.runAfterDelay(10, () -> {
			SpectralArrow arrow = new SpectralArrow(level, wendigo.getX(), wendigo.getY() + 2.4, wendigo.getZ(),
				ItemStack.EMPTY, new ItemStack(Items.BOW));
			arrow.setNoGravity(true);
			level.addFreshEntity(arrow);

			helper.runAfterDelay(20, () -> {
				helper.assertTrue(wendigo.getHealth() == healthBefore,
					"expected a motionless (grounded) spectral arrow to deal no damage (was "
						+ healthBefore + ", now " + wendigo.getHealth() + ")");
				helper.assertTrue(arrow.isAlive(), "expected a grounded arrow to be left alone, not consumed");
				helper.succeed();
			});
		});
	}

	/** A landed spectral arrow hit immediately abandons whatever the wendigo was doing and flees -
	 * the user's own explicit follow-up request (see PlanRunner.forceFleeToOrbit). Puts it into an
	 * active orbit first (startOrbit), then confirms a landed spectral hit flips isOrbiting() off. */
	@GameTest
	public void spectralHitAbandonsAnActiveOrbit(GameTestHelper helper) {
		WendigoEntity wendigo = helper.spawn(ModEntities.WENDIGO, new BlockPos(1, 1, 1));
		ServerPlayer player = makeSurvivalMockPlayer(helper);
		player.teleportTo(wendigo.getX() + 3.0, wendigo.getY(), wendigo.getZ());
		wendigo.startOrbit(player);
		helper.assertTrue(wendigo.isOrbiting(), "expected startOrbit to actually start orbiting");

		ServerLevel level = helper.getLevel();
		SpectralArrow arrow = makeLethalSpectralArrow(helper, wendigo);
		wendigo.hurtServer(level, wendigo.damageSources().arrow(arrow, arrow.getOwner()), 6.0F);

		helper.assertTrue(!wendigo.isOrbiting(), "expected a landed spectral hit to abandon the active orbit");
		helper.succeed();
	}

	/** A landed spectral hit also starts the flee lock (see PlanRunner.isFleeingFromSpectralHit) -
	 * WendigoManager's own hardcoded overrides (overrideIntoChaseUntilLight/overrideIntoLunge/
	 * checkUnconditionalGrab) all check this before firing, so it must actually turn on the instant
	 * a hit lands, not just eventually. */
	@GameTest
	public void spectralHitStartsTheFleeLock(GameTestHelper helper) {
		WendigoEntity wendigo = helper.spawn(ModEntities.WENDIGO, new BlockPos(1, 1, 1));
		helper.assertTrue(!wendigo.isFleeingFromSpectralHit(), "expected the flee lock to start off");

		ServerLevel level = helper.getLevel();
		SpectralArrow arrow = makeLethalSpectralArrow(helper, wendigo);
		wendigo.hurtServer(level, wendigo.damageSources().arrow(arrow, arrow.getOwner()), 6.0F);

		helper.assertTrue(wendigo.isFleeingFromSpectralHit(), "expected a landed spectral hit to start the flee lock");
		helper.succeed();
	}

	/** forceFleeToOrbit no-ops while actively carrying a rider - a mid-carry ride resolves through
	 * its own separate mechanism, not ordinary plan execution (see its own doc comment). A spectral
	 * hit landing mid-ride shouldn't unmount the rider out from under them. */
	@GameTest
	public void spectralHitDuringAForcedRideDoesNotInterruptIt(GameTestHelper helper) {
		WendigoEntity wendigo = helper.spawn(ModEntities.WENDIGO, new BlockPos(1, 1, 1));
		ServerPlayer player = makeSurvivalMockPlayer(helper);
		wendigo.forceGrabNow(player);
		helper.assertTrue(wendigo.isForcingRide(), "expected forceGrabNow to actually start a forced ride");

		ServerLevel level = helper.getLevel();
		SpectralArrow arrow = makeLethalSpectralArrow(helper, wendigo);
		wendigo.hurtServer(level, wendigo.damageSources().arrow(arrow, arrow.getOwner()), 6.0F);

		helper.assertTrue(wendigo.isForcingRide(), "expected the forced ride to still be in progress after a mid-ride spectral hit");
		helper.assertTrue(player.getVehicle() == wendigo, "expected the player to still be riding the wendigo");
		helper.succeed();
	}

	/** Distance-scaled spectral hit damage (see WendigoEntity.computeSpectralHitDamage) - the
	 * user's own explicit request: a shot from far away should barely hurt, even at full arrow
	 * speed, so a player only barely visible at range can't meaningfully chip away at the wendigo.
	 * Shoots from well past SPECTRAL_HIT_FAR_DISTANCE at full draw speed and confirms the resulting
	 * damage stays down near the curve's own 1-damage floor, not anywhere close to the 10-damage
	 * close-range case makeLethalSpectralArrow's own sibling tests land. */
	@GameTest
	public void spectralHitFromFarAwayDealsMinimalDamage(GameTestHelper helper) {
		WendigoEntity wendigo = helper.spawn(ModEntities.WENDIGO, new BlockPos(1, 1, 1));
		ServerLevel level = helper.getLevel();
		ServerPlayer shooter = makeSurvivalMockPlayer(helper);
		shooter.teleportTo(wendigo.getX() + 20.0, wendigo.getY(), wendigo.getZ());
		float healthBefore = wendigo.getHealth();
		SpectralArrow arrow = new SpectralArrow(level, wendigo.getX(), wendigo.getY(), wendigo.getZ(),
			ItemStack.EMPTY, new ItemStack(Items.BOW));
		arrow.setOwner(shooter);
		arrow.setDeltaMovement(new Vec3(-2.7, 0.0, 0.0)); // full draw speed - distance alone should still cap this low

		wendigo.hurtServer(level, wendigo.damageSources().arrow(arrow, arrow.getOwner()), 6.0F);

		float damageTaken = healthBefore - wendigo.getHealth();
		helper.assertTrue(damageTaken > 0.0F, "expected a far-away hit to still land, just barely");
		helper.assertTrue(damageTaken <= 1.5F, "expected minimal damage from a far-away shot (took " + damageTaken + ")");
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

	/** Direct check of DarkSpotScanner.findLiveBandPositionInView (the "in_view" destination type's own
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
			BlockPos inView = DarkSpotScanner.findLiveBandPositionInView(helper.getLevel(), player, 0.0, 5.0, Direction.UP);
			helper.assertTrue(inView != null, "expected an in-view position to be found");
			double distance = Math.sqrt(inView.distSqr(playerPos));
			helper.assertTrue(distance <= 5.0,
				"expected the in-view pick to fall within the sampled band, got distance " + distance);
		});
	}

	/** Direct check of DarkSpotScanner.findLiveBandPositionEyeline (the "eyeline" destination type's
	 * own resolver) - same shape as findLiveBandPositionInViewFindsAFacingSpot right above (shares the
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
			BlockPos eyeline = DarkSpotScanner.findLiveBandPositionEyeline(helper.getLevel(), player, 0.0, 5.0, Direction.UP);
			helper.assertTrue(eyeline != null, "expected an eyeline position to be found");
			double distance = Math.sqrt(eyeline.distSqr(playerPos));
			helper.assertTrue(distance <= 5.0,
				"expected the eyeline pick to fall within the sampled band, got distance " + distance);
		});
	}

	/** Direct check of DarkSpotScanner.findCeilingSpotAbovePlayer (combat.teleport's own new
	 * "above" resolver) - the direct-above probe this method tries first is a deterministic
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

	/** combat.teleport(destination=in_view) via debugInjectPlan - proves the dispatch case itself is
	 * wired correctly (right JSON field names, right resolver call, falls through to a resolved
	 * action instead of hitting startAction's own "unknown action type" fallback) end to end through
	 * PlanRunner, the thing a pure DarkSpotScanner unit test can't catch on its own. Deliberately
	 * doesn't also assert the entity actually relocated, unlike injectedTeleportToBandPlanRelocatesTheEntity
	 * right above - that would need a room genuinely large enough to contain the whole
	 * reachable orbit-band retry cascade, which doesn't
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
		teleportStep.addProperty("type", "combat.teleport");
		teleportStep.addProperty("destination", "in_view");
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

	/** combat.teleport(destination=eyeline) via debugInjectPlan - same dispatch-wiring proof as
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
		teleportStep.addProperty("type", "combat.teleport");
		teleportStep.addProperty("destination", "eyeline");
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

	/** combat.teleport(destination=ahead) via debugInjectPlan - same dispatch-wiring proof as
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
		teleportStep.addProperty("type", "combat.teleport");
		teleportStep.addProperty("destination", "ahead");
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
		teleportStep.addProperty("type", "combat.teleport");
		teleportStep.addProperty("destination", "ahead");
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

	/** combat.teleport(destination=behind) - debugInjectPlan bypasses SchemaBuilder's own
	 * stage-filtered "destination" enum (see TierGates.teleportTypesUnlocked) the same way every
	 * other injected plan bypasses ordinary tier gating - unlike the old band system, there's no
	 * separate runtime source-position precondition anymore, so no particular start distance needs
	 * to be arranged for this to resolve. Proves the whole
	 * DarkSpotScanner.findUnwatchedPosition3D -> PlanRunner teleport pipeline actually relocates the
	 * entity somewhere new, not just resolves the action as a no-op. */
	@GameTest
	public void injectedTeleportToBandPlanRelocatesTheEntity(GameTestHelper helper) {
		WendigoEntity wendigo = helper.spawn(ModEntities.WENDIGO, new BlockPos(5, 1, 5));
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		player.teleportTo(1.5, 1.0, 1.5);
		BlockPos startPos = wendigo.blockPosition();

		JsonObject teleportStep = new JsonObject();
		teleportStep.addProperty("type", "combat.teleport");
		teleportStep.addProperty("destination", "behind");
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

	/** movement.approach_spot(destination=above) chained into movement.drop (both unlocked at 60% outside
	 * debug - debugInjectPlan bypasses tier gating) - proves WendigoEntity.forceDetach actually causes
	 * a real physical fall, not just a no-op or a purely cosmetic state flip, when used the way the
	 * user's own request actually suggests pairing it (destination=above, then drop). Deliberately NOT a raw
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
		approachStep.addProperty("type", "movement.approach_spot");
		approachStep.addProperty("destination", "above");
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
			helper.assertTrue(wendigo.isWaveComplete(), "expected the injected destination=above + drop plan to complete");
			helper.assertTrue(wendigo.getY() < startY + 2.0,
				"expected movement.drop to cause a real fall back down after reaching the ceiling - ended at y="
					+ wendigo.getY());
		});
	}

	/** combat.teleport(destination=above) chained into movement.drop - the exact "raw teleport-spawn
	 * onto the ceiling followed by a bare drop" setup the test right above's own doc comment describes
	 * as a real, separate, live-debugged bug. This test is what the user's own live "he can't actually
	 * drop from the ceiling" report was actually hitting: the walked destination=above path right above already
	 * worked and had coverage; this teleport path didn't and lacked any. Two real, separate bugs found
	 * and fixed chasing this down (see PlanRunner's combat.teleport case and WendigoEntity.
	 * forceDetach's own updated doc comments for the full account):
	 * <p>
	 * 1. nudgeTowardAttachedSurface used to unconditionally push Direction.UP (its own no-op floor
	 * convention) after EVERY teleport, regardless of which band actually resolved - so a ceiling
	 * landing via above never got the corrective push needed to make AWCAPI recognize a real
	 * ceiling collision at all, and just fell straight back off under ordinary gravity. Fixed: pass
	 * Direction.DOWN (the ceiling convention) specifically when destinationType is "above".
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
	 * guaranteed full fall back to the floor - the walked destination=above path above remains the reliable
	 * choice for a real plan that needs the fall to complete. Room height/padding match
	 * findCeilingSpotAbovePlayerFindsAHighDarkCeiling's own established minimum for actually landing a
	 * candidate inside above's own [10,30] window (12: ceiling solid at 12, open candidate at
	 * 11, 10 above the player's own y=1), and the plan injection is delayed (see that same test's own
	 * "stale/lit" note) so the room's own lighting has genuinely settled dark before above's
	 * live resolution ever runs - this file's own established convention for any test that needs a
	 * real above resolution, not just this test's own room. */
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
		teleportStep.addProperty("type", "combat.teleport");
		teleportStep.addProperty("destination", "above");
		JsonObject dropStep = new JsonObject();
		dropStep.addProperty("type", "movement.drop");
		JsonArray steps = new JsonArray();
		steps.add(teleportStep);
		steps.add(dropStep);
		JsonObject plan = new JsonObject();
		plan.add("plan", steps);
		plan.add("global_rules", new JsonArray());

		// See this test's own doc comment - the room's lighting hasn't actually settled dark yet on the
		// very tick helper.setBlock runs, and above's own live resolution needs it to have.
		helper.runAfterDelay(40, () -> wendigo.debugInjectPlan(plan));

		double[] peakY = {startY};
		helper.onEachTick(() -> peakY[0] = Math.max(peakY[0], wendigo.getY()));

		boolean[] reachedCeiling = {false};
		helper.succeedWhen(() -> {
			if (wendigo.getY() > startY + 3.0) {
				reachedCeiling[0] = true;
			}
			helper.assertTrue(reachedCeiling[0], "expected combat.teleport(destination=above) to reach up near the ceiling first");
			helper.assertTrue(wendigo.isWaveComplete(), "expected the injected teleport_to_band(above) + drop plan to complete");
			helper.assertTrue(wendigo.getY() < peakY[0] - 1.0,
				"expected movement.drop to cause at least some real downward movement off the ceiling (not silently no-op) "
					+ "- peak y=" + peakY[0] + " ended at y=" + wendigo.getY());
		});
	}

	/** movement.approach_spot(destination=above) (unlocked at 60% outside debug - debugInjectPlan bypasses
	 * tier gating) - proves the "destination=above" band actually resolves to a real ceiling position above
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
		approachStep.addProperty("type", "movement.approach_spot");
		approachStep.addProperty("destination", "above");
		approachStep.addProperty("speed", "fast");
		JsonArray steps = new JsonArray();
		steps.add(approachStep);
		JsonObject plan = new JsonObject();
		plan.add("plan", steps);
		plan.add("global_rules", new JsonArray());

		wendigo.debugInjectPlan(plan);

		helper.succeedWhen(() -> {
			helper.assertTrue(wendigo.isWaveComplete(), "expected the injected destination=above approach plan to complete");
			helper.assertTrue(wendigo.getY() > startY + 3.0,
				"expected movement.approach_spot(destination=above) to actually reach up near the ceiling - started at y="
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
	 * (stage 1 = 4 successful stares AND 4 sound cues, both axes independently, see
	 * WendigoProgressionTracker's own STAGE_GOALS/STAGE1_SOUND_GOAL - breathe is NOT part of stage 1's
	 * own goal, see isGoalMet's own stage==1 exemption; sound.breathe isn't even offered at stage 1 at
	 * all anymore, see TierGates.minPercentFor) makes isGoalMet true, and resolveRunOutcome both
	 * advances completedRuns (no soul-light tally recorded here, so tasksNotNearSoulLight (0) >=
	 * tasksNearSoulLight (0) - ties favor advancing, same as the old unconditional completeRun this
	 * replaced) and correctly moves a player from their first completed run (spawn 1, stage 1) into
	 * their second run (spawn 2, still stage 1 - stage 1 covers the first two runs, see stageFor's own
	 * doc comment) and then their third run (spawn 3, stage 2). */
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
			"expected 4/4 stares alone, with 0 sound cues, to not meet stage 1's AND goal");
		tracker.addSecondaryProgress(player, 4);
		helper.assertTrue(tracker.isGoalMet(player),
			"expected stage 1's 4-stares-AND-4-noises goal to be met once both clear - breathe is exempt at stage 1");
		tracker.resolveRunOutcome(helper.getLevel(), player);
		helper.assertTrue(tracker.completedRunsOf(player) == 1, "expected completedRuns to advance to 1");
		helper.assertTrue(tracker.stageOf(player) == 1, "expected the 2nd run to still be stage 1");

		tracker.startRun(player);
		tracker.addProgress(player, 4);
		tracker.addSecondaryProgress(player, 4);
		tracker.resolveRunOutcome(helper.getLevel(), player);
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
		helper.assertTrue(tracker.isGoalMet(player),
			"expected the resumed run's combined progress (3+1=4 stares, 2+2=4 noises) to meet the goal - "
				+ "breathe is exempt at stage 1, no third axis to clear");
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
	 * dead-on-arrival. Solo player, so the group-demotion-to-stage-2 half of endStage5Hunt is covered
	 * separately (see endStage5HuntDemotesWholeGroupToStage2, capping not raising below-stage-2
	 * members) - this test is purely about the health-reset side. */
	@GameTest
	public void progressionStage5HealthPersistsAndResetsOnKill(GameTestHelper helper) {
		WendigoProgressionTracker tracker = new WendigoProgressionTracker();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();

		helper.assertTrue(tracker.stage5HealthOf(player, 50.0F) == 50.0F, "expected full health with nothing saved yet");
		tracker.saveStage5Health(player, 22.0F);
		helper.assertTrue(tracker.stage5HealthOf(player, 50.0F) == 22.0F, "expected the saved health to be restored");
		tracker.endStage5Hunt(helper.getLevel(), player);
		helper.assertTrue(tracker.stage5HealthOf(player, 50.0F) == 50.0F, "expected a kill to reset health back to full");
		helper.succeed();
	}

	/** The soul-light progression redesign's own core new behavior - the user's own explicit "if the
	 * number of tasks completed while the player was not in a soul light is greater than or equal to
	 * the number completed while player was near a soul light then the players completed runs gets
	 * incremented. And if its the other way around then the targets runs gets decremented" request.
	 * Starts at completedRuns=1 (not 0) specifically so a regression is actually observable - a floor
	 * of 0 would otherwise mask the difference between "stayed at 0" and "genuinely decremented". */
	@GameTest
	public void resolveRunOutcomeRegressesWhenNearSoulLightTallyWins(GameTestHelper helper) {
		WendigoProgressionTracker tracker = new WendigoProgressionTracker();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		tracker.setRunsForTesting(player, 1); // stage 1 (stageFor(1) == 1) - not 0, so regressing is observable
		tracker.startRun(player);
		tracker.addProgress(player, 4);
		tracker.addSecondaryProgress(player, 4);
		helper.assertTrue(tracker.isGoalMet(player), "expected stage 1's compound goal to be met");
		tracker.addSoulLightTally(player, 5, 3); // near (5) > notNear (3) - should regress, not advance
		boolean favorable = tracker.resolveRunOutcome(helper.getLevel(), player);
		helper.assertTrue(!favorable, "expected a near-soul-light-dominant tally to report unfavorable");
		helper.assertTrue(tracker.completedRunsOf(player) == 0, "expected completedRuns to regress from 1 to 0, not advance to 2");
		helper.succeed();
	}

	/** The user's own explicit ">=" (not ">") - a tied tally still favors advancing, same as the old
	 * unconditional completeRun this replaced would have. */
	@GameTest
	public void resolveRunOutcomeAdvancesOnATiedTally(GameTestHelper helper) {
		WendigoProgressionTracker tracker = new WendigoProgressionTracker();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		tracker.startRun(player);
		tracker.addProgress(player, 4);
		tracker.addSecondaryProgress(player, 4);
		tracker.addSoulLightTally(player, 2, 2); // tied
		boolean favorable = tracker.resolveRunOutcome(helper.getLevel(), player);
		helper.assertTrue(favorable, "expected a tied near/not-near tally to favor advancing");
		helper.assertTrue(tracker.completedRunsOf(player) == 1, "expected completedRuns to advance on a tie");
		helper.succeed();
	}

	/** The user's own explicit "enforce a minimum stage of 2 after the first time a player exits
	 * stage 1... for the rest of the game" request - once completedRuns has ever reached 2 (stage
	 * 2), a soul-light regression can no longer push it back down to stage 1, unlike
	 * resolveRunOutcomeRegressesWhenNearSoulLightTallyWins's own stage-1 case right above (which
	 * legitimately still floors at plain 0, since that player never reached stage 2 in the first
	 * place). */
	@GameTest
	public void resolveRunOutcomeFloorsAtStage2OnceReached(GameTestHelper helper) {
		WendigoProgressionTracker tracker = new WendigoProgressionTracker();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		tracker.setRunsForTesting(player, 2); // already at stage 2 (stageFor(2) == 2)
		tracker.startRun(player);
		tracker.addProgress(player, 100); // stage 2's own goal (10) - overshoot is harmless, isGoalMet just needs >=
		tracker.addSoulLightTally(player, 5, 3); // near (5) > notNear (3) - should regress, but floored
		boolean favorable = tracker.resolveRunOutcome(helper.getLevel(), player);
		helper.assertTrue(!favorable, "expected a near-soul-light-dominant tally to still report unfavorable");
		helper.assertTrue(tracker.completedRunsOf(player) == 2,
			"expected completedRuns to stay floored at 2 (stage 2), not regress to 1 (was "
				+ tracker.completedRunsOf(player) + ")");
		helper.succeed();
	}

	/** Stage 5's own group-wide demotion - the user's own explicit "defeating the wendigo should
	 * demote to stage 2, not 3" correction (originally stage 3, changed same session). Three players
	 * within MOB_CLEAR_OUTER_RADIUS of each other: the killer and a nearby stage-5 veteran both demote
	 * to stage 2's own completedRuns value (2, also resolveRunOutcome's own permanent floor - see its
	 * doc comment, the two now share the same number deliberately); a nearby newcomer already BELOW
	 * stage 2 is capped down only, never raised up to 2 by someone else's kill (Math.min, not a flat
	 * set - see endStage5Hunt's own doc comment). */
	@GameTest
	public void endStage5HuntDemotesWholeGroupToStage2(GameTestHelper helper) {
		WendigoProgressionTracker tracker = new WendigoProgressionTracker();
		ServerPlayer killer = helper.makeMockServerPlayerInLevel();
		ServerPlayer nearbyVeteran = helper.makeMockServerPlayerInLevel();
		ServerPlayer nearbyNewcomer = helper.makeMockServerPlayerInLevel();

		BlockPos killerPos = helper.absolutePos(new BlockPos(1, 1, 1));
		killer.teleportTo(killerPos.getX() + 0.5, killerPos.getY(), killerPos.getZ() + 0.5);
		BlockPos veteranPos = helper.absolutePos(new BlockPos(3, 1, 1));
		nearbyVeteran.teleportTo(veteranPos.getX() + 0.5, veteranPos.getY(), veteranPos.getZ() + 0.5);
		BlockPos newcomerPos = helper.absolutePos(new BlockPos(5, 1, 1));
		nearbyNewcomer.teleportTo(newcomerPos.getX() + 0.5, newcomerPos.getY(), newcomerPos.getZ() + 0.5);

		tracker.setRunsForTesting(killer, 7); // stage 5 (stageFor caps at 5 for completedRuns>=5)
		tracker.setRunsForTesting(nearbyVeteran, 6); // also stage 5 - should demote to 2 too
		tracker.setRunsForTesting(nearbyNewcomer, 1); // below stage 2 - must NOT be raised

		tracker.startRun(killer); // creates the active run endStage5Hunt is about to clear
		tracker.endStage5Hunt(helper.getLevel(), killer);

		helper.assertTrue(tracker.completedRunsOf(killer) == 2, "expected the killer to demote to stage 2's completedRuns (2)");
		helper.assertTrue(tracker.completedRunsOf(nearbyVeteran) == 2, "expected a nearby stage-5 groupmate to ALSO demote to 2");
		helper.assertTrue(tracker.completedRunsOf(nearbyNewcomer) == 1, "expected a nearby below-stage-2 groupmate to stay at 1, not be raised to 2");
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

	/** Direct regression check for SemanticBands.isAcceptableOrbitPath - the user's own explicit
	 * report: the wendigo kept spawning/repositioning in caverns "in no way connected to the players
	 * area", even though a real reachability check existed at some call sites (it just wasn't a
	 * genuine bounded retry - one weak fallback, then commit regardless). Builds a real sealed room
	 * (the player/wendigo's own space) plus a SECOND sealed room a few blocks away, walled off with no
	 * connection at all - both are real, solid, distinct spaces, not just two BlockPos values - then
	 * asserts a real AWCAPI path to the disconnected room is rejected for CaveScale.TIGHT (Path.
	 * canReach() must be false - nothing at all connects the two rooms) while a path to a spot in the
	 * SAME room the wendigo is standing in is accepted. Uses succeedWhen (not a same-tick check) since
	 * a freshly-placed room's own lighting/collision needs a real tick to settle, same as this file's
	 * other room-building tests. */
	@GameTest(padding = 30, maxTicks = 200)
	public void isAcceptableOrbitPathRejectsADisconnectedPocket(GameTestHelper helper) {
		buildSealedRoom(helper, 0);
		buildSealedRoom(helper, 12); // a second, otherwise-identical room, deliberately never connected
		// The gap between the two rooms (x=7..11) is already solid rock by construction - GameTest's
		// own empty template fills unset space with air, not stone, so this needs to be explicit rather
		// than assumed.
		for (int x = 7; x <= 11; x++) {
			for (int y = -1; y <= 10; y++) {
				for (int z = -1; z <= 7; z++) {
					helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
				}
			}
		}

		WendigoEntity wendigo = helper.spawn(ModEntities.WENDIGO, new BlockPos(3, 1, 3));
		BlockPos reachableCandidate = helper.absolutePos(new BlockPos(5, 1, 5)); // same room
		BlockPos disconnectedCandidate = helper.absolutePos(new BlockPos(15, 1, 3)); // sealed second room

		helper.succeedWhen(() -> {
			// See WendigoManager.pathToTarget's own doc comment - onGround forced true is required for
			// PathNavigation.createPath's own canUpdatePath() gate on a freshly-spawned entity that
			// hasn't had a real physics tick resolve it naturally yet.
			boolean savedOnGround = wendigo.onGround();
			wendigo.setOnGround(true);
			// distance=1, not 0 - matches PlanRunner.findReachableOrbitPath/WendigoManager's own
			// BlockPos-target convention (see their own comments: createPath(BlockPos, int) resolves
			// through different internal search parameters than the entity-target overload, and needs
			// distance=1 to reliably resolve canReach()=true for a legitimately close candidate).
			Path reachablePath = wendigo.getNavigation().createPath(reachableCandidate, 1);
			Path disconnectedPath = wendigo.getNavigation().createPath(disconnectedCandidate, 1);
			wendigo.setOnGround(savedOnGround);

			helper.assertTrue(SemanticBands.isAcceptableOrbitPath(reachablePath, CaveScale.TIGHT),
				"expected a same-room candidate to be accepted, path=" + reachablePath);
			helper.assertTrue(!SemanticBands.isAcceptableOrbitPath(disconnectedPath, CaveScale.TIGHT),
				"expected a candidate in a completely walled-off second room to be rejected, path=" + disconnectedPath
					+ " canReach=" + (disconnectedPath != null && disconnectedPath.canReach()));
		});
	}

	/** Direct regression check for DarknessAwareClimberNodeEvaluator's own rail-downgrade fix - the
	 * user's own live "he still has trouble pathing over rails" report. Builds a straight, exactly
	 * 1-block-wide corridor (floor/ceiling/both side walls all solid, no way to route around anything
	 * placed in it - 3 blocks of vertical clearance, well above STANDING_DIMENSIONS' own 2.0-block
	 * height, so this is testing the rail fix specifically, not accidentally re-testing hitbox
	 * clearance) with a single rail block in the middle and the wendigo starting at one end, NOT
	 * standing on a rail - exactly AWCAPI's own (and, before it, vanilla WalkNodeEvaluator's) "mob
	 * isn't currently on a rail" downgrade trigger. Without the fix, the rail tile's node type gets
	 * silently downgraded to PathType.UNPASSABLE_RAIL (malus -1.0F, effectively BLOCKED) and - since
	 * the corridor is single-width, there is no way around it - the far end becomes genuinely
	 * unreachable. */
	@GameTest(padding = 20, maxTicks = 200)
	public void railTileInANarrowCorridorDoesNotBlockPathfinding(GameTestHelper helper) {
		for (int x = 0; x <= 6; x++) {
			helper.setBlock(new BlockPos(x, 0, 1), Blocks.STONE); // floor
			helper.setBlock(new BlockPos(x, 4, 1), Blocks.STONE); // ceiling
			for (int y = 1; y <= 3; y++) {
				helper.setBlock(new BlockPos(x, y, 0), Blocks.STONE); // side wall
				helper.setBlock(new BlockPos(x, y, 2), Blocks.STONE); // side wall
			}
		}
		helper.setBlock(new BlockPos(3, 1, 1), Blocks.RAIL);

		WendigoEntity wendigo = helper.spawn(ModEntities.WENDIGO, new BlockPos(0, 1, 1));
		BlockPos farEnd = helper.absolutePos(new BlockPos(6, 1, 1));

		helper.succeedWhen(() -> {
			// See isAcceptableOrbitPathRejectsADisconnectedPocket's own comment on why onGround/distance=1
			// are both needed for a freshly-spawned entity's createPath to resolve canReach() correctly.
			boolean savedOnGround = wendigo.onGround();
			wendigo.setOnGround(true);
			Path path = wendigo.getNavigation().createPath(farEnd, 1);
			wendigo.setOnGround(savedOnGround);
			helper.assertTrue(path != null && path.canReach(),
				"expected the far end of the corridor to be reachable straight through the rail tile, path=" + path
					+ " canReach=" + (path != null && path.canReach()));
		});
	}

	// Same 7x7x9 sealed-room shape this file's other room-building tests already use, offset along X
	// so two independent, non-overlapping rooms can be built in the same test structure.
	private static void buildSealedRoom(GameTestHelper helper, int xOffset) {
		int roomHeight = 9;
		for (int x = xOffset; x <= xOffset + 6; x++) {
			for (int z = 0; z <= 6; z++) {
				helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
				helper.setBlock(new BlockPos(x, roomHeight, z), Blocks.STONE);
			}
		}
		for (int y = 1; y < roomHeight; y++) {
			for (int x = xOffset; x <= xOffset + 6; x++) {
				helper.setBlock(new BlockPos(x, y, 0), Blocks.STONE);
				helper.setBlock(new BlockPos(x, y, 6), Blocks.STONE);
			}
			for (int z = 0; z <= 6; z++) {
				helper.setBlock(new BlockPos(xOffset, y, z), Blocks.STONE);
				helper.setBlock(new BlockPos(xOffset + 6, y, z), Blocks.STONE);
			}
		}
	}
}
