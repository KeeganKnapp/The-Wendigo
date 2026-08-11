package com.wendigo.debug;

import net.fabricmc.fabric.api.event.player.UseItemCallback;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import com.wendigo.WendigoMod;
import com.wendigo.entity.ModEntities;
import com.wendigo.entity.WendigoEntity;
import com.wendigo.wave.WendigoProgressionTracker;

/**
 * Three testing-convenience items, all granted together the moment a player's own debug session
 * turns on (see WendigoCommands.toggleDebug) - built to support a live wendigo-vs-(real climbing
 * spider) A/B ceiling-comparison test: a bug-report bookmark, a guaranteed-stage-5 spawner, and a
 * tracked-spider summoner. Identified by a CustomData marker (not by item type or display name alone
 * - a renamed/anvil-relabeled copy would otherwise stop matching) so UseItemCallback can tell them
 * apart from an ordinary bell/nether star/spider eye the player happens to be holding.
 */
public final class WendigoDebugItems {
	private static final String MARKER_KEY = "WendigoDebugItem";
	private static final String BOOKMARK_MARKER = "bookmark";
	private static final String STAGE5_MARKER = "stage5_spawner";
	private static final String SPIDER_MARKER = "spider_summoner";

	// Generous - a cave test rig can have a long sightline down a corridor, and pointing at something
	// just out of range should fail with a clear message rather than silently picking the wrong spot.
	private static final double RAYCAST_MAX_DISTANCE = 48.0;

	// Hand-authored single-step plan forcing an immediate, sustained combat.chase toward whoever
	// summoned this test wendigo - the user's own explicit request: startOrbit alone (the previous
	// behavior) only wanders, never actually engages combat.chase, unlike the spider-summoner item's
	// own forced chase. No "speed" field - confirmed via reading PlanRunner's own combat.chase
	// dispatch case that it never reads one (forced to LUNGE_CHASE_SPEED_MULTIPLIER regardless of
	// what a plan step would specify anyway, unlike combat.lunge_attack/combat.break_torch).
	private static final String FORCE_CHASE_PLAN = "{\"plan\": [{\"type\": \"combat.chase\"}]}";

	private WendigoDebugItems() {
	}

	public static void init() {
		UseItemCallback.EVENT.register((player, level, hand) -> {
			if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer) || !(level instanceof ServerLevel serverLevel)) {
				return InteractionResult.PASS;
			}
			ItemStack stack = player.getItemInHand(hand);
			String marker = markerOf(stack);
			if (marker == null) {
				return InteractionResult.PASS;
			}
			switch (marker) {
				case BOOKMARK_MARKER -> useBookmark(serverPlayer, serverLevel);
				case STAGE5_MARKER -> useStage5Spawner(serverPlayer, serverLevel);
				case SPIDER_MARKER -> useSpiderSummoner(serverPlayer, serverLevel);
				default -> {
					return InteractionResult.PASS;
				}
			}
			return InteractionResult.SUCCESS;
		});
	}

	/** Grants all three debug items - called from WendigoCommands.toggleDebug right when a player's
	 * own debug session turns on, alongside the existing Night Vision grant. No dedupe against
	 * already-held copies - matches this codebase's existing "simple, direct" debug-grant style (see
	 * the Night Vision effect grant right next to this call site, which just reapplies unconditionally
	 * too); toggling debug on repeatedly just hands out extra copies to drop or discard. */
	public static void giveDebugItems(ServerPlayer player) {
		player.getInventory().add(bookmarkItem());
		player.getInventory().add(stage5SpawnerItem());
		player.getInventory().add(spiderSummonerItem());
	}

	private static ItemStack bookmarkItem() {
		return markedStack(Items.BELL, BOOKMARK_MARKER, "Wendigo Bug Bookmark");
	}

	private static ItemStack stage5SpawnerItem() {
		return markedStack(Items.NETHER_STAR, STAGE5_MARKER, "Wendigo Stage 5 Spawner");
	}

	private static ItemStack spiderSummonerItem() {
		return markedStack(Items.SPIDER_EYE, SPIDER_MARKER, "Climbing Spider Summoner");
	}

	private static ItemStack markedStack(Item item, String marker, String name) {
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
		CompoundTag tag = new CompoundTag();
		tag.putString(MARKER_KEY, marker);
		CustomData.set(DataComponents.CUSTOM_DATA, stack, tag);
		return stack;
	}

	private static String markerOf(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) {
			return null;
		}
		return data.copyTag().getString(MARKER_KEY).orElse(null);
	}

	/** Writes a clearly-greppable timestamped line to the server log the instant something visibly
	 * goes wrong - the user's own explicit request, to correlate a live "it just bugged out" moment
	 * against the WDIAG/SDIAG per-tick dumps afterward without having to guess a tick range by eye. */
	private static void useBookmark(ServerPlayer player, ServerLevel level) {
		String timestamp = String.format("%.2f", level.getServer().getTickCount() / 20.0);
		WendigoMod.LOGGER.info("BOOKMARK t={}s player={} pos={}", timestamp, player.getGameProfile().name(), player.position());
		player.sendSystemMessage(Component.literal("[wendigo] Bookmarked t=" + timestamp + "s in the server log."));
	}

	/** Raycasts along the player's own look direction and force-spawns a guaranteed stage-5 wendigo
	 * right there, immediately force-chasing the player - same standalone spawn shape WendigoCommands'
	 * own /wendigo orbit command already uses for engaging an existing entity (deliberately not wired
	 * through WendigoManager's WaveState - see that command's own doc comment for why that's fine).
	 * startWave with a hand-authored combat.chase plan, not startOrbit - the user's own explicit
	 * request: plain orbit only wanders, it never actually engages combat.chase the way a real
	 * darkness encounter (or the spider-summoner item's own forced chase) does, so this item wasn't
	 * exercising the actual chase code path this whole investigation has been chasing bugs in.
	 * setRunsForTesting also resets the player's own progression, per the user's own explicit request
	 * ("reset whatever stage is coming"), so the NEXT real automatic spawn is guaranteed stage 5 too. */
	private static void useStage5Spawner(ServerPlayer player, ServerLevel level) {
		BlockHitResult hit = raycastSurface(player);
		if (hit == null) {
			player.sendSystemMessage(Component.literal("[wendigo] Point at a solid surface first."));
			return;
		}
		Direction normal = hit.getDirection();
		BlockPos spawnBlock = hit.getBlockPos().relative(normal);
		if (WendigoMod.progressionTracker != null) {
			WendigoMod.progressionTracker.setRunsForTesting(player, 5);
		}
		WendigoEntity wendigo = new WendigoEntity(ModEntities.WENDIGO, level);
		wendigo.snapTo(spawnBlock.getX() + 0.5, spawnBlock.getY(), spawnBlock.getZ() + 0.5, 0f, 0f);
		wendigo.syncPoseToSpawnPosition();
		wendigo.nudgeTowardAttachedSurface(normal);
		int severityPercent = WendigoProgressionTracker.representativePercent(5);
		wendigo.setSeverityPercent(severityPercent);
		level.addFreshEntity(wendigo);
		JsonObject chasePlan = JsonParser.parseString(FORCE_CHASE_PLAN).getAsJsonObject();
		wendigo.startWave(chasePlan, severityPercent, true);
		player.sendSystemMessage(Component.literal("[wendigo] Spawned a stage 5 wendigo at "
			+ spawnBlock.toShortString() + ", chasing you now."));
	}

	/** Raycasts and summons a real vanilla spider (Stormy's Spiders' own mixins give it wall/ceiling
	 * climbing automatically - see SpiderDiagnostics' own doc comment for why there's no separate
	 * entity type to spawn instead) at the pointed-at surface, then hands it to SpiderDiagnostics for
	 * ongoing per-tick logging alongside the wendigo's own WDIAG lines, plus a forced chase toward the
	 * summoning player (SpiderDiagnostics' own doc comment covers why this works even in creative -
	 * short version: it's a plain navigation.moveTo call, not vanilla combat target selection, so
	 * creative-mode's usual immunity from being targeted doesn't apply here at all). */
	private static void useSpiderSummoner(ServerPlayer player, ServerLevel level) {
		BlockHitResult hit = raycastSurface(player);
		if (hit == null) {
			player.sendSystemMessage(Component.literal("[wendigo] Point at a solid surface first."));
			return;
		}
		BlockPos spawnBlock = hit.getBlockPos().relative(hit.getDirection());
		Spider spider = EntityType.SPIDER.create(level, EntitySpawnReason.SPAWN_ITEM_USE);
		if (spider == null) {
			return;
		}
		spider.snapTo(spawnBlock.getX() + 0.5, spawnBlock.getY(), spawnBlock.getZ() + 0.5, 0f, 0f);
		level.addFreshEntity(spider);
		SpiderDiagnostics.track(spider, level, player);
		player.sendSystemMessage(Component.literal("[wendigo] Summoned a spider at "
			+ spawnBlock.toShortString() + " - it'll chase you and log SDIAG lines to the server log."));
	}

	/** The solid block face the player is currently looking at, if any - getBlockPos()/getDirection()
	 * together give both the adjacent air block to spawn in (matching the same "spawnPos + 0.5 on X/Z,
	 * raw Y" convention WendigoManager.tryEnterOrbit's own real spawn placement already uses) and the
	 * face's own outward normal (e.g. DOWN for a block hit from directly beneath - a ceiling - matching
	 * nudgeTowardAttachedSurface(Direction)'s "normal points away from the surface" convention). Null
	 * if nothing solid is in range. */
	private static BlockHitResult raycastSurface(ServerPlayer player) {
		HitResult hit = player.pick(RAYCAST_MAX_DISTANCE, 1.0f, false);
		if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
			return null;
		}
		return blockHit;
	}
}
