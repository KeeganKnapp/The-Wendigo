package com.wendigo.wave;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.wendigo.debug.WendigoDebug;
import com.wendigo.sound.WendigoSounds;

/**
 * Tracks cumulative time each player has spent below y=0, world-wide, never resetting when they
 * resurface - this is the wendigo's targeting signal (whoever's been in the depths longest,
 * total) and, longer-term, a severity value meant to gate escalating behavior once a "defeat the
 * dweller" progression exists. Deliberately not a reward for spelunking: it only ever goes up.
 * In-memory only - resets on server restart.
 *
 * Also owns two related per-player mechanics that piggyback on the same once-a-second tick: a
 * warmup buffer before a player becomes targetable at all (see warmupUntilTick), and clearing
 * ordinary hostile mobs out of the area around an eligible player (see despawnNearbyHostileMobs) -
 * both keyed off the same "below y=0, past warmup" eligibility this class already computes.
 */
public final class PlayerSeverityTracker {
	// Sampled once per second (not every tick) so the cap represents a sane amount of wall-clock
	// time rather than raw ticks.
	private static final int SAMPLE_INTERVAL_TICKS = 20;
	// Hostile mobs are cleared out of this ring around an eligible player - not the inner radius
	// (an active fight or nearby mob shouldn't just vanish out from under a player), only the wider
	// area, so the wendigo reads as the sole presence without visibly despawning something right in
	// front of someone.
	private static final double MOB_CLEAR_INNER_RADIUS = 16.0;
	private static final double MOB_CLEAR_OUTER_RADIUS = 64.0;
	// Wider than MOB_CLEAR_OUTER_RADIUS on purpose - clearing already-spawned mobs still lets one
	// spawn, get noticed for a moment, then vanish as the player wanders toward it (or the periodic
	// sweep catches up); blocking natural spawns further out in the first place means nothing ever
	// pops into view distance at all, no visible "mobs in the distance just disappear" moment to
	// begin with.
	private static final double MOB_SPAWN_PREVENT_RADIUS = 96.0;
	// Don't discard a mob the player could actually watch vanish - only clear ones outside this
	// forward view cone (and only when there's also clear line of sight, same idiom as
	// PlanPredicates.isLookingAtSelf - a mob hidden behind a wall inside the cone is still safe to
	// discard, since the player can't actually see it happen).
	private static final double MOB_DESPAWN_VIEW_CONE_DEGREES = 70.0;
	// A warning sound plays every time cumulative severity crosses one of these - see addSeverity.
	private static final int WARNING_SOUND_INTERVAL = 1000;
	// Mining an ore below y=0 adds straight to severity - coal at the bottom, scaling by 10 per tier
	// up through diamond. Both the stone and deepslate variant of each ore share the same value
	// (deepslate ore is what's actually common at these depths). First-pass tier ordering, easy to
	// re-order/re-value later.
	private static final Map<Block, Integer> ORE_SEVERITY_VALUES = Map.ofEntries(
		Map.entry(Blocks.COAL_ORE, 10), Map.entry(Blocks.DEEPSLATE_COAL_ORE, 10),
		Map.entry(Blocks.COPPER_ORE, 20), Map.entry(Blocks.DEEPSLATE_COPPER_ORE, 20),
		Map.entry(Blocks.IRON_ORE, 30), Map.entry(Blocks.DEEPSLATE_IRON_ORE, 30),
		Map.entry(Blocks.LAPIS_ORE, 40), Map.entry(Blocks.DEEPSLATE_LAPIS_ORE, 40),
		Map.entry(Blocks.REDSTONE_ORE, 50), Map.entry(Blocks.DEEPSLATE_REDSTONE_ORE, 50),
		Map.entry(Blocks.GOLD_ORE, 60), Map.entry(Blocks.DEEPSLATE_GOLD_ORE, 60),
		Map.entry(Blocks.EMERALD_ORE, 70), Map.entry(Blocks.DEEPSLATE_EMERALD_ORE, 70),
		Map.entry(Blocks.DIAMOND_ORE, 80), Map.entry(Blocks.DEEPSLATE_DIAMOND_ORE, 80)
	);

	private final WendigoWaveConfig config;
	private final Map<UUID, Integer> secondsUnderY0 = new HashMap<>();
	private final Map<UUID, Boolean> wasUnderY0 = new HashMap<>();
	// Server tick count at which each player stops warming up - (re)started on the y=0 rising edge
	// and on rejoin (see register()). Absent/expired means eligible.
	private final Map<UUID, Integer> warmupUntilTick = new HashMap<>();
	private int ticksSinceSample;

	public PlayerSeverityTracker(WendigoWaveConfig config) {
		this.config = config;
	}

	public void register() {
		ServerTickEvents.END_SERVER_TICK.register(this::onEndServerTick);
		// Rejoining always restarts the warmup, even if they're already below y=0 the instant they
		// reconnect (e.g. they logged out mid-descent) - the whole point is a buffer right after
		// (re)appearing, not just after freshly crossing y=0.
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> startWarmup(handler.player, server.getTickCount()));
		ServerEntityEvents.ALLOW_LOAD.register(this::allowLoad);
		PlayerBlockBreakEvents.AFTER.register(this::onBlockBroken);
	}

	/** Mining an ore below y=0 adds straight to severity - see ORE_SEVERITY_VALUES. */
	private void onBlockBroken(Level level, Player player, BlockPos pos, BlockState state, BlockEntity blockEntity) {
		if (pos.getY() >= 0 || !(player instanceof ServerPlayer serverPlayer)) {
			return;
		}
		Integer value = ORE_SEVERITY_VALUES.get(state.getBlock());
		if (value != null) {
			addSeverity(serverPlayer, value);
		}
	}

	/** Single place severity actually increases from real (non-debug) progression - passive y&lt;0
	 * dwell time and ore mining both funnel through this, so the every-WARNING_SOUND_INTERVAL sound
	 * cue can't miss a crossing regardless of which source pushed it over. Deliberately not used by
	 * setSeverity (/wendigo aggression set) - a debug jump shouldn't fire a burst of warning sounds
	 * for every threshold it skips past. */
	private void addSeverity(ServerPlayer player, int amount) {
		int before = severityOf(player);
		int after = Math.min(this.config.severityCap, before + amount);
		this.secondsUnderY0.put(player.getUUID(), after);
		if (after / WARNING_SOUND_INTERVAL > before / WARNING_SOUND_INTERVAL) {
			WendigoSounds.play(player.level(), player.blockPosition(), WendigoSounds.Type.AMBIENT);
		}
	}

	/** Blocks natural hostile-mob (and bat) spawns within MOB_SPAWN_PREVENT_RADIUS of any eligible
	 * player - see MOB_SPAWN_PREVENT_RADIUS. Only EntitySpawnReason.NATURAL is gated - spawners,
	 * summons, structures, breeding etc. are all deliberate and left alone; reloading an
	 * already-existing entity from disk (reason LOAD) is untouched too, so this can't eat a saved
	 * world's mobs on chunk load. The wendigo's own spawn (WendigoManager.spawnWave's
	 * level.addFreshEntity) is untouched regardless - it's never tagged NATURAL, so it never reaches
	 * the predicate below at all. */
	private boolean allowLoad(Entity entity, ServerLevel level, EntitySpawnReason reason, boolean bl) {
		if (reason != EntitySpawnReason.NATURAL || !isSpawnPreventableMob(entity)) {
			return true;
		}
		double preventSq = MOB_SPAWN_PREVENT_RADIUS * MOB_SPAWN_PREVENT_RADIUS;
		int now = level.getServer().getTickCount();
		for (ServerPlayer player : level.players()) {
			if (isEligibleForMobClearing(player, now) && entity.distanceToSqr(player) <= preventSq) {
				return false;
			}
		}
		return true;
	}

	/** Below y=0, past their warmup buffer (or debugging, which bypasses it) - the same condition
	 * selectTarget/onEndServerTick's mob-clearing already use. */
	private boolean isEligible(ServerPlayer player, int now) {
		if (player.isSpectator() || player.getY() >= 0) {
			return false;
		}
		return now >= this.warmupUntilTick.getOrDefault(player.getUUID(), 0) || WendigoDebug.isEnabled(player);
	}

	// Below this, the wendigo hasn't established enough of a presence to be "clearing the area" of
	// other threats yet - mobs actually spawn/exist normally near the player at stage 1, matching its
	// "barely a presence" framing. Only gates the mob-clearing/prevention mechanics below, not the
	// wendigo's own targeting eligibility (isEligible) - a stage-1 wave still needs to be able to fire.
	private static final int MOB_CLEARING_MIN_PERCENT = 20;

	private boolean isEligibleForMobClearing(ServerPlayer player, int now) {
		if (!isEligible(player, now)) {
			return false;
		}
		int cap = severityCap();
		int percent = cap > 0 ? 100 * severityOf(player) / cap : 0;
		return percent >= MOB_CLEARING_MIN_PERCENT;
	}

	private void onEndServerTick(MinecraftServer server) {
		this.ticksSinceSample++;
		if (this.ticksSinceSample < SAMPLE_INTERVAL_TICKS) {
			return;
		}
		this.ticksSinceSample = 0;
		int now = server.getTickCount();

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			// Same reasoning as WendigoManager.onEndServerTick - this hook fires every real server
			// tick regardless of /tick freeze, but severity accrual/warmup/mob-clearing are world state
			// that should only advance when the player's level is actually ticking.
			if (player.isSpectator() || !player.level().tickRateManager().runsNormally()) {
				continue;
			}
			boolean underY0 = player.getY() < 0;
			if (underY0) {
				addSeverity(player, 1);
				if (!this.wasUnderY0.getOrDefault(player.getUUID(), false)) {
					startWarmup(player, now); // rising edge - freshly descended below y=0
				}
				if (isEligibleForMobClearing(player, now)) {
					despawnNearbyHostileMobs(player);
				}
			}
			this.wasUnderY0.put(player.getUUID(), underY0);
		}
	}

	private void startWarmup(ServerPlayer player, int now) {
		int percent = severityCap() > 0 ? 100 * severityOf(player) / severityCap() : 0;
		this.warmupUntilTick.put(player.getUUID(), now + this.config.dynamicWarmupTicks(percent));
	}

	/** Clears ordinary hostile mobs and bats (never the wendigo itself) out of the ring between
	 * MOB_CLEAR_INNER_RADIUS and MOB_CLEAR_OUTER_RADIUS around the player - so the wendigo reads as
	 * the sole presence without visibly despawning something right next to them. Skips anything
	 * currently in the player's own view cone (see MOB_DESPAWN_VIEW_CONE_DEGREES) - it'll get caught
	 * by a later sweep once it's no longer being watched. */
	private void despawnNearbyHostileMobs(ServerPlayer player) {
		ServerLevel level = player.level();
		double innerSq = MOB_CLEAR_INNER_RADIUS * MOB_CLEAR_INNER_RADIUS;
		double outerSq = MOB_CLEAR_OUTER_RADIUS * MOB_CLEAR_OUTER_RADIUS;
		double cosViewCone = Math.cos(Math.toRadians(MOB_DESPAWN_VIEW_CONE_DEGREES));
		AABB area = player.getBoundingBox().inflate(MOB_CLEAR_OUTER_RADIUS);
		for (Mob mob : level.getEntitiesOfClass(Mob.class, area)) {
			if (!isClearableMob(mob)) {
				continue;
			}
			double distSq = mob.distanceToSqr(player);
			if (distSq < innerSq || distSq > outerSq) {
				continue;
			}
			if (isInViewCone(player, mob, cosViewCone)) {
				continue;
			}
			mob.discard();
		}
	}

	/** Monster (hostile), Bat, or Slime, EXCEPT real EnderMen - used by the despawn sweep only (see
	 * despawnNearbyHostileMobs). Checked before the generic Monster case, since EnderMan extends
	 * Monster - this also naturally covers WendigoEntity, an EnderMan subclass, itself never a target
	 * of its own sweep anyway. Real Endermen are exempted here because they're already a quiet,
	 * dark-dwelling presence that fits the same atmosphere as the wendigo instead of competing with
	 * it, and clearing already-spawned ones was an unintended side effect: their ambient stare/idle
	 * sounds were vanishing along with them, misread as a sound-system bug when the actual cause was
	 * every real Enderman near the player being discarded. Deliberately NOT reused by
	 * isSpawnPreventableMob - see that method's own comment for why the two need to differ. */
	private static boolean isClearableMob(Entity entity) {
		if (entity instanceof EnderMan) {
			return false;
		}
		return entity instanceof Monster || entity instanceof Bat || entity instanceof Slime;
	}

	/** Monster (hostile), Bat, or Slime - used by natural-spawn prevention (allowLoad) only, and
	 * deliberately does NOT exempt EnderMan the way isClearableMob does. Real bug: allowLoad and the
	 * despawn sweep used to share isClearableMob outright, so excluding EnderMan there (to stop
	 * clearing already-spawned real Endermen) also silently exempted brand-new Endermen from
	 * natural-spawn prevention as an unavoidable side effect of sharing one predicate - real Endermen
	 * kept spawning freely within MOB_SPAWN_PREVENT_RADIUS above MOB_CLEARING_MIN_PERCENT even though
	 * every other hostile type there was correctly blocked. Splitting the two predicates lets each
	 * mechanism decide independently: don't clear an Enderman that's already there, but still don't
	 * let a fresh one spawn in either. */
	private static boolean isSpawnPreventableMob(Entity entity) {
		return entity instanceof Monster || entity instanceof Bat || entity instanceof Slime;
	}

	/** Same alignment/line-of-sight idiom as PlanPredicates.isLookingAtSelf - true only if the mob is
	 * both within the angular cone AND actually visible (not behind a wall), since a mob hidden
	 * behind a wall inside the cone is still safe to discard. */
	private static boolean isInViewCone(ServerPlayer player, Mob mob, double cosThreshold) {
		Vec3 toMob = mob.getEyePosition().subtract(player.getEyePosition()).normalize();
		double alignment = player.getLookAngle().normalize().dot(toMob);
		return alignment >= cosThreshold && player.hasLineOfSight(mob);
	}

	public int severityOf(ServerPlayer player) {
		return this.secondsUnderY0.getOrDefault(player.getUUID(), 0);
	}

	public int severityCap() {
		return this.config.severityCap;
	}

	/** Directly sets a player's severity (clamped to [0, cap]) - /wendigo aggression set, for jumping
	 * straight to a tier during testing instead of grinding real time below y=0. */
	public void setSeverity(ServerPlayer player, int value) {
		this.secondsUnderY0.put(player.getUUID(), Math.clamp(value, 0, this.config.severityCap));
	}

	public record TargetSelection(ServerPlayer target, int severity) {
	}

	/**
	 * Multiplayer-aware automatic wave target: eligible players (below y=0, past warmup) are grouped
	 * by proximity - two players are in the same group if they're within MOB_CLEAR_OUTER_RADIUS of
	 * each other, transitively (a chain of nearby players all end up in one group even if the two
	 * ends are out of range of each other directly, same "reads as one crowd" idea the mob-clearing
	 * radius already uses). The group with the most players wins; ties broken by whichever group's
	 * average severity is worse (higher). The actual target is picked at random from within that
	 * group - not necessarily its most severe member - but the severity reported back is the group's
	 * worst (highest) member regardless of who got picked, so the wendigo shows up at the intensity
	 * that group as a whole has earned. Null if nobody's eligible right now.
	 */
	public TargetSelection selectTarget(ServerLevel level) {
		int now = level.getServer().getTickCount();
		List<ServerPlayer> eligible = new ArrayList<>();
		for (ServerPlayer player : level.players()) {
			if (isEligible(player, now)) {
				eligible.add(player);
			}
		}
		if (eligible.isEmpty()) {
			return null;
		}
		List<ServerPlayer> group = selectGroup(groupByProximity(eligible));
		ServerPlayer target = group.get(ThreadLocalRandom.current().nextInt(group.size()));
		int worstSeverity = group.stream().mapToInt(this::severityOf).max().orElse(0);
		return new TargetSelection(target, worstSeverity);
	}

	/** Connected components under "within MOB_CLEAR_OUTER_RADIUS of each other" - simple union-find,
	 * player counts here are always small enough that the O(n^2) pairwise distance check doesn't matter. */
	private static List<List<ServerPlayer>> groupByProximity(List<ServerPlayer> players) {
		double radiusSq = MOB_CLEAR_OUTER_RADIUS * MOB_CLEAR_OUTER_RADIUS;
		int n = players.size();
		int[] parent = new int[n];
		for (int i = 0; i < n; i++) {
			parent[i] = i;
		}
		for (int i = 0; i < n; i++) {
			for (int j = i + 1; j < n; j++) {
				if (players.get(i).distanceToSqr(players.get(j)) <= radiusSq) {
					union(parent, i, j);
				}
			}
		}
		Map<Integer, List<ServerPlayer>> byRoot = new HashMap<>();
		for (int i = 0; i < n; i++) {
			byRoot.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(players.get(i));
		}
		return new ArrayList<>(byRoot.values());
	}

	private static int find(int[] parent, int i) {
		while (parent[i] != i) {
			parent[i] = parent[parent[i]];
			i = parent[i];
		}
		return i;
	}

	private static void union(int[] parent, int a, int b) {
		int rootA = find(parent, a);
		int rootB = find(parent, b);
		if (rootA != rootB) {
			parent[rootA] = rootB;
		}
	}

	/** Most players wins; ties broken by whichever group's average severity is worse (higher). */
	private List<ServerPlayer> selectGroup(List<List<ServerPlayer>> groups) {
		List<ServerPlayer> best = null;
		double bestAverage = -1.0;
		for (List<ServerPlayer> group : groups) {
			double average = group.stream().mapToInt(this::severityOf).average().orElse(0.0);
			if (best == null || group.size() > best.size() || (group.size() == best.size() && average > bestAverage)) {
				best = group;
				bestAverage = average;
			}
		}
		return best;
	}
}
