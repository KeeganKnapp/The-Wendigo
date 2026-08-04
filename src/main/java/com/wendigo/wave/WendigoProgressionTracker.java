package com.wendigo.wave;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.ToIntFunction;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.wendigo.debug.WendigoDebug;

/**
 * Replaces PlayerSeverityTracker's whole "cumulative dwell time is a continuously-growing score"
 * model with a spawn-count-driven one: escalation is now how many times the wendigo has ever fully
 * completed an encounter with this player (completedRuns), not how long they've spent below y=0
 * overall. A player becomes eligible for a FRESH run once they've spent ELIGIBILITY_TICKS_REQUIRED
 * ticks below y=0 with no run currently in progress (see eligibilityTicks) - but an already-ACTIVE
 * run (goal not yet met) always takes priority for resumption the instant they're back below y=0 and
 * no wendigo is active anywhere, bypassing that timer entirely, since existing despawn/relocate/
 * teleport-away mechanics are purely cosmetic now and never actually end a run on their own (only
 * WendigoManager calling completeRun once the run's hidden goal is met does).
 *
 * Also owns the same mob-clearing/spawn-prevention responsibilities PlayerSeverityTracker did,
 * piggybacking on the same once-a-second tick, now keyed off stage instead of severity percent. The
 * ore-mining severity bonus and the severity-milestone warning chime are both dropped outright - the
 * former has no continuous score left to feed under this model, the latter per the user's own explicit
 * request (the darkness-overstay warning noise in DarknessOverstayTracker is a separate, unaffected
 * mechanism that still plays). In-memory only - resets on server restart, same as before.
 */
public final class WendigoProgressionTracker {
	private static final int SAMPLE_INTERVAL_TICKS = 20;
	private static final double MOB_CLEAR_INNER_RADIUS = 16.0;
	private static final double MOB_CLEAR_OUTER_RADIUS = 64.0;
	private static final double MOB_SPAWN_PREVENT_RADIUS = 96.0;
	private static final double MOB_DESPAWN_VIEW_CONE_DEGREES = 70.0;

	// Fixed, no longer severity-scaled (nothing left to scale against) - a short buffer after
	// crossing y=0 or rejoining, purely for the mob-clearing/spawn-prevention eligibility check.
	// Deliberately decoupled from ELIGIBILITY_TICKS_REQUIRED below - that timer governs wendigo-spawn
	// eligibility specifically and is already a far longer buffer for a first-timer; this one only
	// controls how quickly the area starts reading as "the wendigo's own territory" once someone's
	// genuinely settled in down here.
	private static final int MOB_CLEARING_WARMUP_TICKS = 200; // 10s
	private static final int MOB_CLEARING_MIN_STAGE = 2; // mirrors the old 20%-severity threshold

	// How many cumulative ticks below y=0 (while no run is active) a player needs before becoming
	// eligible for a fresh wendigo spawn - see the class doc comment. Reset to 0 the instant a run
	// starts or resumes, frozen (not incrementing) for the run's entire duration, only resuming once
	// the run truly completes.
	private static final int ELIGIBILITY_TICKS_REQUIRED = 2000;

	// Index by stage (1-5); index 0 unused, kept only so stage numbers can index directly without an
	// off-by-one everywhere. Stage 5's goal is intentionally unreachable - see isGoalMet.
	private static final int[] STAGE_GOALS = {0, 10, 10, 6, 4, Integer.MAX_VALUE};
	private static final int[] STAGE_PERCENTS = {0, 10, 30, 50, 70, 90};

	private static final class ActiveRun {
		final int stage;
		int progress;

		ActiveRun(int stage) {
			this.stage = stage;
		}
	}

	private final Map<UUID, Integer> completedRuns = new HashMap<>();
	private final Map<UUID, Integer> eligibilityTicks = new HashMap<>();
	private final Map<UUID, ActiveRun> activeRuns = new HashMap<>();
	private final Map<UUID, Boolean> wasUnderY0 = new HashMap<>();
	private final Map<UUID, Integer> warmupUntilTick = new HashMap<>();
	private int ticksSinceSample;

	// Null until the real server actually starts (see onServerStarted) - never set for the throwaway
	// trackers GameTests construct directly (they never call register()), so completedRuns stays a
	// purely in-memory map there, exactly like before this class gained persistence at all. Non-null
	// only for the one real WendigoMod.progressionTracker instance.
	private WendigoProgressionData data;

	public void register() {
		ServerTickEvents.END_SERVER_TICK.register(this::onEndServerTick);
		// Rejoining always restarts the mob-clearing warmup, even if already below y=0 the instant
		// they reconnect (e.g. logged out mid-descent) - the whole point is a buffer right after
		// (re)appearing, not just after freshly crossing y=0.
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> startWarmup(handler.player, server.getTickCount()));
		ServerEntityEvents.ALLOW_LOAD.register(this::allowLoad);
		// The user's own explicit request: completedRuns (spawn-count progression) should survive a
		// real server restart. Loaded from the overworld's own SavedData the moment the server is up
		// (not in this tracker's constructor, which runs during mod init before any level exists).
		ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
	}

	private void onServerStarted(MinecraftServer server) {
		this.data = server.overworld().getDataStorage().computeIfAbsent(WendigoProgressionData.TYPE);
		this.completedRuns.putAll(this.data.completedRuns);
	}

	/** Mirrors a completedRuns write into the persisted copy too (if attached - see the data field's
	 * own comment) and marks it dirty so the game's normal save cycle actually writes it to disk.
	 * completedRuns itself stays the single source of truth for reads (stageOf/completedRunsOf etc.) -
	 * this is purely a write-through, never a read path. */
	private void persistCompletedRuns(UUID id, int value) {
		if (this.data != null) {
			this.data.completedRuns.put(id, value);
			this.data.setDirty();
		}
	}

	/** Below y=0, not spectating - the base condition both resume and fresh eligibility require.
	 * Deliberately NOT gated by the mob-clearing warmup (see MOB_CLEARING_WARMUP_TICKS's own comment)
	 * - that buffer only controls how quickly mobs stop spawning nearby, not wendigo-spawn eligibility
	 * itself, which the (much longer) ELIGIBILITY_TICKS_REQUIRED timer already governs on its own. */
	private static boolean isUnderY0(ServerPlayer player) {
		return !player.isSpectator() && player.getY() < 0;
	}

	private boolean isPastWarmup(ServerPlayer player, int now) {
		return now >= this.warmupUntilTick.getOrDefault(player.getUUID(), 0) || WendigoDebug.isEnabled(player);
	}

	private boolean isEligibleForMobClearing(ServerPlayer player, int now) {
		return isUnderY0(player) && isPastWarmup(player, now) && stageOf(player) >= MOB_CLEARING_MIN_STAGE;
	}

	/** Blocks natural hostile-mob (and bat) spawns within MOB_SPAWN_PREVENT_RADIUS of any player
	 * eligible for mob-clearing - see MOB_SPAWN_PREVENT_RADIUS. Only EntitySpawnReason.NATURAL is
	 * gated; the wendigo's own spawn is never tagged NATURAL, so it never reaches the predicate below. */
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

	private void onEndServerTick(MinecraftServer server) {
		this.ticksSinceSample++;
		if (this.ticksSinceSample < SAMPLE_INTERVAL_TICKS) {
			return;
		}
		this.ticksSinceSample = 0;
		int now = server.getTickCount();

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			// Same reasoning as every other tick hook in this mod - fires every real server tick
			// regardless of /tick freeze, but eligibility accrual/warmup/mob-clearing are world state
			// that should only advance while the player's level is actually ticking.
			if (player.isSpectator() || !player.level().tickRateManager().runsNormally()) {
				continue;
			}
			boolean underY0 = player.getY() < 0;
			if (underY0) {
				UUID id = player.getUUID();
				if (!this.activeRuns.containsKey(id)) {
					int ticks = this.eligibilityTicks.merge(id, SAMPLE_INTERVAL_TICKS, Integer::sum);
					// Hold at the threshold rather than letting it run away - eligibility is a boolean
					// derived from crossing ELIGIBILITY_TICKS_REQUIRED, no benefit to counting further.
					if (ticks > ELIGIBILITY_TICKS_REQUIRED) {
						this.eligibilityTicks.put(id, ELIGIBILITY_TICKS_REQUIRED);
					}
				}
				if (!this.wasUnderY0.getOrDefault(id, false)) {
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
		this.warmupUntilTick.put(player.getUUID(), now + MOB_CLEARING_WARMUP_TICKS);
	}

	/** Clears ordinary hostile mobs and bats (never the wendigo itself) out of the ring between
	 * MOB_CLEAR_INNER_RADIUS and MOB_CLEAR_OUTER_RADIUS around the player - so the wendigo reads as
	 * the sole presence without visibly despawning something right next to them. Skips anything
	 * currently in the player's own view cone - it'll get caught by a later sweep once it's no longer
	 * being watched. */
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

	/** Monster (hostile), Bat, or Slime, EXCEPT real EnderMen - real Endermen are exempted here since
	 * they're already a quiet, dark-dwelling presence that fits the same atmosphere as the wendigo
	 * instead of competing with it (clearing already-spawned ones was a real bug found live: their
	 * ambient stare/idle sounds vanished along with them, misread as a sound-system bug at first).
	 * Deliberately NOT reused by isSpawnPreventableMob - see that method's own comment for why. */
	private static boolean isClearableMob(Entity entity) {
		if (entity instanceof EnderMan) {
			return false;
		}
		return entity instanceof Monster || entity instanceof Bat || entity instanceof Slime;
	}

	/** Monster (hostile), Bat, or Slime - used by natural-spawn prevention only, deliberately does
	 * NOT exempt EnderMan the way isClearableMob does (don't clear an Enderman that's already there,
	 * but still don't let a fresh one spawn either). */
	private static boolean isSpawnPreventableMob(Entity entity) {
		return entity instanceof Monster || entity instanceof Bat || entity instanceof Slime;
	}

	private static boolean isInViewCone(ServerPlayer player, Mob mob, double cosThreshold) {
		Vec3 toMob = mob.getEyePosition().subtract(player.getEyePosition()).normalize();
		double alignment = player.getLookAngle().normalize().dot(toMob);
		return alignment >= cosThreshold && player.hasLineOfSight(mob);
	}

	public int completedRunsOf(ServerPlayer player) {
		return this.completedRuns.getOrDefault(player.getUUID(), 0);
	}

	/** Which stage governs this player right now - their active run's own (fixed-at-start) stage if
	 * one's in progress, otherwise whatever their completed-run count would start next. */
	public int stageOf(ServerPlayer player) {
		ActiveRun run = this.activeRuns.get(player.getUUID());
		return run != null ? run.stage : stageFor(completedRunsOf(player));
	}

	/** Stage-to-spawn-count mapping, confirmed with the user: spawns 1-2 -> stage 1, 3 -> stage 2,
	 * 4 -> stage 3, 5 -> stage 4, 6+ -> stage 5 (permanent). completedRuns is 0-indexed (how many are
	 * already fully done), so this answers "what stage does the NEXT/current run belong to". */
	public static int stageFor(int completedRuns) {
		if (completedRuns <= 1) {
			return 1;
		}
		if (completedRuns == 2) {
			return 2;
		}
		if (completedRuns == 3) {
			return 3;
		}
		if (completedRuns == 4) {
			return 4;
		}
		return 5;
	}

	/** Fixed representative percent per stage (10/30/50/70/90) - feeds directly into every existing
	 * severityPercent-consuming call site (buildSystemPrompt's STAGE_* selection, TierGates,
	 * SchemaBuilder) completely unchanged, since none of them care how the number was derived. */
	public static int representativePercent(int stage) {
		return STAGE_PERCENTS[stage];
	}

	public int percentOf(ServerPlayer player) {
		return representativePercent(stageOf(player));
	}

	/** Called the instant a wendigo actually spawns/resumes on this player - resets the eligibility
	 * timer to 0 (per the user's own explicit "as soon as a wendigo spawns on the player their 2000
	 * ticks get reset to 0" rule) and creates a fresh ActiveRun if one doesn't already exist (a resume
	 * leaves the existing one, with its existing progress, completely untouched). Returns true only
	 * for that fresh-ActiveRun case - the caller's own signal for "this is the first spawn of a run"
	 * (see WendigoManager.tryEnterOrbit's own SPAWN-cue gate), since every other spawn here is really
	 * just a cosmetic mid-run relocate of an already-active run. */
	public boolean startRun(ServerPlayer player) {
		UUID id = player.getUUID();
		this.eligibilityTicks.put(id, 0);
		boolean isFreshRun = !this.activeRuns.containsKey(id);
		this.activeRuns.computeIfAbsent(id, k -> new ActiveRun(stageFor(completedRunsOf(player))));
		return isFreshRun;
	}

	public void addProgress(ServerPlayer player, int amount) {
		ActiveRun run = this.activeRuns.get(player.getUUID());
		if (run != null) {
			run.progress += amount;
		}
	}

	/** Stage 5 never reads true here (STAGE_GOALS[5] is Integer.MAX_VALUE) - it has its own separate
	 * stop condition entirely (see endStage5Hunt): the player has to actually kill it, not accumulate
	 * a counted goal, so a "progress" number has nothing to compare against here at all. */
	public boolean isGoalMet(ServerPlayer player) {
		ActiveRun run = this.activeRuns.get(player.getUUID());
		return run != null && run.progress >= STAGE_GOALS[run.stage];
	}

	/** Marks the active run as genuinely, permanently over - completedRuns advances (deciding the
	 * NEXT run's stage), the run itself clears, and the eligibility timer starts fresh from right now
	 * (per "only starts counting up after their run with the wendigo is over"). Stages 1-4 only - see
	 * endStage5Hunt for stage 5's own version of this (kill-triggered, no completedRuns advance). */
	public void completeRun(ServerPlayer player) {
		UUID id = player.getUUID();
		this.completedRuns.merge(id, 1, Integer::sum);
		this.activeRuns.remove(id);
		this.eligibilityTicks.put(id, 0);
		persistCompletedRuns(id, this.completedRuns.get(id));
	}

	// Stage 5's persisted health - restored on every spawn, saved on every cosmetic pause (see
	// stage5HealthOf/saveStage5Health) - the user's own explicit fix for the wendigo healing back to
	// full every time a fresh entity replaced the old one on a teleport-away. Absent means never
	// damaged yet, or just killed (see endStage5Hunt) - either way, full health next spawn.
	private final Map<UUID, Float> stage5Health = new HashMap<>();

	public float stage5HealthOf(ServerPlayer player, float maxHealth) {
		return this.stage5Health.getOrDefault(player.getUUID(), maxHealth);
	}

	public void saveStage5Health(ServerPlayer player, float health) {
		this.stage5Health.put(player.getUUID(), health);
	}

	/** Stage 5's own stop condition (see isGoalMet's own comment) - the player has to actually kill
	 * it. Restarts the eligibility timer exactly like completeRun does for every other stage
	 * (deliberately does NOT touch completedRuns - there's no stage 6 to advance to, a fresh stage-5
	 * hunt just starts again once eligible), and resets the persisted health back to full so the next
	 * encounter starts fresh rather than resuming at 0/negative. */
	public void endStage5Hunt(ServerPlayer player) {
		UUID id = player.getUUID();
		this.activeRuns.remove(id);
		this.eligibilityTicks.put(id, 0);
		this.stage5Health.remove(id);
	}

	/** Directly sets a player's completed-run count (clamped to >=0) and clears any in-progress run -
	 * /wendigo runs set, for jumping straight to a stage during testing instead of grinding real
	 * encounters. Debug jump, so no fair-timer/progress preservation expected. */
	public void setRunsForTesting(ServerPlayer player, int count) {
		UUID id = player.getUUID();
		int clamped = Math.max(0, count);
		this.completedRuns.put(id, clamped);
		this.activeRuns.remove(id);
		this.eligibilityTicks.put(id, 0);
		persistCompletedRuns(id, clamped);
	}

	public record TargetSelection(ServerPlayer target, int stage, boolean isResume) {
	}

	/**
	 * Multiplayer-aware automatic wave target, in priority order:
	 * 1. Anyone with an active (unfinished) run, currently below y=0 - always wins over fresh
	 *    candidates outright, bypassing the 2000-tick gate entirely (a paused run's timer isn't even
	 *    running - see startRun/completeRun). The run's own already-fixed stage is used, not
	 *    recomputed from group members.
	 * 2. Otherwise, players who've reached ELIGIBILITY_TICKS_REQUIRED with no active run - grouped by
	 *    proximity exactly like the old severity-based selection (two players in the same group if
	 *    within MOB_CLEAR_OUTER_RADIUS of each other, transitively). Group with the most players wins;
	 *    ties broken by whichever group's highest completedRuns member is furthest along. The actual
	 *    target is picked at random from within that group, but the stage used for the whole engagement
	 *    comes from that max-completedRuns member, matching "the player with the most spawns on them
	 *    will be the one to base what stage the group is in on".
	 * Null if nobody qualifies either way right now.
	 */
	public TargetSelection selectTarget(ServerLevel level) {
		List<ServerPlayer> resumable = new ArrayList<>();
		List<ServerPlayer> fresh = new ArrayList<>();
		for (ServerPlayer player : level.players()) {
			if (!isUnderY0(player)) {
				continue;
			}
			if (this.activeRuns.containsKey(player.getUUID())) {
				resumable.add(player);
			} else if (this.eligibilityTicks.getOrDefault(player.getUUID(), 0) >= ELIGIBILITY_TICKS_REQUIRED) {
				fresh.add(player);
			}
		}
		if (!resumable.isEmpty()) {
			List<ServerPlayer> group = selectGroup(groupByProximity(resumable), this::completedRunsOf);
			ServerPlayer target = group.get(ThreadLocalRandom.current().nextInt(group.size()));
			return new TargetSelection(target, this.activeRuns.get(target.getUUID()).stage, true);
		}
		if (fresh.isEmpty()) {
			return null;
		}
		List<ServerPlayer> group = selectGroup(groupByProximity(fresh), this::completedRunsOf);
		ServerPlayer target = group.get(ThreadLocalRandom.current().nextInt(group.size()));
		int maxCompletedRuns = group.stream().mapToInt(this::completedRunsOf).max().orElse(0);
		return new TargetSelection(target, stageFor(maxCompletedRuns), false);
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

	/** Most players wins; ties broken by whichever group's highest-ranked (by rankFn) member scores
	 * higher - shared by both the resume and fresh selection passes above, ranking on completedRuns
	 * either way. */
	private static List<ServerPlayer> selectGroup(List<List<ServerPlayer>> groups, ToIntFunction<ServerPlayer> rankFn) {
		List<ServerPlayer> best = null;
		int bestRank = -1;
		for (List<ServerPlayer> group : groups) {
			int rank = group.stream().mapToInt(rankFn).max().orElse(0);
			if (best == null || group.size() > best.size() || (group.size() == best.size() && rank > bestRank)) {
				best = group;
				bestRank = rank;
			}
		}
		return best;
	}
}
