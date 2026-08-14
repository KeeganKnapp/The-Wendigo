package com.wendigo.plan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import com.nyfaria.awcapi.entity.movement.DirectionalPathPoint;

import com.wendigo.WendigoMod;
import com.wendigo.advancement.WendigoAdvancements;
import com.wendigo.debug.WendigoDebug;
import com.wendigo.entity.WendigoEntity;
import com.wendigo.sound.WendigoSounds;
import com.wendigo.spatial.CaveScaleScanner;
import com.wendigo.spatial.CaveScaleScanner.CaveScale;
import com.wendigo.WendigoTuningConfig;
import com.wendigo.spatial.DarkSpotScanner;
import com.wendigo.spatial.LightSourceScanner;
import com.wendigo.spatial.SoulLightScanner;

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

	// The user's own explicit "always" rule: at stage 1 specifically, regardless of what the model's
	// own plan/global_rules array says, if the player closes at least SemanticBands.
	// approachCoverageFraction("medium") of the distance they were at when THIS wave began, the plan
	// ends - same engine-enforced-guarantee precedent global_rules itself was built on (a model can
	// forget to author a rule; this can't be forgotten). Deliberately NOT implemented by reusing
	// predicate.player_approaching - that predicate's own schema doc is explicit that it "only
	// meaningful inside a control.while... used inside control.if or a global_rule (neither of which
	// loop) it always reads as though nothing has been covered yet", since its baseline is the
	// currently-running while loop's own start distance, not anything wave-scoped. This needs a
	// baseline that exists independent of whatever the plan happens to be doing at any given moment,
	// so it gets its own: the distance to the nearest player at the exact moment this wave started
	// (stage1ApproachBaselineDistance, NaN if not stage 1 or nobody was nearby to measure from),
	// checked every tick in tick() right alongside checkGlobalRules via checkStage1ApproachRule, one-
	// shot per wave (stage1ApproachRuleFired) the same way each ordinary global rule already is.
	private double stage1ApproachBaselineDistance = Double.NaN;
	private boolean stage1ApproachRuleFired;
	// Mirrors WendigoEntity.STAGE1_MAX_PERCENT exactly (severityPercent < this = stage 1) - duplicated
	// rather than shared since PlanRunner has no dependency on WendigoEntity's own stage bookkeeping
	// otherwise, same reasoning WendigoManager.WENDIGO_MAX_HEALTH duplicates ModEntities' own attribute
	// value rather than reaching across packages for one constant.
	private static final int STAGE1_MAX_PERCENT = 20; // exclusive

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
	// Consecutive ticks the TARGET has continuously satisfied at least this look-angle band - see
	// updateLookStreaks (polled every tick, not just during an active control.while, so a streak
	// that started before a stare-hold loop even began still counts) and PlanPredicates.
	// isLookedAtByTargetGraduated (what actually reads these). Reset to 0 the instant the target
	// doesn't currently satisfy that band.
	private int inViewStreakTicks;
	private int cornerOfEyeStreakTicks;
	// How many times the current control.while has actually run a body containing an approach-type
	// step (movement.approach/approach_band) - capped at MAX_WHILE_BODY_APPROACHES.
	// Real logs showed a "creep closer and stare" loop running approach_band many times in
	// a row (each one resolving near-instantly once already close), producing a lot of movement and
	// essentially zero time actually holding still and staring - once the cap is hit, the loop falls
	// back to synthesized holds (see holdStep) for any further iterations instead of repeating the
	// body, same mechanism the stare-hold extension already uses.
	private int whileApproachesRun;
	private static final int MAX_WHILE_BODY_APPROACHES = 1;

	private JsonObject currentAction;
	private int actionDeadlineTick;

	// Whether this run has a despawn phase at all - false only for /wendigo plantest's raw
	// debug-injected plans (see startRaw), which have no wave lifecycle backing them to flee/despawn
	// into. Every real wave (spawnWave/engageExistingWendigo/the hand-built override plans) always
	// has one.
	private boolean despawnEnabled = true;
	// How many live despawn/retreat attempts have been tried this wave - each one re-resolves a
	// fresh target, seeded from wherever the entity actually is at that moment (naturally a
	// different search each retry, since the entity moved), rather than walking a pre-scanned
	// candidate list. Bounded so a wave can't retry forever if genuinely nothing is ever found.
	private int despawnAttemptCount;
	private static final int MAX_DESPAWN_ATTEMPTS = 4;
	private boolean despawnSucceeded;
	private BlockPos currentDespawnTarget;
	// "Is it OK to actually withdraw yet" tracking for readyToWithdraw - separate from
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
	// Real bug found live: isMakingNoProgress ending ONE movement action only ends that action -
	// nothing stops the plan from immediately trying another move that runs straight back into the
	// exact same physical obstruction (e.g. a solid 1-block ledge a narrow trench needs to be
	// climbed over, which AWCAPI's own climbing didn't reliably initiate from in a live test), giving
	// up and retrying in a loop that looks identical to "never resolving" from outside - nothing
	// backstops that short of WendigoManager's own 4-minute hard wave timeout. Counts CONSECUTIVE
	// plain-movement actions that specifically ended via noProgress (not stuckInLight, timeout, or a
	// genuine arrival - see isActionDone's own accounting), reset the instant one resolves any other
	// way. isRepeatedlyStuck() lets WendigoManager's checkForcedWaveEnd escalate to a real forced
	// end (-> relocateOrDiscard-style teleport back to orbit) well before that 4-minute backstop,
	// without this engine needing to understand WHY the geometry defeated it.
	private int consecutiveNoProgressGiveUps;
	private static final int NO_PROGRESS_GIVEUP_ESCALATION_THRESHOLD = 3;
	// --- Orbit (no active plan) state ---------------------------------------------------------
	// See startOrbit/tickOrbit. Mutually exclusive with an active plan - start() always clears
	// orbiting, startOrbit always clears any in-flight plan state - never concurrent.
	private boolean orbiting;
	private boolean orbitTargetLost;
	private int orbitRecheckTicks;
	private static final int ORBIT_RECHECK_INTERVAL_TICKS = 20; // ~1s
	// See tickOrbit's in-band branch - even once satisfied, periodically re-picks a different live
	// in-band position instead of holding the same spot forever, so different vantage points/torches
	// come into play across a single orbit rather than every engagement seeing the exact same angle.
	// Deliberately NOT reset by startOrbit (see its own comment) - self.tickCount keeps advancing
	// through engagements too, so a deadline that lapses mid-plan is honored (wandering promptly)
	// the moment orbit resumes and next reads in-band, instead of every return-to-orbit handing out
	// a brand new full-length delay and effectively starving this of ever firing.
	private int nextOrbitWanderTick;
	private static final int ORBIT_WANDER_MIN_TICKS = 300; // 15s
	private static final int ORBIT_WANDER_MAX_TICKS = 900; // 45s
	// The user's own explicit "buffer zone between wanders so that he's not always moving after every
	// next move" request: every time nextOrbitWanderTick comes up, this is the chance the wander is
	// actually skipped (the timer just re-rolls, the wendigo stays put) instead of always picking a
	// fresh spot and walking there. Without this, EVERY wander deadline unconditionally moved it,
	// reading as constant idle fidgeting rather than genuinely settling somewhere for a while - see
	// WendigoTuningConfig.orbitWanderSkipChance, editable at config/wendigo-tuning.json.
	// Same windowed snapshot-and-compare technique as isMakingNoProgress above, just tracked over a
	// much longer fuse and only while actively navigating toward a waypoint (holding position
	// in-band would otherwise also look "stuck" under a naive displacement check) - orbit isn't
	// ending an action the instant one window comes up short, it's deciding whether to give up on
	// the current waypoint attempt entirely and let WendigoManager teleport-relocate instead.
	private Vec3 orbitStuckCheckPosition;
	private int orbitStuckCheckTicks;
	private int orbitStuckWindowsFailed;
	private static final int ORBIT_STUCK_WINDOW_TICKS = 40; // ~2s per window
	private static final double ORBIT_STUCK_DISTANCE_SQR = 0.25;
	private static final int ORBIT_TRAPPED_WINDOWS = 5; // ~10s total across failed windows
	// The user's own explicit "10 blocks or whatever" - how far past whichever band edge is nearer the
	// urgency ramp travels before capping at ORBIT_URGENT_SPEED_MULTIPLIER. Flat rather than scaled by
	// cave scale/band width - "how far outside the band is genuinely urgent" reads the same regardless
	// of how tight or loose the current band happens to be.
	private static final double ORBIT_SPEED_RAMP_DISTANCE = 5.0;
	// The user's own explicit target ceiling - notably above even SemanticBands.speedMultiplier("fast")
	// (1.75), since this is meant to read as real "hurrying back into position" urgency, not just
	// another semantic speed choice.
	private static final double ORBIT_URGENT_SPEED_MULTIPLIER = 1.75;
	// The user's own explicit "drop his speed when he's orbiting in his band down to 1.0" - a
	// deliberate step down from SemanticBands.speedMultiplier("normal") (1.5), specifically for the
	// occasional in-band wander move (see tickOrbit's in-band branch) - a slower, more idle amble while
	// already comfortably in-band, contrasted against the urgency ramp above for actually being outside it.
	private static final double ORBIT_IN_BAND_SPEED_MULTIPLIER = 1.25;
	// See startReturnToOrbit/isReturningToOrbit/tickReturnToOrbit - the post-grab "walk to a fresh
	// spot before resuming orbit" transition.
	private boolean returningToOrbit;
	private ServerPlayer returnToOrbitTarget;
	// combat.chase bookkeeping - see isChaseResolved/maybeDestroyNearbyTorches.
	private int chaseUnreachableTicks;
	private static final int CHASE_GIVE_UP_TICKS = 100; // ~5s of sustained unreachability
	private static final int CHASE_MAX_TICKS = 600; // 30s hard backstop even if never fully unreachable
	private static final double CHASE_TORCH_RADIUS = 10.0;
	private static final int CHASE_TORCH_MAX_PER_SCAN = 6;
	private static final int CHASE_TORCH_SCAN_INTERVAL_TICKS = 10; // throttle the light-source scan
	// The user's own explicit hardcoded combat.break_torch cutoff - see that action's own dispatch.
	private static final float BREAK_TORCH_MAX_HEALTH_FRACTION = 0.75F;
	// The user's own explicit hardcoded posture.stare cutoff at stage 5 - see that action's own dispatch.
	private static final float STAGE5_STARE_MAX_HEALTH_FRACTION = 0.5F;
	// combat.lunge_attack precondition - don't commit to a lunge with nowhere dark enough nearby to
	// retreat to afterward. Scales with severity (see lungeSafeLightRadius) rather than a single
	// fixed value - these are its bounds.
	private static final double LUNGE_SAFE_LIGHT_RADIUS_MIN = 10.0; // at lunge's own unlock threshold
	private static final double LUNGE_SAFE_LIGHT_RADIUS_MAX = 20.0; // at 100% severity
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
	// artificially locked onto the player mid-approach - correct for the visual, but a "creep closer
	// and stare" control.while's own body is typically movement.approach_spot, so its
	// very first iteration was silently clearing isStaring() and permanently disabling the hold
	// extension for the rest of that loop, even though the model clearly still intends to be staring
	// (real logs showed exactly this: a while loop burning through its whole iteration budget in a
	// few seconds despite the player never having looked). This flag only ever changes on an explicit
	// posture.stare step, never on movement, so the hold logic can tell "the model wants a stare held
	// here" apart from "is the rig visually locked onto the player at this exact instant".
	private boolean modelIntendedStaring;
	// The type of whichever action most recently finished (see tick()'s own "String finishedType"
	// Non-zero while a stare must keep holding regardless of what the plan/control.while would
	// otherwise do. Originally only applied right after a resolved combat.teleport(destination=
	// in_view) (the user's own explicit original request) - broadened to EVERY fresh
	// posture.stare(enabled=true) session after a real live bug: a plan that went straight from a
	// different combat.teleport destination type (also a similarly-instant reposition) into
	// posture.stare then immediately into sound.breathe/
	// movement.retreat_with_fallback, with no control.while in between, showed the stare for
	// effectively zero real time - the old narrower trigger only ever covered the teleport_in_view
	// pairing specifically, not this equally-plausible one. Now set on every fresh stare start
	// regardless of what ran immediately before it - the user's own later "make sure it's 3 seconds
	// minimum wait time unless something else happens" generalization; "something else" is the
	// existing early-exit escape hatches (isPlayerTooFarAwayToKeepStaring, WendigoManager's own
	// disengage/proximity/wave-timeout backstops), not a narrower trigger condition. Enforced in
	// nextActionStep's own blanket check at the very top (before any real step - from topLevelSteps, a
	// control.if branch, OR a control.while body alike - is ever pulled), so it holds regardless of
	// whether the model's own plan even uses a control.while at all. 0 means no minimum is currently
	// active.
	private int stareMinimumEndTick;
	// "3 seconds" - the user's own explicit number - see stareMinimumEndTick's own comment.
	private static final int STARE_MIN_HOLD_TICKS = 60;
	private boolean waveComplete;

	// See startAction's own resets - tickCount as of whenever the CURRENT action started, used to
	// compute how long it actually took once it finishes (see reEvaluateStepLog below). Not the same
	// thing as actionDeadlineTick (a forward-looking timeout), this is the backward-looking start mark.
	private int currentActionStartTick;
	// control.re_evaluate's own "steps that have run so far, and how long each took" report - persists
	// for the WHOLE wave (only cleared in startInternal, a genuinely fresh wave), never cleared when a
	// sub-plan gets spliced in (resumeFromReEvaluate), so a SECOND re-evaluate later in the same wave
	// still reports the full history back to the true start, not just since the last splice. Formatted
	// strings ("posture.stare (long)"), not a structured type - this only ever gets joined into prompt
	// text (see WendigoManager's own re-evaluate request assembly), nothing else reads it.
	private final List<String> reEvaluateStepLog = new ArrayList<>();
	// The user's own explicit correction: control.re_evaluate is for checking whether something that's
	// ALREADY been tried (a held stare, an ongoing chase) is actually working - not for immediately
	// scrapping a freshly-handed-back plan before any of it has run. Set true the moment any MEANINGFUL
	// action (see isMeaningfulActionType) STARTS this wave (see startAction's own comment on why this
	// has to be start-based, not completion-based: most instant actions - posture.stare included, the
	// user's own explicit example - never hold long enough to reach the currentAction/isActionDone
	// "finished" bookkeeping at all, so a completion-based hook would silently never fire for them).
	// Reset in BOTH startInternal AND resumeFromReEvaluate - unlike reEvaluateStepLog (which
	// deliberately keeps accumulating across splices, since it's a HISTORY report the next re-evaluate
	// request wants in full), this is a gate on the plan CURRENTLY running, and a freshly-spliced
	// sub-plan is itself "an already made plan" in exactly the sense the user's original request meant -
	// real live evidence confirmed the gap: with this wave-scoped instead of per-splice, a sub-plan
	// whose own first step was ALSO control.re_evaluate fired immediately (2.6s after the previous one
	// resolved, no real action in between) purely because SOME meaningful action had happened earlier
	// in the wave, before the first re-evaluate. Per-splice reset closes that; the field's own name
	// ("ThisWave") is now slightly imprecise (it really means "since the current plan/sub-plan began")
	// but kept rather than a wider rename, since every OTHER field genuinely is wave-scoped for real.
	private boolean meaningfulActionCompletedThisWave;
	// Set the instant a control.re_evaluate action starts (see its own startAction case), cleared either
	// by resumeFromReEvaluate (a real sub-plan came back) or cancelReEvaluate (the request errored or
	// came back stale) - WendigoManager polls isReEvaluateRequested() every tick (same "private field +
	// public getter, polled externally" idiom isOrbitTargetLost/isWaveComplete/etc. already use) to know
	// when to actually fire the LLM call. isActionDone() holds (returns false) for control.re_evaluate
	// the whole time this stays true.
	private boolean reEvaluateRequested;
	// Real LLM round-trips can take far longer than the generic ACTION_TIMEOUT_TICKS (200/10s) - this
	// overrides that deadline the same way CHASE_MAX_TICKS already does for combat.chase/
	// internal.chase_until_light. If a re-evaluate request never comes back within this window, the
	// generic timedOut path in isActionDone() resolves it as done anyway (no sub-plan applied) and the
	// plan just continues into whatever originally followed control.re_evaluate - the same "never hang a
	// plan indefinitely" degradation every other resolver in this file already follows.
	private static final int RE_EVALUATE_TIMEOUT_TICKS = 1200; // 60s

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
	// Two separate facts, not one - see EncounterHistory.Entry's own doc comment for why conflating
	// them was a real bug (an escaped catch used to read identically to never having caught the player
	// at all). grabbedSuccessfully is set the instant beginForcedRide actually mounts the player;
	// dealtDamage only when resolveRiderOnEnd's own darkness/fair-chance despawn damage lands - a real
	// catch can still end with dealtDamage staying false.
	private boolean grabbedSuccessfully;
	private boolean dealtDamage;
	private boolean reachedDeadStare;
	private boolean withdrewInstantly;
	private List<String> planShape = List.of();
	// Hidden per-wave goal-progress counters for WendigoProgressionTracker (see WendigoManager's own
	// wave-end handling) - deliberately never surfaced to the model anywhere (prompt text, schema),
	// per explicit design: telling it "the goal is X" would make it only ever do X. successfulStareCount
	// counts once per posture.stare(enabled=true) SESSION that gets noticed at all (see
	// currentStareCounted, reset whenever a fresh session begins), not once per tick held.
	private int successfulStareCount;
	private int torchBreakCount;
	private int lungeAttemptCount;
	// Counts every sound.ambient_cue that actually played (see playAmbientCue) - stage 1's own new
	// "noises" half of its compound goal (see WendigoProgressionTracker), the user's own explicit
	// "4 stares AND 4 noises" request. Any cue value counts, not just "stare" specifically.
	private int soundCueCount;
	// Counts every sound.breathe that actually played within BREATHE_SUCCESS_RADIUS of any player
	// (see playBreathe) - the user's own explicit "1 successful breathe" goal on stages 2-4 (see
	// WendigoProgressionTracker - sound.breathe isn't even offered at stage 1 anymore), independent of
	// whatever else that stage's own progress already tracks. A breathe played from farther away still
	// plays (never a no-op), it just doesn't count
	// toward this - same "always plays, only sometimes counts" shape torchBreakCount/
	// successfulStareCount above already have for their own success conditions.
	private int successfulBreatheCount;
	// The soul-light progression redesign - the user's own explicit "track if the target was within a
	// safe distance to a soul light... when a task was satisfied" request. Bumped once per task-
	// counter increment above (successfulStareCount/torchBreakCount/lungeAttemptCount/soundCueCount/
	// successfulBreatheCount/grabbedSuccessfully - see recordSoulLightTally's own call sites), checking
	// SoulLightScanner.isNearSoulLight at that exact moment. Fed into WendigoProgressionTracker.
	// ActiveRun via WendigoManager's own wave-end accounting (same shape as the counters above),
	// where the whole RUN's cumulative tally (not just this one wave's) finally decides whether
	// completing the run's goal advances or regresses the stage - see
	// WendigoProgressionTracker.resolveRunOutcome.
	private int tasksNearSoulLight;
	private int tasksNotNearSoulLight;
	private boolean currentStareCounted;

	public PlanRunner(WendigoEntity self) {
		this.self = self;
		// One-time initial roll (see the field's own comment for why startOrbit no longer re-rolls
		// this on every return to orbit) - without this, a freshly-constructed entity's first-ever
		// orbit would read the field's default 0 and always wander immediately the instant it first
		// settles in-band.
		this.nextOrbitWanderTick = rollOrbitWanderDelay();
	}

	/**
	 * Replaces whatever's currently running with this newly received plan (the full top-level
	 * WendigoActionPlan object - both "plan" and "global_rules" are pulled from it here). Once the
	 * body is exhausted, the runner automatically attempts a live despawn move before signaling
	 * completion via {@link #isWaveComplete()} - the caller (WendigoManager) polls that to know when
	 * it's safe to remove the entity.
	 */
	public void start(JsonObject fullPlan, int severityPercent, boolean tierGatingBypassed) {
		startInternal(fullPlan, severityPercent, tierGatingBypassed, true, true);
	}

	/** /wendigo plantest's own entry point - same as start() but with no despawn phase at all,
	 * matching its own "run this plan body raw, independent of the wave system" purpose (see
	 * WendigoEntity.debugInjectPlan) - there's no wave lifecycle backing it to flee/despawn into. */
	public void startRaw(JsonObject fullPlan) {
		startInternal(fullPlan, 100, true, false, true);
	}

	/**
	 * WendigoManager's own hardcoded, circumstance-triggered overrides (darkness-overstay
	 * internal.chase_until_light, a too-close bounded lunge) - interrupts whatever this wave was
	 * already doing, WITHOUT resetting the wave's own already-earned task progress the way an
	 * ordinary start() would (resetProgress=false - see startInternal's own new parameter). The
	 * user's own explicit "plans that are hardcoded and dependent on specific circumstances
	 * (darkness, too close) should not affect the current run's tasks" request. Real bug this fixes:
	 * neither override ever went through WendigoManager.tickLevel's own normal wave-end accounting
	 * before firing (they interrupt mid-tick, not at a clean wave boundary), so whatever this wave
	 * had already earned before being interrupted (successfulStareCount, torchBreakCount, a real
	 * grabbedSuccessfully/dealtDamage/reachedDeadStare fact, planShape's own "story so far") was
	 * silently wiped by startInternal's own ordinary reset, well before the (now-overridden) wave
	 * eventually did end and get accounted for - the earlier fact simply never got recorded anywhere.
	 * Always bypasses tier gating (these overrides are engine-synthesized, never model-authored, so
	 * there's nothing to gate) and always keeps despawnEnabled=true (an override always eventually
	 * needs to resolve back into a real despawn attempt, same as an ordinary wave). */
	public void startOverride(JsonObject plan, int severityPercent) {
		startInternal(plan, severityPercent, true, true, false);
	}

	private void startInternal(JsonObject fullPlan, int severityPercent, boolean tierGatingBypassed,
			boolean despawnEnabled, boolean resetProgress) {
		// Generalizes overrideIntoChaseUntilLight's existing "interrupt whatever's currently running,
		// reuse the same still-alive entity" idiom to also interrupt orbit, not just an already-active
		// plan - the one line that lets WendigoManager start a plan on an orbiting entity the exact
		// same way it already restarts one on a mid-plan entity.
		this.orbiting = false;
		// Defensive, same reasoning as orbiting above - every real caller already guards against
		// starting a fresh plan mid-carry (checkUnconditionalGrab/beginEngagement's staleness checks),
		// but a stuck carryingAway=true left over from anywhere else would make tick() permanently
		// stick on tickCarryFlee (checked before topLevelSteps==null) and silently swallow this new
		// plan forever, which is a much worse failure mode than any of the other flags being stale.
		this.carryingAway = false;
		this.grabLocation = null;
		this.self.getNavigation().stop();
		this.self.setNavigationFailed(false);
		this.self.setSeverityPercent(severityPercent);
		this.topLevelSteps = fullPlan.getAsJsonArray("plan");
		this.topIndex = 0;
		this.severityPercent = severityPercent;
		this.tierGatingBypassed = tierGatingBypassed;
		this.despawnEnabled = despawnEnabled;
		this.despawnAttemptCount = 0;
		this.despawnSucceeded = false;
		this.currentDespawnTarget = null;
		this.consecutiveNoProgressGiveUps = 0;
		// The user's own explicit "hardcoded, circumstance-triggered plans shouldn't affect the
		// current run's tasks" request - see startOverride's own doc comment for the real bug this
		// resetProgress=false path fixes. Everything else in this method still resets unconditionally
		// even for an override - only these facts/counters (and planShape, below) represent real
		// earned progress toward this wave's own outcome/goal, worth preserving across an interrupt;
		// everything else here is plan-EXECUTION state, which genuinely should restart fresh either way.
		if (resetProgress) {
			this.grabbedSuccessfully = false;
			this.dealtDamage = false;
			this.reachedDeadStare = false;
			this.withdrewInstantly = false;
			this.successfulStareCount = 0;
			this.torchBreakCount = 0;
			this.lungeAttemptCount = 0;
			this.soundCueCount = 0;
			this.successfulBreatheCount = 0;
			this.tasksNearSoulLight = 0;
			this.tasksNotNearSoulLight = 0;
		}
		this.currentStareCounted = false;
		this.modelIntendedStaring = false;
		this.meaningfulActionCompletedThisWave = false;
		this.stareMinimumEndTick = 0;
		this.reEvaluateRequested = false;
		this.reEvaluateStepLog.clear();
		// Defensive, matching completeWave's own reset - most paths into a fresh plan already went
		// through completeWave first (which now clears this too), but this guards the same "leftover
		// glowing eyes/face-lock" case for whatever path doesn't (e.g. a debug-injected plan straight
		// after another debug-injected plan, bypassing the normal wave-end flow).
		this.self.setStaring(false);
		// Extended (not replaced) when resetProgress is false - same "connect the two" shape
		// resumeFromReEvaluate already established for splicing a sub-plan onto an in-progress wave,
		// so EncounterHistory/the "tasks completed this run" debug line still show the whole real
		// story, including whatever ran before this override took over.
		List<String> shape = resetProgress ? new ArrayList<>() : new ArrayList<>(this.planShape);
		for (var element : this.topLevelSteps) {
			shape.add(element.getAsJsonObject().get("type").getAsString());
		}
		this.planShape = shape;
		this.actionQueue.clear();
		this.activeWhile = null;
		this.whileBaselineDistance = Double.NaN;
		this.inViewStreakTicks = 0;
		this.cornerOfEyeStreakTicks = 0;
		this.currentAction = null;
		JsonElement globalRulesElement = fullPlan.get("global_rules");
		this.globalRules = globalRulesElement != null && !globalRulesElement.isJsonNull()
			? globalRulesElement.getAsJsonArray() : null;
		this.globalRulesFired = new boolean[this.globalRules != null ? this.globalRules.size() : 0];
		// See stage1ApproachBaselineDistance's own field comment - captured once, right here, at the
		// true start of a fresh wave (NOT reset on a re-evaluate splice - resumeFromReEvaluate doesn't
		// touch either field, so both the baseline and its one-shot fired flag stay anchored to the
		// whole wave's real start, same as reEvaluateStepLog already does - unlike
		// meaningfulActionCompletedThisWave, which now IS reset per-splice, see its own field comment).
		this.stage1ApproachRuleFired = false;
		Player stage1BaselinePlayer = severityPercent < STAGE1_MAX_PERCENT ? Targeting.nearestPlayer(this.self) : null;
		this.stage1ApproachBaselineDistance = stage1BaselinePlayer != null
			? this.self.distanceTo(stage1BaselinePlayer) : Double.NaN;
		this.waveComplete = false;
	}

	/** Enters orbit mode: no active plan, just holding roughly SemanticBands.ORBIT_MIN_DISTANCE to
	 * ORBIT_MAX_DISTANCE from target, dark-pathing only (see tickOrbit). Clears any in-flight plan
	 * state the same way start() clears orbiting - the two are mutually exclusive. */
	public void startOrbit(ServerPlayer target) {
		this.self.getNavigation().stop();
		this.self.setNavigationFailed(false);
		this.self.setLockedTarget(target);
		this.topLevelSteps = null;
		this.currentAction = null;
		this.actionQueue.clear();
		this.activeWhile = null;
		// Every path that ends up back in orbit funnels through here - the fresh-spawn case, a
		// relocate/discard-and-respawn, the post-grab return-to-orbit handoff, and (the real bug this
		// specific line fixes) WendigoManager's own forced-backstop wave-end path, which relocates
		// straight into a fresh startOrbit call without ever going through completeWave (see its own
		// "visual stare lock" comment - that reset only covers a NORMAL plan-driven wave end). Live
		// report: the wendigo was seen sitting in orbit mode still visibly staring - facing the player,
		// eyes glowing - which completeWave's own reset can't have caused since orbit only starts here.
		// Resetting it in this one shared entry point, rather than patching every individual caller,
		// guarantees orbit never begins still visually locked onto whoever it was last staring at.
		this.self.setStaring(false);
		this.orbiting = true;
		this.orbitTargetLost = false;
		this.orbitRecheckTicks = 0;
		this.orbitStuckCheckPosition = null;
		this.orbitStuckCheckTicks = 0;
		this.orbitStuckWindowsFailed = 0;
		// nextOrbitWanderTick deliberately NOT reset here - see its own field comment. Resetting it on
		// every return to orbit (a real engagement's plan finishing, a relocate, a post-grab return)
		// handed out a brand new full 15-45s delay each time, and those returns happen often enough
		// in practice that the deadline routinely never survived long enough to actually fire - which
		// is exactly the reported "never wanders, just sits in one spot" bug. Left alone, it just
		// keeps counting across engagements the same way self.tickCount itself does.
	}

	public boolean isOrbiting() {
		return this.orbiting;
	}

	/** One-shot: walk to destination, then enter orbit around target once arrived (or once giving up
	 * - stuck/unreachable - orbit's own waypoint-picking sorts out a better position from there
	 * regardless, no need for this transitional walk to be perfect). This is the "grab landed a hit"
	 * case's second phase - a plan that never landed a hit just calls startOrbit directly instead,
	 * since it's already sitting somewhere dark via its own despawn-move fallback chain. Deliberately
	 * NOT modeled as a synthetic plan/action (unlike internal.despawn_move) - this never touches the
	 * LLM-facing schema at all, so a small dedicated sub-state (mirroring startOrbit/tickOrbit's own
	 * shape) is simpler than wiring a new action type through the whole plan machinery for a purely
	 * engine-internal transition. */
	public void startReturnToOrbit(BlockPos destination, ServerPlayer target) {
		this.orbiting = false;
		this.topLevelSteps = null;
		this.currentAction = null;
		this.actionQueue.clear();
		this.activeWhile = null;
		// Same reset, same reason, as startOrbit's own - this is the OTHER path back toward idle (the
		// post-grab walk before orbit properly resumes), so it needs it too rather than just visibly
		// staring the whole way there.
		this.self.setStaring(false);
		this.self.getNavigation().stop();
		this.self.setNavigationFailed(false);
		this.self.setLightTolerantPathing(false);
		this.returningToOrbit = true;
		this.returnToOrbitTarget = target;
		this.self.getNavigation().moveTo(destination.getX() + 0.5, destination.getY(), destination.getZ() + 0.5,
			SemanticBands.speedMultiplier("normal"));
	}

	/** True while mid-transit toward a post-grab return-to-orbit destination (see
	 * startReturnToOrbit) - WendigoManager treats this the same as isOrbiting() for dispatch
	 * purposes (not mid-plan), but doesn't apply orbit's own target-lost/trapped/re-engage-trigger
	 * checks to it - this is a short, self-contained transition PlanRunner resolves on its own via
	 * tickReturnToOrbit, not a steady state to supervise. */
	public boolean isReturningToOrbit() {
		return this.returningToOrbit;
	}

	/** True once tickOrbit couldn't resolve a target at all (Targeting.nearestPlayer returned null -
	 * the locked target went offline/died/changed level, and no fallback nearest-player exists
	 * either) - WendigoManager polls this to know when to retreat/despawn/re-search rather than
	 * orbit around nothing. */
	public boolean isOrbitTargetLost() {
		return this.orbitTargetLost;
	}

	/** True once ORBIT_TRAPPED_WINDOWS consecutive stuck-tracking windows have all shown no real
	 * progress toward the current waypoint - WendigoManager polls this to know when to give up on
	 * walking there and teleport-relocate instead (the "despawn when trapped/can't move" case). */
	public boolean isOrbitTrapped() {
		return this.orbitStuckWindowsFailed >= ORBIT_TRAPPED_WINDOWS;
	}

	/** True once NO_PROGRESS_GIVEUP_ESCALATION_THRESHOLD consecutive plain-movement actions have
	 * each individually given up specifically via isMakingNoProgress - see
	 * consecutiveNoProgressGiveUps' own field comment. WendigoManager's checkForcedWaveEnd polls this
	 * mid-plan the same way it already polls isOrbitTrapped during orbit, to force-end a wave that's
	 * genuinely wedged against the same piece of geometry instead of trusting the plan to route
	 * around it on its own before the much longer hard wave timeout ever catches it. */
	public boolean isRepeatedlyStuck() {
		return this.consecutiveNoProgressGiveUps >= NO_PROGRESS_GIVEUP_ESCALATION_THRESHOLD;
	}

	/** True once the plan body (and any despawn move) has fully finished. */
	public boolean isWaveComplete() {
		return this.waveComplete;
	}

	/** True while a control.re_evaluate action is holding, waiting on WendigoManager to fire the actual
	 * LLM call and hand back a sub-plan (or give up) - see reEvaluateRequested's own field comment.
	 * Polled every tick by WendigoManager, same idiom as isOrbitTargetLost/isWaveComplete/etc. */
	public boolean isReEvaluateRequested() {
		return this.reEvaluateRequested;
	}

	/** The full "steps run so far, with how long each took" log for THIS wave - see reEvaluateStepLog's
	 * own field comment. Read by WendigoManager once it notices isReEvaluateRequested(), to fold into
	 * the fresh context it sends back to the LLM. */
	public List<String> reEvaluateStepLog() {
		return List.copyOf(this.reEvaluateStepLog);
	}

	/** Gives up on the in-flight re-evaluate request with no sub-plan - the LLM call errored, or came
	 * back stale (this wave/entity is no longer the one it was requested for). The control.re_evaluate
	 * action resolves as done on the very next isActionDone() check, and the plan simply continues into
	 * whatever originally followed it in the (still intact) original plan. */
	public void cancelReEvaluate() {
		this.reEvaluateRequested = false;
	}

	/** The success path: a real sub-plan response came back. Splices it in as the new remainder of the
	 * plan - same field-setting shape startInternal uses for a genuinely fresh plan (plan/global_rules
	 * both fully replaced), EXCEPT the cumulative wave-level goal-progress counters
	 * (successfulStareCount, torchBreakCount, lungeAttemptCount, soundCueCount, successfulBreatheCount)
	 * and reEvaluateStepLog itself are deliberately left untouched - those persist across the splice for
	 * the whole wave, not reset per sub-plan. planShape is EXTENDED (not replaced) with the sub-plan's
	 * own step types - since "control.re_evaluate" itself was already appended to planShape when it
	 * started as an ordinary step, the final list naturally reads [...original, "control.re_evaluate",
	 * ...sub-plan] once the wave ends, connecting the two for EncounterOutcome/EncounterHistory with no
	 * extra bookkeeping - the user's own explicit "connect the plan and subplan at the re-evaluate"
	 * request. */
	public void resumeFromReEvaluate(JsonObject subPlan) {
		this.topLevelSteps = subPlan.getAsJsonArray("plan");
		this.topIndex = 0;
		this.actionQueue.clear();
		this.activeWhile = null;
		this.whileBaselineDistance = Double.NaN;
		JsonElement globalRulesElement = subPlan.get("global_rules");
		this.globalRules = globalRulesElement != null && !globalRulesElement.isJsonNull()
			? globalRulesElement.getAsJsonArray() : null;
		this.globalRulesFired = new boolean[this.globalRules != null ? this.globalRules.size() : 0];
		for (var element : this.topLevelSteps) {
			this.planShape.add(element.getAsJsonObject().get("type").getAsString());
		}
		this.reEvaluateRequested = false;
		// See meaningfulActionCompletedThisWave's own field comment - this sub-plan is itself "an
		// already made plan," so it needs the same "at least one real action before another
		// re-evaluate is allowed" gate a genuinely fresh wave gets, not a free pass just because
		// something happened earlier in the wave before the FIRST re-evaluate fired.
		this.meaningfulActionCompletedThisWave = false;
	}

	/** Snapshot of what actually happened this wave - see EncounterHistory, which is what this
	 * exists for. Safe to call any time (not just after completion); mid-wave it just reflects
	 * whatever's happened so far. */
	public EncounterOutcome outcome() {
		return new EncounterOutcome(this.planShape, this.grabbedSuccessfully, this.dealtDamage, this.reachedDeadStare,
			this.withdrewInstantly, this.successfulStareCount, this.torchBreakCount, this.lungeAttemptCount,
			this.soundCueCount, this.successfulBreatheCount, this.tasksNearSoulLight, this.tasksNotNearSoulLight,
			currentActionType());
	}

	/** Whatever action was actually in flight the instant this snapshot was taken, or null if nothing
	 * was mid-execution (between steps, or the plan hasn't started/already finished) - purely
	 * informational, read by WendigoManager to describe WHERE a forced-ended wave got cut off (see
	 * EncounterOutcome.interruptedDuringAction/EncounterHistory.Entry's own doc comment). Not itself
	 * gated on whether the wave was actually interrupted - the caller only reads it in that case. */
	private String currentActionType() {
		return this.currentAction != null ? this.currentAction.get("type").getAsString() : null;
	}

	public record EncounterOutcome(List<String> planShape, boolean grabbedSuccessfully, boolean dealtDamage,
			boolean reachedDeadStare, boolean withdrewInstantly, int successfulStareCount, int torchBreakCount,
			int lungeAttemptCount, int soundCueCount, int successfulBreatheCount, int tasksNearSoulLight,
			int tasksNotNearSoulLight, String interruptedDuringAction) {

		/** Human-readable "what actually happened" checklist - only the parts that actually happened,
		 * not a wall of false/0 fields - for WendigoManager's own always-on (not verbose-gated) per-run
		 * debug print, the user's own explicit "list what tasks have been completed per run in the
		 * debug, non-verbose mode" request. Distinct from EncounterHistory's own synthesized recap
		 * fallback (WaveContext.describeEntry) - that one's written for the MODEL to read back as prose;
		 * this one's written for a person watching chat mid-session, so it's terser and lists raw
		 * counts rather than narrating them. */
		public String describeCompletedTasks() {
			List<String> tasks = new java.util.ArrayList<>();
			if (this.successfulStareCount > 0) {
				tasks.add(this.successfulStareCount + " successful stare(s)");
			}
			if (this.reachedDeadStare) {
				tasks.add("reached a dead-on stare");
			}
			if (this.torchBreakCount > 0) {
				tasks.add(this.torchBreakCount + " torch(es) broken");
			}
			if (this.lungeAttemptCount > 0) {
				tasks.add(this.lungeAttemptCount + " lunge attempt(s)");
			}
			if (this.soundCueCount > 0) {
				tasks.add(this.soundCueCount + " sound cue(s)");
			}
			if (this.successfulBreatheCount > 0) {
				tasks.add(this.successfulBreatheCount + " breathe cue(s)");
			}
			if (this.grabbedSuccessfully) {
				tasks.add("grabbed the player");
			}
			// See tasksNearSoulLight/tasksNotNearSoulLight's own field comment - this wave's own
			// contribution only, not the whole run's cumulative tally (WendigoManager's own
			// run-completion debug line covers that, once resolveRunOutcome actually decides the
			// advance/regress outcome).
			if (this.tasksNearSoulLight > 0 || this.tasksNotNearSoulLight > 0) {
				tasks.add(this.tasksNotNearSoulLight + " task(s) not near soul light, "
					+ this.tasksNearSoulLight + " near");
			}
			if (this.dealtDamage) {
				tasks.add("dealt damage");
			}
			if (this.withdrewInstantly) {
				tasks.add("withdrew instantly");
			}
			if (this.interruptedDuringAction != null) {
				tasks.add("interrupted during " + this.interruptedDuringAction);
			}
			return tasks.isEmpty() ? "no tasks completed" : String.join(", ", tasks);
		}
	}

	/**
	 * Single place every normal (non-forced) wave-ending path funnels through - sets the completion
	 * flags and resolves a still-forced rider (see resolveRiderOnEnd) before doing so. Also clears
	 * the visual stare lock (self.setStaring(false)) - real playtesting found this never got reset
	 * once a wave ended by falling straight through into orbit (no further movement action ever runs
	 * there to trigger startAction's own "moving away" reset - see modelIntendedStaring's own field
	 * comment for that mechanism), leaving the glowing eyes/face-lock visibly stuck through the whole
	 * orbit period and silently carrying over into whatever the NEXT wave's own first action happened
	 * to be. modelIntendedStaring itself doesn't need clearing here - startInternal already resets it
	 * at the top of every fresh plan, it's just the visual flag that was missing its own reset.
	 */
	private void completeWave(boolean withdrewInstantly) {
		resolveRiderOnEnd();
		this.forcingRide = false;
		// The forced flee from a landed spectral hit (see forceFleeToOrbit) is genuinely over once
		// the wave it triggered actually completes, whichever way that happens - a real despawn-point
		// arrival or an exhausted fallback chain both end up here.
		this.fleeingFromSpectralHit = false;
		this.topLevelSteps = null;
		this.waveComplete = true;
		this.withdrewInstantly = withdrewInstantly;
		this.self.setStaring(false);
	}

	/**
	 * Decides the finishing blow for a still-forced rider, whatever wave-ending path got here -
	 * completeWave (a genuine despawn-point arrival, an exhausted fallback chain, or an immediate
	 * control.despawn) or WendigoManager's own forced backstop discard (see WendigoEntity's own
	 * delegate, called from WendigoManager.tickLevel right before it discards the entity). Just one
	 * live question now, checked right here, right now: has the ride run long enough for a fair
	 * escape chance (hasHadFairRideChance - see its own comment for the original bug this guards
	 * against: catching the player and ending the wave the same tick)? True -> the despawn damage
	 * lands; false -> the rider is just released. Deliberately no darkness requirement anymore - the
	 * user's own explicit simplification: a real-time random countdown alone, not gated on the
	 * player's actual light level, since a player able to light up their own surroundings while being
	 * carried (a separate mod adding a hold-a-torch-for-light mechanic) would otherwise never
	 * accumulate the darkness this used to require, and never take the despawn damage at all - see
	 * hasHadFairRideChance's own updated comment for how the ride-duration countdown itself changed to
	 * match. The wendigo still behaviorally prefers dark spots as its carry-flee/despawn targets (see
	 * beginNextDespawnAttempt/PlanGeometry.findDarkSpot) - only the DAMAGE gate lost its darkness
	 * requirement, not the navigation preference. Always actually dismounts them (stopRiding) before
	 * returning - real playtesting found the player left stuck riding indefinitely (never dropped off)
	 * once this stopped being backed by an actual entity removal/ejectPassengers() every time (see
	 * WendigoEntity.remove) - resolving the ride outcome was never itself enough to end it. Also
	 * clears forcingRide and sets rideJustEnded itself (rather than leaving that to each caller) -
	 * WendigoManager's own backstop-discard path calls this directly with no follow-up reset, and
	 * rideJustEnded is what starts checkUnconditionalGrab's re-grab grace period, needed here just as
	 * much as for a spammed-shift escape (release, drop, and grab_distance can trivially both be true
	 * on the exact same tick). No-ops entirely if nobody's currently a forced rider.
	 * <p>
	 * forcingRide flips to false BEFORE the despawn-damage hurtServer call below, not after - a real,
	 * live-reported bug (stage-5 boss bar sometimes staying up after a death-and-respawn): if THIS
	 * exact hit is what kills the player, ServerLivingEntityEvents.ALLOW_DEATH's handler
	 * (WendigoManager.handleTargetDeath -> endEngagementIfTarget) fires synchronously from inside
	 * this same hurtServer call, and endEngagementIfTarget's own isForcingRide() guard - there
	 * specifically to avoid short-circuiting a still-genuinely-active carry - would otherwise still
	 * read true at that exact instant, silently skipping the whole "hand off to another player /
	 * reset state.stage" reset. That left state.stage (and state.lockedTarget, until AFTER_RESPAWN's
	 * own swap) stale at whatever they were, which is exactly why updateStage5BossBar (keyed purely
	 * off state.stage) kept showing the bar again once the player respawned alive. Flipped AFTER
	 * hasHadFairRideChance() is evaluated, not before - that method's own "!this.forcingRide" branch
	 * means something different (a completely separate "no ride in progress at all" case for other
	 * callers), so evaluating it first preserves its real rideTicks-based check unchanged; nothing
	 * else in this method reads forcingRide again afterward, so flipping it here is otherwise safe.
	 */
	public void resolveRiderOnEnd() {
		if (!this.forcingRide || this.ridingPlayer == null || !this.ridingPlayer.isAlive()) {
			return;
		}
		boolean fairChanceHad = hasHadFairRideChance();
		this.forcingRide = false;
		if (fairChanceHad && this.self.level() instanceof ServerLevel serverLevel) {
			float damage = despawnDamageForCurrentStage();
			this.ridingPlayer.hurtServer(serverLevel, this.self.damageSources().mobAttack(this.self), damage);
			debugSay("despawning with a forced rider still aboard - dealing " + damage + " damage");
			this.dealtDamage = true;
		} else {
			debugSay("despawning with a forced rider still aboard, but too soon after catching them for a "
				+ "fair escape chance - releasing them instead of dealing the despawn damage");
		}
		this.ridingPlayer.removeEffect(MobEffects.BLINDNESS);
		this.ridingPlayer.stopRiding();
		this.rideJustEnded = true;
	}

	/** The despawn-hit raw damage for the current stage - STAGE_DESPAWN_RAW_DAMAGE's own stage-tier
	 * value, unclamped: the user's own explicit "let the wendigo finish the player off at other
	 * [stages] too" reversal of an earlier "can't actually die outside stage 5" restriction - a real
	 * kill is allowed at every stage now, not just the final one. */
	private float despawnDamageForCurrentStage() {
		return STAGE_DESPAWN_RAW_DAMAGE[stageForSeverityPercent(this.severityPercent)];
	}

	public void tick() {
		if (this.carryingAway) {
			tickCarryFlee();
			return;
		}
		if (this.returningToOrbit) {
			tickReturnToOrbit();
			return;
		}
		if (this.orbiting) {
			tickOrbit();
			return;
		}
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
		updateLookStreaks();
		// Hidden goal-progress signal (see successfulStareCount's own field comment) - counts once per
		// stare session the instant it's noticed at all (corner_of_eye, the widest band - matches
		// STAGE_UNDER_20's own "spotted, even peripherally" framing for what counts as a success),
		// latched so a long-held stare doesn't rack up dozens of counts across the ticks it stays true.
		if (this.modelIntendedStaring && !this.currentStareCounted && PlanPredicates.isLookedAtByTarget(this.self, "corner_of_eye")) {
			this.successfulStareCount++;
			this.currentStareCounted = true;
			recordSoulLightTally(Targeting.nearestPlayer(this.self));
		}
		if (checkGlobalRules() || checkStage1ApproachRule()) {
			return; // interrupted this tick - whatever was running got preempted, resolve next tick
		}
		if (this.currentAction != null) {
			if (!isActionDone()) {
				return;
			}
			String finishedType = this.currentAction.get("type").getAsString();
			// See reEvaluateStepLog's own field comment - control.re_evaluate itself never reaches here
			// while genuinely holding (isActionDone() keeps returning false the whole time), so this
			// naturally only logs steps that actually ran to completion, in order, before it.
			this.reEvaluateStepLog.add(finishedType + " ("
				+ SemanticBands.classifyTicksAsDurationBand(this.self.tickCount - this.currentActionStartTick) + ")");
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
			if (this.forcingRide) {
				// A grab just landed (combat.lunge_attack/combat.chase/internal.chase_until_light's own
				// catch resolution, all via beginForcedRide) - immediately preempt whatever the rest of
				// this plan would have queued up next (typically a sound cue then movement.
				// retreat_with_fallback) and carry the catch away instead (see startCarryFlee). The
				// engine now owns this transition unconditionally rather than leaving it to the model's
				// own authored plan, so it can't be skipped by a plan that does something else first.
				startCarryFlee();
				return;
			}
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
			if (!PlanPredicates.evaluate(rule.getAsJsonObject("condition"), this.self, currentPredicateContext())) {
				continue;
			}
			this.globalRulesFired[i] = true;
			debugSay("global rule triggered - condition met, running: " + rule.getAsJsonObject("action"));
			preemptWithAction(rule.getAsJsonObject("action"), "global rule wanted control.despawn but severity is too "
				+ "high to vanish suddenly - fleeing instead");
			return true;
		}
		return false;
	}

	/** The user's own explicit "always" stage-1 rule - see stage1ApproachBaselineDistance's own field
	 * comment for why this needs its own wave-scoped baseline instead of reusing
	 * predicate.player_approaching (which is only meaningful inside a currently-running control.while).
	 * Same one-shot-per-wave/preemption shape as checkGlobalRules, just a single hardcoded, always-on
	 * condition instead of iterating the model's own array - engine-enforced so it can't be skipped by
	 * a plan that never authored an equivalent global_rule itself. */
	private boolean checkStage1ApproachRule() {
		if (this.stage1ApproachRuleFired || Double.isNaN(this.stage1ApproachBaselineDistance)) {
			return false;
		}
		double threshold = this.stage1ApproachBaselineDistance * SemanticBands.approachCoverageFraction("medium");
		boolean approached = false;
		for (Player player : Targeting.nearbyPlayers(this.self)) {
			double covered = Math.max(0.0, this.stage1ApproachBaselineDistance - this.self.distanceTo(player));
			if (covered >= threshold) {
				approached = true;
				break;
			}
		}
		if (!approached) {
			return false;
		}
		this.stage1ApproachRuleFired = true;
		debugSay("stage-1 approach rule triggered - player closed at least " + threshold
			+ " blocks of the " + this.stage1ApproachBaselineDistance + "-block wave-start distance - ending the plan");
		preemptWithAction(despawnStep(), "stage-1 approach rule wanted control.despawn but severity is too "
			+ "high to vanish suddenly - fleeing instead");
		return true;
	}

	private static JsonObject despawnStep() {
		JsonObject step = new JsonObject();
		step.addProperty("type", "control.despawn");
		return step;
	}

	/** Shared preemption shape both checkGlobalRules and checkStage1ApproachRule use: stop navigation,
	 * try to end the wave right here via control.despawn if the action calls for it and severity
	 * allows a sudden vanish, otherwise fall back to a real flee (tooBrightMessage logged in that
	 * case) - then drop whatever was running (current action, queue, active while) and start the
	 * (possibly substituted) action immediately, this same tick. */
	private void preemptWithAction(JsonObject action, String tooBrightMessage) {
		this.self.getNavigation().stop();
		if ("control.despawn".equals(action.get("type").getAsString())) {
			if (isSuddenDespawnAllowed()) {
				// Same handling as a plan-authored control.despawn - ends the wave right here, no
				// travel, no fallback chain.
				completeWave(true);
				return;
			}
			debugSay(tooBrightMessage);
			action = retreatFallbackStep();
		}
		this.currentAction = null;
		this.actionQueue.clear();
		this.activeWhile = null;
		if (startAction(action)) {
			this.currentAction = action;
		}
		// Whether the action finished instantly or is now running, normal plan execution resumes from
		// wherever topIndex/actionQueue naturally continue once it's done - this was a preemption, not
		// a restart.
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

	/** True while this run has a despawn phase at all and hasn't yet exhausted its bounded retry budget. */
	private boolean hasDespawnWork() {
		return this.despawnEnabled && this.despawnAttemptCount < MAX_DESPAWN_ATTEMPTS;
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
	 * else it could do instead (no despawn phase at all, e.g. a raw debug-injected plan - see
	 * startRaw). Anywhere else, a wanted control.despawn gets redirected to a real, visible flee. */
	private boolean isSuddenDespawnAllowed() {
		return this.tierGatingBypassed || this.severityPercent < TierGates.SUDDEN_DESPAWN_MAX_PERCENT || !this.despawnEnabled;
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

	/** Substitute control.while condition for a stare-hold loop caught using predicate.player_distance
	 * (see its own call site's comment) - "keep holding while not yet noticed", the exact idiom the
	 * schema's own control.while example already recommends for a stare, and the same dead_stare band
	 * isPlayerTooFarAwayToKeepStaring/EncounterHistory's own outcome tracking already center the "was
	 * this stare actually seen" question on. */
	private static JsonObject staringHoldCondition() {
		JsonObject lookingAtSelf = new JsonObject();
		lookingAtSelf.addProperty("type", "predicate.player_looking_at_self");
		lookingAtSelf.addProperty("band", "dead_stare");
		JsonObject condition = new JsonObject();
		condition.addProperty("type", "predicate.not");
		condition.add("operand", lookingAtSelf);
		return condition;
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

	// The user's own explicit rule for control.re_evaluate (see meaningfulActionCompletedThisWave's own
	// field comment): "meaningful" means the wendigo actually DID something the player could perceive -
	// movement, combat, staring, ambient sound - not pure control-flow (control.*, which isn't a real
	// action at all) or the two purely-internal/instantaneous bookkeeping steps (timing.wait, a bare
	// pause with no behavior of its own; memory.store_dark_location, invisible to the player). Every
	// other real action type counts, deliberately checked as an exclusion list rather than an inclusion
	// list so a future new action type is meaningful by default instead of silently never counting.
	private static boolean isMeaningfulActionType(String type) {
		return !type.startsWith("control.") && !"timing.wait".equals(type) && !"memory.store_dark_location".equals(type);
	}

	/** True while the currently-executing action is combat.chase or internal.chase_until_light -
	 * WendigoVisual reads this (via WendigoEntity.isChasing) to keep the face glowing/head tracking
	 * the target during an active chase, the same visual treatment a held stare already gets. */
	public boolean isChasing() {
		return this.currentAction != null && isChaseType(this.currentAction.get("type").getAsString());
	}

	/** Broader than isChasing() - true whenever the wendigo is actively going for the player, not just
	 * mid-chase: chase/chase_until_light (isChasing's own check), combat.lunge_attack (the commit-to-
	 * the-grab moment), or already forcing a ride (isForcingRide - the whole carry-flee sequence after
	 * a successful grab, which has no "currentAction" type of its own to match against here). The
	 * user's own explicit "angry crawl" trigger list: "during any chase, lunge, while the player is
	 * being grabbed, and anything else where the wendigo actually goes to capture the player" -
	 * WendigoVisual reads this (via WendigoEntity.isCapturing) to pick CRAWL_ANGRY over CRAWL_NORMAL. */
	public boolean isCapturing() {
		if (this.currentAction != null) {
			String type = this.currentAction.get("type").getAsString();
			if (isChaseType(type) || "combat.lunge_attack".equals(type)) {
				return true;
			}
		}
		return isForcingRide();
	}

	/**
	 * Picks the next despawn target to try - a live "farthest" band resolution seeded from wherever
	 * the entity actually is right now (naturally a different search each retry, since the entity
	 * moved since the last attempt), falling back to a plain away-from-player scan and finally to
	 * despawning in place if genuinely nothing is found - and kicks off movement toward it. Shared by
	 * the automatic terminal despawn phase and the mid-plan movement.retreat_with_fallback step, so
	 * "try again, live, from here" behaves identically either way.
	 */
	/** Fleeing no longer has a model-chosen distance at all - the user's own explicit "just provide a
	 * home-base... no point having him go further or closer than [the orbit range] since he'll
	 * certainly return to it anyway" request: a despawn/retreat attempt now always aims for the
	 * wendigo's own ordinary orbit band for its current stage (SemanticBands.orbitMinDistance/
	 * orbitMaxDistance), real-pathfind-verified via the exact same findReachableOrbitPath helper
	 * tickOrbit's own idle wander/waypoint search already uses - not a separately-chosen band, and no
	 * longer just a geometric guess (findLiveBandPosition's own flood-fill reachability-from-self,
	 * which is a weaker guarantee than a real verified path - see findReachableOrbitPath's own doc
	 * comment). Falls back to the old unverified findDarkestAwayFrom scan, then despawning in place,
	 * only once that budget is exhausted - same "some darkness beats none" last resort as before. */
	private void beginNextDespawnAttempt() {
		this.despawnAttemptCount++;
		BlockPos selfPos = this.self.blockPosition();
		Player nearestPlayer = Targeting.nearestPlayer(this.self);
		BlockPos target = null;
		if (nearestPlayer != null) {
			CaveScale caveScale = CaveScaleScanner.classify(this.self.level(), selfPos);
			Path path = findReachableOrbitPath(SemanticBands.orbitMinDistance(caveScale),
				SemanticBands.orbitMaxDistance(caveScale), nearestPlayer.blockPosition(), caveScale);
			if (path != null) {
				target = path.getTarget();
				this.currentDespawnTarget = target;
				this.actionDeadlineTick = this.self.tickCount + SemanticBands.ACTION_TIMEOUT_TICKS;
				// moveTo(Path, double) returns boolean (confirmed via decompile) - real bug found live:
				// every one of this pass's new moveTo(Path, ...) call sites was discarding this, so a
				// path that failed to actually start following (still non-null, but rejected/empty)
				// silently left setNavigationFailed() at whatever it was before, instead of correctly
				// marking the failure - the same class of bug the ORIGINAL moveTo(x,y,z,speed) call
				// sites already guarded against by capturing their own boolean return.
				boolean started = this.self.getNavigation().moveTo(path, this.despawnSpeedMultiplier);
				this.self.setNavigationFailed(!started);
				debugSay("despawn attempt " + this.despawnAttemptCount + ": target=" + target.toShortString() + " self=" + selfPos.toShortString()
					+ " (orbit band, verified) moveTo started=" + started + " onGround=" + this.self.onGround() + " inLiquid=" + this.self.isInLiquid()
					+ " minY=" + this.self.level().getMinY());
				return;
			}
		}
		BlockPos avoid = nearestPlayer != null ? nearestPlayer.blockPosition() : null;
		target = DarkSpotScanner.findDarkestAwayFrom(this.self.level(), selfPos, SemanticBands.searchRadiusBlocks("near"), avoid);
		if (target == null) {
			// Last resort found nothing acceptable nearby either - despawning in place (matches
			// the documented backstop), but this was previously silent, indistinguishable from a
			// real successful despawn even with debug on.
			debugSay("issue: no dark spot found near current position either - despawning in place");
			target = selfPos;
		}
		this.currentDespawnTarget = target;
		this.actionDeadlineTick = this.self.tickCount + SemanticBands.ACTION_TIMEOUT_TICKS;
		boolean started = this.self.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, this.despawnSpeedMultiplier);
		this.self.setNavigationFailed(!started);
		debugSay("despawn attempt " + this.despawnAttemptCount + ": target=" + target.toShortString() + " self=" + selfPos.toShortString()
			+ " (unverified fallback) moveTo started=" + started + " onGround=" + this.self.onGround() + " inLiquid=" + this.self.isInLiquid()
			+ " minY=" + this.self.level().getMinY());
	}

	/** Pulls the next action_step to run, expanding control.if/control.while as they're reached. */
	private JsonObject nextActionStep() {
		// The stare minimum-hold rule (see stareMinimumEndTick's own field comment) - checked before
		// anything else here, unconditionally,
		// so it applies regardless of whether the model's own plan even wrapped the stare in a
		// control.while at all: while the minimum is still active, this returns a synthetic hold step
		// instead of ever actually pulling the real next one (from actionQueue, topLevelSteps, or an
		// active control.while's own body) - that real step just stays pending, untouched, until the
		// minimum elapses and this check stops firing, at which point normal pulling resumes exactly
		// where it left off.
		if (this.modelIntendedStaring && this.self.tickCount < this.stareMinimumEndTick) {
			return holdStep();
		}
		while (true) {
			if (!this.actionQueue.isEmpty()) {
				JsonObject queued = this.actionQueue.poll();
				// The one nesting exception (control_while_body_step in action_schema.json): a
				// control.while body element can itself be a control.if. Resolved lazily, right here,
				// rather than at while-body queue time - matches control.if's own "evaluate once, at the
				// point this step is reached" semantics even though it arrived via a body queued all at
				// once. The chosen branch replaces it in place - spliced onto the FRONT of actionQueue, not
				// the back, so it runs before whatever else from this same body pass is still waiting.
				if (queued.get("type").getAsString().equals("control.if")) {
					List<JsonObject> branchSteps = resolveControlIfBranch(queued);
					for (int i = branchSteps.size() - 1; i >= 0; i--) {
						this.actionQueue.addFirst(branchSteps.get(i));
					}
					continue;
				}
				return queued;
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
				// Sibling hardcoded escape hatch to isPlayerTooFarAwayToKeepStaring right above - same "let
				// a held stare-hold loop end on its own, as if genuinely spotted" idea, opposite direction:
				// the user's own explicit "a hardcoded stare rule that moves on with the plan if ANY player
				// in the group starts approaching him at medium approach scale" request. Not gated on
				// whileIterationsRun>0 the way the too-far check is - firing on the very first evaluation is
				// correct here (someone already closing in fast right as the hold begins should end it
				// immediately, not after burning at least one wasted iteration first).
				boolean conditionTrue = PlanPredicates.evaluate(condition, this.self, currentPredicateContext())
					&& !(this.modelIntendedStaring && this.whileIterationsRun > 0 && isPlayerTooFarAwayToKeepStaring())
					&& !(this.modelIntendedStaring && isAnyPlayerApproachingDuringStare());
				logStep("predicate: " + condition + " -> " + conditionTrue);
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
					if (bodyElementHasApproach(bodyStep)) {
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
				for (JsonObject branchStep : resolveControlIfBranch(step)) {
					this.actionQueue.add(branchStep);
				}
				continue;
			}
			if (type.equals("control.while")) {
				logStep("step: control.while");
				this.reEvaluateStepLog.add("control.while (immediate)");
				this.activeWhile = step;
				// Schema marks body required with minItems 1 (same guarantees as max_iterations below),
				// but a real crash log showed a live generation missing it anyway (NullPointerException
				// out of whileBodyHasApproach's own getAsJsonArray("body").iterator() - the very first
				// read of it, on the next tick's advance()) - same defensive treatment as the
				// max_iterations fallback just below: substitute a harmless single-step placeholder
				// body rather than crash the server on a step this central to every plan.
				JsonElement bodyElement = step.get("body");
				if (bodyElement == null || bodyElement.isJsonNull() || bodyElement.getAsJsonArray().isEmpty()) {
					debugSay("issue: control.while missing/empty body (schema violation) - substituting a placeholder hold");
					JsonArray placeholderBody = new JsonArray();
					placeholderBody.add(holdStep());
					step.add("body", placeholderBody);
				}
				// Two independent reasons predicate.player_distance can be disallowed here, each with
				// its own substitution:
				JsonObject condition = step.getAsJsonObject("condition");
				if (this.modelIntendedStaring && PlanPredicates.containsDistanceGate(condition)) {
					// (1) A stare is currently held - a stare-hold is gated purely on being noticed,
					// full stop, never on distance at any band. The "wait quietly without staring near
					// a lit spot, then reveal once close enough to lunge" trap is the correct pattern
					// for a combat-range wait instead, so a held stare doesn't give away the face for
					// nothing before the player's even close. Substitutes the same "hold until actually
					// noticed" default the schema's own control.while example already recommends,
					// rather than just breaking the loop - the graduated-look-band timeout (see
					// PlanPredicates.isLookedAtByTargetGraduated) still gives this a real time bound
					// even without an exact dead-on look.
					debugSay("issue: control.while condition uses predicate.player_distance while staring - "
						+ "a stare-hold must be gated purely on being noticed - substituting a look-based hold instead");
					step.add("condition", staringHoldCondition());
					condition = step.getAsJsonObject("condition");
				}
				if (PlanPredicates.containsWideDistanceGate(condition)) {
					// (2) The band itself is medium/far, regardless of staring - "wait for the player to
					// wander closer" they can just never do, stalling the loop indefinitely either way.
					// grab_distance/lunge_distance/close_quarters stay allowed for a non-staring wait -
					// the schema's own bait-and-lunge example. Narrows to lunge_distance in place rather
					// than swapping predicate types entirely - unlike the staring case, there's nothing
					// wrong with the shape of what the model wrote, just the specific band.
					debugSay("issue: control.while condition uses predicate.player_distance(medium/far) - "
						+ "the player could just never approach - narrowing to lunge_distance instead");
					PlanPredicates.narrowWideDistanceGatesToLungeDistance(condition);
				}
				// Schema marks max_iterations required (and OpenAI's strict mode/Anthropic's structured
				// output are both supposed to guarantee that), but a real crash log showed a live
				// generation missing it anyway - defensive fallback rather than trusting the provider's
				// enforcement completely, since the alternative is a server-crashing NPE on a step this
				// central to every plan.
				JsonElement maxIterationsElement = step.get("max_iterations");
				if (maxIterationsElement == null || maxIterationsElement.isJsonNull()) {
					debugSay("issue: control.while missing max_iterations (schema violation) - defaulting to 'some'");
				}
				String maxIterationsBand = maxIterationsElement != null && !maxIterationsElement.isJsonNull()
					? maxIterationsElement.getAsString() : "some";
				this.whileIterationsRemaining = SemanticBands.maxIterations(maxIterationsBand);
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

	/** Evaluates a control.if step's condition (right now, at the moment it's actually reached -
	 * never earlier) and returns the chosen branch's steps in order, or an empty list if there's
	 * nothing to run on that side. Shared by both places a control.if can now turn up: directly in
	 * topLevelSteps, and (the one nesting exception) queued inside a control.while body - see
	 * nextActionStep's own actionQueue-poll branch. */
	private List<JsonObject> resolveControlIfBranch(JsonObject step) {
		logStep("step: control.if");
		this.reEvaluateStepLog.add("control.if (immediate)");
		boolean condition = PlanPredicates.evaluate(step.getAsJsonObject("condition"), this.self, currentPredicateContext());
		logStep("predicate: " + step.getAsJsonObject("condition") + " -> " + condition);
		// "else" is required by the schema now (OpenAI's strict structured-output mode disallows
		// optional properties) but nullable - the model sends an explicit JSON null rather than
		// omitting the key when there's nothing for the false branch to do. JsonObject.getAsJsonArray
		// casts straight to JsonArray, which throws on a JsonNull value rather than treating it like
		// an absent key, so that has to be checked first.
		JsonElement branchElement = condition ? step.get("then") : step.get("else");
		JsonArray branch = branchElement != null && !branchElement.isJsonNull() ? branchElement.getAsJsonArray() : null;
		List<JsonObject> steps = new ArrayList<>();
		if (branch != null) {
			for (var element : branch) {
				steps.add(element.getAsJsonObject());
			}
		}
		return steps;
	}

	/** One-time setup for an action_step. Returns false if it already finished within this tick. */
	private boolean startAction(JsonObject step) {
		String type = step.get("type").getAsString();
		logStep("step: " + type);
		if (!TierGates.isAllowed(step, this.severityPercent)) {
			// Deterministic backstop for the prompt's severity-tier guidance - prose alone proved
			// unreliable (real generations used combat primitives well below the tier that's supposed
			// to unlock them). Skipped like any other unmet precondition, never a wave-ending error.
			debugSay("issue: tier-gated - " + type + " not allowed at severity " + this.severityPercent + "% - skipping");
			return false;
		}
		// See meaningfulActionCompletedThisWave's own field comment - set on START, not completion:
		// most instant actions (posture.stare included - the user's own explicit example) never hold
		// long enough to reach isActionDone()'s "finished" bookkeeping in tick() at all (advance()'s
		// own "if (!startAction(next)) continue" loop skips straight past currentAction for those), so
		// hooking this to completion would never fire for exactly the case the user described. Placed
		// after the tier-gate check above so a step that got SKIPPED for being tier-gated doesn't
		// count as something that actually happened.
		if (isMeaningfulActionType(type)) {
			this.meaningfulActionCompletedThisWave = true;
		}
		this.actionDeadlineTick = this.self.tickCount + SemanticBands.ACTION_TIMEOUT_TICKS;
		this.currentActionStartTick = this.self.tickCount;
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
				double speed = SemanticBands.speedMultiplier(step.get("speed").getAsString());
				boolean started = this.self.getNavigation().moveTo(target, speed);
				this.self.setNavigationFailed(!started);
				debugMoveTo(type, started);
			}
			case "movement.approach_spot" -> {
				String destinationType = step.get("destination").getAsString();
				Player target = Targeting.nearestPlayer(this.self);
				if (target == null) {
					return false;
				}
				CaveScale caveScale = CaveScaleScanner.classify(this.self.level(), target.blockPosition());
				// Real reachability verification, not just a geometric guess - the user's own explicit
				// "if he can't reach the player in a spot he spawns/teleports to, try again" request,
				// same shape findReachableOrbitPath already established for orbit positioning. Unlike
				// combat.teleport (instant, no path needed), this is a walk, so every candidate gets a
				// real pathfind check regardless of cave scale here (not just TIGHT) - see
				// resolveDestinationPath's own doc comment.
				Path path = resolveDestinationPath(destinationType, target, caveScale);
				if (path == null) {
					debugSay("issue: nothing reachable at destination '" + destinationType + "' right now - skipping movement.approach_spot");
					return false;
				}
				double speed = SemanticBands.speedMultiplier(step.get("speed").getAsString());
				// Reuses the already-computed, already-verified path directly - confirmed via decompile
				// that moveTo(Path, double) does NOT internally repath (unlike moveTo(x,y,z,speed), whose
				// own body is just createPath(x,y,z,1) then moveTo(path,speed)), so this is a genuine
				// elimination of a redundant pathfind, not just a convenience overload. moveTo(Path,
				// double) itself still returns boolean though (confirmed via decompile) - this was
				// previously hardcoded to setNavigationFailed(false) regardless of outcome, which is
				// exactly the bug that let a rejected/degenerate move sit forever instead of being
				// recognized and retried.
				boolean approachStarted = this.self.getNavigation().moveTo(path, speed);
				this.self.setNavigationFailed(!approachStarted);
				debugMoveTo(type, approachStarted);
			}
			case "movement.retreat_to_dark" -> {
				BlockPos dest = retreatDestination(step);
				if (dest == null) {
					return false;
				}
				double speed = SemanticBands.speedMultiplier(step.get("speed").getAsString());
				boolean started = this.self.getNavigation().moveTo(dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5, speed);
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
				// resolveChaseDestination/rejectDegeneratePathIfNeeded TEMPORARILY REMOVED - the user's
				// own explicit request, to isolate results without them.
				boolean started = this.self.getNavigation().moveTo(target, LUNGE_CHASE_SPEED_MULTIPLIER);
				this.self.setNavigationFailed(!started);
				debugMoveTo(type, started);
				logPathNodes("combat.lunge_attack start");
				// Hidden goal-progress signal (see the field's own comment) - counts the commit itself,
				// not whether it actually lands the catch, only reached once tier-gating and the
				// safe-darkness precondition above already passed.
				this.lungeAttemptCount++;
				recordSoulLightTally(target);
			}
			case "combat.teleport" -> {
				// Always available as an action type (see TierGates.minPercentFor's own comment) - the
				// real gating is entirely which destination type(s) SchemaBuilder.filterTeleportType
				// even offers for the current stage. No more source-band precondition - now that
				// distance is cave-scale-driven rather than severity-gated, there's nothing left for
				// a "can it teleport away from here" check to gate against. An instant relocation, not
				// a walked approach - resolves the same tick it starts (falls through to isActionDone's
				// default -> true, same as every other single-shot engine action), no navigation/moveTo
				// involved, so (unlike movement.approach_spot) resolveDestinationSpot is used directly
				// rather than needing a verified Path.
				String destinationType = step.get("destination").getAsString();
				Player target = Targeting.nearestPlayer(this.self);
				if (target == null) {
					return false;
				}
				CaveScale caveScale = CaveScaleScanner.classify(this.self.level(), target.blockPosition());
				BlockPos spot = resolveDestinationSpot(destinationType, target, caveScale);
				// Every teleport destination must be exactly 0 light - the user's own explicit request
				// - EXCEPT torch, deliberately exempted: the whole point of that destination is landing
				// right at a live light source, which can never itself read as unlit. The underlying
				// resolvers resolveDestinationSpot calls are shared with movement.approach_spot (which
				// only needs MAX_DARK_LIGHT, not exactly 0) - so this is a post-filter scoped to just
				// this teleport case, not a change to those shared searches themselves.
				if (spot != null && !"torch".equals(destinationType)
						&& this.self.level().getMaxLocalRawBrightness(spot) > 0) {
					spot = null;
				}
				// Real live-reported bug: "sometimes when he teleports he teleports into the ground,
				// causing suffocation." Root cause: the radial destination types (behind/unwatched/
				// in_view/eyeline/ahead) resolve through findRadialDestinationPath, which wraps an
				// already isAttachable/isPassable-verified candidate in createPath(candidate, 1) and
				// hands back Path.getTarget() - canReach()/isAcceptableOrbitPath only guarantee the
				// path's own last node landed WITHIN that 1-block tolerance of the original candidate,
				// not exactly at it (confirmed via SemanticBands.isAcceptableOrbitPath's own body -
				// canReach() alone, no exact-position check). movement.approach_spot never had this
				// problem (a real walked arrival is collision-mediated regardless of any such drift),
				// but combat.teleport's instant snapTo has no physics step to catch a final position
				// that drifted onto solid ground. One last direct re-check of the EXACT coordinates
				// about to be snapped into, right here, independent of whichever resolver produced
				// them - closes the gap regardless of where in the chain the drift happened.
				if (spot != null && !DarkSpotScanner.isPassable(this.self.level(), spot)) {
					spot = null;
				}
				if (spot == null) {
					debugSay("issue: nothing live-resolvable to teleport to at destination '" + destinationType + "' - skipping combat.teleport");
					return false;
				}
				this.self.snapTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, this.self.getYRot(), 0f);
				this.self.syncPoseToSpawnPosition();
				// "above" is the one destination type here that lands on a CEILING - Direction.DOWN is
				// nudgeTowardAttachedSurface's own ceiling convention (Direction.UP, its floor
				// convention, is a deliberate no-op against a ceiling landing - see that method's own
				// doc comment for the real bug this guards against: without the correct nudge, AWCAPI
				// never recognizes the ceiling collision and the entity just falls back off under
				// ordinary gravity almost immediately). Every other destination type here is still an
				// ordinary floor/wall landing, unaffected.
				this.self.nudgeTowardAttachedSurface("above".equals(destinationType) ? Direction.DOWN : Direction.UP);
				if ("above".equals(destinationType)) {
					// See WendigoEntity.startCeilingSettle's own doc comment - the nudge above alone
					// wasn't reliably enough for a movement.drop right after this to actually detach.
					// isDropResolved() is what actually waits on this before calling forceDetach().
					this.self.startCeilingSettle();
				}
				debugSay("teleport: relocated to " + spot.toShortString() + " (destination=" + destinationType
					+ ") near " + target.getGameProfile().name());
			}
			case "movement.drop" -> {
				// The user's own explicit "literally just removes their attachment" request - see
				// WendigoEntity.forceDetach's own doc comment for the mechanism (a real AWCAPI physics
				// detail, decompiled to confirm: 5 consecutive onGround()==false ticks fully depletes
				// ClimberComponent's own attachedTicks, the same natural-fall path a genuine detach
				// already goes through). The actual forceDetach() call is deferred to isDropResolved()
				// (see isActionDone's own dispatch) rather than firing here unconditionally - a drop
				// issued right after a ceiling teleport needs to wait out WendigoEntity's own
				// isCeilingSettling() window first (see startCeilingSettle's doc comment for why), and
				// this is a single-shot action with nothing else to set up here either way, so the whole
				// thing (including the ordinary immediate case, when isCeilingSettling() is already
				// false) lives there instead.
			}
			case "combat.break_torch" -> {
				// The user's own explicit hardcoded exception, not a schema/TierGates concern - too
				// hurt to bother with torches anymore once he's dropped to 3/4 health or less,
				// regardless of what the model's own plan wants. Checked here, not at plan-build time,
				// since health can drop mid-wave after the plan already committed to this step.
				if (this.self.getHealth() <= this.self.getMaxHealth() * BREAK_TORCH_MAX_HEALTH_FRACTION) {
					debugSay("issue: too hurt (<=75% health) to bother breaking torches - skipping break_torch");
					return false;
				}
				// Always the nearest torch to the WENDIGO's own current position - no band-constrained
				// option anymore (removed per a real reported failure mode: picking "nearest torch
				// within some band of the PLAYER" could pick one on the far side of the player from
				// wherever the wendigo actually is, sending it straight through the lit area around
				// the player just to reach it, which is exactly the kind of exposure this creature is
				// supposed to avoid).
				BlockPos torch = nearestTorch();
				if (torch == null) {
					WendigoMod.LOGGER.debug("Wendigo {} found no torch nearby - skipping break_torch", this.self.getId());
					debugSay("issue: no torch found nearby - skipping break_torch");
					return false;
				}
				this.currentTorchTarget = torch;
				this.self.setLightTolerantPathing(true);
				double speed = SemanticBands.speedMultiplier(step.get("speed").getAsString());
				boolean started = this.self.getNavigation().moveTo(torch.getX() + 0.5, torch.getY(), torch.getZ() + 0.5, speed);
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
				logPathNodes("combat.chase start");
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
				logPathNodes("internal.chase_until_light start");
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
				// The user's own explicit hardcoded exception: no staring at stage 5 once he's down to
				// half health or less - "signifying he's done playing around," the theatrical
				// stare-and-wait doesn't fit a desperate final-stage encounter anymore. Only blocks
				// STARTING a fresh stare - enabled=false (ending one already in progress) always goes
				// through regardless, same reasoning as combat.break_torch's own health cutoff not
				// forcibly aborting an already-in-progress approach.
				if (enabled && stageForSeverityPercent(this.severityPercent) == 5
						&& this.self.getHealth() <= this.self.getMaxHealth() * STAGE5_STARE_MAX_HEALTH_FRACTION) {
					debugSay("issue: stage 5 and <=50% health - too far gone to bother staring, skipping posture.stare");
					return false;
				}
				// Only blocks STARTING a fresh stare, same as the stage-5/health cutoff just above -
				// enabled=false always goes through regardless. See WendigoEntity.isStareEligibleGround's
				// own comment: tilted/uneven ground produces a visible hitbox-vs-visual mismatch during a
				// held stare, so a fresh one isn't allowed to begin until the ground has read as flat for
				// STARE_FLAT_GROUND_DEBOUNCE_TICKS in a row.
				if (enabled && !this.modelIntendedStaring && !this.self.isStareEligibleGround()) {
					debugSay("issue: ground hasn't been flat long enough yet, skipping posture.stare");
					return false;
				}
				if (enabled && !this.modelIntendedStaring) {
					// Allow a new success to be counted, see successfulStareCount's own field comment.
					this.currentStareCounted = false;
					// Every fresh stare session gets the minimum hold now - see stareMinimumEndTick's own
					// field comment for why this is no longer conditioned on what ran immediately before.
					this.stareMinimumEndTick = this.self.tickCount + STARE_MIN_HOLD_TICKS;
				}
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
			case "sound.breathe" -> {
				playBreathe();
				return false; // instantaneous - plays once, the plan continues the same tick
			}
			case "control.none" -> {
				return false;
			}
			case "control.re_evaluate" -> {
				// See meaningfulActionCompletedThisWave's own field comment - the user's own explicit
				// correction: re-evaluate is for checking whether something already tried (a held
				// stare, an ongoing chase) is working, not for scrapping a freshly-handed-back plan
				// before any of it has actually run. Engine-enforced (same "narrow exception to pure
				// prompt-composability" precedent global_rules gating already established), not just
				// prompt guidance, since a schema description alone doesn't reliably stop the model
				// from reaching for this reflexively as literally its first step. Resolves instantly as
				// a no-op instead of holding for a real LLM round trip - the plan just continues into
				// whatever step originally followed it, same degradation every other resolver in this
				// file already falls back to rather than erroring out.
				if (!this.meaningfulActionCompletedThisWave) {
					debugSay("re-evaluate: skipped (nothing meaningful has run yet this wave) - continuing plan");
					return false;
				}
				// See reEvaluateRequested's own field comment - a genuine indeterminate hold, not a
				// fixed-timeout wait, so this needs a real "still running" action (falls through to the
				// bottom `return true`, unlike every other single-shot control.* case above) plus a
				// longer deadline than the generic ACTION_TIMEOUT_TICKS.
				this.self.getNavigation().stop();
				this.actionDeadlineTick = this.self.tickCount + RE_EVALUATE_TIMEOUT_TICKS;
				this.reEvaluateRequested = true;
			}
			default -> {
				WendigoMod.LOGGER.warn("Wendigo {} got unknown action type '{}', skipping", this.self.getId(), type);
				return false;
			}
		}
		return true;
	}

	// combat.teleport/movement.approach_spot's own "ahead" extrapolation - same "is the player
	// actually moving" threshold PlanPredicates.targetMoving/WaveContext.describePlayerMovement
	// already use (horizontal delta magnitude, not the vanilla isSprinting() flag alone), duplicated
	// here rather than widened to package-visible since both of those live in different reasons for
	// staying private (see WaveContext's own doc comment on this exact duplication tradeoff).
	private static final double TELEPORT_AHEAD_MOVING_SPEED_THRESHOLD_SQR = 0.0009; // (~0.03 blocks/tick)^2
	// How far ahead of the player's current position (along their live horizontal movement direction)
	// the predicted landing point sits, and how wide a radius around that predicted point
	// DarkSpotScanner.findDimSpotNear then searches - unlike every other destination type, this is
	// deliberately NOT cave-scale/SemanticBands.actionSearchMinDistance-driven, since it's not a
	// distance-from-player concept at all - first pass, tune by feel like every other distance
	// constant in this file.
	private static final double TELEPORT_AHEAD_LOOKAHEAD_DISTANCE = 20.0;
	private static final double TELEPORT_AHEAD_SEARCH_RADIUS = 12.0;

	/** The ahead destination type's own path-prediction step - the user's own explicit "guess the
	 * player's path... a straight-line path in their direction" request. Null if the player is
	 * currently standing still - there's no direction to extrapolate from a zero velocity, and
	 * guessing one would be pure noise. Otherwise projects TELEPORT_AHEAD_LOOKAHEAD_DISTANCE blocks
	 * out along their live horizontal movement heading - resolveDestinationSpot's own "ahead" case
	 * then searches DarkSpotScanner.findDimSpotNear around this point (any-surface, same as every
	 * other non-face-specific destination type), falling back to "in_view" if this comes back null
	 * (not moving) or the dim search around it turns up nothing. */
	private BlockPos predictedPathOrigin(Player target) {
		Vec3 delta = target.getDeltaMovement();
		double horizontalSpeedSqr = delta.x * delta.x + delta.z * delta.z;
		if (horizontalSpeedSqr <= TELEPORT_AHEAD_MOVING_SPEED_THRESHOLD_SQR) {
			return null;
		}
		double horizontalSpeed = Math.sqrt(horizontalSpeedSqr);
		double dirX = delta.x / horizontalSpeed;
		double dirZ = delta.z / horizontalSpeed;
		return BlockPos.containing(
			target.getX() + dirX * TELEPORT_AHEAD_LOOKAHEAD_DISTANCE,
			target.getY(),
			target.getZ() + dirZ * TELEPORT_AHEAD_LOOKAHEAD_DISTANCE);
	}

	/** Shared destination resolver for both combat.teleport and movement.approach_spot - see
	 * SchemaBuilder's own combat_teleport/movement_approach_spot $defs for the full semantics of each
	 * destination type. "above" (ceiling only) and "torch" (nearest live light source, already
	 * surface-agnostic) are face-specific, resolved directly with no retry/cave-scale search at all -
	 * unaffected by cave scale. Every other type - behind/unwatched/in_view/eyeline/ahead - can
	 * legitimately land on floor, wall, OR ceiling ("behind the player" could just as easily be on
	 * the ceiling behind them), so each retry samples a freshly-randomized surface normal (same
	 * technique randomOrbitSurfaceNormal already establishes for orbit positioning) rather than being
	 * tied to one surface. Every candidate is now real-pathfind-verified (SemanticBands.
	 * isAcceptableOrbitPath) regardless of cave scale - previously this only happened for CaveScale
	 * .TIGHT, which let a NORMAL/MASSIVE-cave teleport land geometry-valid but genuinely unreachable
	 * (the user's own live report: an eyeline teleport resolving "super far out in an unreachable
	 * cave"). See findRadialDestinationPath's own doc comment for how the search now also prefers
	 * closer candidates before ever widening out to SemanticBands.actionSearchMaxDistance. Returns
	 * null if nothing valid turns up within budget. */
	private BlockPos resolveDestinationSpot(String destinationType, Player target, CaveScale caveScale) {
		if ("above".equals(destinationType)) {
			return DarkSpotScanner.findCeilingSpotAbovePlayer(this.self.level(), target.blockPosition());
		}
		if ("torch".equals(destinationType)) {
			List<BlockPos> torches = LightSourceScanner.findLightSources(this.self.level(),
				target.blockPosition(), TORCH_BREAK_SEARCH_RADIUS, 1);
			return torches.isEmpty() ? null : torches.get(0);
		}
		if ("ahead".equals(destinationType)) {
			BlockPos predictedOrigin = predictedPathOrigin(target);
			if (predictedOrigin == null) {
				return resolveDestinationSpot("in_view", target, caveScale); // not moving - nothing to predict
			}
			Path path = resolveAheadCandidatePath(predictedOrigin, caveScale);
			return path != null ? path.getTarget() : resolveDestinationSpot("in_view", target, caveScale);
		}
		Path path = findRadialDestinationPath(destinationType, target, caveScale);
		return path != null ? path.getTarget() : null;
	}

	// Successively wider distance rings tried before giving up at SemanticBands.actionSearchMaxDistance
	// - the user's own explicit ask: try other candidates at roughly the same distance before searching
	// farther out, since a farther candidate is statistically less likely to actually be reachable from
	// wherever the player currently is. Each ring gets its own full orbitReachRetryAttempts budget
	// before the search widens to the next one.
	private static final int ACTION_SEARCH_RING_COUNT = 3;

	/** Shared radial-destination resolver for the behind/unwatched/in_view/eyeline destination types
	 * (combat.teleport and movement.approach_spot alike): searches in ACTION_SEARCH_RING_COUNT
	 * successively wider rings from SemanticBands.actionSearchMinDistance out to
	 * SemanticBands.actionSearchMaxDistance, exhausting a full WendigoTuningConfig
	 * .orbitReachRetryAttempts budget of freshly-randomized-normal candidates at the CURRENT ring
	 * (real-pathfind-verified via SemanticBands.isAcceptableOrbitPath) before ever widening to the
	 * next one - so a genuinely close-but-disconnected candidate doesn't cause the search to jump
	 * straight out toward the far edge of the whole range, it keeps trying other spots at roughly the
	 * same distance first. Returns the already-computed, already-verified Path (not just a destination
	 * BlockPos) so movement.approach_spot can hand it straight to PathNavigation.moveTo(Path, double);
	 * combat.teleport instead reads Path.getTarget() off the result, since it only needs the
	 * destination itself, not the path there. */
	private Path findRadialDestinationPath(String destinationType, Player target, CaveScale caveScale) {
		double minDistance = SemanticBands.actionSearchMinDistance(caveScale);
		double hardMaxDistance = SemanticBands.actionSearchMaxDistance();
		double ringStep = (hardMaxDistance - minDistance) / ACTION_SEARCH_RING_COUNT;
		for (int ring = 1; ring <= ACTION_SEARCH_RING_COUNT; ring++) {
			double ringMaxDistance = ring == ACTION_SEARCH_RING_COUNT ? hardMaxDistance : minDistance + ringStep * ring;
			for (int attempt = 0; attempt < WendigoMod.tuningConfig.orbitReachRetryAttempts; attempt++) {
				Direction normal = randomOrbitSurfaceNormal();
				BlockPos candidate = switch (destinationType) {
					case "behind", "unwatched" -> DarkSpotScanner.findUnwatchedPosition3D(this.self.level(), target, minDistance, ringMaxDistance);
					case "in_view" -> DarkSpotScanner.findLiveBandPositionInView(this.self.level(), target, minDistance, ringMaxDistance, normal);
					case "eyeline" -> DarkSpotScanner.findLiveBandPositionEyeline(this.self.level(), target, minDistance, ringMaxDistance, normal);
					default -> null;
				};
				if (candidate == null) {
					continue;
				}
				Path path = this.self.getNavigation().createPath(candidate, 1);
				if (SemanticBands.isAcceptableOrbitPath(path, caveScale)) {
					return path;
				}
			}
		}
		return null;
	}

	/** The ahead destination type's own candidate search (fixed TELEPORT_AHEAD_SEARCH_RADIUS around
	 * the predicted point, not a min/max band around the player, so it doesn't go through
	 * findRadialDestinationPath's own ring-expansion) - now always real-pathfind-verified regardless
	 * of cave scale, same fix as findRadialDestinationPath's own for the same live-reported reason. */
	private Path resolveAheadCandidatePath(BlockPos predictedOrigin, CaveScale caveScale) {
		for (int attempt = 0; attempt < WendigoMod.tuningConfig.orbitReachRetryAttempts; attempt++) {
			Direction normal = randomOrbitSurfaceNormal();
			BlockPos candidate = DarkSpotScanner.findDimSpotNear(this.self.level(), predictedOrigin, TELEPORT_AHEAD_SEARCH_RADIUS, normal);
			if (candidate == null) {
				continue;
			}
			Path path = this.self.getNavigation().createPath(candidate, 1);
			if (SemanticBands.isAcceptableOrbitPath(path, caveScale)) {
				return path;
			}
		}
		return null;
	}

	/** movement.approach_spot's own destination resolver - same candidate generation as
	 * resolveDestinationSpot above (both now real-pathfind-verified regardless of cave scale - see
	 * findRadialDestinationPath's own doc comment for why that changed). Returns the
	 * already-computed, already-verified Path directly (not just a destination BlockPos) so the
	 * caller can hand it straight to PathNavigation.moveTo(Path, double) instead of re-pathing from
	 * scratch. */
	private Path resolveDestinationPath(String destinationType, Player target, CaveScale caveScale) {
		// "above" deliberately does NOT reuse resolveDestinationSpot's own "above" case here - that one
		// (findCeilingSpotAbovePlayer) enforces a 10-block minimum height, correct for a dramatic
		// teleport ambush but wrong for a walked approach: the OLD, proven-working
		// movement.approach_band(spot_above) always used findCeilingVantagePoint instead (confirmed via
		// git history), a plain straight-up probe with no minimum height at all. Real regression found
		// live via GameTest: a walked approach into a modest, < 10-block-tall room (an entirely ordinary
		// cave ceiling height, not a degenerate case) silently found nothing and never moved, while the
		// same room's combat.teleport(above) sibling test uses a taller room specifically because it
		// needs findCeilingSpotAbovePlayer's own [10,30] window to have anything to find at all.
		if ("above".equals(destinationType)) {
			BlockPos spot = DarkSpotScanner.findCeilingVantagePoint(this.self.level(), target.blockPosition());
			if (spot == null) {
				return null;
			}
			// Real gap found while widening reachability verification elsewhere in this method: this
			// branch (and torch's, right below) used to hand back a raw createPath result with no
			// isAcceptableOrbitPath check at all, contradicting this method's own doc comment promise
			// that movement.approach_spot ALWAYS verifies reachability regardless of destination type.
			Path path = this.self.getNavigation().createPath(spot, 1);
			return SemanticBands.isAcceptableOrbitPath(path, caveScale) ? path : null;
		}
		if ("torch".equals(destinationType)) {
			BlockPos spot = resolveDestinationSpot(destinationType, target, caveScale);
			if (spot == null) {
				return null;
			}
			Path path = this.self.getNavigation().createPath(spot, 1);
			return SemanticBands.isAcceptableOrbitPath(path, caveScale) ? path : null;
		}
		if ("ahead".equals(destinationType)) {
			BlockPos predictedOrigin = predictedPathOrigin(target);
			if (predictedOrigin == null) {
				return resolveDestinationPath("in_view", target, caveScale); // not moving - nothing to predict
			}
			Path path = resolveAheadCandidatePath(predictedOrigin, caveScale);
			// "ahead" falls back to "in_view" once its own budget is exhausted (nothing dim/reachable
			// found near the predicted point), same as resolveDestinationSpot's own teleport-side
			// fallback.
			return path != null ? path : resolveDestinationPath("in_view", target, caveScale);
		}
		return findRadialDestinationPath(destinationType, target, caveScale);
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
				case "movement.approach_spot", "movement.retreat_to_dark" ->
					navigationFinished() || checkStuck();
				case "combat.lunge_attack" -> isLungeResolved();
				case "combat.break_torch" -> isBreakTorchResolved();
				case "combat.chase" -> isChaseResolved();
				case "internal.chase_until_light" -> isChaseUntilLightResolved();
				case "movement.drop" -> isDropResolved();
				case "timing.wait" -> false; // only the deadline check above ends a wait
				// Holds until WendigoManager clears reEvaluateRequested (a sub-plan came back via
				// resumeFromReEvaluate, or the request errored/came stale via cancelReEvaluate) - see
				// that field's own comment. The generic timedOut check above still applies on top (via
				// RE_EVALUATE_TIMEOUT_TICKS), so this can't hold forever even if nothing ever clears it.
				case "control.re_evaluate" -> !this.reEvaluateRequested;
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
			// See consecutiveNoProgressGiveUps' own field comment - only noProgress specifically counts
			// as a "physically wedged" strike; a clean arrival, a stuckInLight give-up, or hitting the
			// per-action timeout while still genuinely making progress all reset it back to 0.
			if (noProgress) {
				this.consecutiveNoProgressGiveUps++;
			} else {
				this.consecutiveNoProgressGiveUps = 0;
			}
		}
		return resolved;
	}

	private static boolean isPlainMovementType(String type) {
		return switch (type) {
			case "movement.approach", "movement.approach_spot", "movement.retreat_to_dark" -> true;
			default -> false;
		};
	}

	/** Whether a control.while's body contains an approach-type step at all - see whileApproachesRun. */
	private static boolean whileBodyHasApproach(JsonObject whileStep) {
		for (var element : whileStep.getAsJsonArray("body")) {
			if (bodyElementHasApproach(element.getAsJsonObject())) {
				return true;
			}
		}
		return false;
	}

	private static boolean isApproachType(String type) {
		return type.equals("movement.approach") || type.equals("movement.approach_spot");
	}

	/** Whether this control.while body element is itself an approach-type step, or (the one nesting
	 * exception - see control_while_body_step in action_schema.json) a control.if with an approach
	 * type step on either branch. Only one level deep to check - a nested control.if's own then/else
	 * are always flat action_step lists, never another control.if/control.while. Used to extend the
	 * "at most one approach step queued per body pass" cap (see whileApproachesRun's own comment)
	 * to steps hidden behind a branch, conservatively - which branch actually runs isn't known until
	 * the control.if is reached at queue-poll time, so a body element that COULD run an approach on
	 * either side counts as one for capping purposes even if the branch actually taken wouldn't. */
	private static boolean bodyElementHasApproach(JsonObject element) {
		String type = element.get("type").getAsString();
		if (isApproachType(type)) {
			return true;
		}
		if (!type.equals("control.if")) {
			return false;
		}
		for (String branchKey : new String[] {"then", "else"}) {
			JsonElement branchElement = element.get(branchKey);
			if (branchElement == null || branchElement.isJsonNull()) {
				continue;
			}
			for (var step : branchElement.getAsJsonArray()) {
				if (isApproachType(step.getAsJsonObject().get("type").getAsString())) {
					return true;
				}
			}
		}
		return false;
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
			if (readyToWithdraw() && hasHadFairRideChance()) {
				return true; // arrived cleanly, dark enough (or stuck long enough), and carried them long enough
			}
			if (hasDespawnWork()) {
				debugSay(readyToWithdraw()
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
		if (!readyToWithdraw() || !hasHadFairRideChance()) {
			return false; // exhausted every candidate but still too bright, or hasn't held/ridden long enough
		}
		debugSay("issue: exhausted every despawn/retreat candidate - giving up here");
		return true; // exhausted every option - give up here, same backstop as before
	}

	/** True once a caught player has been carried long enough (rideFairChanceThreshold, randomized per
	 * grab - see beginForcedRide) for a fair chance to escape - a plain real-time countdown (rideTicks,
	 * elapsed since the grab, no darkness requirement - see rideTicks' own field comment for why that
	 * got dropped), always true when nobody's currently a forced rider. Gates BOTH whether completeWave
	 * is allowed to deal the despawn-damage killing blow (see its own comment for that original bug)
	 * AND, separately, whether a despawn attempt is even allowed to be considered "arrived" while
	 * carrying a rider (see isDespawnAttemptResolved) - without the latter, catching the player right
	 * as (or just before) reaching an already-close despawn candidate read as an anticlimactic instant
	 * grab-and-vanish far too often, even though no damage was ever dealt in that case. Forcing the
	 * despawn attempt to keep dashing on to further candidates until the ride has run long enough gives
	 * a real "carried off" beat instead - readyToWithdraw's own separate dark-spot preference (see its
	 * own comment) is what still steers those candidates toward the dark, independent of this. */
	private boolean hasHadFairRideChance() {
		return !this.forcingRide || this.rideTicks >= this.rideFairChanceThreshold;
	}

	/** Whether it's OK for a despawn attempt to actually conclude right now - either it's dark
	 * enough (at or below DarkSpotScanner.MAX_DARK_LIGHT, the same bar a spot has to clear to be
	 * offered as a dark spot in the first place), or it's been genuinely motionless for
	 * DESPAWN_STATIONARY_GIVEUP_TICKS with nowhere better left to try - a last-resort escape hatch,
	 * not the normal path. Without this, a despawn move that merely stopped moving (arrived at the
	 * pathfinder's best-effort spot, or ran out of fallback candidates) while still standing in
	 * plain light would vanish right there, reading as teleporting away at will instead of actually
	 * fleeing into the dark. */
	private boolean readyToWithdraw() {
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

	/** movement.drop's own resolution - see startAction's own updated comment on why the real
	 * forceDetach() call lives here instead of firing unconditionally at action-start. Holds
	 * (returns false, same as timing.wait) for as long as WendigoEntity.isCeilingSettling() is true -
	 * only relevant right after a combat.teleport(destination="above") landing (see
	 * WendigoEntity.startCeilingSettle's own doc comment); every other case (a walked climb to destination=above,
	 * or movement.drop called with nothing to detach from at all) already has isCeilingSettling()
	 * reading false, so this resolves on the very same tick it starts, identical to the old
	 * unconditional behavior. */
	private boolean isDropResolved() {
		if (this.self.isCeilingSettling()) {
			return false;
		}
		this.self.forceDetach();
		debugSay("drop: released attachment at self=" + this.self.blockPosition().toShortString());
		return true;
	}

	/**
	 * Unlike lunge/break_torch, combat.chase doesn't end the instant it hits a single stuck tick -
	 * it's meant to be sustained, passively destroying nearby torches as it runs, until it either
	 * catches the player (see beginForcedRide - resolves immediately, same as lunge, rather than
	 * sustaining a repeated-strike loop the way this used to before the ride mechanic replaced
	 * damage-on-contact) or gives up once they've been unreachable for CHASE_GIVE_UP_TICKS straight.
	 *
	 * <p>Reactive repathing again - the user's own explicit revert back to "only repath once the
	 * current path has actually finished, or stuck detection has fired," undoing an earlier same-
	 * session-line fixed-timer interim (chaseRepathTicks/rollChaseRepathDelay, since deleted) that
	 * was itself adopted to fix a real live bug: re-issuing moveTo() immediately the instant
	 * navigationFinished() went true (even for a perfectly good, just-completed path) was corrupting
	 * this entity's own ceiling attachment state - confirmed live via CHASE_REPATH logging showing
	 * repath spam landing exactly on arrival, matching an observed "a single path node gets placed on
	 * him and he starts glitching" symptom. If that same corruption resurfaces after this revert,
	 * this is the first place to look. isStuck()/navigationFinished() now drive BOTH
	 * chaseUnreachableTicks' own give-up bookkeeping AND the repath trigger itself again, computed
	 * once and reused for both rather than queried twice.
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
		boolean needsRepath = this.self.getNavigation().isStuck() || (navigationFinished() && !withinMeleeRange());
		if (needsRepath) {
			this.chaseUnreachableTicks++;
			dropIfNewlyStuckOnCeiling();
		} else {
			this.chaseUnreachableTicks = 0;
		}
		if (this.chaseUnreachableTicks >= CHASE_GIVE_UP_TICKS) {
			debugSay("issue: couldn't reach the player during combat.chase for a while - giving up the chase");
			this.self.setNavigationFailed(true);
			return true;
		}
		if (needsRepath) {
			logChaseRepath("combat.chase");
			this.self.getNavigation().moveTo(player, LUNGE_CHASE_SPEED_MULTIPLIER);
			logPathNodes("combat.chase repath");
		}
		return false;
	}

	/** The user's own explicit "first pass of that mechanic" request, restored in place of the removed
	 * drop-vs-original-path comparison (see isChaseResolved's own doc comment on that mechanic's
	 * history: "first split into a mid-chase 'stuck' check and a chase-start 'is dropping actually
	 * shorter' check, then unified" - this restores exactly the first, mid-chase half, not the second).
	 * Reactive, not proactive: only when the wendigo is GENUINELY stuck (chaseUnreachableTicks having
	 * just gone from 0 to 1 this tick - the very first tick of a new stuck streak, not every tick it
	 * stays stuck, so this can't spam forceDetach against itself before gravity even has a chance to
	 * act) AND currently attached to a wall/ceiling rather than the floor (getGroundSide() != DOWN - a
	 * floor-stuck wendigo has nothing to gain from a drop). Called from both isChaseResolved and
	 * isChaseUntilLightResolved, right where each already increments chaseUnreachableTicks. */
	private void dropIfNewlyStuckOnCeiling() {
		if (this.chaseUnreachableTicks == 1 && this.self.getGroundSide() != Direction.DOWN) {
			debugSay("chase: stuck while attached to a wall/ceiling - dropping to try continuing on the ground");
			this.self.forceDetach();
		}
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
		// See isChaseResolved's own doc comment for the full reasoning - reactive again, repathing
		// (and driving chaseUnreachableTicks' own give-up bookkeeping) only once the current path has
		// actually finished or stuck detection has fired, computed once and reused for both.
		boolean needsRepath = this.self.getNavigation().isStuck() || (navigationFinished() && !withinMeleeRange());
		if (needsRepath) {
			this.chaseUnreachableTicks++;
			dropIfNewlyStuckOnCeiling();
		} else {
			this.chaseUnreachableTicks = 0;
		}
		if (this.chaseUnreachableTicks >= CHASE_GIVE_UP_TICKS) {
			debugSay("issue: couldn't reach the player during internal.chase_until_light - giving up");
			this.self.setNavigationFailed(true);
			return true;
		}
		if (needsRepath) {
			logChaseRepath("internal.chase_until_light");
			this.self.getNavigation().moveTo(player, LUNGE_CHASE_SPEED_MULTIPLIER);
			logPathNodes("internal.chase_until_light repath");
		}
		return false;
	}

	/** Passive collateral from the chase itself, not a targeted break_torch - no pathing to torches,
	 * no aiming for them, just anything within CHASE_TORCH_RADIUS of wherever the wendigo currently
	 * is gets snuffed as it runs through, always, unconditionally - combat.chase itself is now
	 * 80%+ only (see TierGates), so there's no lower tier left where a chase shouldn't also leave this
	 * kind of wreckage. Throttled so this isn't a full light-source scan every single tick.
	 * findSnuffableLightSources/snuffByWendigo, not findLightSources/destroyByWendigo - lanterns and
	 * everything else outside the snuffable scope are left alone, same narrowing performTorchBreak's
	 * own targeted case uses. Stays off for good, same as performTorchBreak's own deliberate snuff -
	 * the user's own explicit "lights should not come back on after the plan is done" request removed
	 * the post-wave relight queue this used to feed entirely (see LightSourceScanner.snuffByWendigo's
	 * own doc comment), so there's nothing left to hand collateral torches off to here either. */
	private void maybeDestroyNearbyTorches() {
		if (this.self.tickCount % CHASE_TORCH_SCAN_INTERVAL_TICKS != 0) {
			return;
		}
		if (!(this.self.level() instanceof ServerLevel serverLevel)) {
			return;
		}
		for (BlockPos torch : LightSourceScanner.findSnuffableLightSources(serverLevel, this.self.blockPosition(), CHASE_TORCH_RADIUS, CHASE_TORCH_MAX_PER_SCAN)) {
			LightSourceScanner.snuffByWendigo(serverLevel, torch, this.self);
		}
	}

	private static boolean isMovementType(String type) {
		return switch (type) {
			case "movement.approach", "movement.approach_spot",
				"movement.retreat_to_dark", "movement.retreat_with_fallback",
				"internal.despawn_move", "combat.lunge_attack", "combat.break_torch", "combat.chase",
				"internal.chase_until_light" -> true;
			default -> false;
		};
	}


	// The user's own explicit "too much fluff" request - see WendigoDebug.verboseEnabled's own doc
	// comment. Single centralized gate: every one of this class's ~50 debugSay call sites ("chase:",
	// "issue:", "drop check:", etc.) funnels through here, so this one change quiets all of them at
	// once without touching each site individually. The two things the user explicitly wants to
	// always see (the plan's own steps/predicates as they run, the AI's own previous_encounter_recap)
	// deliberately do NOT go through this method - see logStep below and
	// WendigoManager.applyPreviousEncounterRecap, both of which call WendigoDebug.say directly so they
	// print whenever a debug session is on at all, independent of verboseEnabled().
	private void debugSay(String message) {
		if (WendigoDebug.verboseEnabled() && this.self.level() instanceof ServerLevel serverLevel) {
			WendigoDebug.say(serverLevel, message);
		}
	}

	/** Always-on (not gated on verboseEnabled(), just the master debug switch inside WendigoDebug.say
	 * itself) - one of the two things the user explicitly wants kept when everything else about debug
	 * chat output got quieted down. Used for both the plan's own steps (startAction, and control.if/
	 * control.while right where nextActionStep resolves them - those two never reach startAction) and
	 * the predicates they evaluate (control.if's condition, control.while's own condition re-checked
	 * every time the loop considers running its body again) - the user's own explicit "print out one at
	 * a time as each step runs rather than printing 'stare -> chase -> control.while' at the beginning"
	 * request, replacing the old single upfront logPlanStructure summary this same always-on visibility
	 * used to belong to exclusively. */
	private void logStep(String message) {
		if (this.self.level() instanceof ServerLevel serverLevel) {
			WendigoDebug.say(serverLevel, message);
		}
	}

	// How far to search for a real attachment point near the player, vertically (findVerticalAttachablePoint,
	// ceiling/floor) - matches DarkSpotScanner.findCeilingVantagePoint's own MAX_CEILING_VANTAGE_HEIGHT
	// precedent, generous enough for a player who's flown well clear of the actual surface.
	private static final double CHASE_DESTINATION_MAX_SEARCH_DISTANCE = 30.0;

	/** Resolves where combat.chase/internal.chase_until_light should actually path toward - the
	 * player's own raw position directly, UNLESS that spot isn't itself attachable (floating in open
	 * air near, but not touching, a real surface - e.g. flying in creative just under a ceiling).
	 * Live-diagnosed as a real contributor to the recurring ceiling-flip bug: the debug path
	 * particles were confirmed terminating visibly in mid-air whenever the target player was
	 * hovering just off a surface instead of touching it, and pathing an entity toward a destination
	 * it can never actually attach to is exactly when it loses its grip and orientation.pitch never
	 * recovers (a live-confirmed SEPARATE-from-this trigger for the same symptom also exists - see
	 * this method's own callers' history - so fixing this alone isn't expected to be a full fix on
	 * its own). Finds the nearest REAL attachment point on the same surface type this entity is
	 * already using instead, using this entity's own orientation.normal (snapped to the nearest
	 * axis) - NOT getGroundSide(), which stays "down" almost unconditionally regardless of actual
	 * floor/wall/ceiling attachment, per that method's own doc comment, so it can't tell a ceiling
	 * chase from a floor one here. For a vertical normal (floor/ceiling - the common case, and the
	 * one this was actually diagnosed against), uses findVerticalAttachablePoint's own exhaustive,
	 * gapless straight-line probe rather than attachableColumn's own coarser layered-relax search (a
	 * handful of fixed offset windows that can simply miss a real surface further away than any
	 * single window reaches - confirmed live as the reason a destination could still resolve to open
	 * air even after this method's own first version shipped). Falls back to attachableColumn for a
	 * wall normal (no single vertical column to walk along the same way), then to the player's own
	 * raw position if nothing is found at all - better to attempt an imperfect path than refuse to
	 * chase entirely. */
	private Vec3 resolveChaseDestination(Player player) {
		Direction surfaceNormal = Direction.getApproximateNearest(this.self.getOrientation().normal);
		BlockPos playerPos = player.blockPosition();
		BlockPos attachable = (surfaceNormal == Direction.UP || surfaceNormal == Direction.DOWN)
			? DarkSpotScanner.findVerticalAttachablePoint(this.self.level(), playerPos, surfaceNormal, CHASE_DESTINATION_MAX_SEARCH_DISTANCE)
			: DarkSpotScanner.attachableColumn(this.self.level(), playerPos, surfaceNormal);
		if (attachable != null) {
			return new Vec3(attachable.getX() + 0.5, attachable.getY(), attachable.getZ() + 0.5);
		}
		return player.position();
	}

	// A resolved chase destination this close to where the entity already is isn't real progress -
	// see isDegenerateChaseRepath's own doc comment. 1.5 blocks, not the ARRIVAL_DISTANCE_SQR-style
	// razor-thin 0.1 blocks WendigoMoveController uses for a different purpose (confirming this tick's
	// own move target was reached) - this needs to catch "close enough that a fresh path would be
	// trivial/degenerate," a coarser bar than "exactly arrived."
	private static final double CHASE_TRIVIAL_REPATH_DISTANCE_SQR = 4.0 * 4.0;

	/** True when repathing toward destination right now would be a no-op in every way that matters -
	 * the entity is already essentially standing on it. Live-diagnosed as the actual root cause of the
	 * recurring ceiling-flip bug, more precisely than any previous theory this session: once a chase
	 * path finishes and the entity isn't yet in melee range, isChaseResolved/isChaseUntilLightResolved
	 * re-issue moveTo() EVERY SINGLE TICK the trail stays "lost" - and if the player hasn't moved
	 * enough to shift resolveChaseDestination's own answer, that's the SAME destination, over and
	 * over, computed fresh each time. Live-captured CHASE_REPATH data caught this directly: 16+
	 * consecutive ticks with selfPos completely unchanged, each one a genuine repath call - not one
	 * long detach, a storm of degenerate single-node paths landing exactly on the entity's own current
	 * position. The user's own live observation matches exactly: "one pathfinding node being placed on
	 * him" right as he arrives, continuing every tick after. internal.orbit's own wander (see
	 * tickOrbit) never does this - it only repaths on its own slower timer, and only once it actually
	 * has a genuinely different target, never immediately upon arriving at its own current one - which
	 * is exactly why wander never exhibits this bug at all. Skipping the repath entirely here (not
	 * just throttling it) leaves the entity's own already-settled attachment state alone instead of
	 * repeatedly asking AWCAPI's move controller to re-derive a direction from a near-zero delta. */
	private boolean isDegenerateChaseRepath(Vec3 destination) {
		return this.self.position().distanceToSqr(destination) < CHASE_TRIVIAL_REPATH_DISTANCE_SQR;
	}

	/** Fires the SERVER LOG (not chat - meant to correlate precisely against WendigoEntity's own
	 * WDIAG/ONGROUND_TRANSITION timestamps, not to be read live) the exact instant combat.chase/
	 * internal.chase_until_light re-issues moveTo() after losing the trail - the live-reported moment
	 * this session's single most important new lead points at directly: a wendigo driven purely by
	 * internal.orbit's own wander re-pathing (see tickOrbit) never flips even through repeated
	 * repaths, but a real chase does, right after "his path ended." Both wander and chase resolve to
	 * the same underlying PathNavigation.moveTo call once invoked (confirmed via decompile - the
	 * Entity-target overload just snapshots the entity's current position into an ordinary Path, no
	 * different from a raw x/y/z target), so if this line's own timestamp lines up with a WDIAG pitch
	 * jump or ONGROUND_TRANSITION, the actual difference has to be something upstream of the
	 * pathfinder itself - what triggers THIS re-path (navigationFinished()/isStuck(), on a MOVING,
	 * possibly-unreachable-in-3D player position) versus wander's own trigger (a fixed timer, toward a
	 * position already pre-validated as attachable by findLiveBandPosition3D). */
	private void logChaseRepath(String primitive) {
		if (!WendigoDebug.verboseEnabled() || !(this.self.level() instanceof ServerLevel serverLevel)) {
			return;
		}
		boolean stuck = this.self.getNavigation().isStuck();
		boolean navFinished = navigationFinished();
		WendigoMod.LOGGER.info("CHASE_REPATH id={} t={} primitive={} stuck={} navFinished={} unreachableTicks={} selfPos={}",
			this.self.getId(), String.format("%.2f", serverLevel.getServer().getTickCount() / 20.0),
			primitive, stuck, navFinished, this.chaseUnreachableTicks, this.self.blockPosition().toShortString());
	}

	/** Dumps every node in the entity's OWN CURRENT path (read right after a moveTo call, so this
	 * shows what AWCAPI's own pathfinder actually produced, not what was requested) - real ground
	 * truth instead of more theorizing about what a "bad" path node might look like. For each node,
	 * logs its raw x/y/z plus, if it's a DirectionalPathPoint (confirmed public via javap against
	 * AWCAPI's own jar - getPathSide()/getPathableSides()/isDrop() all directly callable), its own
	 * resolved attachment side, every side AWCAPI considered climbable there, and whether it's a
	 * drop node - or, if it's a PLAIN vanilla Node instead, says so explicitly and names the class.
	 * AdvancedClimberPathNavigator.followThePath() (confirmed via decompile - see WendigoMoveController's
	 * own class doc comment) only uses a node's real attachment side at all when it's a
	 * DirectionalPathPoint with a non-null getPathSide() - anything else falls back to a plain,
	 * side-less vanilla move. This is the direct, empirical way to see whether that's actually
	 * happening on the specific paths that trigger the flip, instead of inferring it secondhand. */
	private void logPathNodes(String context) {
		if (!WendigoDebug.verboseEnabled() || !(this.self.level() instanceof ServerLevel serverLevel)) {
			return;
		}
		Path path = this.self.getNavigation().getPath();
		if (path == null) {
			WendigoMod.LOGGER.info("PATH_NODES id={} t={} context={} path=null", this.self.getId(),
				String.format("%.2f", serverLevel.getServer().getTickCount() / 20.0), context);
			return;
		}
		StringBuilder nodes = new StringBuilder();
		for (int i = 0; i < path.getNodeCount(); i++) {
			Node node = path.getNode(i);
			if (nodes.length() > 0) {
				nodes.append(", ");
			}
			nodes.append('[').append(i).append("]=(").append(node.x).append(',').append(node.y).append(',').append(node.z).append(')');
			if (node instanceof DirectionalPathPoint dpp) {
				nodes.append(" DIRECTIONAL side=").append(dpp.getPathSide())
					.append(" pathableSides=").append(java.util.Arrays.toString(dpp.getPathableSides()))
					.append(" isDrop=").append(dpp.isDrop());
			} else {
				nodes.append(" PLAIN class=").append(node.getClass().getSimpleName());
			}
		}
		WendigoMod.LOGGER.info("PATH_NODES id={} t={} context={} nextIndex={} nodeCount={} nodes=[{}]",
			this.self.getId(), String.format("%.2f", serverLevel.getServer().getTickCount() / 20.0),
			context, path.getNextNodeIndex(), path.getNodeCount(), nodes);
	}

	/** True for the specific degenerate path signature live-captured (via logPathNodes' own output)
	 * as the direct trigger for the recurring ceiling-flip bug: a single node whose own
	 * getPathableSides() comes back completely empty, even though the node itself is a real
	 * DirectionalPathPoint with a non-null side. A genuinely well-formed path (confirmed by comparing
	 * against a healthy 22-node path logged moments earlier in the same live test) always has a
	 * non-empty pathableSides at every node - this one-node/empty-sides shape only ever showed up
	 * right as AWCAPI's own pathfinder gave up finding a real route and handed back a bare
	 * best-effort destination point instead of an actual path, immediately followed by the entity's
	 * own orientation snapping to the floor default and never recovering. */
	private boolean isDegeneratePath() {
		Path path = this.self.getNavigation().getPath();
		if (path == null || path.getNodeCount() != 1) {
			return false;
		}
		return path.getNode(0) instanceof DirectionalPathPoint dpp && dpp.getPathableSides().length == 0;
	}

	/** Called right after every moveTo() this class issues for combat.lunge_attack/combat.chase/
	 * internal.chase_until_light - cancels the path immediately, before the entity ever takes a step
	 * along it, if it's the degenerate signature isDegeneratePath() checks for. Better to make no
	 * progress this cycle (the next timer tick, or the next primitive dispatch, gets another attempt
	 * with a fresh destination) than to let the entity actually walk a path already known to trigger
	 * the flip. */
	private void rejectDegeneratePathIfNeeded(String context) {
		if (!isDegeneratePath()) {
			return;
		}
		this.self.getNavigation().stop();
		if (WendigoDebug.anyEnabled() && this.self.level() instanceof ServerLevel serverLevel) {
			WendigoMod.LOGGER.info("REJECTED_DEGENERATE_PATH id={} t={} context={}", this.self.getId(),
				String.format("%.2f", serverLevel.getServer().getTickCount() / 20.0), context);
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

	private void playAmbientCue(String cue) {
		if (!(this.self.level() instanceof ServerLevel serverLevel)) {
			return;
		}
		// Centered on every player, offset toward self - see WendigoSounds' own class doc comment. No
		// player currently resolvable (rare - Targeting.nearestPlayer's own "nothing found" case)
		// means nobody to play this for at all, so skip rather than calling play() for no reason.
		Player target = Targeting.nearestPlayer(this.self);
		if (target == null) {
			return;
		}
		WendigoSounds.Type type = switch (cue) {
			case "chase" -> WendigoSounds.Type.CHASE;
			case "flee" -> WendigoSounds.Type.FLEE;
			case "stare" -> WendigoSounds.Type.STARE;
			default -> WendigoSounds.Type.AMBIENT; // "ambient"
		};
		WendigoSounds.play(serverLevel, this.self, type);
		this.soundCueCount++; // hidden goal-progress signal - see the field's own comment
		recordSoulLightTally(target);
		debugSay("sound cue played: " + cue);
	}

	// The user's own explicit "within 14 blocks" threshold for sound.breathe's own success condition
	// (changed from an initial 16 the same session) - see successfulBreatheCount's own field comment.
	private static final double BREATHE_SUCCESS_RADIUS = 14.0;

	/** sound.breathe always plays regardless of distance (see WendigoSounds.playBreathe - the same
	 * "always plays, never a no-op" shape combat.break_torch/combat.lunge_attack's own precondition-free
	 * siblings have), but only counts as a hidden goal-progress "successful" breathe (see
	 * successfulBreatheCount's own field comment) when ANY nearby player is within
	 * BREATHE_SUCCESS_RADIUS at the moment it plays - the user's own explicit "the wendigo is
	 * recommended to get as close as possible... before running that noise" framing, checked against
	 * every nearby player (Targeting.nearbyPlayers), not just whichever one is locked/nearest -
	 * deliberately still multiplayer-global ("did ANY player experience this"), unlike stare
	 * DETECTION (PlanPredicates.isLookedAtByTarget, now target-only - see its own doc comment); this
	 * is closer in kind to approach detection (hasApproachedByAnyone), an atmosphere/opportunity
	 * signal rather than a "was I personally spotted" one. */
	private void playBreathe() {
		if (!(this.self.level() instanceof ServerLevel serverLevel)) {
			return;
		}
		WendigoSounds.playBreathe(serverLevel, this.self);
		boolean successful = false;
		for (Player player : Targeting.nearbyPlayers(this.self)) {
			if (this.self.distanceTo(player) <= BREATHE_SUCCESS_RADIUS) {
				successful = true;
				break;
			}
		}
		if (successful) {
			this.successfulBreatheCount++;
			recordSoulLightTally(Targeting.nearestPlayer(this.self));
		}
		debugSay("breathe played - successful=" + successful);
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

	// Tried against attachableColumn/isAttachable for each live-band position pick below - UP (floor)
	// and DOWN (ceiling) alongside all 4 horizontal walls, so orbit settles onto whatever surface
	// actually has a valid in-band spot rather than being forced toward any one of them. User's own
	// explicit call: forcing a ceiling vantage point every recheck (the old behavior) meant the
	// wendigo was permanently navigating straight back overhead the instant it settled anywhere else,
	// which in practice prevented the lateral wander below from ever doing anything - it never got a
	// chance to hold a non-ceiling position long enough to matter.
	private static final Direction[] ORBIT_SURFACE_NORMALS =
		{Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

	private Direction randomOrbitSurfaceNormal() {
		return ORBIT_SURFACE_NORMALS[this.self.getRandom().nextInt(ORBIT_SURFACE_NORMALS.length)];
	}

	/** Bounded-retry candidate-and-verify loop shared by tickOrbit's in-band wander and out-of-band
	 * waypoint branches - the user's own explicit "if he can't reach the player in a spot he
	 * spawns/teleports to, try again" request, applied to the one orbit-position search that
	 * previously had NO reachability check or retry of any kind (findLiveBandPosition3D's own doc
	 * comment admits it's "deliberately NOT flood-verified reachable"). Samples a fresh
	 * findLiveBandPosition3D candidate (fresh randomOrbitSurfaceNormal() each attempt, same as
	 * before) and computes a REAL path to it from the entity's own current, actual position - no
	 * snap/teleport, since the entity is walking there, not blinking there (unlike WendigoManager's
	 * teleport-relocate callers). Returns the already-computed Path itself, not just a destination
	 * BlockPos, so the caller can hand it straight to PathNavigation.moveTo(Path, double) instead of
	 * re-pathing from scratch via the (x,y,z,speed) overload (that overload's own body is just
	 * createPath(x,y,z,1) then moveTo(path,speed) - reusing our own already-computed path skips the
	 * redundant createPath entirely). Bounded by WendigoTuningConfig.orbitReachRetryAttempts, the same
	 * shared budget every orbit-position search in this codebase now uses. Returns null if every
	 * attempt fails verification - callers fall back to their own existing unverified behavior, same
	 * "some darkness beats none" last resort as before, just no longer the first thing tried. */
	private Path findReachableOrbitPath(double minDistance, double maxDistance, BlockPos targetPos, CaveScale caveScale) {
		for (int attempt = 0; attempt < WendigoMod.tuningConfig.orbitReachRetryAttempts; attempt++) {
			BlockPos candidate = DarkSpotScanner.findLiveBandPosition3D(this.self.level(), targetPos,
				minDistance, maxDistance, randomOrbitSurfaceNormal());
			if (candidate == null) {
				continue;
			}
			// distance=1, NOT 0 - confirmed via decompile that createPath(BlockPos, int) resolves
			// through materially different internal search parameters than createPath(Entity, int)
			// (WendigoManager.pathToTarget's own entity-target convention, distance=0 there) - a raw
			// BlockPos target needs distance=1 to reliably resolve canReach()=true for legitimately
			// close candidates, confirmed live via a GameTest that started failing on a trivial
			// same-room candidate with distance=0. This also happens to be the exact distance value
			// the old moveTo(x,y,z,speed) already used internally (it resolves through this same
			// BlockPos overload) - so reusing this Path via moveTo(Path, double) is a genuine zero
			// behavior change in arrival tolerance, not the "minor tightening" distance=0 would have
			// been.
			Path path = this.self.getNavigation().createPath(candidate, 1);
			if (SemanticBands.isAcceptableOrbitPath(path, caveScale)) {
				return path;
			}
		}
		return null;
	}

	// How far past the entity's own current position the "run directly away" waypoint gets placed
	// each recheck - just needs to be far enough that a fresh moveTo call actually commits to real
	// distance each time rather than fizzling out almost immediately once reached, not tied to any
	// particular cave-scale band the way ordinary orbit distance is (this doesn't care about bands).
	private static final double FLEE_WHILE_GLOWING_STEP_DISTANCE = 12.0;

	/** Straight-line "get away from whoever's closest" movement while genuinely glowing - see
	 * tickOrbit's own doc comment for why this fires. Re-evaluates on the same ORBIT_RECHECK_INTERVAL_
	 * TICKS cadence ordinary orbit rechecks use (reusing orbitRecheckTicks itself - the two states are
	 * mutually exclusive within a single tick, tickOrbit only ever calls one or the other, so sharing
	 * the counter is safe and avoids a redundant field). Deliberately doesn't verify darkness/
	 * reachability/pathfind-acceptability the way ordinary orbit waypoints do - the point is raw
	 * distance from the target as fast as possible, not a considered destination; PathNavigation
	 * .moveTo's own normal unreachable-target handling (silently finds nothing, tries again next
	 * recheck) is all the robustness this needs. LUNGE_CHASE_SPEED_MULTIPLIER (SemanticBands' fastest
	 * tier, "fast") - the user's own explicit "at the max speed." */
	private void tickFleeWhileGlowing(Player target) {
		this.orbitRecheckTicks++;
		if (this.orbitRecheckTicks < ORBIT_RECHECK_INTERVAL_TICKS) {
			return;
		}
		this.orbitRecheckTicks = 0;
		Vec3 selfPos = this.self.position();
		Vec3 away = selfPos.subtract(target.position());
		if (away.lengthSqr() < 1.0E-4) {
			// Degenerate case (standing right on top of the target) - direction is meaningless, pick
			// an arbitrary horizontal one rather than leaving them stuck with nowhere to flee toward.
			double angle = this.self.getRandom().nextDouble() * 2.0 * Math.PI;
			away = new Vec3(Math.cos(angle), 0.0, Math.sin(angle));
		}
		Vec3 destination = selfPos.add(away.normalize().scale(FLEE_WHILE_GLOWING_STEP_DISTANCE));
		this.self.setLightTolerantPathing(false);
		boolean started = this.self.getNavigation().moveTo(destination.x, destination.y, destination.z,
			LUNGE_CHASE_SPEED_MULTIPLIER);
		debugMoveTo("flee-while-glowing", started);
	}

	/** The internal.orbit primitive's own per-tick logic - see startOrbit for entry and
	 * isOrbiting/isOrbitTargetLost/isOrbitTrapped for what WendigoManager polls. Re-evaluates roughly
	 * every ORBIT_RECHECK_INTERVAL_TICKS (not every tick - this doesn't need to be as responsive as
	 * an actual plan action, and re-picking a waypoint every single tick would fight its own
	 * in-flight navigation). Holds the ordinary horizontal cave-scaled band (SemanticBands.
	 * orbitMinDistance/orbitMaxDistance - tighter in a small cave, see CaveScaleScanner): moving
	 * toward a fresh dark waypoint if outside it, on a randomly picked surface (see
	 * randomOrbitSurfaceNormal) each time - floor, ceiling, or a wall, whatever's actually reachable
	 * within the band, not forced toward any one of them (no more standing ceiling-vantage
	 * preference - see ORBIT_SURFACE_NORMALS' own comment for why that was removed). Once genuinely
	 * in-band, doesn't just hold that one point forever either - see the wander timer below, so
	 * different lateral positions (and whatever torches/geometry/surface happen to be near each one)
	 * come into play over time rather than every engagement starting from the exact same angle on the
	 * player. Uses light-averse pathing (setLightTolerantPathing(false), same default every other
	 * action already uses) - the existing DarknessMalus soft-cost/hard-block-below-40%-severity
	 * system already gives "prefer dark routes, only cross light when severity allows and there's no
	 * better option" for free, no separate light-crossing mechanism needed here. */
	private void tickOrbit() {
		Player target = Targeting.nearestPlayer(this.self);
		if (target == null) {
			this.orbitTargetLost = true;
			return;
		}
		// The user's own explicit follow-up: while genuinely glowing (a real landed spectral hit -
		// isCurrentlyGlowing() only ever reflects setGlowingTag, never the debug-only rig glow, which
		// lives entirely in WendigoVisual and never touches this flag - so this is already naturally
		// excluded, no separate check needed) he should keep running directly away from whoever's
		// closest instead of settling into (or re-entering) the normal orbit band at all - "he does
		// not care about orbit distance after just getting shot," so a player who trapped him inside
		// orbit range can't just keep shooting him in place. Checked here, at the very top of every
		// orbit tick, rather than only at the moment orbiting starts - WendigoManager's own post-wave
		// routing (startOrbit/startReturnToOrbit) doesn't know or care about glow state, so this is
		// what actually intercepts it regardless of which path led back into "orbiting=true." Falls
		// through to the real orbit logic below the instant glow ends on its own (updateGlow).
		if (this.self.isCurrentlyGlowing()) {
			tickFleeWhileGlowing(target);
			return;
		}
		if (this.self.getNavigation().isInProgress()) {
			updateOrbitStuckTracking();
		} else {
			this.orbitStuckCheckPosition = null;
			this.orbitStuckCheckTicks = 0;
			this.orbitStuckWindowsFailed = 0;
		}
		this.orbitRecheckTicks++;
		if (this.orbitRecheckTicks < ORBIT_RECHECK_INTERVAL_TICKS) {
			return;
		}
		this.orbitRecheckTicks = 0;
		CaveScale caveScale = CaveScaleScanner.classify(this.self.level(), this.self.blockPosition());
		double minDistance = SemanticBands.orbitMinDistance(caveScale);
		double maxDistance = SemanticBands.orbitMaxDistance(caveScale);
		BlockPos selfPos = this.self.blockPosition();
		BlockPos targetPos = target.blockPosition();
		double distance = this.self.distanceTo(target);
		if (distance >= minDistance && distance <= maxDistance) {
			// In-band - normally just holds here, except for the occasional wander (see field
			// comment): pick a fresh in-band position and walk there instead of staying frozen at
			// this exact spot forever.
			if (this.self.tickCount >= this.nextOrbitWanderTick) {
				this.nextOrbitWanderTick = this.self.tickCount + rollOrbitWanderDelay();
				// See WendigoTuningConfig.orbitWanderSkipChance's own comment - some wander deadlines
				// just re-roll and do nothing instead of always relocating, so holding position is a
				// real, common outcome too, not just a brief pause between guaranteed moves.
				if (this.self.getRandom().nextDouble() < WendigoMod.tuningConfig.orbitWanderSkipChance) {
					this.self.getNavigation().stop();
					return;
				}
				this.self.setLightTolerantPathing(false);
				// findLiveBandPosition3D, not the flood-based findLiveBandPosition - see its own doc
				// comment: the flood only ever searches for the SAME normal at every step, so a
				// ceiling/wall pick from a floor-standing entity routinely failed to even find a seed
				// column, systematically under-representing anything but the floor despite
				// randomOrbitSurfaceNormal() already intending an equal shot at all 6 directions. Now
				// real-pathfind-verified (see findReachableOrbitPath) rather than accepted on geometry
				// alone.
				Path wanderPath = findReachableOrbitPath(minDistance, maxDistance, targetPos, caveScale);
				if (wanderPath != null) {
					// moveTo(Path, double) returns boolean (confirmed via decompile) - orbit doesn't lean on
					// setNavigationFailed at all (isInProgress()/updateOrbitStuckTracking above already covers
					// "did this actually go anywhere," including a moveTo that never started), so capturing
					// this is purely for debug visibility, not a behavior fix like the approach_spot one was.
					boolean wanderStarted = this.self.getNavigation().moveTo(wanderPath, ORBIT_IN_BAND_SPEED_MULTIPLIER);
					debugMoveTo("orbit.wander", wanderStarted);
					return;
				}
			}
			this.self.getNavigation().stop();
			return;
		}
		// Real live bug ("spazzy... can't seem to settle on a spot"): before this check existed, EVERY
		// recheck (every ~1s, ORBIT_RECHECK_INTERVAL_TICKS) while still out of band re-rolled a brand
		// new waypoint - a fresh randomOrbitSurfaceNormal() each time, so potentially a totally
		// different surface/direction than whatever it was already mid-path toward - and immediately
		// redirected onto it via moveTo, which restarts the path from scratch. Reaching an out-of-band
		// waypoint (especially a cross-surface one - floor to a wall/ceiling pick) routinely takes
		// longer than 1s, so this was constantly yanking the mob onto a new target before it ever got
		// anywhere near the last one, reading as erratic thrashing rather than purposeful movement -
		// and the resulting back-and-forth could easily carry it closer to the player mid-thrash than
		// any single chosen waypoint ever intended, which is almost certainly also behind the separate
		// "sometimes navigates toward the player" symptom reported alongside this. Fixed the same way
		// this file already treats "is the current attempt actually failing" elsewhere (isMakingNoProgress/
		// updateOrbitStuckTracking): only actually re-pick once navigation isn't already in progress
		// (arrived, gave up, or never started) OR the stuck-tracking above has flagged at least one full
		// ORBIT_STUCK_WINDOW_TICKS window with no real progress - otherwise just let the in-flight path
		// keep running and skip straight past the repick this cycle.
		if (this.self.getNavigation().isInProgress() && this.orbitStuckWindowsFailed == 0) {
			return;
		}
		this.self.setLightTolerantPathing(false);
		Path waypointPath = findReachableOrbitPath(minDistance, maxDistance, targetPos, caveScale);
		if (waypointPath != null) {
			// Same debug-visibility-only capture as the in-band wander branch above - see its comment.
			boolean waypointStarted = this.self.getNavigation().moveTo(waypointPath, orbitSpeedMultiplier(distance, minDistance, maxDistance));
			debugMoveTo("orbit.waypoint", waypointStarted);
			return;
		}
		// Nothing in-band/reachable within the retry budget - unverified last resort, same philosophy
		// as WendigoManager's own fallbacks: head somewhere dark and away from the target rather than
		// standing still with no destination at all.
		BlockPos waypoint = DarkSpotScanner.findDarkestAwayFrom(this.self.level(), selfPos, maxDistance, targetPos);
		if (waypoint != null) {
			this.self.getNavigation().moveTo(waypoint.getX() + 0.5, waypoint.getY(), waypoint.getZ() + 0.5,
				orbitSpeedMultiplier(distance, minDistance, maxDistance));
		}
	}

	/** The user's own explicit request: normal orbit speed right at either band edge, lerping up to
	 * ORBIT_URGENT_SPEED_MULTIPLIER once ORBIT_SPEED_RAMP_DISTANCE blocks past whichever edge is
	 * nearer - starting the instant the wendigo steps outside the band, not after some extra grace
	 * distance first. distance is expected to already be outside [minDistance, maxDistance] (the only
	 * case tickOrbit's own out-of-band branch calls this from); 0 exactly at either edge, clamped to 1
	 * (full urgency) beyond the ramp distance. */
	private static double orbitSpeedMultiplier(double distance, double minDistance, double maxDistance) {
		double distanceOutsideBand = distance < minDistance ? minDistance - distance : distance - maxDistance;
		double t = Math.min(1.0, distanceOutsideBand / ORBIT_SPEED_RAMP_DISTANCE);
		double normalSpeed = SemanticBands.speedMultiplier("normal");
		return normalSpeed + t * (ORBIT_URGENT_SPEED_MULTIPLIER - normalSpeed);
	}

	private int rollOrbitWanderDelay() {
		return ORBIT_WANDER_MIN_TICKS + this.self.getRandom().nextInt(ORBIT_WANDER_MAX_TICKS - ORBIT_WANDER_MIN_TICKS + 1);
	}

	/** Same windowed snapshot-and-compare technique as isMakingNoProgress, just accumulated across
	 * multiple failed windows (see ORBIT_TRAPPED_WINDOWS) rather than acting on the first one -
	 * orbit isn't ending an action the instant one window comes up short, it's deciding whether to
	 * give up on the current waypoint attempt entirely. Only called while a waypoint move is
	 * actually in progress (see tickOrbit) - holding position in-band would otherwise also look
	 * "stuck" under a naive displacement check. */
	private void updateOrbitStuckTracking() {
		Vec3 current = this.self.position();
		if (this.orbitStuckCheckPosition == null) {
			this.orbitStuckCheckPosition = current;
			this.orbitStuckCheckTicks = 0;
			return;
		}
		this.orbitStuckCheckTicks++;
		if (this.orbitStuckCheckTicks < ORBIT_STUCK_WINDOW_TICKS) {
			return;
		}
		boolean noProgress = current.distanceToSqr(this.orbitStuckCheckPosition) < ORBIT_STUCK_DISTANCE_SQR;
		this.orbitStuckCheckPosition = current;
		this.orbitStuckCheckTicks = 0;
		this.orbitStuckWindowsFailed = noProgress ? this.orbitStuckWindowsFailed + 1 : 0;
	}

	/** See startReturnToOrbit - waits for the one-shot walk there to either arrive or give up
	 * (unreachable/stuck), then hands off to startOrbit. Deliberately simple (vanilla's own
	 * isStuck()/isNavigationFailed, not the windowed tracking tickOrbit's own waypoint-following
	 * uses) since this is a short, one-time transition, not a steady state that needs to resist
	 * false positives from a long hold. */
	private void tickReturnToOrbit() {
		PathNavigation nav = this.self.getNavigation();
		boolean arrivedOrGaveUp = (nav.isDone() && !nav.isInProgress()) || this.self.isNavigationFailed() || nav.isStuck();
		if (arrivedOrGaveUp) {
			ServerPlayer target = this.returnToOrbitTarget;
			this.returningToOrbit = false;
			this.returnToOrbitTarget = null;
			startOrbit(target);
		}
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

	/** Hardcoded (not model-authored) stare-interrupt safety valve - the user's own explicit "a
	 * hardcoded stare rule that moves on with the plan if ANY player in the group starts approaching
	 * him at medium approach scale" request. "Any player" deliberately, unlike PlanPredicates.
	 * isLookedAtByTarget's own target-only look-detection (see that method's own doc comment) - a
	 * DIFFERENT group member closing in on the wendigo mid-stare is still a real approaching threat/
	 * opportunity worth reacting to, even if they're not who the encounter is officially "for."
	 * Reuses whileBaselineDistance (NaN with no active control.while, which hasApproachedByAnyone
	 * already treats as "nothing covered yet" - false, a safe no-op outside a while loop). */
	private boolean isAnyPlayerApproachingDuringStare() {
		return PlanPredicates.hasApproachedByAnyone(this.self, "medium", this.whileBaselineDistance);
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
	// makes it that much harder to shake off once caught. See WendigoTuningConfig.rideEscapeAttemptsMin/
	// Max, editable at config/wendigo-tuning.json.
	// Dealt once, only if the wave concludes (a real despawn, not a forced/backstop wave-end) while
	// still forcing the ride - see completeWave/resolveRiderOnEnd. The user's own real, live-tested
	// values - "/damage @s <amount> minecraft:mob_attack" (the exact same damage source this hit
	// itself uses, no weapon item, so no enchant-effectiveness wrinkle either) against a player
	// wearing each tier bare, from full health - replacing an earlier hand-derived-from-CombatRules
	// approximation (this codebase's own simplified re-implementation of the real formula landed
	// close but not exact; these are the real tested numbers, not computed ones). Stage 1 isn't
	// separately specified by the user - reuses stage 2's leather value, since there's no armor tier
	// weaker than leather to reach for instead. Unclamped now (a real kill is allowed at every stage,
	// not just stage 5 - the user's own explicit "let the wendigo finish the player off at other
	// [stages] too" reversal of an earlier restriction - see despawnDamageForCurrentStage).
	// Index 0 unused (stages are 1-5) - mirrors WendigoProgressionTracker.STAGE_PERCENTS' own
	// same-shape 0-padded array for the same reason (stage numbers read naturally as array indices).
	private static final float[] STAGE_DESPAWN_RAW_DAMAGE = {
		0.0F,
		22.0F, // stage 1 (see above) - leather
		22.0F, // stage 2 - leather
		22.0F, // stage 3 - copper
		24.0F, // stage 4 - iron
		36.0F, // stage 5 - diamond
	};

	/** Same stage bucketing STAGE1_MAX_PERCENT already establishes for stage 1 (severityPercent < 20),
	 * extended symmetrically across the rest of WendigoProgressionTracker.STAGE_PERCENTS' own fixed
	 * per-stage values (10/30/50/70/90) - every real wave's severityPercent is always exactly one of
	 * those five (see rideFairChanceTicks's own comment on STAGE1_PERCENT/STAGE5_PERCENT), so bucket
	 * boundaries at the midpoints (20/40/60/80) unambiguously recover the stage a wave belongs to. */
	private static int stageForSeverityPercent(int severityPercent) {
		if (severityPercent < 20) {
			return 1;
		}
		if (severityPercent < 40) {
			return 2;
		}
		if (severityPercent < 60) {
			return 3;
		}
		if (severityPercent < 80) {
			return 4;
		}
		return 5;
	}

	private static final String RIDE_ESCAPE_HINT = "Spam SHIFT to break free!";
	// How long the ride has to have actually run (see rideTicks) before completeWave/resolveRiderOnEnd
	// is allowed to deal the despawn damage - see their own comments for the real bug this originally
	// guarded against (catching the player and completing the wave the same tick). A plain real-time
	// countdown now, not gated on the player's actual light level - the user's own explicit
	// simplification, dropping the earlier "must have stood in darkness this long" requirement (which
	// a player able to light up their own surroundings while carried could otherwise dodge forever).
	// Scales with severity - see WendigoTuningConfig.rideFairChanceSecondsAtStage1/5, editable at
	// config/wendigo-tuning.json (the user's own explicit numbers: 7s at stage 1 down to 2s at stage 5
	// by default). Interpolated against the REAL stage representative percents
	// (WendigoProgressionTracker.representativePercent: 10/30/50/70/90 for stages 1-5, duplicated here
	// as STAGE1_PERCENT/STAGE5_PERCENT - NOT config-exposed, unlike the seconds values above, since
	// these have to match representativePercent's own real values or the interpolation breaks) - an
	// earlier version of this interpolated against a raw 20-100 range instead, which doesn't match any
	// stage's real percent at either end (stage 1 is 10%, not 20%; stage 5 is 90%, not 100%) and open a
	// real, live-reported bug: stage 1 got clamped up to the 20%-anchored value every time (never
	// actually reaching the intended longest wait) while stage 5's own 90% landed short of the
	// 100%-anchored value too - neither endpoint the formula was tuned around was ever actually hit by
	// a real stage. Clamped flat outside [STAGE1_PERCENT, STAGE5_PERCENT] rather than extrapolating
	// further (no stage's percent ever falls outside that range anyway).
	private static final int STAGE1_PERCENT = 10;
	private static final int STAGE5_PERCENT = 90;

	private static int rideFairChanceTicks(int severityPercent) {
		WendigoTuningConfig tuning = WendigoMod.tuningConfig;
		int clampedPercent = Math.clamp(severityPercent, STAGE1_PERCENT, STAGE5_PERCENT);
		double seconds = tuning.rideFairChanceSecondsAtStage1
			+ (clampedPercent - STAGE1_PERCENT) * (double) (tuning.rideFairChanceSecondsAtStage5 - tuning.rideFairChanceSecondsAtStage1)
				/ (STAGE5_PERCENT - STAGE1_PERCENT);
		return (int) Math.round(seconds * 20.0); // ticks
	}

	private boolean forcingRide;
	// See isFleeingFromSpectralHit's own doc comment - set by forceFleeToOrbit, cleared by
	// completeWave once the forced flee genuinely finishes.
	private boolean fleeingFromSpectralHit;
	private int rideEscapeAttempts;
	private int rideEscapeThreshold;
	// Set once per grab from rideFairChanceTicks(severityPercent) (see beginForcedRide) -
	// hasHadFairRideChance's own threshold.
	private int rideFairChanceThreshold;
	// Cumulative ticks spent forcingRide - reset in beginForcedRide, incremented unconditionally every
	// tick in updateForcedRide regardless of whether the rider is currently mounted or momentarily off
	// getting force-remounted, and regardless of the player's actual light level (see
	// hasHadFairRideChance's own comment - deliberately no darkness requirement anymore, the user's
	// own explicit simplification). Plain elapsed carry time, nothing more.
	private int rideTicks;
	// Set the instant a forced ride ends and the rider is actually released, however that happened -
	// updateForcedRide's own dismount-threshold path (a genuine "spammed shift enough times" escape)
	// OR resolveRiderOnEnd (a carry that resolved into a drop, with or without the despawn damage
	// landing). See WendigoManager.checkUnconditionalGrab, which must not immediately re-grab someone
	// who was just released while they're still standing right next to (or literally on top of) the
	// wendigo - true for an escape (never went anywhere) just as much as a carry-flee drop (the
	// wendigo walked THEM there, so they start right at 0 distance the instant they're set free) -
	// or the release would be undone the very next tick, forever. Consumed (read and cleared) via
	// consumeRideJustEnded rather than polled, so a tick where nobody checks it can't lose it.
	private boolean rideJustEnded;
	// Who's actually being carried - getFirstPassenger() goes empty the instant they dismount, so a
	// separate reference is needed to know who to force back on.
	private Player ridingPlayer;

	// See startCarryFlee/tickCarryFlee - the post-grab "carry them off into the dark, away from where
	// they were caught" sub-state, mirroring returningToOrbit's own small-dedicated-sub-state shape.
	private boolean carryingAway;
	// Where the grab actually happened (self's position at that moment) - the reference point the
	// flee bands are measured from, NOT the player's own live position (~0 blocks away the whole
	// time they're mounted, which would make every band trivially "satisfied" immediately).
	private BlockPos grabLocation;
	private int carryFleeAttempts;
	private static final int MAX_CARRY_FLEE_ATTEMPTS = 4; // mirrors MAX_DESPAWN_ATTEMPTS
	// Randomized per grab (see beginNextCarryFleeAttempt) rather than always the farthest band - "a
	// random distance band away from the grab spot" per the user's own request, so a shallow grab
	// near the surface doesn't always drag the player the absolute maximum distance every single time.
	private static final String[] CARRY_FLEE_BANDS = {"medium", "far", "farther", "farthest"};
	// The carry resolves on a flat timer (see startCarryFlee/tickCarryFlee), not on ever actually
	// reaching the live-band flee target - per the user's own explicit request, replacing an earlier
	// arrival-based design. A passenger-carrying pathfind through real cave terrain toward a far-off
	// band isn't guaranteed to cleanly arrive-or-fail within any bounded time, and a flat timer gives a
	// predictable "how long am I being carried" window regardless. The flee target picked below is
	// still used to actually keep moving toward darkness during that window - just no longer what
	// decides when the carry ends. Set directly from rideFairChanceThreshold (see startCarryFlee) -
	// NOT its own separate flat-random range like it used to be (a real, live-reported bug: that old
	// range, 5-15s, was always longer than rideFairChanceThreshold's own stage-scaled 2-7s max, so this
	// timer - not that one - was silently the ACTUAL gate on how long a carry lasted, completely
	// undoing the stage scaling despite hasHadFairRideChance/resolveRiderOnEnd still reading
	// rideFairChanceThreshold correctly once this finally did fire). One shared, stage-scaled source of
	// truth now instead of two disconnected timers.
	private int carryFleeReleaseTick;

	/** True while a player is currently a forced rider - see WendigoManager.overrideIntoChaseUntilLight,
	 * which must not restart internal.chase_until_light from scratch while this is already true (that
	 * would call beginForcedRide a second time on someone already caught, discarding despawn progress
	 * and re-rolling a fresh escape/grace-period pair every time it happens). */
	public boolean isForcingRide() {
		return this.forcingRide;
	}

	/** True from the instant a landed spectral hit starts a forced flee (forceFleeToOrbit) until that
	 * flee genuinely finishes (completeWave, whichever way it resolves - a real despawn-point
	 * arrival or an exhausted fallback chain). The user's own explicit follow-up: fleeing from a hit
	 * must not be interruptible by a hardcoded override (WendigoManager.overrideIntoChaseUntilLight/
	 * overrideIntoLunge/checkUnconditionalGrab all check this before firing) - he needs to actually
	 * finish fleeing before anything else can start running on him again. */
	public boolean isFleeingFromSpectralHit() {
		return this.fleeingFromSpectralHit;
	}

	/** A landed spectral arrow hit (see WendigoEntity.hurtServer) immediately abandons whatever this
	 * wave was doing and flees back into darkness/orbit - the user's own explicit follow-up request,
	 * the same shape the old, now-removed spear-defense repel used to trigger. Deliberately doesn't
	 * inject a new hardcoded plan the way startOverride does - just empties the current one entirely
	 * (an already-exhausted plan, same as a normal completion), so the very next tick's advance()
	 * falls straight into the same hasDespawnWork()/farthest-band flee every other plan exhaustion
	 * already uses, instead of a second, separate flee implementation.
	 * <p>
	 * Also clears currentAction, not just topLevelSteps/actionQueue - a real bug in the first version
	 * of this method, confirmed live: tick()'s own "if (this.currentAction != null) { if
	 * (!isActionDone()) return; ...}" gate runs BEFORE advance() ever gets a chance to notice the
	 * emptied plan, so a hit landing mid-action (mid-chase, most commonly - exactly when a player
	 * would realistically be shooting him) left the STALE currentAction in place, silently blocking
	 * tick() from ever reaching advance() until that old action happened to resolve on its own,
	 * however long that took - "doesn't flee at all" from the outside, even though the plan really
	 * had been emptied. Clearing it here (mirroring exactly how preemptWithAction/the chase-give-up
	 * abandon branch above both already interrupt an in-progress action immediately, not just let it
	 * finish) makes the very next tick's advance() actually run.
	 * <p>
	 * Also resets despawnAttemptCount/despawnSucceeded, not just despawnEnabled - a second real bug
	 * confirmed live alongside the currentAction one above: if the wendigo was hit while ORBITING
	 * (arguably the single most common time to actually get shot - nothing stops a player from
	 * shooting on sight before he's even properly engaged), these carry over STALE from whatever the
	 * last completed wave left them at, since only startInternal (an ordinary start/startOverride)
	 * ever resets them - startOrbit itself doesn't touch them at all. An already-exhausted stale
	 * despawnAttemptCount made advance()'s very first call after the plan was emptied fall straight
	 * into hasDespawnWork()==false and complete the wave immediately, with zero actual flee motion -
	 * a real flee needs the SAME fresh full attempt budget an ordinary start()/startOverride() gets,
	 * not whatever was left over from an unrelated earlier wave.
	 * <p>
	 * No-ops while actively carrying a rider (isForcingRide()) - a mid-carry ride resolves through
	 * its own separate mechanism entirely (see beginForcedRide/resolveRiderOnEnd), not ordinary plan
	 * execution, and clearing plan state out from under it would be interrupting the wrong thing. */
	public void forceFleeToOrbit() {
		if (this.forcingRide) {
			return;
		}
		this.fleeingFromSpectralHit = true;
		this.orbiting = false;
		this.returningToOrbit = false;
		this.carryingAway = false;
		this.currentAction = null;
		this.topLevelSteps = new JsonArray();
		this.topIndex = 0;
		this.actionQueue.clear();
		this.activeWhile = null;
		this.despawnEnabled = true;
		this.despawnAttemptCount = 0;
		this.despawnSucceeded = false;
		this.currentDespawnTarget = null;
		this.self.getNavigation().stop();
		this.self.setNavigationFailed(false);
		if (this.self.level() instanceof ServerLevel serverLevel) {
			// Centered on every player, offset toward self - see WendigoSounds' own class doc comment.
			// Same cue every other flee trigger in this codebase pairs with a retreat.
			WendigoSounds.play(serverLevel, this.self, WendigoSounds.Type.FLEE);
		}
	}

	/** Reads and clears rideJustEnded in one step - see its own field comment. WendigoManager calls
	 * this once per tick from checkUnconditionalGrab to know whether a grace period (no re-grab
	 * until the target has put actual distance between themselves and the wendigo) needs to start. */
	public boolean consumeRideJustEnded() {
		boolean result = this.rideJustEnded;
		this.rideJustEnded = false;
		return result;
	}


	/**
	 * combat.lunge_attack/combat.chase's "caught them" resolution, replacing straight damage-on-
	 * contact: mounts the player on the wendigo (force=true bypasses the normal can-ride checks - a
	 * hostile mob isn't normally rideable) instead of hurting them immediately, simulating a pickup.
	 * The carry itself (see startCarryFlee, kicked off right after this returns - either from tick()'s
	 * own post-catch hook for the 3 in-plan callers, or directly from forceGrabNow) carries the rider
	 * along for free via ordinary vehicle/passenger mechanics - see updateForcedRide for the escape
	 * side of this, and completeWave for what happens once the carry finishes. Also fires the
	 * jumpscare cue right at the moment of the grab (see WendigoSounds.play's own doc comment - every
	 * cue is a plain availability check now, so this either lands at this exact instant or not at all).
	 * <p>
	 * Sets grabbedSuccessfully right here, the moment of the actual catch - NOT in resolveRiderOnEnd's
	 * own damage-dealt branch, where an earlier version of this code left it. That meant a real catch
	 * that later ended in an escape (never reaching resolveRiderOnEnd's own darkness/fair-chance
	 * condition) was reported to EncounterHistory identically to never having caught the player at
	 * all - confirmed a real bug live, from the model's own recaps never once mentioning a catch across
	 * a whole session despite the wendigo visibly grabbing the player. dealtDamage (set separately, in
	 * resolveRiderOnEnd) is the narrower, later fact this doesn't cover.
	 */
	private boolean beginForcedRide(Player player) {
		// User's own explicit request: a creative-mode player is testing/observing, not playing the
		// encounter, and shouldn't be grabbable at all. Every grab pathway (combat.lunge_attack,
		// combat.chase, internal.chase_until_light, WendigoManager's forceGrabNow) funnels through this
		// one method. The 3 in-plan callers already gate their own post-catch carry-off on
		// this.forcingRide (see tick()'s own "if (this.forcingRide)" check just above startCarryFlee),
		// so leaving it false there is enough for them to fall through to their normal not-caught
		// behavior.
		if (player.isCreative()) {
			return false;
		}
		if (player instanceof ServerPlayer serverPlayer) {
			WendigoAdvancements.grant(serverPlayer, WendigoAdvancements.GRABBED);
		}
		this.grabbedSuccessfully = true;
		recordSoulLightTally(player);
		this.forcingRide = true;
		this.ridingPlayer = player;
		this.rideEscapeAttempts = 0;
		WendigoTuningConfig tuning = WendigoMod.tuningConfig;
		int escapeMin = tuning.rideEscapeAttemptsMin;
		int escapeMax = Math.max(escapeMin, tuning.rideEscapeAttemptsMax);
		int ceiling = escapeMin + (int) Math.round((escapeMax - escapeMin) * (Math.clamp(this.severityPercent, 0, 100) / 100.0));
		this.rideEscapeThreshold = escapeMin + this.self.getRandom().nextInt(ceiling - escapeMin + 1);
		this.rideFairChanceThreshold = rideFairChanceTicks(this.severityPercent);
		this.rideTicks = 0;
		player.startRiding(this.self, true, true);
		applyRideBlindness(player);
		sendRideEscapeHint(player);
		// Centered on every player, offset toward self - see WendigoSounds' own class doc comment.
		if (this.self.level() instanceof ServerLevel serverLevel) {
			WendigoSounds.play(serverLevel, this.self, WendigoSounds.Type.JUMPSCARE);
		}
		debugSay("picked up the player - forcing a ride (escapes after " + this.rideEscapeThreshold + " dismount attempt(s))");
		return true;
	}

	/** WendigoManager's own grab_distance override (checked every tick regardless of current state -
	 * orbiting or mid-plan, doesn't matter) - unconditionally catches player, bypassing every
	 * precondition a normal combat.lunge_attack/combat.chase catch would have (the nearby-safe-
	 * retreat check, travel-to-range requirement, tier gating): the user asked for this to fire "no
	 * matter what" the instant the target is close enough, not something skippable the way an
	 * ordinary lunge's own preconditions would make it. No-ops if already forcing a ride (see
	 * beginForcedRide's own precondition, documented on isForcingRide). beginForcedRide itself now
	 * sets grabbedSuccessfully (see its own doc comment), so there's nothing extra to set here. */
	public void forceGrabNow(ServerPlayer target) {
		if (this.forcingRide) {
			return;
		}
		if (!beginForcedRide(target)) {
			// Creative-mode target - see beginForcedRide's own doc comment. No grab happened, so
			// nothing here should either: grabbedSuccessfully would wrongly mark this as a landed catch, and
			// startCarryFlee would begin carrying off a rider that was never actually mounted.
			return;
		}
		// Called directly by WendigoManager, not from inside isActionDone() mid-tick like the other 3
		// beginForcedRide call sites, so there's no in-flight tick()/currentAction frame to corrupt -
		// safe to kick off the carry immediately rather than waiting for tick()'s own post-catch hook.
		startCarryFlee();
	}

	/** Kicked off the instant any grab lands - either directly from forceGrabNow, or from tick()'s
	 * own post-catch hook for the 3 in-plan callers (combat.lunge_attack/combat.chase/
	 * internal.chase_until_light). Marks the grab location, sets the stage-scaled release timer (see
	 * carryFleeReleaseTick's own comment), and immediately preempts whatever the plan or orbit was
	 * doing (same "small dedicated sub-state, not a synthetic plan step" shape as startReturnToOrbit),
	 * then hands off to beginNextCarryFleeAttempt for the actual live-band movement pick. */
	private void startCarryFlee() {
		this.orbiting = false;
		this.returningToOrbit = false;
		this.topLevelSteps = null;
		this.currentAction = null;
		this.actionQueue.clear();
		this.activeWhile = null;
		this.self.getNavigation().stop();
		this.self.setNavigationFailed(false);
		this.self.setLightTolerantPathing(false);
		this.carryingAway = true;
		this.grabLocation = this.self.blockPosition();
		this.carryFleeAttempts = 0;
		// rideFairChanceThreshold is already set - beginForcedRide (the only way forcingRide/this.self's
		// passenger got here at all) always runs first, same tick, on both paths that reach this method.
		this.carryFleeReleaseTick = this.self.tickCount + this.rideFairChanceThreshold;
		beginNextCarryFleeAttempt();
	}

	/** One live-band pick toward carrying the catch away from grabLocation - a random outer band
	 * (CARRY_FLEE_BANDS) each attempt, not just the first roll repeated, so a retry after a failed
	 * pathfind tries a genuinely different target rather than the same unreachable one. Falls back to
	 * DarkSpotScanner.findDarkestAwayFrom (same last-resort every despawn attempt already uses) if the
	 * live-band search itself comes up empty. Purely cosmetic/atmospheric now (see
	 * carryFleeReleaseTick's own comment) - nothing about arriving here ends the carry, it's just
	 * where the wendigo tries to actually go while the release timer runs down. */
	private void beginNextCarryFleeAttempt() {
		this.carryFleeAttempts++;
		String band = CARRY_FLEE_BANDS[this.self.getRandom().nextInt(CARRY_FLEE_BANDS.length)];
		BlockPos selfPos = this.self.blockPosition();
		BlockPos target = DarkSpotScanner.findLiveBandPosition(this.self.level(), selfPos, this.grabLocation,
			PositionBands.distanceMin(band), PositionBands.distanceMax(band), Direction.UP);
		if (target == null) {
			target = DarkSpotScanner.findDarkestAwayFrom(this.self.level(), selfPos, PositionBands.distanceMax(band), this.grabLocation);
		}
		if (target != null) {
			debugSay("carrying grabbed player toward '" + band + "' band away from grab spot "
				+ this.grabLocation.toShortString() + " - target=" + target.toShortString());
			this.self.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5,
				SemanticBands.speedMultiplier("fast"));
			// Deliberately no forced drop here, same as everywhere else this session's own removal of
			// the drop-vs-original-path mechanic left alone - a forceDetach() here would fall WHILE
			// actively carrying a forced rider (forcingRide is still true throughout this whole
			// carry-flee sequence), an untested interaction with the ride-escape/darkness-race mechanics
			// this project's own history shows were already fragile enough to need dedicated stability
			// fixes on their own. Excluded on purpose, not an oversight.
		} else {
			debugSay("issue: nowhere reachable to carry the grabbed player toward - holding until the carry timer ends");
		}
	}

	/** See startCarryFlee. Polls updateForcedRide itself (not called from tick()'s normal plan path
	 * while this sub-state is active) so the escape-attempt/blindness mechanics stay live throughout
	 * the whole carry, exactly as if this were still an ordinary plan action - spamming shift can
	 * still end the ride early, same as ever, independent of the release timer below. Ends the carry
	 * strictly on carryFleeReleaseTick, not on ever actually reaching a flee target - a stuck/failed
	 * pathfind (up to MAX_CARRY_FLEE_ATTEMPTS retries, each a fresh live-band pick) just means the
	 * wendigo holds in place for whatever's left of the timer instead of ending the carry early. */
	private void tickCarryFlee() {
		updateForcedRide();
		if (!this.forcingRide) {
			// Escaped mid-carry (spammed free before the timer ran out) - updateForcedRide already
			// resolved the escape itself (blindness removed, rideJustEnded set); just resume ordinary
			// orbit from wherever this got to rather than still waiting out the timer for a player
			// who's no longer being carried at all.
			this.carryingAway = false;
			this.grabLocation = null;
			startOrbit(this.self.getLockedTarget());
			return;
		}
		if (this.self.tickCount >= this.carryFleeReleaseTick) {
			finishCarryFlee();
			return;
		}
		PathNavigation nav = this.self.getNavigation();
		boolean arrivedOrGaveUp = (nav.isDone() && !nav.isInProgress()) || this.self.isNavigationFailed() || nav.isStuck();
		if (arrivedOrGaveUp && this.carryFleeAttempts < MAX_CARRY_FLEE_ATTEMPTS) {
			beginNextCarryFleeAttempt();
		}
	}

	/** carryFleeReleaseTick has passed - resolves the ride exactly like any other wave-ending path
	 * (fair-chance/darkness damage gating, and the actual drop - see resolveRiderOnEnd, which reads
	 * wherever the wendigo happens to be standing RIGHT NOW: still in light at this exact moment means
	 * no damage, released clean) via the same completeWave every normal plan completion already
	 * funnels through, so WendigoManager's own post-grabbedSuccessfully handling (a second, distinct dark spot
	 * to resume orbiting from) picks this up exactly as it already does for any other grab. */
	private void finishCarryFlee() {
		this.carryingAway = false;
		this.grabLocation = null;
		completeWave(false);
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
		this.rideTicks++;
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
			this.rideJustEnded = true;
			this.ridingPlayer.removeEffect(MobEffects.BLINDNESS);
			return;
		}
		this.ridingPlayer.startRiding(this.self, true, true);
		applyRideBlindness(this.ridingPlayer);
		sendRideEscapeHint(this.ridingPlayer);
	}

	/** See inViewStreakTicks/cornerOfEyeStreakTicks' own field comment and PlanPredicates.
	 * isLookedAtByTargetGraduated (the actual consumer) - polled every tick regardless of what's
	 * currently running, same reasoning as updateForcedRide, so a streak already in progress before a
	 * stare-hold loop starts still counts once it does. */
	private void updateLookStreaks() {
		this.inViewStreakTicks = PlanPredicates.isLookedAtByTarget(this.self, "in_view")
			? this.inViewStreakTicks + 1 : 0;
		this.cornerOfEyeStreakTicks = PlanPredicates.isLookedAtByTarget(this.self, "corner_of_eye")
			? this.cornerOfEyeStreakTicks + 1 : 0;
	}

	/** See tasksNearSoulLight/tasksNotNearSoulLight's own field comment - called once at each of the 6
	 * existing hidden-goal-progress increment sites (successfulStareCount/torchBreakCount/
	 * lungeAttemptCount/soundCueCount/successfulBreatheCount/grabbedSuccessfully), right alongside that
	 * counter's own increment. Null-safe - a caller that already null-checked its own target can pass
	 * it straight through, one that hasn't can just call Targeting.nearestPlayer(this.self) inline. */
	private void recordSoulLightTally(Player target) {
		if (target == null) {
			return;
		}
		if (SoulLightScanner.isNearSoulLight(this.self.level(), target.blockPosition())) {
			this.tasksNearSoulLight++;
		} else {
			this.tasksNotNearSoulLight++;
		}
	}

	/** Bundles this instant's predicate-evaluation state for PlanPredicates.evaluate - see
	 * PlanPredicates.Context's own doc comment for what each field means. */
	private PlanPredicates.Context currentPredicateContext() {
		return new PlanPredicates.Context(this.whileBaselineDistance, this.inViewStreakTicks, this.cornerOfEyeStreakTicks);
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

	/** Snuffs exactly the one torch combat.break_torch pathfound to - no connectivity/cluster
	 * destruction (removed: a plan wanting several torches gone just issues combat.break_torch more
	 * than once, each call re-targeting whatever's now nearest - see nearestTorch). See
	 * LightSourceScanner.snuffByWendigo - leaves a relightable (by a PLAYER, flint-and-steel, at their
	 * own initiative), still-physically-present unlit torch/candle behind instead of removing the
	 * block outright. Stays off for good otherwise - the user's own explicit "lights should not come
	 * back on after the plan is done" request - same as combat.chase's own passive collateral (see
	 * maybeDestroyNearbyTorches), neither one is ever engine-relit anymore. */
	private void performTorchBreak() {
		if (this.currentTorchTarget == null || !(this.self.level() instanceof ServerLevel serverLevel)) {
			return;
		}
		this.torchBreakCount++; // hidden goal-progress signal - see the field's own comment
		recordSoulLightTally(Targeting.nearestPlayer(this.self));
		LightSourceScanner.snuffByWendigo(serverLevel, this.currentTorchTarget, this.self);
	}

	// Own dedicated radius rather than reusing SemanticBands' "far" proximity band (30 - a
	// player-distance descriptive value, not tuned for this) - matches LightSourceScanner's own hard
	// cap (35), so going any higher wouldn't reach further anyway.
	private static final double TORCH_BREAK_SEARCH_RADIUS = 35.0;

	/** Nearest torch/candle the wendigo is willing to snuff, or null if none is nearby -
	 * combat.break_torch's own default (band-less) target resolution. findSnuffableLightSources, not
	 * findLightSources - lanterns/everything else findLightSources would otherwise return are
	 * deliberately not valid targets here (see that method's own doc comment). */
	private BlockPos nearestTorch() {
		var torches = LightSourceScanner.findSnuffableLightSources(this.self.level(), this.self.blockPosition(), TORCH_BREAK_SEARCH_RADIUS, 1);
		return torches.isEmpty() ? null : torches.get(0);
	}

	private BlockPos retreatDestination(JsonObject step) {
		if ("stored".equals(step.get("source").getAsString()) && this.self.getStoredDarkLocation() != null) {
			return this.self.getStoredDarkLocation();
		}
		return PlanGeometry.findDarkSpot(this.self, SemanticBands.searchRadiusBlocks("near"));
	}
}
