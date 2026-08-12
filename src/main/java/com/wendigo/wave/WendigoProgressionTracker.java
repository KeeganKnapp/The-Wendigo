package com.wendigo.wave;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
	// The user's own explicit "let mobs spawn on stage 2 as well" request - raised from 2 to 3, so
	// ordinary hostile mobs can still spawn/survive at stage 2 (the wendigo doesn't start clearing
	// its own "territory" of them until stage 3).
	private static final int MOB_CLEARING_MIN_STAGE = 3;

	// How many cumulative ticks below y=0 (while no run is active) a player needs before becoming
	// eligible for a fresh wendigo spawn - see the class doc comment. Reset to 0 the instant a run
	// starts or resumes, frozen (not incrementing) for the run's entire duration, only resuming once
	// the run truly completes.
	private static final int ELIGIBILITY_TICKS_REQUIRED = 2000;
	// The user's own explicit "larger grace period for those who beat the wendigo" request - a real
	// kill (endStage5Hunt) starts the eligibility timer this far BELOW zero instead of at zero (see
	// the accrual loop below - eligibilityTicks.merge only ever adds, so a negative starting point
	// just means SAMPLE_INTERVAL_TICKS-sized accrual has to climb back up through zero first before
	// it starts counting toward ELIGIBILITY_TICKS_REQUIRED the normal way) - a genuinely earned kill
	// buys real extra breathing room beyond the ordinary post-run reset every other stage completion
	// gets. First-pass value (3x the ordinary grace period, total), tune by feel.
	private static final int KILL_BONUS_ELIGIBILITY_TICKS = ELIGIBILITY_TICKS_REQUIRED * 2;

	// Index by stage (1-5); index 0 unused, kept only so stage numbers can index directly without an
	// off-by-one everywhere. Stage 5's goal is intentionally unreachable - see isGoalMet. Stage 1's
	// own progress field is stare count only now (was a combined "10 of anything" figure) - see
	// STAGE1_SOUND_GOAL/ActiveRun.soundProgress right below for its own separate second axis.
	private static final int[] STAGE_GOALS = {0, 4, 10, 6, 4, Integer.MAX_VALUE};
	private static final int[] STAGE_PERCENTS = {0, 10, 30, 50, 70, 90};

	// Stage 1's own compound goal - the user's own explicit "4 stares AND 4 noises" request, replacing
	// the old single "10 successful stares" figure. Only stage 1 has a second axis at all (every other
	// stage's own STAGE_GOALS entry is still a single number checked against ActiveRun.progress alone -
	// see isGoalMet), so this doesn't generalize into an array the way STAGE_GOALS does.
	private static final int STAGE1_SOUND_GOAL = 4;
	// A THIRD axis, this one originally applying to every stage 1-4 uniformly - the user's own explicit
	// "1 successful breathe on stage 1, 1 on stage 2... and so on" request. Stage 5 is deliberately
	// exempt ("it doesn't really matter" there, the user's own words) - matching its own separate
	// kill-triggered stop condition (endStage5Hunt) that bypasses isGoalMet's own ordinary progress
	// check entirely anyway, so this would never actually be reachable there regardless. Stage 1 is now
	// ALSO exempt (see isGoalMet) - the user's own later reversal, paired with sound.breathe itself no
	// longer being offered at stage 1 at all (see TierGates.minPercentFor) - so this goal now really
	// only applies to stages 2-4, despite the field/comment names still saying "1-4".
	private static final int BREATHE_GOAL = 1;

	private static final class ActiveRun {
		final int stage;
		int progress;
		// Stage 1's own "noises" axis (see STAGE1_SOUND_GOAL) - every sound.ambient_cue that actually
		// played this run, across however many waves it took. Stays 0 and unused for every other stage.
		int soundProgress;
		// Stages 2-4's own "successful breathe" axis (see BREATHE_GOAL) - stays 0 and unused for stage
		// 1 and stage 5.
		int breatheProgress;
		// The soul-light progression redesign - the user's own explicit "track if the target was
		// within a safe distance to a soul light... when a task was satisfied" request. Accumulated
		// across every wave this run takes (see addSoulLightTally, fed from PlanRunner's own per-wave
		// EncounterOutcome.tasksNearSoulLight/tasksNotNearSoulLight), read once at run-completion time
		// by resolveRunOutcome to decide whether the run's goal being met advances or REGRESSES the
		// stage. Stays 0 and unused for stage 5 - see endStage5Hunt's own doc comment for why that
		// stage has no task tally at all.
		int tasksNearSoulLight;
		int tasksNotNearSoulLight;

		ActiveRun(int stage) {
			this.stage = stage;
		}
	}

	private final Map<UUID, Integer> completedRuns = new HashMap<>();
	private final Map<UUID, Integer> eligibilityTicks = new HashMap<>();
	private final Map<UUID, ActiveRun> activeRuns = new HashMap<>();
	private final Map<UUID, Boolean> wasUnderY0 = new HashMap<>();
	private final Map<UUID, Integer> warmupUntilTick = new HashMap<>();
	// The user's own explicit death-handling request: a player who just died shouldn't be immediately
	// re-selected as a hand-off target (they're still physically "under y=0" and, if they have an
	// active run, still "resumable" too - neither fact changes just because they died), so
	// WendigoManager's own ALLOW_DEATH handler marks them here right as the current engagement on
	// them ends, and selectTarget skips anyone in this set entirely. Cleared on
	// ServerPlayerEvents.AFTER_RESPAWN (see markRespawned) - once they're back and genuinely eligible
	// again (immediately, if resumable), ordinary selection rules apply again.
	private final Set<UUID> recentlyDied = new HashSet<>();
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

	/** Stage-to-spawn-count mapping: spawns 1-2 -> stage 1, 3 -> stage 2, 4 -> stage 3, 5 -> stage 4,
	 * 6+ -> stage 5 (permanent). Reverted back to giving stage 1 both spawns 1 and 2, the user's own
	 * explicit "revert to two runs in stage 1" request (same session as the original "cut stage 1 to
	 * only be the first run" change this undoes - stage 1's own loosened prose/sound-cue-unlock/
	 * combat.breathe additions from that same stretch of changes all stay, only this run-count mapping
	 * reverts). completedRuns is 0-indexed (how many are already fully done), so this answers "what
	 * stage does the NEXT/current run belong to". */
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

	/** Stage 1's own second axis (see STAGE1_SOUND_GOAL) - a no-op for every other stage's ActiveRun
	 * (nothing ever reads soundProgress outside isGoalMet's own stage==1 check), but harmless to call
	 * unconditionally rather than requiring every caller to check the stage first. */
	public void addSecondaryProgress(ServerPlayer player, int amount) {
		ActiveRun run = this.activeRuns.get(player.getUUID());
		if (run != null) {
			run.soundProgress += amount;
		}
	}

	/** Stages 2-4's own third axis (see BREATHE_GOAL) - a no-op for stage 1 and stage 5's ActiveRun
	 * (nothing ever reads breatheProgress outside isGoalMet's own stage checks), same "harmless to call
	 * unconditionally" reasoning addSecondaryProgress above already has. */
	public void addBreatheProgress(ServerPlayer player, int amount) {
		ActiveRun run = this.activeRuns.get(player.getUUID());
		if (run != null) {
			run.breatheProgress += amount;
		}
	}

	/** Same shape as addProgress/addSecondaryProgress/addBreatheProgress above - see
	 * ActiveRun.tasksNearSoulLight/tasksNotNearSoulLight's own field comment. */
	public void addSoulLightTally(ServerPlayer player, int near, int notNear) {
		ActiveRun run = this.activeRuns.get(player.getUUID());
		if (run != null) {
			run.tasksNearSoulLight += near;
			run.tasksNotNearSoulLight += notNear;
		}
	}

	/** Stage 5 never reads true here (STAGE_GOALS[5] is Integer.MAX_VALUE) - it has its own separate
	 * stop condition entirely (see endStage5Hunt): the player has to actually kill it, not accumulate
	 * a counted goal, so a "progress" number has nothing to compare against here at all (breatheMet is
	 * still explicitly exempted for stage 5 too, for clarity, even though primaryMet alone already
	 * blocks it from ever reading true). Stage 1 is the one DOUBLY-compound case - the user's own
	 * explicit "4 stares AND 4 noises" request layered under a THIRD "1 successful breathe" axis, later
	 * narrowed to stages 2-4 only once sound.breathe itself stopped being offered at stage 1 at all
	 * (see TierGates.minPercentFor) - requiring progress (stares/torch-breaks/lunges/grab, whichever
	 * that stage's own primary metric is), soundProgress (stage 1 only), and breatheProgress (stages
	 * 2-4) to each independently clear their own goal, not a combined total of any kind. */
	public boolean isGoalMet(ServerPlayer player) {
		ActiveRun run = this.activeRuns.get(player.getUUID());
		if (run == null) {
			return false;
		}
		boolean primaryMet = run.progress >= STAGE_GOALS[run.stage];
		boolean soundMet = run.stage != 1 || run.soundProgress >= STAGE1_SOUND_GOAL;
		// Stage 1 is now ALSO exempt (the user's own later reversal, alongside sound.breathe no longer
		// being offered at stage 1 at all - see TierGates.minPercentFor) - a run can't clear a goal it's
		// not even allowed to attempt.
		boolean breatheMet = run.stage == 5 || run.stage == 1 || run.breatheProgress >= BREATHE_GOAL;
		return primaryMet && soundMet && breatheMet;
	}

	/**
	 * Marks the active run as genuinely, permanently over - the user's own explicit "soul light is a
	 * mask" redesign: this used to unconditionally advance completedRuns by 1; now the run's own
	 * cumulative soul-light tally (ActiveRun.tasksNearSoulLight/tasksNotNearSoulLight, accumulated
	 * across every wave the run took - see addSoulLightTally) decides the DIRECTION. Tasks completed
	 * not-near-soul-light "winning" (tasksNotNearSoulLight >= tasksNearSoulLight - ties favor
	 * advancing, the user's own explicit ">=") advances the stage exactly like before; the reverse now
	 * REGRESSES it, a real new possibility this introduces - "stay within the soul lights to keep the
	 * wendigo at stage 1... the soul lights are like a mask leaving the wendigo curious but not
	 * letting him settle in for the hunt," the user's own words.
	 * <p>
	 * Applies to the WHOLE proximity group around player, not just the technical wave target -
	 * "one plan, one set of tasks per group, whether 5 in a group or 1" (see groupNear) - every
	 * member's own completedRuns moves by the same +1/-1, floored at 0 (matches setRunsForTesting's
	 * own clamp - this floor is also exactly how staying near soul light forever keeps a stage-1
	 * player pinned at stage 1, never going negative). Every group member's eligibility timer resets
	 * together too, not just the technical target's - otherwise a non-target member's timer keeps
	 * running and could immediately trigger a second, separate fresh run the instant this one
	 * resolves.
	 * <p>
	 * Stages 1-4 only - see endStage5Hunt for stage 5's own version of this (kill-triggered,
	 * unconditional demotion, no soul-light tally at all). Returns whether the tally favored
	 * advancing (true) or regressing (false), for WendigoManager's own debug logging.
	 * <p>
	 * Floored at STAGE_2_COMPLETED_RUNS, not 0, for a member whose own completedRuns has ALREADY
	 * reached that once - the user's own explicit "enforce a minimum stage of 2 after the first time
	 * a player exits stage 1... for the rest of the game" request. No separate persistent "has ever
	 * reached stage 2" flag needed: since this same floor applies every time this method ever
	 * regresses anyone, completedRuns can never drop below 2 again once it's gotten there, so
	 * whether the CURRENT (pre-regression) value already clears that bar is itself already always an
	 * accurate answer to "has this member ever exited stage 1," by induction. A member who hasn't
	 * reached it yet still floors at plain 0 as before - stage 1 itself can still regress/fail to
	 * ever reach stage 2 at all.
	 */
	public boolean resolveRunOutcome(ServerLevel level, ServerPlayer player) {
		ActiveRun run = this.activeRuns.get(player.getUUID());
		boolean favorable = run == null || run.tasksNotNearSoulLight >= run.tasksNearSoulLight;
		int delta = favorable ? 1 : -1;
		for (ServerPlayer member : groupNear(level, player)) {
			UUID id = member.getUUID();
			int current = completedRunsOf(member);
			int floor = current >= STAGE_2_COMPLETED_RUNS ? STAGE_2_COMPLETED_RUNS : 0;
			int updated = Math.max(floor, current + delta);
			this.completedRuns.put(id, updated);
			persistCompletedRuns(id, updated);
			this.eligibilityTicks.put(id, 0);
		}
		this.activeRuns.remove(player.getUUID());
		return favorable;
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

	// resolveRunOutcome's own permanent regression floor once reached, and also the completedRuns
	// value a demoted stage-5 kill drops down to (see endStage5Hunt - the user's own explicit
	// "defeating the wendigo should demote to stage 2, not 3" correction) - matches stageFor(2) == 2
	// exactly, so this really does mean "stage 2," not some arbitrary number. The two uses landing on
	// the exact same value isn't a coincidence being relied on for correctness - a kill demoting
	// anyone is now just this same permanent floor, not a separate, higher one.
	private static final int STAGE_2_COMPLETED_RUNS = 2;

	/**
	 * Stage 5's own stop condition (see isGoalMet's own comment) - the player has to actually kill
	 * it. Unlike resolveRunOutcome, there's no soul-light tally to consult at all here - "for the
	 * last stage there are no tasks other than killing the wendigo," the user's own words, so this is
	 * unconditional: a genuine kill now DEMOTES the stage back to 2 (not "no change", the old
	 * behavior) - killing it doesn't advance further (there's no stage 6), but it also doesn't just
	 * loop back into fresh stage-5 hunts forever; it gives the group a real breather before climbing
	 * back up. Lands on STAGE_2_COMPLETED_RUNS specifically, not some deeper demotion, since stage 2
	 * is already the permanent floor everyone who's ever gotten this far is entitled to anyway (see
	 * resolveRunOutcome's own doc comment).
	 * <p>
	 * Applies to the WHOLE proximity group around player, same "one plan, one set of tasks per group"
	 * reasoning as resolveRunOutcome - see groupNear. Capped DOWN only (Math.min, never
	 * Math.max/a flat set) - a group member who was somehow below stage 2 already is never bumped UP
	 * by someone else's kill, consistent with the min-based (not max/average) group-stage-selection
	 * philosophy this project already settled on (protect the least-established member, never inflate
	 * their progress based on someone else's activity).
	 * <p>
	 * Restarts every group member's eligibility timer with a real bonus grace period on top (see
	 * KILL_BONUS_ELIGIBILITY_TICKS - the user's own explicit "larger grace period for those who beat
	 * the wendigo" request, applied to the whole group same as everything else here), and resets the
	 * persisted health back to full so the next encounter starts fresh rather than resuming at
	 * 0/negative.
	 */
	public void endStage5Hunt(ServerLevel level, ServerPlayer player) {
		for (ServerPlayer member : groupNear(level, player)) {
			UUID id = member.getUUID();
			int demoted = Math.min(completedRunsOf(member), STAGE_2_COMPLETED_RUNS);
			this.completedRuns.put(id, demoted);
			persistCompletedRuns(id, demoted);
			this.eligibilityTicks.put(id, -KILL_BONUS_ELIGIBILITY_TICKS);
		}
		UUID id = player.getUUID();
		this.activeRuns.remove(id);
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

	/** See recentlyDied's own field comment - called from WendigoManager's ServerPlayerEvents.
	 * ALLOW_DEATH handler, right as the current engagement on this player ends, so selectTarget can't
	 * immediately hand right back to the same player who just died. */
	public void markDied(ServerPlayer player) {
		this.recentlyDied.add(player.getUUID());
	}

	/** See recentlyDied's own field comment - called from WendigoManager's ServerPlayerEvents.
	 * AFTER_RESPAWN handler. Takes the UUID directly (not a ServerPlayer) since respawn always hands
	 * back a NEW ServerPlayer instance - the UUID is the only thing guaranteed to still match. */
	public void markRespawned(UUID playerId) {
		this.recentlyDied.remove(playerId);
	}

	/** Public specifically so WendigoManager's own async LLM-completion staleness guards
	 * (beginWave/beginEngagement) can check whether the SPECIFIC player a request was issued for has
	 * since died, without comparing object identity against state.lockedTarget directly - that
	 * comparison would incorrectly flag a legitimate debug target-redirect (/wendigo wave onto a
	 * DIFFERENT player than whoever's currently locked) as stale too, which this avoids entirely. */
	public boolean isRecentlyDied(ServerPlayer player) {
		return this.recentlyDied.contains(player.getUUID());
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
	 *    ties broken by whichever group's highest completedRuns member is furthest along (a separate
	 *    concern from step 3 below - which CLUSTER of players is worth spawning near at all, not what
	 *    intensity to show up at).
	 * 3. The actual target is picked at random from within the winning group, but the STAGE used for
	 *    the whole engagement comes from that group's LEAST-established (lowest completedRuns) member
	 *    - the user's own explicit correction: the old "runs" progression model (discrete per-player
	 *    stages, no shared continuously-climbing severity score) has no sensible way to "average" a
	 *    group's intensity anymore, and using the group's MOST-established member (the literal
	 *    previous behavior here, before this fix) meant a barely-eligible newcomer standing near a
	 *    stage-5 veteran got shown a full stage-5 haunting they hadn't remotely earned - using the
	 *    lowest instead protects whoever's least established, which is the whole point of a
	 *    per-player progression system in the first place.
	 * Excludes anyone in recentlyDied entirely from BOTH lists - a player who just died is still
	 * physically under y=0 (death doesn't move them) and, if they had an active run, still nominally
	 * "resumable" too, so without this exclusion a hand-off search immediately after their own death
	 * would just find them again instead of moving on to someone else or genuinely coming up empty.
	 * Null if nobody qualifies either way right now.
	 */
	public TargetSelection selectTarget(ServerLevel level) {
		List<ServerPlayer> resumable = new ArrayList<>();
		List<ServerPlayer> fresh = new ArrayList<>();
		for (ServerPlayer player : level.players()) {
			if (!isUnderY0(player) || this.recentlyDied.contains(player.getUUID())) {
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
		int minCompletedRuns = group.stream().mapToInt(this::completedRunsOf).min().orElse(0);
		return new TargetSelection(target, stageFor(minCompletedRuns), false);
	}

	/**
	 * Everyone currently transitively within MOB_CLEAR_OUTER_RADIUS of player (including player
	 * themselves), regardless of eligibility state - unlike selectTarget's own resumable/fresh
	 * filtering (which only groups CANDIDATES for starting/resuming a run), this is used at RUN
	 * COMPLETION time (resolveRunOutcome/endStage5Hunt) to decide who else's completedRuns should
	 * move alongside the technical target's own - "one plan, one set of tasks per group, whether 5 in
	 * a group or 1," the user's own words. Only considers players currently under y=0 (isUnderY0) in
	 * the SAME level as player - someone who wandered off above ground or into a different dimension
	 * mid-run isn't meaningfully "still in the group" by the time it resolves. Falls back to just
	 * [player] if nobody else qualifies (including if player themselves doesn't turn up in any
	 * connected component for some reason - defensive, shouldn't happen given the caller's own
	 * preconditions, but never leaves the technical target out of their own group's resolution).
	 */
	private List<ServerPlayer> groupNear(ServerLevel level, ServerPlayer player) {
		List<ServerPlayer> candidates = new ArrayList<>();
		for (ServerPlayer candidate : level.players()) {
			if (isUnderY0(candidate)) {
				candidates.add(candidate);
			}
		}
		if (!candidates.contains(player)) {
			candidates.add(player);
		}
		for (List<ServerPlayer> group : groupByProximity(candidates)) {
			if (group.contains(player)) {
				return group;
			}
		}
		return List.of(player);
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
