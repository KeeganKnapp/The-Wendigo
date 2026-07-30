package com.wendigo.plan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import com.wendigo.WendigoMod;
import com.wendigo.debug.WendigoDebug;
import com.wendigo.entity.WendigoEntity;
import com.wendigo.sound.WendigoSounds;
import com.wendigo.spatial.DarkSpotScanner;
import com.wendigo.spatial.LightSourceScanner;

/**
 * Per-entity interpreter for a WendigoActionPlan (see action_schema.json). Advances at most one
 * action per tick; control.if/control.while are resolved into the run queue as they're reached,
 * never nested more than one level deep - the schema itself enforces that cap, so this doesn't
 * need to handle deeper recursion.
 */
public class PlanRunner {
	// Guards a single tick's cascade of zero-duration steps (control.if resolution, instant
	// actions like posture.stare). Not a normal code path - just a trip wire against a bug
	// turning into an infinite loop within one tick.
	private static final int MAX_STEPS_PER_TICK = 32;

	private final WendigoEntity self;

	private JsonArray topLevelSteps;
	private int topIndex;
	private final Deque<JsonObject> actionQueue = new ArrayDeque<>();

	// Checked every tick regardless of topIndex/actionQueue/activeWhile state - see "global_rules" in
	// action_schema.json. Each rule fires at most once per wave (globalRulesFired is index-aligned
	// with globalRules); null means this plan declared none (e.g. a hand-authored debug/showcase
	// plan predating this field).
	private JsonArray globalRules;
	private boolean[] globalRulesFired;

	// At most one active control.while at a time - a while body can't contain another while.
	private JsonObject activeWhile;
	private int whileIterationsRemaining;
	// How many iterations the current/just-ended while actually ran - purely diagnostic, logged on
	// exit alongside a live predicate snapshot so a while that ends after 0 iterations (condition
	// already false the very first time it's checked) is distinguishable in the log from one that
	// ran its course normally.
	private int whileIterationsRun;
	// Wendigo-to-player distance captured the instant the current control.while began (capped - see
	// SemanticBands.APPROACH_BASELINE_CAP_BLOCKS) - backs predicate.player_approaching/
	// player_undetected's approach_band. NaN when there's no active while (see PlanPredicates.evaluate).
	private double whileBaselineDistance = Double.NaN;
	// How many times the current control.while has actually run a body containing an approach-type
	// step (movement.approach/approach_spot/approach_dim_spot) - capped at MAX_WHILE_BODY_APPROACHES.
	// Real logs showed a "creep to a dim spot and stare" loop running approach_dim_spot many times in
	// a row (each one resolving near-instantly once already close), producing a lot of movement and
	// essentially zero time actually holding still and staring - once the cap is hit, the loop falls
	// back to synthesized holds (see holdStep) for any further iterations instead of repeating the
	// body, same mechanism the stare-hold extension already uses.
	private int whileApproachesRun;
	private static final int MAX_WHILE_BODY_APPROACHES = 1;

	private JsonObject currentAction;
	private int actionDeadlineTick;

	// Where to try moving once the plan body is exhausted, in order - null/empty means "no despawn
	// phase" (e.g. raw debug-injected plans). Pre-ranked by WendigoManager.rankDespawnCandidates
	// (farthest/most-obstructed-from-any-player first, not a model choice); once that list is
	// exhausted, one live rescan from wherever the entity ended up is tried as a last resort before
	// giving up.
	private List<BlockPos> despawnCandidates;
	private int despawnCandidateIndex;
	private boolean despawnFallbackAttempted;
	private boolean despawnSucceeded;
	private BlockPos currentDespawnTarget;
	// "Is it OK to actually vanish yet" tracking for readyToVanish - separate from
	// lastStuckCheckPosition/stuckInLightTicks below (a broader, longer-fuse "give up on this whole
	// movement action" check that applies to every movement type at a much dimmer-still light
	// level). This one is despawn-specific and short-fused on purpose: 3 seconds motionless in
	// anything brighter than a valid dark spot is enough to conclude nothing better is coming.
	private BlockPos despawnStationaryPosition;
	private int despawnStationaryTicks;
	private static final int DESPAWN_STATIONARY_GIVEUP_TICKS = 60; // ~3s
	// Chosen once when a despawn attempt (internal.despawn_move or movement.retreat_with_fallback)
	// starts, and reused across its own internal retries - internal.despawn_move has no step object
	// of its own to read a speed from (it's an engine-injected marker), so it defaults to "normal".
	private double despawnSpeedMultiplier;
	// Which block combat.break_torch is currently headed for - remembered at start rather than
	// re-queried at completion, so a drifted "nearest torch" can't cause it to break the wrong one.
	private BlockPos currentTorchTarget;
	// "Not moving + in light + supposed to be moving" stuck detector - broader than vanilla's own
	// isStuck() (which only fires for specific pathfinder-detected stuck states), reset per-action in
	// startAction(). See isStuckMotionlessInLight.
	private BlockPos lastStuckCheckPosition;
	private int stuckInLightTicks;
	private static final int STUCK_IN_LIGHT_GIVEUP_TICKS = 100; // ~5s motionless and exposed
	private static final int STUCK_IN_LIGHT_THRESHOLD = 8; // genuinely lit, not just borderline-dark
	// General-purpose "wedged, no net progress" detector - unlike isStuckMotionlessInLight above
	// (light-gated, and exact per-tick block-position equality only), this works in the dark too and
	// catches a mob that's still visibly jittering/bumping tick to tick (e.g. wedged in a tight or
	// concave gap, repeatedly colliding and re-turning) without ever sitting perfectly still - see
	// isMakingNoProgress's own comment. Much shorter fuse than either isStuckMotionlessInLight or
	// vanilla's own isStuck() (which only re-evaluates internally every ~100 ticks, confirmed via
	// javap against PathNavigation) - sitting visibly wedged in a dark cave for 5+ seconds before
	// anything reacted was the actual "looks dumb" complaint this exists to fix.
	private Vec3 noProgressCheckPosition;
	private int noProgressCheckTicks;
	private static final int STUCK_NO_PROGRESS_TICKS = 40; // ~2s
	private static final double STUCK_NO_PROGRESS_DISTANCE_SQR = 0.25; // net displacement under ~0.5 blocks
	// combat.chase bookkeeping - see isChaseResolved/maybeDestroyNearbyTorches.
	private int chaseUnreachableTicks;
	private static final int CHASE_GIVE_UP_TICKS = 100; // ~5s of sustained unreachability
	private static final int CHASE_MAX_TICKS = 600; // 30s hard backstop even if never fully unreachable
	private static final double CHASE_TORCH_RADIUS = 10.0;
	private static final int CHASE_TORCH_MAX_PER_SCAN = 6;
	private static final int CHASE_TORCH_SCAN_INTERVAL_TICKS = 10; // throttle the light-source scan
	// combat.lunge_attack precondition - don't commit to a lunge with nowhere dark enough nearby to
	// retreat to afterward. Scales with severity (see lungeSafeLightRadius) rather than a single
	// fixed value - these are its bounds.
	private static final double LUNGE_SAFE_LIGHT_RADIUS_MIN = 6.0; // at lunge's own unlock threshold
	private static final double LUNGE_SAFE_LIGHT_RADIUS_MAX = 15.0; // at 100% severity
	// Forced speed for both combat.lunge_attack and combat.chase, regardless of whatever the model's
	// plan specified - SemanticBands' fastest tier ("fast" = 1.75).
	private static final double LUNGE_CHASE_SPEED_MULTIPLIER = SemanticBands.speedMultiplier("fast");
	// How far away the player has to wander before a held stare gives up on being noticed - see
	// isPlayerTooFarAwayToKeepStaring.
	private static final double STARING_DISENGAGED_DISTANCE = 40.0;
	// Tracks the model's own posture.stare(enabled=...) intent, separate from self.isStaring() (the
	// visual rig's own look-at-player override). The two used to be the same flag, which broke the
	// stare-hold mechanic: startAction resets self.isStaring() at the top of every real movement
	// action (see isMovementType) so the rig faces its actual travel direction instead of staying
	// artificially locked onto the player mid-approach - correct for the visual, but a "creep to a
	// dim spot and stare" control.while's own body is typically movement.approach_dim_spot, so its
	// very first iteration was silently clearing isStaring() and permanently disabling the hold
	// extension for the rest of that loop, even though the model clearly still intends to be staring
	// (real logs showed exactly this: a while loop burning through its whole iteration budget in a
	// few seconds despite the player never having looked). This flag only ever changes on an explicit
	// posture.stare step, never on movement, so the hold logic can tell "the model wants a stare held
	// here" apart from "is the rig visually locked onto the player at this exact instant".
	private boolean modelIntendedStaring;
	private boolean waveComplete;
	// Full labeled spot_a..spot_f list (not just the despawn candidate chain) so
	// movement.approach_spot can resolve a label mid-plan - same label order as WaveContext, kept in
	// sync by hand (com.wendigo.plan has no dependency on com.wendigo.wave).
	private List<BlockPos> allSpots;
	private static final String[] SPOT_LABELS = {"spot_a", "spot_b", "spot_c", "spot_d", "spot_e", "spot_f"};

	// Deterministic tier enforcement (see TierGates) - 0-100, or 100 to allow everything (used by
	// debug-injected/hand-authored plans, which should never be second-guessed by the automatic
	// severity system). Not the same number as WaveContext's severity/severityCap ratio necessarily
	// being wrong - this is just what gates action execution, independent of whatever the prompt
	// told the model.
	private int severityPercent;
	// True for debug-injected/hand-authored plans (/wendigo plantest, /wendigo wavetest) - distinct
	// from severityPercent==100, which a REAL wave can also genuinely reach and which must still
	// respect isSuddenDespawnAllowed's inverted (allowed BELOW a threshold) check. Without this
	// separate flag, a bypassed wavetest plan's control.despawn would get incorrectly redirected to
	// movement.retreat_with_fallback instead of actually vanishing.
	private boolean tierGatingBypassed;

	// Outcome bookkeeping purely for EncounterHistory (see WendigoManager) - what actually happened
	// this wave, so the next request can tell the model rather than it re-deriving from a static
	// severity number every time.
	private boolean hitLanded;
	private boolean reachedDeadStare;
	private boolean vanishedCleanly;
	private List<String> planShape = List.of();

	public PlanRunner(WendigoEntity self) {
		this.self = self;
	}

	/**
	 * Replaces whatever's currently running with this newly received plan (the full top-level
	 * WendigoActionPlan object - both "plan" and "global_rules" are pulled from it here). Once the
	 * body is exhausted, the runner automatically moves to a despawn candidate (if any were given)
	 * before signaling completion via {@link #isWaveComplete()} - the caller (WendigoManager) polls
	 * that to know when it's safe to remove the entity.
	 */
	public void start(JsonObject fullPlan, List<BlockPos> despawnCandidates, List<BlockPos> allSpots, int severityPercent, boolean tierGatingBypassed) {
		this.self.getNavigation().stop();
		this.self.setNavigationFailed(false);
		this.self.setSeverityPercent(severityPercent);
		this.topLevelSteps = fullPlan.getAsJsonArray("plan");
		this.topIndex = 0;
		this.severityPercent = severityPercent;
		this.tierGatingBypassed = tierGatingBypassed;
		this.hitLanded = false;
		this.reachedDeadStare = false;
		this.vanishedCleanly = false;
		this.modelIntendedStaring = false;
		List<String> shape = new ArrayList<>();
		for (var element : this.topLevelSteps) {
			shape.add(element.getAsJsonObject().get("type").getAsString());
		}
		this.planShape = shape;
		this.actionQueue.clear();
		this.activeWhile = null;
		this.whileBaselineDistance = Double.NaN;
		this.currentAction = null;
		JsonElement globalRulesElement = fullPlan.get("global_rules");
		this.globalRules = globalRulesElement != null && !globalRulesElement.isJsonNull()
			? globalRulesElement.getAsJsonArray() : null;
		this.globalRulesFired = new boolean[this.globalRules != null ? this.globalRules.size() : 0];
		this.despawnCandidates = despawnCandidates;
		this.despawnCandidateIndex = 0;
		this.despawnFallbackAttempted = false;
		this.despawnSucceeded = false;
		this.currentDespawnTarget = null;
		this.allSpots = allSpots;
		this.waveComplete = false;
	}

	/** True once the plan body (and any despawn move) has fully finished. */
	public boolean isWaveComplete() {
		return this.waveComplete;
	}

	/** Snapshot of what actually happened this wave - see EncounterHistory, which is what this
	 * exists for. Safe to call any time (not just after completion); mid-wave it just reflects
	 * whatever's happened so far. */
	public EncounterOutcome outcome() {
		return new EncounterOutcome(this.planShape, this.hitLanded, this.reachedDeadStare, this.vanishedCleanly);
	}

	public record EncounterOutcome(List<String> planShape, boolean hitLanded, boolean reachedDeadStare, boolean vanishedCleanly) {
	}

	/**
	 * Single place every normal (non-forced) wave-ending path funnels through - sets the completion
	 * flags and resolves a still-forced rider (see resolveRiderOnEnd) before doing so.
	 */
	private void completeWave(boolean vanishedCleanly) {
		resolveRiderOnEnd();
		this.forcingRide = false;
		this.topLevelSteps = null;
		this.waveComplete = true;
		this.vanishedCleanly = vanishedCleanly;
	}

	/**
	 * Decides the finishing blow for a still-forced rider, whatever wave-ending path got here -
	 * completeWave (a genuine despawn-point arrival, an exhausted fallback chain, or an immediate
	 * control.despawn) or WendigoManager's own forced backstop discard (see WendigoEntity's own
	 * delegate, called from WendigoManager.tickLevel right before it discards the entity). Not "did
	 * it reach a specific chosen despawn point" - just two live questions, checked right here, right
	 * now: is the wendigo standing somewhere dark enough (the same MAX_DARK_LIGHT bar spawning/
	 * despawning already use), and has the ride gone on long enough for a fair escape chance
	 * (MIN_RIDE_TICKS - see its own comment for the original bug this guards against: catching the
	 * player and ending the wave the same tick). Both true -> the despawn damage lands; either false
	 * -> the rider is just released. No-ops entirely if nobody's currently a forced rider.
	 */
	public void resolveRiderOnEnd() {
		if (!this.forcingRide || this.ridingPlayer == null || !this.ridingPlayer.isAlive()) {
			return;
		}
		boolean hadFairChance = hasHadFairRideChance();
		boolean inDarkness = currentLight() <= DarkSpotScanner.MAX_DARK_LIGHT;
		if (hadFairChance && inDarkness && this.self.level() instanceof ServerLevel serverLevel) {
			this.ridingPlayer.hurtServer(serverLevel, this.self.damageSources().mobAttack(this.self), FORCED_RIDE_DESPAWN_DAMAGE);
			debugSay("despawning with a forced rider still aboard, in darkness - dealing " + FORCED_RIDE_DESPAWN_DAMAGE + " damage");
			this.hitLanded = true;
		} else if (!hadFairChance) {
			debugSay("despawning with a forced rider still aboard, but too soon after catching them for a "
				+ "fair escape chance - releasing them instead of dealing the despawn damage");
		} else {
			debugSay("despawning with a forced rider still aboard, but not in darkness (light=" + currentLight()
				+ ") - releasing them instead of dealing the despawn damage");
		}
		this.ridingPlayer.removeEffect(MobEffects.BLINDNESS);
	}

	public void tick() {
		if (this.topLevelSteps == null) {
			return;
		}
		if (WendigoDebug.anyEnabled() && this.self.level() instanceof ServerLevel serverLevel) {
			WendigoDebug.showPath(serverLevel, this.self.getNavigation());
		}
		if (!this.reachedDeadStare) {
			// Polled every tick rather than only when a plan happens to check it - a plan that never
			// includes predicate.player_looking_at_self(dead_stare) at all shouldn't make this outcome
			// signal (see EncounterHistory) silently always read false regardless of what really happened.
			this.reachedDeadStare = PlanPredicates.isDeadStare(this.self);
		}
		// Also polled every tick, independent of the current action - a forced ride outlives whichever
		// single action (combat.lunge_attack/combat.chase) started it.
		updateForcedRide();
		if (checkGlobalRules()) {
			return; // interrupted this tick - whatever was running got preempted, resolve next tick
		}
		if (this.currentAction != null) {
			if (!isActionDone()) {
				return;
			}
			String finishedType = this.currentAction.get("type").getAsString();
			if (isDespawnAttemptType(finishedType) && !this.self.isNavigationFailed()) {
				this.despawnSucceeded = true; // genuinely arrived - stop trying further candidates
			}
			// A chase (combat.chase or the darkness-ambush's internal.chase_until_light) that gave up
			// rather than catching the player or otherwise resolving cleanly (isChaseResolved/
			// isChaseUntilLightResolved only set navigationFailed in that specific give-up path) means
			// the pursuit failed in the open, cheap-pathing sense the whole mode runs on - not something
			// to leave to the authored plan's own judgment the way an ordinary predicate.player_unreachable
			// check would. Abandon whatever's left of the plan (further top-level steps, a still-active
			// control.while, anything queued) and drop straight into the same despawn-candidate fallback
			// chain a normal end-of-plan flee already uses - "flee to the spawn/despawn spot, or the next
			// best dark spot, with a live rescan as a last resort" - rather than risk it standing exposed
			// wherever the chase left it, or blundering into whatever step the model happened to queue
			// next assuming a caught player.
			if (isChaseType(finishedType) && this.self.isNavigationFailed()) {
				debugSay(finishedType + " gave up chasing - abandoning the rest of the plan to flee instead");
				this.topIndex = this.topLevelSteps.size();
				this.actionQueue.clear();
				this.activeWhile = null;
			}
			this.currentAction = null;
		}
		if (this.currentAction == null && !this.self.onGround() && !this.self.isInLiquid()) {
			// GroundPathNavigation.createPath refuses to even attempt a path unless the mob is
			// onGround/inLiquid/a passenger - right after spawning, onGround can still read false for
			// a tick or two before physics settles it, which made the very first action's moveTo fail
			// outright regardless of distance or light (confirmed via debug logging: onGround=false at
			// the failing attempt, onGround=true one tick later). Wait for that to resolve instead of
			// starting the next action into a guaranteed instant failure.
			return;
		}
		advance();
	}

	/**
	 * Checks every not-yet-fired global rule's condition against live state. The first one that's
	 * true preempts whatever's currently running - stops navigation, drops the current action and
	 * any queued/looping steps - and starts its action immediately, this same tick, rather than
	 * waiting for the current step to finish on its own. Marks that rule fired (one-shot per wave)
	 * regardless of what its action turns out to do. Returns true if a rule fired, so the caller
	 * knows not to also run its own normal per-tick logic this tick.
	 */
	private boolean checkGlobalRules() {
		if (this.globalRules == null) {
			return false;
		}
		for (int i = 0; i < this.globalRules.size(); i++) {
			if (this.globalRulesFired[i]) {
				continue;
			}
			JsonObject rule = this.globalRules.get(i).getAsJsonObject();
			if (!PlanPredicates.evaluate(rule.getAsJsonObject("condition"), this.self)) {
				continue;
			}
			this.globalRulesFired[i] = true;
			JsonObject action = rule.getAsJsonObject("action");
			debugSay("global rule triggered - condition met, running: " + action);
			this.self.getNavigation().stop();
			if ("control.despawn".equals(action.get("type").getAsString())) {
				if (isSuddenDespawnAllowed()) {
					// Same handling as a plan-authored control.despawn - ends the wave right here, no
					// travel, no fallback chain.
					completeWave(true);
					return true;
				}
				debugSay("global rule wanted control.despawn but severity is too high to vanish suddenly - fleeing instead");
				action = retreatFallbackStep();
			}
			this.currentAction = null;
			this.actionQueue.clear();
			this.activeWhile = null;
			if (startAction(action)) {
				this.currentAction = action;
			}
			// Whether the action finished instantly or is now running, normal plan execution resumes
			// from wherever topIndex/actionQueue naturally continue once it's done - this was a
			// preemption, not a restart.
			return true;
		}
		return false;
	}

	/** Pulls and resolves steps until an action starts consuming ticks, or the plan runs out. */
	private void advance() {
		for (int guard = 0; guard < MAX_STEPS_PER_TICK; guard++) {
			JsonObject next = nextActionStep();
			if (next != null && "control.despawn".equals(next.get("type").getAsString())) {
				if (isSuddenDespawnAllowed()) {
					// Ends the wave right here, right now - no travel to despawn_at, no fallback chain,
					// no walk-away transition. Distinct from the normal end-of-plan despawn phase and
					// from movement.retreat_with_fallback (a real flight into darkness); this is
					// "vanish", not "flee". Handled here rather than through the normal
					// startAction/isActionDone flow since there's no completion condition to wait for.
					this.self.getNavigation().stop();
					debugSay("despawning immediately (control.despawn)");
					completeWave(true);
					return;
				}
				// Vanishing suddenly reads as jarring/inconsistent above the lowest severity tier -
				// redirect to a real, visible flight into darkness instead (falls through to the
				// normal startAction/isActionDone handling below, same as any other action).
				debugSay("issue: control.despawn not allowed at this severity - fleeing instead");
				next = retreatFallbackStep();
			}
			if (next == null) {
				if (!this.despawnSucceeded && hasDespawnWork()) {
					next = nextDespawnMoveStep();
				} else {
					completeWave(false); // exhausted - a new wave is what starts a fresh plan
					return;
				}
			}
			if (!startAction(next)) {
				continue; // finished instantly (e.g. posture.stare) - keep draining this tick
			}
			this.currentAction = next;
			return;
		}
		WendigoMod.LOGGER.warn("Wendigo {} exceeded the per-tick plan step budget - possible malformed plan", this.self.getId());
	}

	/** True while there's still an untried despawn candidate, or the one-shot live-scan fallback hasn't run yet. */
	private boolean hasDespawnWork() {
		if (this.despawnCandidates == null || this.despawnCandidates.isEmpty()) {
			return false;
		}
		return this.despawnCandidateIndex < this.despawnCandidates.size() || !this.despawnFallbackAttempted;
	}

	/** Marker step for the automatic terminal despawn phase - startAction resolves the actual target. */
	private static JsonObject nextDespawnMoveStep() {
		JsonObject step = new JsonObject();
		step.addProperty("type", "internal.despawn_move");
		return step;
	}

	/** control.despawn ("vanish right where it stands") reads as jarring/inconsistent above the
	 * lowest severity tier - only allowed unconditionally at stage 1 (its whole flavor is a
	 * fleeting, passive presence that just isn't there anymore), or when there's genuinely nothing
	 * else it could do instead (no despawn candidates configured at all, e.g. a raw debug-injected
	 * plan). Anywhere else, a wanted control.despawn gets redirected to a real, visible flee. */
	private boolean isSuddenDespawnAllowed() {
		return this.tierGatingBypassed || this.severityPercent < TierGates.SUDDEN_DESPAWN_MAX_PERCENT
			|| this.despawnCandidates == null || this.despawnCandidates.isEmpty();
	}

	/** Synthesized filler for a stage-1 control.while that's spent its authored iteration budget but
	 * still needs to keep holding (see nextActionStep) - a short, cheap re-check pause rather than
	 * re-running the model's own body indefinitely (which may include movement/combat steps that
	 * don't make sense to keep repeating forever). */
	private static JsonObject holdStep() {
		JsonObject step = new JsonObject();
		step.addProperty("type", "timing.wait");
		step.addProperty("duration", "short");
		return step;
	}

	private static JsonObject retreatFallbackStep() {
		JsonObject step = new JsonObject();
		step.addProperty("type", "movement.retreat_with_fallback");
		step.addProperty("speed", "fast");
		return step;
	}

	private static boolean isDespawnAttemptType(String type) {
		return "internal.despawn_move".equals(type) || "movement.retreat_with_fallback".equals(type);
	}

	private static boolean isChaseType(String type) {
		return "combat.chase".equals(type) || "internal.chase_until_light".equals(type);
	}

	/** True while the currently-executing action is combat.chase or internal.chase_until_light -
	 * WendigoVisual reads this (via WendigoEntity.isChasing) to keep the face glowing/head tracking
	 * the target during an active chase, the same visual treatment a held stare already gets. */
	public boolean isChasing() {
		return this.currentAction != null && isChaseType(this.currentAction.get("type").getAsString());
	}

	/**
	 * Picks the next despawn target to try - the next pre-scanned candidate, or (once that list is
	 * exhausted) one live rescan from wherever the entity currently is - and kicks off movement
	 * toward it. Shared by the automatic terminal despawn phase and the mid-plan
	 * movement.retreat_with_fallback step, so "try the others, then rescan" behaves identically
	 * either way.
	 */
	private void beginNextDespawnAttempt() {
		if (this.despawnCandidateIndex < this.despawnCandidates.size()) {
			this.currentDespawnTarget = this.despawnCandidates.get(this.despawnCandidateIndex);
			this.despawnCandidateIndex++;
		} else {
			this.despawnFallbackAttempted = true;
			Player nearestPlayer = Targeting.nearestPlayer(this.self);
			BlockPos avoid = nearestPlayer != null ? nearestPlayer.blockPosition() : null;
			BlockPos rescanned = DarkSpotScanner.findDarkestAwayFrom(this.self.level(), this.self.blockPosition(), SemanticBands.searchRadiusBlocks("near"), avoid);
			if (rescanned == null) {
				// Last resort found nothing acceptable nearby either - despawning in place (matches
				// the documented backstop), but this was previously silent, indistinguishable from a
				// real successful despawn even with debug on.
				debugSay("issue: no dark spot found near current position either - despawning in place");
			}
			this.currentDespawnTarget = rescanned != null ? rescanned : this.self.blockPosition();
		}
		this.actionDeadlineTick = this.self.tickCount + SemanticBands.ACTION_TIMEOUT_TICKS;
		BlockPos dest = this.currentDespawnTarget;
		boolean started = this.self.getNavigation().moveTo(dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5, this.despawnSpeedMultiplier);
		this.self.setNavigationFailed(!started);
		debugSay("despawn attempt: target=" + dest.toShortString() + " self=" + this.self.blockPosition().toShortString()
			+ " moveTo started=" + started + " onGround=" + this.self.onGround() + " inLiquid=" + this.self.isInLiquid()
			+ " minY=" + this.self.level().getMinY());
	}

	/** Pulls the next action_step to run, expanding control.if/control.while as they're reached. */
	private JsonObject nextActionStep() {
		while (true) {
			if (!this.actionQueue.isEmpty()) {
				return this.actionQueue.poll();
			}
			if (this.activeWhile != null) {
				JsonObject condition = this.activeWhile.getAsJsonObject("condition");
				// A held stare gives up and lets the loop end - same as the player actually being spotted
				// - once they've wandered well away, so the rest of the plan (a torch to break, a sound
				// cue, its own despawn) still gets a chance to run instead of the wendigo sitting frozen
				// mid-stare for a player who isn't coming back. Deliberately scoped to staring only, not
				// a general wave-ending mechanic - see isPlayerTooFarAwayToKeepStaring's own comment. Uses
				// modelIntendedStaring, NOT self.isStaring() - see that field's own comment for why. Only
				// checked once whileIterationsRun>0 - real bug found via logs: a stage-1 "spawn unwatched,
				// far away, then creep closer while staring" plan could spawn well past
				// STARING_DISENGAGED_DISTANCE from the player (a large/massive cave's "unwatched" spot can
				// easily be 40+ blocks out), and this check fired on the very first evaluation - before
				// the loop's own approach_spot/approach_dim_spot body ever got a single chance to actually
				// close that distance - ending the whole encounter in 2 ticks with no movement at all.
				boolean conditionTrue = PlanPredicates.evaluate(condition, this.self, this.whileBaselineDistance)
					&& !(this.modelIntendedStaring && this.whileIterationsRun > 0 && isPlayerTooFarAwayToKeepStaring());
				// The body is only allowed to actually run again while both the authored iteration
				// budget remains AND (for a body that includes an approach step) it hasn't already
				// repositioned once this loop - see whileApproachesRun's own comment for why that cap
				// exists. A body with no approach step (timing.wait, movement.hold) isn't subject to it.
				boolean bodyHasApproach = whileBodyHasApproach(this.activeWhile);
				boolean approachBudgetOk = !bodyHasApproach || this.whileApproachesRun < MAX_WHILE_BODY_APPROACHES;
				boolean canRunBodyAgain = this.whileIterationsRemaining > 0 && approachBudgetOk;
				// Two independent reasons to keep holding instead of falling through once the loop can't
				// run its body again: isSuddenDespawnAllowed (stage 1's own gate - the encounter itself is
				// meant to be brief either way, so it especially can't afford to also cut the stalk short)
				// OR the model intended to be staring here, any severity. The latter is the atmosphere
				// fix: "the stare, the pause when eyes meet" needs actual screen time to read as horror
				// rather than as a random pop into combat - real logs showed a whole "few"=3 loop
				// finishing in well under a second because the body (typically movement.approach_spot/
				// approach_dim_spot) resolves near-instantly once already near the target, ending the
				// stalk before the player had any real chance to even catch it in the corner of their eye
				// and skipping straight to chase/lunge afterward. Keep genuinely holding in place - still
				// staring - until the condition itself goes false (the player is actually detected) rather
				// than falling through to whatever's next. WendigoManager's own disengage/proximity/wave-
				// timeout backstops still apply on top, so this can't hang forever even if the player
				// truly never looks.
				if (conditionTrue && !canRunBodyAgain && (isSuddenDespawnAllowed() || this.modelIntendedStaring)) {
					this.whileIterationsRun++;
					this.actionQueue.add(holdStep());
					continue;
				}
				if (!conditionTrue || !canRunBodyAgain) {
					debugSay("control.while ended after " + this.whileIterationsRun + " iteration(s) - condition: "
						+ condition + " - live state: " + PlanPredicates.debugSnapshot(this.self)
						+ " approachBaseline=" + this.whileBaselineDistance);
					this.activeWhile = null;
					continue;
				}
				this.whileIterationsRemaining--;
				this.whileIterationsRun++;
				if (bodyHasApproach) {
					this.whileApproachesRun++;
				}
				// Real bug found via logs: whileApproachesRun only capped an approach from repeating
				// across separate ITERATIONS - nothing stopped the model from chaining several approach
				// steps back-to-back within a single body (e.g. approach_spot A, then B, then C, all in
				// one control.while body), which all queued and ran here regardless, one full traversal
				// of the whole cave before ever actually settling into the stare/hold the loop was meant
				// to protect. Cap it to at most one approach step actually queued per body pass here too
				// - any further approach-type step in the same body becomes a synthesized hold instead,
				// same substitution already used for skipped iterations.
				boolean approachQueuedThisPass = false;
				for (var element : this.activeWhile.getAsJsonArray("body")) {
					JsonObject bodyStep = element.getAsJsonObject();
					if (isApproachType(bodyStep.get("type").getAsString())) {
						if (approachQueuedThisPass) {
							this.actionQueue.add(holdStep());
							continue;
						}
						approachQueuedThisPass = true;
					}
					this.actionQueue.add(bodyStep);
				}
				continue;
			}
			if (this.topIndex >= this.topLevelSteps.size()) {
				return null;
			}
			JsonObject step = this.topLevelSteps.get(this.topIndex++).getAsJsonObject();
			String type = step.get("type").getAsString();
			if (type.equals("control.if")) {
				boolean condition = PlanPredicates.evaluate(step.getAsJsonObject("condition"), this.self);
				// "else" is required by the schema now (OpenAI's strict structured-output mode
				// disallows optional properties) but nullable - the model sends an explicit JSON null
				// rather than omitting the key when there's nothing for the false branch to do.
				// JsonObject.getAsJsonArray casts straight to JsonArray, which throws on a JsonNull
				// value rather than treating it like an absent key, so that has to be checked first.
				JsonElement branchElement = condition ? step.get("then") : step.get("else");
				JsonArray branch = branchElement != null && !branchElement.isJsonNull() ? branchElement.getAsJsonArray() : null;
				if (branch != null) {
					for (var element : branch) {
						this.actionQueue.add(element.getAsJsonObject());
					}
				}
				continue;
			}
			if (type.equals("control.while")) {
				this.activeWhile = step;
				this.whileIterationsRemaining = SemanticBands.maxIterations(step.get("max_iterations").getAsString());
				this.whileIterationsRun = 0;
				this.whileApproachesRun = 0;
				Player baselinePlayer = Targeting.nearestPlayer(this.self);
				this.whileBaselineDistance = baselinePlayer != null
					? Math.min(this.self.distanceTo(baselinePlayer), SemanticBands.APPROACH_BASELINE_CAP_BLOCKS)
					: Double.NaN;
				continue;
			}
			return step;
		}
	}

	/** One-time setup for an action_step. Returns false if it already finished within this tick. */
	private boolean startAction(JsonObject step) {
		String type = step.get("type").getAsString();
		if (!TierGates.isAllowed(step, this.severityPercent)) {
			// Deterministic backstop for the prompt's severity-tier guidance - prose alone proved
			// unreliable (real generations used combat primitives well below the tier that's supposed
			// to unlock them). Skipped like any other unmet precondition, never a wave-ending error.
			debugSay("issue: tier-gated - " + type + " not allowed at severity " + this.severityPercent + "% - skipping");
			return false;
		}
		this.actionDeadlineTick = this.self.tickCount + SemanticBands.ACTION_TIMEOUT_TICKS;
		debugSay("doing: " + step);
		// Fresh stuck-in-light tracking per action - see isStuckMotionlessInLight - so stale state
		// from whatever ran before this can't immediately misfire against a brand new action.
		this.lastStuckCheckPosition = null;
		this.stuckInLightTicks = 0;
		// Same reasoning for isMakingNoProgress's own tracking.
		this.noProgressCheckPosition = null;
		this.noProgressCheckTicks = 0;
		// Only combat.lunge_attack/combat.break_torch re-enable this below - every other action
		// should stay light-averse, and resetting here (rather than only after those two) covers
		// every path back to "averse" without needing a matching reset in each other branch.
		this.self.setLightTolerantPathing(false);
		// A held stare visually locks the whole rig facing the player (see WendigoVisual) - nothing
		// else ever turns it back off on its own. Every real movement/navigation action should read
		// the wendigo as facing whichever way it's actually walking, not artificially locked onto the
		// player - lunge/chase/break_torch still end up facing the player anyway since they're moving
		// toward their target, this just stops that from being an artificial override that could
		// point the wrong way (e.g. crawling away for a retreat while still visually facing back at
		// the player). Deliberately NOT applied to movement.hold/timing.wait/posture.stare itself -
		// those are exactly the primitives a "hold and stare" idiom is built from, and resetting here
		// would turn the stare off the instant the first hold/wait tick ran.
		//
		// The other side of that same coin: once a held stare's own body finishes its one allowed
		// approach (see whileApproachesRun) and settles into holding/waiting again, the visual needs
		// to go back to facing the player - otherwise the rig is left facing wherever that one
		// approach happened to end, for the entire rest of the hold, even though modelIntendedStaring
		// (the gameplay flag) correctly kept the loop open the whole time. Restoring it here, for
		// every non-movement action while a stare is still logically active, covers the model's own
		// timing.wait/movement.hold body steps as well as this engine's own synthesized holdStep -
		// posture.stare itself still gets the final say immediately afterward in the switch below.
		if (isMovementType(type)) {
			this.self.setStaring(false);
		} else if (this.modelIntendedStaring) {
			this.self.setStaring(true);
		}
		switch (type) {
			case "movement.approach" -> {
				Player target = Targeting.nearestPlayer(this.self);
				if (target == null) {
					return false;
				}
				boolean started = this.self.getNavigation().moveTo(target, SemanticBands.speedMultiplier(step.get("speed").getAsString()));
				this.self.setNavigationFailed(!started);
				debugMoveTo(type, started);
			}
			case "movement.approach_spot" -> {
				BlockPos dest = resolveSpotLabel(step.get("spot").getAsString());
				if (dest == null) {
					WendigoMod.LOGGER.debug("Wendigo {} got an unresolvable spot label for approach_spot - skipping", this.self.getId());
					debugSay("issue: unresolvable spot label - skipping approach_spot");
					return false;
				}
				boolean started = this.self.getNavigation().moveTo(dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5, SemanticBands.speedMultiplier(step.get("speed").getAsString()));
				this.self.setNavigationFailed(!started);
				debugMoveTo(type, started);
			}
			case "movement.approach_dim_spot" -> {
				BlockPos dest = nearestDimSpot();
				if (dest == null) {
					WendigoMod.LOGGER.debug("Wendigo {} found no dim spot near its current position - skipping approach_dim_spot", this.self.getId());
					debugSay("issue: no dim spot found nearby - skipping approach_dim_spot");
					return false;
				}
				boolean started = this.self.getNavigation().moveTo(dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5, SemanticBands.speedMultiplier(step.get("speed").getAsString()));
				this.self.setNavigationFailed(!started);
				debugMoveTo(type, started);
			}
			case "movement.retreat_to_dark" -> {
				BlockPos dest = retreatDestination(step);
				if (dest == null) {
					return false;
				}
				boolean started = this.self.getNavigation().moveTo(dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5, SemanticBands.speedMultiplier(step.get("speed").getAsString()));
				this.self.setNavigationFailed(!started);
				debugMoveTo(type, started);
			}
			case "movement.reposition" -> {
				BlockPos dest = PlanGeometry.repositionTarget(step, this.self);
				if (dest == null) {
					return false;
				}
				boolean started = this.self.getNavigation().moveTo(dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5, SemanticBands.speedMultiplier(step.get("speed").getAsString()));
				this.self.setNavigationFailed(!started);
				debugMoveTo(type, started);
			}
			case "internal.despawn_move", "movement.retreat_with_fallback" -> {
				if (!hasDespawnWork()) {
					return false; // nothing configured to fall back to (e.g. a raw debug-injected plan)
				}
				// internal.despawn_move is an engine-injected marker with no "speed" of its own.
				this.despawnSpeedMultiplier = step.has("speed")
					? SemanticBands.speedMultiplier(step.get("speed").getAsString())
					: SemanticBands.speedMultiplier("normal");
				beginNextDespawnAttempt();
			}
			case "combat.lunge_attack" -> {
				Player target = Targeting.nearestPlayer(this.self);
				if (target == null) {
					return false;
				}
				double safeLightRadius = lungeSafeLightRadius();
				if (!DarkSpotScanner.hasDarkSpotWithin(this.self.level(), target.blockPosition(), safeLightRadius)) {
					// Nowhere dark enough near the player to retreat to afterward - don't commit this
					// deep into the light for it.
					debugSay("issue: no safe darkness within " + safeLightRadius + " blocks of the player - skipping lunge");
					return false;
				}
				this.self.setLightTolerantPathing(true);
				// Forced to the fastest speed regardless of what the model specified - a lunge is a
				// commit-to-the-grab moment, not something that reads right ambling in at "slow"/"normal".
				boolean started = this.self.getNavigation().moveTo(target, LUNGE_CHASE_SPEED_MULTIPLIER);
				this.self.setNavigationFailed(!started);
				debugMoveTo(type, started);
			}
			case "combat.break_torch" -> {
				BlockPos torch = nearestTorch();
				if (torch == null) {
					WendigoMod.LOGGER.debug("Wendigo {} found no torch nearby - skipping break_torch", this.self.getId());
					debugSay("issue: no torch found nearby - skipping break_torch");
					return false;
				}
				this.currentTorchTarget = torch;
				this.self.setLightTolerantPathing(true);
				boolean started = this.self.getNavigation().moveTo(torch.getX() + 0.5, torch.getY(), torch.getZ() + 0.5, SemanticBands.speedMultiplier(step.get("speed").getAsString()));
				this.self.setNavigationFailed(!started);
				debugSay("break_torch: target=" + torch.toShortString() + " self=" + this.self.blockPosition().toShortString()
					+ " moveTo started=" + started + " onGround=" + this.self.onGround() + " inLiquid=" + this.self.isInLiquid()
					+ " minY=" + this.self.level().getMinY());
			}
			case "combat.chase" -> {
				Player target = Targeting.nearestPlayer(this.self);
				if (target == null) {
					return false;
				}
				this.self.setLightTolerantPathing(true);
				this.chaseUnreachableTicks = 0;
				// A real chase can run much longer than the default per-action timeout without that
				// meaning anything's wrong - only the sustained-unreachable check below should end it.
				this.actionDeadlineTick = this.self.tickCount + CHASE_MAX_TICKS;
				// Forced to the fastest speed regardless of what the model specified - same reasoning
				// as combat.lunge_attack above.
				boolean started = this.self.getNavigation().moveTo(target, LUNGE_CHASE_SPEED_MULTIPLIER);
				this.self.setNavigationFailed(!started);
				debugMoveTo(type, started);
			}
			case "internal.chase_until_light" -> {
				// Engine-only primitive, never offered to the model (see WendigoManager.
				// buildDarknessAmbushPlan) - the darkness-overstay punishment: hunt the player down
				// until they either reach real light (see isChaseUntilLightResolved) or get caught
				// (same beginForcedRide pickup as combat.lunge_attack/combat.chase).
				Player target = Targeting.nearestPlayer(this.self);
				if (target == null) {
					return false;
				}
				this.self.setLightTolerantPathing(true);
				this.chaseUnreachableTicks = 0;
				this.actionDeadlineTick = this.self.tickCount + CHASE_MAX_TICKS;
				boolean started = this.self.getNavigation().moveTo(target, LUNGE_CHASE_SPEED_MULTIPLIER);
				this.self.setNavigationFailed(!started);
				debugMoveTo(type, started);
			}
			case "movement.hold" -> {
				this.self.getNavigation().stop();
				return false;
			}
			case "memory.store_dark_location" -> {
				double radius = SemanticBands.searchRadiusBlocks(step.get("search_radius").getAsString());
				this.self.setStoredDarkLocation(PlanGeometry.findDarkSpot(this.self, radius));
				return false;
			}
			case "posture.stare" -> {
				boolean enabled = step.get("enabled").getAsBoolean();
				this.self.setStaring(enabled);
				this.modelIntendedStaring = enabled;
				return false;
			}
			case "timing.wait" -> {
				int[] range = SemanticBands.waitTicks(step.get("duration").getAsString());
				int ticks = range[0] + this.self.getRandom().nextInt(range[1] - range[0] + 1);
				this.actionDeadlineTick = this.self.tickCount + ticks;
			}
			case "sound.ambient_cue" -> {
				playAmbientCue(step.get("cue").getAsString());
				return false; // instantaneous - plays once, the plan continues the same tick
			}
			case "control.none" -> {
				return false;
			}
			default -> {
				WendigoMod.LOGGER.warn("Wendigo {} got unknown action type '{}', skipping", this.self.getId(), type);
				return false;
			}
		}
		return true;
	}

	/** Whether the in-progress action has reached its completion condition, or timed out. */
	private boolean isActionDone() {
		String type = this.currentAction.get("type").getAsString();
		boolean timedOut = this.self.tickCount >= this.actionDeadlineTick;
		boolean stuckInLight = isMovementType(type) && isStuckMotionlessInLight();
		// Broader still than stuckInLight - see isMakingNoProgress's own comment for why NET
		// displacement (not stuckInLight's exact per-tick block-position equality, or vanilla's own
		// isStuck(), which only re-evaluates internally every ~100 ticks) is needed to catch a mob
		// wedged in a tight/concave DARK gap (stuckInLight only fires when lit) that's still visibly
		// jittering/bumping against the obstacle tick to tick without making real progress.
		boolean noProgress = isPlainMovementType(type) && isMakingNoProgress();
		if (stuckInLight || noProgress) {
			debugSay("issue: " + (noProgress ? "wedged, making no real progress" : "stuck motionless in a lit area")
				+ " during " + type + " - giving up on it");
		}
		if (timedOut || stuckInLight || noProgress) {
			this.self.getNavigation().stop();
			if (isMovementType(type)) {
				// A movement action that only ends via timeout/stuck-detection didn't actually arrive -
				// without this, a despawn move in that state (a plausible outcome for a far spot, given
				// the fixed per-action timeout, or a genuinely bad path) would be misread as a success
				// and never retried. timing.wait deliberately isn't in isMovementType - it's meant to
				// end via deadline.
				this.self.setNavigationFailed(true);
			}
		}
		boolean resolved;
		if (isDespawnAttemptType(type)) {
			resolved = isDespawnAttemptResolved(timedOut || stuckInLight || noProgress);
		} else if (timedOut || stuckInLight || noProgress) {
			resolved = true;
		} else {
			resolved = switch (type) {
				case "movement.approach" -> arrivedNearPlayer() || navigationFinished() || checkStuck();
				case "movement.approach_spot", "movement.approach_dim_spot", "movement.retreat_to_dark", "movement.reposition" ->
					navigationFinished() || checkStuck();
				case "combat.lunge_attack" -> isLungeResolved();
				case "combat.break_torch" -> isBreakTorchResolved();
				case "combat.chase" -> isChaseResolved();
				case "internal.chase_until_light" -> isChaseUntilLightResolved();
				case "timing.wait" -> false; // only the deadline check above ends a wait
				default -> true;
			};
		}
		// Plain movement.* types had no completion-side log at all before this (despawn/break_torch
		// already narrate their own outcome; combat.lunge_attack/chase now do too, above) - this is
		// the "did the pathfind actually work" signal for the rest, uniformly, including the
		// timedOut/stuckInLight paths above.
		if (resolved && isPlainMovementType(type)) {
			debugSay(type + ": pathfind " + (this.self.isNavigationFailed() ? "failed" : "succeeded")
				+ " ending at " + this.self.blockPosition().toShortString()
				+ " (timedOut=" + timedOut + " stuckInLight=" + stuckInLight + ")");
		}
		return resolved;
	}

	private static boolean isPlainMovementType(String type) {
		return switch (type) {
			case "movement.approach", "movement.approach_spot", "movement.approach_dim_spot",
				"movement.retreat_to_dark", "movement.reposition" -> true;
			default -> false;
		};
	}

	/** Whether a control.while's body contains an approach-type step at all - see whileApproachesRun. */
	private static boolean whileBodyHasApproach(JsonObject whileStep) {
		for (var element : whileStep.getAsJsonArray("body")) {
			if (isApproachType(element.getAsJsonObject().get("type").getAsString())) {
				return true;
			}
		}
		return false;
	}

	private static boolean isApproachType(String type) {
		return type.equals("movement.approach") || type.equals("movement.approach_spot") || type.equals("movement.approach_dim_spot");
	}

	/**
	 * Resolves a despawn attempt (internal.despawn_move or movement.retreat_with_fallback): if it
	 * hasn't reached a stopping point yet (still moving, hasn't timed out), not done. Once stopped,
	 * a clean arrival ends it successfully; a failure retries with the next candidate - staying the
	 * same action/step - until the fallback chain is exhausted, only then giving up.
	 */
	private boolean isDespawnAttemptResolved(boolean timedOut) {
		if (!timedOut && !navigationFinished() && !checkStuck()) {
			return false;
		}
		if (!this.self.isNavigationFailed()) {
			if (readyToVanish() && hasHadFairRideChance()) {
				return true; // arrived cleanly, dark enough (or stuck long enough), and carried them long enough
			}
			if (hasDespawnWork()) {
				debugSay(readyToVanish()
					? "issue: arrived and dark enough, but hasn't carried the caught player long enough for a "
						+ "fair chance yet - dashing on to the next candidate instead of vanishing here"
					: "issue: arrived but still in light (" + currentLight() + ") - trying the next candidate instead of vanishing here");
				beginNextDespawnAttempt();
				return false;
			}
			return false; // nothing left to try, but not stationary long enough yet either - keep waiting
		}
		if (hasDespawnWork()) {
			debugSay("issue: despawn/retreat attempt failed (" + this.currentDespawnTarget + ") - trying the next candidate");
			beginNextDespawnAttempt(); // retry with the next candidate, same action continues
			return false;
		}
		if (!readyToVanish() || !hasHadFairRideChance()) {
			return false; // exhausted every candidate but still too bright, or hasn't held/ridden long enough
		}
		debugSay("issue: exhausted every despawn/retreat candidate - giving up here");
		return true; // exhausted every option - give up here, same backstop as before
	}

	/** True once a caught player has been carried long enough (MIN_RIDE_TICKS) for a fair chance to
	 * escape - always true when nobody's currently a forced rider. Gates BOTH whether completeWave is
	 * allowed to deal the despawn-damage killing blow (see its own comment for that original bug) AND,
	 * separately, whether a despawn attempt is even allowed to be considered "arrived" while carrying a
	 * rider (see isDespawnAttemptResolved) - without the latter, catching the player right as (or just
	 * before) reaching an already-close despawn candidate read as an anticlimactic instant grab-and-
	 * vanish far too often, even though no damage was ever dealt in that case. Forcing the despawn
	 * attempt to keep dashing on to further candidates until the ride has run long enough gives a real
	 * "carried off toward the dark" beat instead. */
	private boolean hasHadFairRideChance() {
		return !this.forcingRide || (this.self.tickCount - this.rideStartTick >= MIN_RIDE_TICKS);
	}

	/** Whether it's OK for a despawn attempt to actually conclude right now - either it's dark
	 * enough (at or below DarkSpotScanner.MAX_DARK_LIGHT, the same bar a spot has to clear to be
	 * offered as a dark spot in the first place), or it's been genuinely motionless for
	 * DESPAWN_STATIONARY_GIVEUP_TICKS with nowhere better left to try - a last-resort escape hatch,
	 * not the normal path. Without this, a despawn move that merely stopped moving (arrived at the
	 * pathfinder's best-effort spot, or ran out of fallback candidates) while still standing in
	 * plain light would vanish right there, reading as teleporting away at will instead of actually
	 * fleeing into the dark. */
	private boolean readyToVanish() {
		if (currentLight() <= DarkSpotScanner.MAX_DARK_LIGHT) {
			return true;
		}
		BlockPos current = this.self.blockPosition();
		if (current.equals(this.despawnStationaryPosition)) {
			this.despawnStationaryTicks++;
		} else {
			this.despawnStationaryPosition = current;
			this.despawnStationaryTicks = 0;
		}
		return this.despawnStationaryTicks >= DESPAWN_STATIONARY_GIVEUP_TICKS;
	}

	private int currentLight() {
		return this.self.level().getMaxLocalRawBrightness(this.self.blockPosition());
	}

	private boolean isLungeResolved() {
		if (withinMeleeRange()) {
			Player player = Targeting.nearestPlayer(this.self);
			if (player != null) {
				beginForcedRide(player);
			}
			debugSay("lunge: caught the player at self=" + this.self.blockPosition().toShortString());
			return true;
		}
		if (navigationFinished() || checkStuck()) {
			debugSay("lunge: missed - ended without reaching melee range (navFailed=" + this.self.isNavigationFailed()
				+ ") self=" + this.self.blockPosition().toShortString());
			return true;
		}
		return false;
	}

	private boolean isBreakTorchResolved() {
		if (withinTorchBreakRange()) {
			debugSay("break_torch: in range at self=" + this.self.blockPosition().toShortString() + " - breaking");
			performTorchBreak();
			return true;
		}
		if (!navigationFinished() && !checkStuck()) {
			return false;
		}
		double dist = Math.sqrt(this.self.blockPosition().distSqr(this.currentTorchTarget));
		if (!this.self.isNavigationFailed()) {
			// Navigation completed on its own (not stuck, not a failed moveTo) but stopped just
			// outside TORCH_BREAK_RANGE - typical for a wall-mounted block, where the nearest
			// reachable node the pathfinder can actually stand on isn't necessarily inside that
			// radius. That's the pathfinder's honest best-effort arrival, not a failure to reach it,
			// so treat it as close enough rather than silently ending the action with the torch
			// never actually broken.
			debugSay("break_torch: nav finished (not failed) at self=" + this.self.blockPosition().toShortString()
				+ " dist=" + dist + " - breaking anyway");
			performTorchBreak();
			return true;
		}
		// Distinct from the clean-arrival case above - moveTo never got a path at all, or got stuck
		// partway there (e.g. the found light source turned out unreachable), so nothing was broken.
		debugSay("issue: couldn't path to torch (" + this.currentTorchTarget + ") - self=" + this.self.blockPosition().toShortString()
			+ " dist=" + dist + " - giving up here");
		return true;
	}

	/**
	 * Unlike lunge/break_torch, combat.chase doesn't end the instant it hits a single stuck tick -
	 * it's meant to be sustained, passively destroying nearby torches as it runs, until it either
	 * catches the player (see beginForcedRide - resolves immediately, same as lunge, rather than
	 * sustaining a repeated-strike loop the way this used to before the ride mechanic replaced
	 * damage-on-contact) or gives up once they've been unreachable for CHASE_GIVE_UP_TICKS straight
	 * (re-issuing pursuit toward their live position in the meantime, since a finished path stops
	 * vanilla's own auto-retargeting toward a moving entity target).
	 */
	private boolean isChaseResolved() {
		Player player = Targeting.nearestPlayer(this.self);
		if (player == null) {
			return true; // nothing left to chase
		}
		if (withinMeleeRange()) {
			beginForcedRide(player);
			debugSay("chase: caught the player at self=" + this.self.blockPosition().toShortString());
			return true;
		}
		maybeDestroyNearbyTorches();
		boolean lostTrail = this.self.getNavigation().isStuck() || (navigationFinished() && !withinMeleeRange());
		if (!lostTrail) {
			this.chaseUnreachableTicks = 0;
			return false;
		}
		this.chaseUnreachableTicks++;
		if (navigationFinished() && !withinMeleeRange()) {
			this.self.getNavigation().moveTo(player, LUNGE_CHASE_SPEED_MULTIPLIER);
		}
		if (this.chaseUnreachableTicks >= CHASE_GIVE_UP_TICKS) {
			debugSay("issue: couldn't reach the player during combat.chase for a while - giving up the chase");
			this.self.setNavigationFailed(true);
			return true;
		}
		return false;
	}

	/**
	 * internal.chase_until_light's resolution - the darkness-overstay ambush's whole point is "get
	 * out of the dark or get grabbed": checked first, every tick, regardless of distance - the
	 * instant the player is standing somewhere brighter than DarkSpotScanner.MAX_DARK_LIGHT, they've
	 * won and the chase ends right there, no capture. Otherwise behaves exactly like combat.chase
	 * (catches on contact via beginForcedRide, gives up after a sustained stretch of being
	 * unreachable) - shares its chaseUnreachableTicks/CHASE_GIVE_UP_TICKS bookkeeping since the two
	 * never run at the same time.
	 */
	private boolean isChaseUntilLightResolved() {
		Player player = Targeting.nearestPlayer(this.self);
		if (player == null) {
			return true;
		}
		if (this.self.level().getMaxLocalRawBrightness(player.blockPosition()) > DarkSpotScanner.MAX_DARK_LIGHT) {
			debugSay("internal.chase_until_light: player reached light - letting them go");
			return true;
		}
		if (withinMeleeRange()) {
			beginForcedRide(player);
			debugSay("internal.chase_until_light: caught the player at self=" + this.self.blockPosition().toShortString());
			return true;
		}
		boolean lostTrail = this.self.getNavigation().isStuck() || (navigationFinished() && !withinMeleeRange());
		if (!lostTrail) {
			this.chaseUnreachableTicks = 0;
			return false;
		}
		this.chaseUnreachableTicks++;
		if (navigationFinished() && !withinMeleeRange()) {
			this.self.getNavigation().moveTo(player, LUNGE_CHASE_SPEED_MULTIPLIER);
		}
		if (this.chaseUnreachableTicks >= CHASE_GIVE_UP_TICKS) {
			debugSay("issue: couldn't reach the player during internal.chase_until_light - giving up");
			this.self.setNavigationFailed(true);
			return true;
		}
		return false;
	}

	/** Passive collateral from the chase itself, not a targeted break_torch - no pathing to torches,
	 * no aiming for them, just anything within CHASE_TORCH_RADIUS of wherever the wendigo currently
	 * is gets destroyed as it runs through, always, unconditionally - combat.chase itself is now
	 * 80%+ only (see TierGates), so there's no lower tier left where a chase shouldn't also be a
	 * demolition. Throttled so this isn't a full light-source scan every single tick. */
	private void maybeDestroyNearbyTorches() {
		if (this.self.tickCount % CHASE_TORCH_SCAN_INTERVAL_TICKS != 0) {
			return;
		}
		if (!(this.self.level() instanceof ServerLevel serverLevel)) {
			return;
		}
		for (BlockPos torch : LightSourceScanner.findLightSources(serverLevel, this.self.blockPosition(), CHASE_TORCH_RADIUS, CHASE_TORCH_MAX_PER_SCAN)) {
			LightSourceScanner.destroyByWendigo(serverLevel, torch, this.self);
		}
	}

	private static boolean isMovementType(String type) {
		return switch (type) {
			case "movement.approach", "movement.approach_spot", "movement.approach_dim_spot",
				"movement.retreat_to_dark", "movement.reposition", "movement.retreat_with_fallback",
				"internal.despawn_move", "combat.lunge_attack", "combat.break_torch", "combat.chase",
				"internal.chase_until_light" -> true;
			default -> false;
		};
	}


	/** Resolves a schema spot label (e.g. "spot_c") against the full spot_a..spot_f list handed to
	 * start() - same label order as WaveContext.resolve, kept in sync by hand. Null if allSpots
	 * wasn't provided (e.g. a raw debug-injected plan) or the label/index doesn't exist. */
	private BlockPos resolveSpotLabel(String label) {
		if (this.allSpots == null) {
			return null;
		}
		for (int i = 0; i < SPOT_LABELS.length; i++) {
			if (SPOT_LABELS[i].equals(label) && i < this.allSpots.size()) {
				return this.allSpots.get(i);
			}
		}
		return null;
	}

	private void debugSay(String message) {
		if (this.self.level() instanceof ServerLevel serverLevel) {
			WendigoDebug.say(serverLevel, message);
		}
	}

	/** Uniform "did the pathfind even start" log for every movement primitive's moveTo call - the
	 * per-action logs that already existed (despawn attempts, break_torch) were one-offs added ad
	 * hoc while chasing specific bugs, leaving most movement types silent about whether moveTo ever
	 * found a path in the first place versus only failing later via stuck/timeout. */
	private void debugMoveTo(String actionType, boolean started) {
		debugSay(actionType + ": moveTo started=" + started + " self=" + this.self.blockPosition().toShortString()
			+ " onGround=" + this.self.onGround() + " inLiquid=" + this.self.isInLiquid());
	}

	/** All three cues currently play the same vanilla ambient cave sound - distinct sound design per
	 * cue (a real jumpscare sting, a "caught" reaction, plain ambience) is future work, but the
	 * labels are meaningful now so plans can be authored against the final shape already. */
	private void playAmbientCue(String cue) {
		if (!(this.self.level() instanceof ServerLevel serverLevel)) {
			return;
		}
		WendigoSounds.Type type = switch (cue) {
			case "caught" -> WendigoSounds.Type.CAUGHT;
			case "jumpscare" -> WendigoSounds.Type.JUMPSCARE;
			// Not offered in the model-facing schema's cue enum - only WendigoManager.
			// buildDarknessAmbushPlan's hardcoded plan ever uses this value.
			case "danger" -> WendigoSounds.Type.DANGER;
			default -> WendigoSounds.Type.AMBIENCE;
		};
		WendigoSounds.play(serverLevel, this.self.blockPosition(), type);
		debugSay("sound cue played: " + cue);
	}

	private boolean navigationFinished() {
		PathNavigation nav = this.self.getNavigation();
		return nav.isDone() && !nav.isInProgress();
	}

	/** Backs predicate.player_unreachable - a stuck navigator ends the action early so the plan can react. */
	private boolean checkStuck() {
		if (this.self.getNavigation().isStuck()) {
			this.self.setNavigationFailed(true);
			debugSay("issue: navigation reports stuck at " + this.self.blockPosition().toShortString());
			return true;
		}
		return false;
	}

	/** True once the wendigo has sat motionless in a genuinely lit position for
	 * STUCK_IN_LIGHT_GIVEUP_TICKS straight - a broader safety net than vanilla's own isStuck() (which
	 * only fires for specific pathfinder-detected states), for a movement action that's silently
	 * failing to make progress by some other mechanism. Resets its own counter once it fires, and
	 * whenever the position changes or it's no longer lit. */
	private boolean isStuckMotionlessInLight() {
		BlockPos current = this.self.blockPosition();
		boolean stationary = current.equals(this.lastStuckCheckPosition);
		this.lastStuckCheckPosition = current;
		int light = this.self.level().getMaxLocalRawBrightness(current);
		if (stationary && light >= STUCK_IN_LIGHT_THRESHOLD) {
			this.stuckInLightTicks++;
		} else {
			this.stuckInLightTicks = 0;
		}
		if (this.stuckInLightTicks >= STUCK_IN_LIGHT_GIVEUP_TICKS) {
			this.stuckInLightTicks = 0;
			return true;
		}
		return false;
	}

	/** True once NET displacement over the last STUCK_NO_PROGRESS_TICKS has stayed under
	 * STUCK_NO_PROGRESS_DISTANCE_SQR - unlike isStuckMotionlessInLight's exact per-tick block-position
	 * equality, this catches a mob that's still moving SOME each tick (bumping/jittering against a
	 * tight or concave gap it can't actually get through, repeatedly colliding and re-turning -
	 * exactly what reads as chaotic yaw on the visual side too) without ever registering as literally
	 * stationary on any single tick. Checked as a snapshot-and-compare over a real window (not a
	 * rolling average) - simpler, and fine here since a genuinely wedged mob won't suddenly start
	 * making progress mid-window either way. */
	private boolean isMakingNoProgress() {
		Vec3 current = this.self.position();
		if (this.noProgressCheckPosition == null) {
			this.noProgressCheckPosition = current;
			this.noProgressCheckTicks = 0;
			return false;
		}
		this.noProgressCheckTicks++;
		if (this.noProgressCheckTicks < STUCK_NO_PROGRESS_TICKS) {
			return false;
		}
		boolean noProgress = current.distanceToSqr(this.noProgressCheckPosition) < STUCK_NO_PROGRESS_DISTANCE_SQR;
		this.noProgressCheckPosition = current;
		this.noProgressCheckTicks = 0;
		return noProgress;
	}

	private boolean arrivedNearPlayer() {
		Player player = Targeting.nearestPlayer(this.self);
		return player != null && this.self.distanceTo(player) <= SemanticBands.ARRIVAL_DISTANCE;
	}

	private boolean withinMeleeRange() {
		Player player = Targeting.nearestPlayer(this.self);
		return player != null && this.self.distanceTo(player) <= SemanticBands.MELEE_RANGE;
	}

	/** Whether a held stare should give up on being noticed and let its control.while end - not a
	 * general wave-ending mechanic (see WendigoManager.checkForcedWaveEnd, which used to also force
	 * the whole wave to end here and no longer does), just this one waiting-for-detection step, so
	 * whatever comes after it in the plan (a torch to break, a sound cue, its own despawn) still gets
	 * to run instead of the wave being killed outright. */
	private boolean isPlayerTooFarAwayToKeepStaring() {
		Player player = Targeting.nearestPlayer(this.self);
		return player == null || this.self.distanceTo(player) > STARING_DISENGAGED_DISTANCE;
	}

	/** Linearly interpolates LUNGE_SAFE_LIGHT_RADIUS_MIN (at combat.lunge_attack's own TierGates
	 * unlock threshold) up to LUNGE_SAFE_LIGHT_RADIUS_MAX (at 100% severity) - the wendigo is allowed
	 * to commit to a lunge further from real darkness the more established this player's relationship
	 * with it already is, rather than a single fixed tolerance regardless of stage. */
	private double lungeSafeLightRadius() {
		int minPercent = TierGates.minPercentFor("combat.lunge_attack");
		int clamped = Math.clamp(this.severityPercent, minPercent, 100);
		double fraction = (double) (clamped - minPercent) / (100 - minPercent);
		return LUNGE_SAFE_LIGHT_RADIUS_MIN + (LUNGE_SAFE_LIGHT_RADIUS_MAX - LUNGE_SAFE_LIGHT_RADIUS_MIN) * fraction;
	}

	// Roll bounds for how many dismount attempts the player needs before a forced ride actually lets
	// go of them - see beginForcedRide/updateForcedRide. Scales with severity: the roll floor never
	// drops below the min, but the ceiling it can reach climbs from the min (0% severity, so it's
	// always exactly the min) up to the max (100%) - a well-established relationship with the dark
	// makes it that much harder to shake off once caught.
	private static final int RIDE_ESCAPE_ATTEMPTS_MIN = 5;
	private static final int RIDE_ESCAPE_ATTEMPTS_MAX = 20;
	// Dealt once, only if the wave concludes (a real despawn, not a forced/backstop wave-end) while
	// still forcing the ride - see completeWave.
	private static final float FORCED_RIDE_DESPAWN_DAMAGE = 20.0F;
	private static final String RIDE_ESCAPE_HINT = "Spam SHIFT to break free!";
	// Minimum real time a forced ride has to run before completeWave is allowed to deal the despawn
	// damage - see completeWave's own comment for the real bug this guards against (catching the
	// player and completing the wave the same tick, e.g. when combat.chase is the last plan step and
	// the wendigo is already standing at a despawn candidate the instant it catches them).
	private static final int MIN_RIDE_TICKS = 100; // 5s

	private boolean forcingRide;
	private int rideEscapeAttempts;
	private int rideEscapeThreshold;
	private int rideStartTick;
	// Who's actually being carried - getFirstPassenger() goes empty the instant they dismount, so a
	// separate reference is needed to know who to force back on.
	private Player ridingPlayer;

	/** True while a player is currently a forced rider - see WendigoManager.overrideIntoChaseUntilLight,
	 * which must not restart internal.chase_until_light from scratch while this is already true (that
	 * would call beginForcedRide a second time on someone already caught, discarding despawn progress
	 * and re-rolling a fresh escape/grace-period pair every time it happens). */
	public boolean isForcingRide() {
		return this.forcingRide;
	}

	/**
	 * combat.lunge_attack/combat.chase's "caught them" resolution, replacing straight damage-on-
	 * contact: mounts the player on the wendigo (force=true bypasses the normal can-ride checks - a
	 * hostile mob isn't normally rideable) instead of hurting them immediately, simulating a pickup.
	 * Whatever the plan does next (typically movement.retreat_with_fallback) carries the rider along
	 * for free via ordinary vehicle/passenger mechanics - see updateForcedRide for the escape side of
	 * this, and completeWave for what happens if the wendigo reaches its despawn point first.
	 */
	private void beginForcedRide(Player player) {
		this.forcingRide = true;
		this.ridingPlayer = player;
		this.rideStartTick = this.self.tickCount;
		this.rideEscapeAttempts = 0;
		int ceiling = RIDE_ESCAPE_ATTEMPTS_MIN
			+ (int) Math.round((RIDE_ESCAPE_ATTEMPTS_MAX - RIDE_ESCAPE_ATTEMPTS_MIN) * (Math.clamp(this.severityPercent, 0, 100) / 100.0));
		this.rideEscapeThreshold = RIDE_ESCAPE_ATTEMPTS_MIN + this.self.getRandom().nextInt(ceiling - RIDE_ESCAPE_ATTEMPTS_MIN + 1);
		player.startRiding(this.self, true, true);
		applyRideBlindness(player);
		sendRideEscapeHint(player);
		debugSay("picked up the player - forcing a ride (escapes after " + this.rideEscapeThreshold + " dismount attempt(s))");
	}

	/**
	 * Polled every tick regardless of the current action (see tick()) - a forced ride spans whatever
	 * the plan does after the lunge/chase that started it (typically a despawn move), not just one
	 * action. Vanilla dismounts a rider the instant they press shift; that's the only thing that can
	 * make ridingPlayer.getVehicle() stop being this wendigo while forcingRide is still true, so a
	 * dismount observed here is read as one escape attempt. Force them back on until the rolled
	 * threshold is reached, then actually let them go.
	 */
	private void updateForcedRide() {
		if (!this.forcingRide) {
			return;
		}
		if (this.ridingPlayer == null || !this.ridingPlayer.isAlive()) {
			this.forcingRide = false;
			return;
		}
		if (this.ridingPlayer.getVehicle() == this.self) {
			// Still aboard - keep the blindness topped up (short duration, reapplied every tick, so it
			// can never outlast the ride if something ends it uncleanly).
			applyRideBlindness(this.ridingPlayer);
			return;
		}
		this.rideEscapeAttempts++;
		if (this.rideEscapeAttempts >= this.rideEscapeThreshold) {
			debugSay("player escaped the forced ride after " + this.rideEscapeAttempts + " dismount attempt(s)");
			this.forcingRide = false;
			this.ridingPlayer.removeEffect(MobEffects.BLINDNESS);
			return;
		}
		this.ridingPlayer.startRiding(this.self, true, true);
		applyRideBlindness(this.ridingPlayer);
		sendRideEscapeHint(this.ridingPlayer);
	}

	/** Refreshed every tick rather than applied once with a long duration - a short duration that
	 * outlives one tick by only a small margin means the effect can never meaningfully outlast the
	 * ride itself even if some path forgets to explicitly remove it. */
	private static final int RIDE_BLINDNESS_REFRESH_TICKS = 20; // 1s

	private static void applyRideBlindness(Player player) {
		player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, RIDE_BLINDNESS_REFRESH_TICKS, 0, false, false));
	}

	/** Action-bar reminder of how to escape a forced ride - Player.sendSystemMessage's overlay
	 * overload only exists on ServerPlayer, which every real player is at runtime here (this all runs
	 * server-side), but Targeting.nearestPlayer's declared type is the more general Player. */
	private static void sendRideEscapeHint(Player player) {
		if (player instanceof ServerPlayer serverPlayer) {
			serverPlayer.sendSystemMessage(Component.literal(RIDE_ESCAPE_HINT), true);
		}
	}

	private boolean withinTorchBreakRange() {
		return this.currentTorchTarget != null
			&& this.self.blockPosition().distSqr(this.currentTorchTarget) <= SemanticBands.TORCH_BREAK_RANGE * SemanticBands.TORCH_BREAK_RANGE;
	}

	/** Destroys exactly the one torch combat.break_torch pathfound to - no connectivity/cluster
	 * destruction (removed: a plan wanting several torches gone just issues combat.break_torch more
	 * than once, each call re-targeting whatever's now nearest - see nearestTorch). See
	 * LightSourceScanner.destroyByWendigo for the coal+stick drop (torches only). */
	private void performTorchBreak() {
		if (this.currentTorchTarget == null || !(this.self.level() instanceof ServerLevel serverLevel)) {
			return;
		}
		LightSourceScanner.destroyByWendigo(serverLevel, this.currentTorchTarget, this.self);
	}

	// Own dedicated radius rather than reusing SemanticBands' "far" proximity band (30 - a
	// player-distance descriptive value, not tuned for this) - needs to comfortably reach whatever
	// DarkSpotScanner.findSpotDimSpots found near the spawn spot the wendigo is standing at,
	// including its outermost tier (35, which this matches exactly - also LightSourceScanner's own
	// hard cap, so going any higher wouldn't reach further anyway).
	private static final double TORCH_BREAK_SEARCH_RADIUS = 35.0;

	/** Nearest known light source to the wendigo's current position, or null if none is nearby. */
	private BlockPos nearestTorch() {
		var torches = LightSourceScanner.findLightSources(this.self.level(), this.self.blockPosition(), TORCH_BREAK_SEARCH_RADIUS, 1);
		return torches.isEmpty() ? null : torches.get(0);
	}

	/** Nearest edge-of-light waypoint reachable from here, toward the nearest player - or null if none is nearby. */
	private BlockPos nearestDimSpot() {
		Player player = Targeting.nearestPlayer(this.self);
		if (player == null) {
			return null;
		}
		var dimSpots = DarkSpotScanner.findDimSpots(this.self.level(), this.self.blockPosition(), player.blockPosition(), 1);
		return dimSpots.isEmpty() ? null : dimSpots.get(0);
	}

	private BlockPos retreatDestination(JsonObject step) {
		if ("stored".equals(step.get("source").getAsString()) && this.self.getStoredDarkLocation() != null) {
			return this.self.getStoredDarkLocation();
		}
		return PlanGeometry.findDarkSpot(this.self, SemanticBands.searchRadiusBlocks("near"));
	}
}
