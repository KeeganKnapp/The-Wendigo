package com.wendigo.wave;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import com.wendigo.WendigoMod;
import com.wendigo.debug.WendigoDebug;
import com.wendigo.entity.ModEntities;
import com.wendigo.entity.WendigoEntity;
import com.wendigo.plan.ProximityBands;
import com.wendigo.plan.SchemaBuilder;
import com.wendigo.sound.WendigoSounds;
import com.wendigo.spatial.CaveScaleScanner;
import com.wendigo.spatial.CaveScaleScanner.CaveScale;
import com.wendigo.spatial.DarkSpotScanner;
import com.wendigo.spatial.LightSourceScanner;

/**
 * Owns the wendigo's spawn/despawn lifecycle: at most one per level, spawned near whichever
 * player currently has the highest dweller severity, running a single LLM-authored plan from
 * spawn to despawn with no mid-wave re-planning. See PlanRunner for how the plan body itself
 * executes once the entity exists.
 */
public final class WendigoManager {
	// Sent on every request, regardless of severity - the primitives/predicates/mechanics don't
	// change per stage, only which of them are unlocked and how bold to be with them (see the
	// STAGE_* blocks below, picked per-request by buildSystemPrompt). Splitting the prompt this way
	// keeps each request smaller (a low-severity wave doesn't need the high-severity tiers' text at
	// all) and lets each stage's guidance be much more specific than fitting all five into one
	// combined tier table ever allowed.
	private static final String SYSTEM_PROMPT_GENERIC =
		"You control a wendigo, a shadow-dwelling stalker creature in Minecraft. It's a persistent "
		+ "presence, not something that spawns fresh every time - usually it's already nearby, "
		+ "quietly keeping its distance, before you're ever asked for a plan. You are given a "
		+ "target player, their dweller severity, and a handful of scanned dark spots near them "
		+ "labeled spot_a through spot_f (nearest to furthest, or fewer if that's all that was "
		+ "found). Choose which spot to position at (spawn_at - walked to if it's not already close, "
		+ "or where it first appears for a genuine fresh encounter) and a short plan (1-6 steps) of "
		+ "actions/predicates to run from there. Where it withdraws to afterward is not your choice - "
		+ "the engine always picks the best hiding spot automatically."
		+ "Each spot also lists a nearby dim-spot count - the number of edge-of-light positions "
		+ "reachable from it (not fully dark, not fully lit). A spot with several dim spots supports "
		+ "creeping to the edge of the light to observe (movement.approach_dim_spot); a spot with "
		+ "zero only supports staying hidden and waiting UNLESS one of its listed pathfindable-to "
		+ "spots has dim spots of its own - movement.approach_spot there first, then "
		+ "movement.approach_dim_spot, gets a spawn-far-then-creep-closer effect out of a spot that "
		+ "has nothing to offer on its own. Not every action needs to avoid light - "
		+ "decide per action whether darkness matters for what it's doing, and wrap movement in "
		+ "control.while checking predicate.self_in_darkness when you want it to stop rather than "
		+ "commit further once it's no longer hidden. "
		+ "Distance is always described with the same proximity ladder, nearest to furthest: "
		+ "grab_distance (0-4 blocks), lunge_distance (5-9), close_quarters (10-14), medium (15-24), "
		+ "far (25+) - both for each scanned spot in this prompt and for predicate.player_distance, "
		+ "so check a spot's band before picking spawn_at (spawning already inside grab_distance or "
		+ "lunge_distance of the player defeats the point of staying hidden). Orchestrate a hunt out "
		+ "of these pieces yourself: creep to a dim spot and stare, wait "
		+ "(control.while farther_than lunge_distance) until the player closes in, then commit with "
		+ "combat.lunge_attack (the one primitive allowed to cross into light - catching the player "
		+ "grabs them, forcing them to ride along until they struggle free or the wendigo reaches "
		+ "wherever it's headed next), then movement.retreat_with_fallback to reliably get back into "
		+ "hiding, carrying a still-grabbed player along with it. Or stay "
		+ "cautious and hold at close_quarters distance, retreating the moment they close past that "
		+ "instead of ever committing to a lunge. Pick whichever posture fits the moment - bold or "
		+ "cautious - there's no single right sequence. "
		+ "Each spot also lists a nearby torch count - light sources (torches, lanterns, etc.) found "
		+ "close to that specific spot, not just close to the player. A distant spot with its own "
		+ "nearby torch is still worth spawning at: combat.break_torch pathfinds to and destroys the "
		+ "single nearest known light source live from wherever the wendigo currently is (no label "
		+ "needed, like combat.lunge_attack it's allowed to cross into light to reach its target) - "
		+ "just that one torch, nothing else. Include combat.break_torch more than once in the plan to "
		+ "work through several torches at the same spot one at a time (each call re-targets whatever "
		+ "is nearest at that moment). E.g. spawn far away at a spot with a nearby torch, break_torch to "
		+ "snuff it out, then movement.retreat_with_fallback before despawning. Skip it entirely for a "
		+ "spot with zero nearby torches - it would have nothing to target. Treat a spot with a high "
		+ "torch count (3+) as a good target once combat.break_torch is available (20%+ severity) - "
		+ "repeat the step to clear several of them rather than treating it as a rare escalation move. "
		+ "spawn_at also sometimes offers spawn_on_torch - only when there's little to no real darkness "
		+ "found near the player at all, at whatever distance this severity currently allows (the same "
		+ "e/d/c/b progression spot_e..spot_b unlock at, just measured against these candidates instead) "
		+ "- spawning already exposed on a random nearby lit position instead of waiting on a hiding spot "
		+ "that doesn't really exist here. Below 80% severity treat it as an ordinary spawn location choice "
		+ "like any other - no obligation to immediately chase, plan it however the moment calls for. At "
		+ "80%+ it means something different: choosing it there is a commitment to a direct hunt, and the "
		+ "plan needs to follow through on that (sound.ambient_cue(chase), straight into combat.chase) since there's "
		+ "nothing subtle left to do once spawning fully exposed at that range. Never pick it as a "
		+ "substitute for a genuine dark spot when one's actually available and would fit the moment better. "
		+ "control.despawn ends this engagement by vanishing right where the wendigo stands, instead of "
		+ "walking to a hiding spot first - it still retreats into darkness afterward and keeps watching "
		+ "from a distance either way, this only changes whether that withdrawal is instant or a visible "
		+ "walk. The engine only allows the instant version below 20% severity (or when nothing else is "
		+ "configured to fall back on) since vanishing suddenly reads as jarring once the wendigo is "
		+ "established enough to be more than a faint presence; a control.despawn attempted above that "
		+ "gets automatically redirected into a real flee instead, so don't rely on it for the ending of "
		+ "a higher-severity plan - use movement.retreat_with_fallback there directly. "
		+ "IMPORTANT - steps do not wait for anything on their own: only control.while (and "
		+ "timing.wait/movement while it's still resolving) actually consumes time. Every other kind "
		+ "of step, including posture.stare, runs and then immediately falls through to whatever comes "
		+ "next in the SAME tick. A plan that does posture.stare(enabled=true) followed directly by "
		+ "movement.retreat_with_fallback flees the instant the wendigo appears, whether or not the "
		+ "player ever looked at it - that is almost never what you want. If a stare is meant to hold "
		+ "and react to whether the player notices, it must be followed by a control.while whose "
		+ "condition is exactly the thing that should end the hold (predicate.player_looking_at_self at "
		+ "whichever band fits, predicate.player_distance, or predicate.player_undetected) - the hold "
		+ "time comes entirely from that loop existing, not from posture.stare itself. "
		+ "For the specific, very common 'keep creeping closer while still unseen and they haven't closed "
		+ "in on me much' loop, use predicate.player_undetected(band, approach_band) directly rather than "
		+ "hand-building predicate.and(predicate.not(...), predicate.not(...)) yourself - manually composing "
		+ "a double negation is easy to get backwards (predicate.and(player_looking_at_self, "
		+ "player_approaching), with no predicate.not, looks similar but means the opposite thing - 'only "
		+ "keep looping once already spotted and already closing in' - which is false the instant the "
		+ "wendigo spawns unseen, so the loop runs zero iterations and falls straight through to whatever "
		+ "comes next). approach_band measures how much of the distance-at-loop-start the player has closed "
		+ "(see predicate.player_approaching) rather than a fixed absolute distance, so unlike an absolute "
		+ "distance check it can't already be violated by whatever step happened to run right before the "
		+ "loop started (e.g. the wendigo's own movement.approach_dim_spot closing distance on its way in) - "
		+ "it only measures change from the moment the loop itself began. "
		+ "global_rules (a separate top-level field alongside spawn_at/plan) back hard "
		+ "requirements that must hold no matter which plan step happens to be running - the engine "
		+ "checks every rule's condition every tick, independent of plan position, and the instant one "
		+ "fires it preempts whatever's currently happening (interrupting mid-movement if needed) and "
		+ "runs that rule's action immediately, once per wave. Use this for things that must never "
		+ "depend on the rest of the plan having been written correctly - e.g. a rule with condition "
		+ "predicate.player_looking_at_self(band=dead_stare) and action control.despawn (or "
		+ "movement.retreat_with_fallback if it needs to visibly flee first) guarantees a dead-on stare "
		+ "always cuts the encounter short immediately, even if whatever movement/posture step happens "
		+ "to be running at that instant never checked for it itself. Don't use global_rules for "
		+ "ordinary reactive branching where the timing of the check matters (e.g. 'wait until the "
		+ "player looks away before creeping closer') - that belongs in control.if/control.while inside "
		+ "'plan' as normal. Most waves need zero or one global_rules entry, not several. "
		+ "How bold to be is governed by dweller severity (a number out of its cap each request - "
		+ "compute it as a percentage) - a slow-burning escalation across this player's entire "
		+ "relationship with the dark, not something to ramp up within one plan: severity climbs over "
		+ "many separate descents below y=0 (five to ten short trips, or a couple of long ones), so one "
		+ "wave is one beat in a much longer story. You'll be told which stage that percentage falls "
		+ "into below, along with what's unlocked and what the moment should feel like - the engine "
		+ "also enforces the unlocks as a hard limit (anything not yet earned isn't even offered in this "
		+ "request's schema), so treat the stage text as atmosphere/intent to hit, not a checklist of "
		+ "restrictions to police yourself. The prompt also reports what happened during the wendigo's "
		+ "previous real encounter with this player, if any (how it ended, whether it was ever directly "
		+ "spotted, its plan shape) - react to that outcome instead of ignoring it, and vary the "
		+ "approach rather than repeating the same sequence again.\n\n";

	private static final String STAGE_UNDER_20 =
		"CURRENT STAGE: under 20%, barely a presence. This is the very first, faintest phase of this "
		+ "player's relationship with the dark - they likely have no conscious reason yet to believe "
		+ "anything down here is watching them. The wendigo's entire job this wave is to plant exactly "
		+ "one seed of unease and nothing more: it must not be caught doing anything, must not risk "
		+ "confrontation, must not linger. Concretely: spawn_at must be no_players_looking (this is the "
		+ "whole point of that option - a clean, unnoticed first appearance, and the engine forces it "
		+ "regardless of what you pick, so choose it deliberately rather than a specific spot). Once "
		+ "spawned, do exactly one posture.stare(enabled=true), immediately followed by exactly one "
		+ "control.while gated on predicate.player_undetected (or predicate.player_looking_at_self) so "
		+ "the hold ends the instant either happens: spotted, even peripherally, or the player closes "
		+ "in. The very next step after that while is control.despawn - an immediate, silent vanish, no "
		+ "padding. Nothing else belongs in this plan: no torches, no traps, no approaching the player, "
		+ "no sound cues, no chase - those primitives aren't even offered at this stage. Example: spawn "
		+ "via no_players_looking, stare until noticed or approached, control.despawn.";

	private static final String STAGE_20_39 =
		"CURRENT STAGE: 20-39%, curious. Enough has happened that outright caution is giving way to "
		+ "curiosity - the wendigo is starting to test the edges of the player's space instead of just "
		+ "appearing and vanishing. It's still not ready to be seen up close or to fight, but it'll "
		+ "creep to the edge of the light to get a better look (movement.approach_dim_spot), and a "
		+ "torch that's right there is worth snuffing out on the way (combat.break_torch just unlocked "
		+ "this stage - no further threshold above this one, so use it whenever a spot's torch count is "
		+ "high, every wave that offers the chance, not as an occasional flourish). "
		+ "sound.ambient_cue(ambient) can accompany the stalking for a low, unplaced presence cue. "
		+ "movement.retreat_with_fallback (a real, visible flight into darkness) is now available as an "
		+ "alternative ending to control.despawn (still allowed below 20%, but redirected to a flee "
		+ "above it) - withdrawing into the dark reads better than vanishing once it's been this bold, and "
		+ "sound.ambient_cue(flee) right beforehand is heavily encouraged to announce the withdrawal. "
		+ "Example: spawn near a light source, combat.break_torch, sound.ambient_cue(flee), "
		+ "movement.retreat_with_fallback before despawning.";

	private static final String STAGE_40_59 =
		"CURRENT STAGE: 40-59%, prey-driven and starting to plan. The wendigo has stopped just "
		+ "reacting to the player's presence and started treating them as something to be hunted "
		+ "deliberately - this is the first stage where it sets real traps instead of just observing. "
		+ "memory.store_dark_location is available for remembering a fallback retreat point before "
		+ "committing to something riskier. A good trap shape: approach one of a spot's dim spots, "
		+ "posture.stare(enabled=true) (sound.ambient_cue(stare) is heavily encouraged right here), then "
		+ "control.while(predicate.player_distance farther_than lunge_distance) to bait them closer "
		+ "before deciding whether to press on or pull back. combat.break_torch "
		+ "remains a strong opener wherever torch count is high. Example: spawn at a spot with dim "
		+ "spots, approach one, bait-and-decide via a lunge_distance while loop, retreat or press on "
		+ "based on how it resolves.";

	private static final String STAGE_60_79 =
		"CURRENT STAGE: 60-79%, openly aggressive. Subtlety is mostly gone - the wendigo commits now. "
		+ "combat.lunge_attack (catching the player grabs them - see its own description) is available, "
		+ "and sound.ambient_cue(chase) unlocks alongside it - pair the two for the reveal (heavily "
		+ "encouraged right before the lunge) rather than always retreating "
		+ "the moment the player closes in. combat.chase is not available yet - it's reserved for 80%+ "
		+ "(see next stage), so a lunge is still the sole point of contact here. What does change within "
		+ "this stage: how far from real darkness it's willing to commit to that lunge keeps widening as "
		+ "severity climbs - tightest right at 60%, loosest approaching 80% - so leaning into a lunge from "
		+ "a position that isn't perfectly safe to retreat from afterward reads as more in-character the "
		+ "higher into this range you are, not something to always play cautiously. combat.lunge_attack "
		+ "should almost always be preceded by a posture.stare, whether in the plan body or via a "
		+ "global_rule - it's the payoff for being noticed while staring, not something to trigger with no "
		+ "reveal moment first. A reliable way to guarantee the transition happens the instant it's "
		+ "spotted, regardless of what step the plan was on: a global_rule with condition "
		+ "predicate.player_looking_at_self at whichever band fits and action combat.lunge_attack. "
		+ "Example: stalk while undetected, sound.ambient_cue(chase), combat.lunge_attack once close, "
		+ "sound.ambient_cue(flee), movement.retreat_with_fallback afterward.";

	private static final String STAGE_80_PLUS =
		"CURRENT STAGE: 80% and up, restless. The wendigo is done pretending to be subtle - it wants "
		+ "direct contact and isn't holding back to get it. combat.chase unlocks here for the first time "
		+ "(it wasn't available at all below 80%) - a sustained pursuit rather than lunge's single "
		+ "commit, chasing the player down until it catches them (grabbing them, same as a lunge does - "
		+ "see combat.lunge_attack) or genuinely can't reach them anymore, and always passively destroys "
		+ "any torch within 10 blocks as it goes - a chase at this stage leaves real wreckage behind it, "
		+ "every time, not just sometimes. Close the distance directly; combat.chase is the expected move "
		+ "once seen, not a rare escalation, and retreating only makes sense after catching them or being "
		+ "genuinely forced to. combat.chase should almost always be preceded by a "
		+ "posture.stare, whether in the plan body or via a global_rule - it's the payoff for being "
		+ "noticed while staring, not something to trigger with no reveal moment first. A reliable way to "
		+ "guarantee the transition happens the instant it's spotted, regardless of what step the plan was "
		+ "on: a global_rule with condition predicate.player_looking_at_self at whichever band fits and "
		+ "action combat.chase. Example: stalk and stare, a global_rule turns a spotted stare straight into "
		+ "combat.chase, no explicit ending needed after it - the chase resolves on its own once it can't "
		+ "reach the player anymore.";

	private static String buildSystemPrompt(int severityPercent) {
		String stage = severityPercent < 20 ? STAGE_UNDER_20
			: severityPercent < 40 ? STAGE_20_39
			: severityPercent < 60 ? STAGE_40_59
			: severityPercent < 80 ? STAGE_60_79
			: STAGE_80_PLUS;
		return SYSTEM_PROMPT_GENERIC + stage;
	}

	private final WendigoWaveConfig config;
	private final PlayerSeverityTracker severityTracker;
	private final EncounterHistory encounterHistory;
	private final Map<ServerLevel, WaveState> waves = new HashMap<>();

	public WendigoManager(WendigoWaveConfig config, PlayerSeverityTracker severityTracker, EncounterHistory encounterHistory) {
		this.config = config;
		this.severityTracker = severityTracker;
		this.encounterHistory = encounterHistory;
	}

	public void register() {
		ServerTickEvents.END_SERVER_TICK.register(this::onEndServerTick);
	}

	private void onEndServerTick(MinecraftServer server) {
		for (ServerLevel level : server.getAllLevels()) {
			// ServerTickEvents.END_SERVER_TICK fires every real server tick regardless of any single
			// level's /tick freeze state - the server loop itself never stops, only level/entity
			// ticking does (confirmed via TickRateManager's bytecode: runsNormally() is exactly the
			// flag vanilla uses to decide whether to tick that level's entities this call, including
			// this wendigo). Without this guard, WaveState's own bookkeeping (disengage/proximity
			// counters, cooldowns) kept advancing on real time even while the level - and the wendigo
			// entity itself - was frozen, so walking away during a freeze could force-end a wave that,
			// from the world's perspective, hadn't ticked at all yet.
			if (!level.tickRateManager().runsNormally()) {
				continue;
			}
			tickLevel(level, this.waves.computeIfAbsent(level, l -> new WaveState()));
		}
	}

	// Point-blank, sustained a long time - real combat/close holds can legitimately last a while, so
	// this tolerance is generous; it's a backstop against a genuinely stuck state, not a normal-play trigger.
	private static final double EXTREME_PROXIMITY_DISTANCE = 2.0;
	private static final int EXTREME_PROXIMITY_GIVEUP_TICKS = 400; // 20s sustained
	// Kept in sync by hand with SemanticBands.orbitMinDistance/orbitMaxDistance (com.wendigo.plan,
	// package-private there) - same tradeoff already accepted elsewhere in this codebase (see
	// DarkSpotScanner's own DIM_LIGHT_MIN/MAX comment) rather than widening that class's visibility
	// just for this.
	private static double orbitMinDistance(CaveScale caveScale) {
		return switch (caveScale) {
			case TIGHT -> 12.0;
			case MASSIVE -> 28.0;
			default -> 20.0; // NORMAL
		};
	}

	private static double orbitMaxDistance(CaveScale caveScale) {
		return switch (caveScale) {
			case TIGHT -> 18.0;
			case MASSIVE -> 40.0;
			default -> 30.0; // NORMAL
		};
	}

	// Flat performance cap on how far an orbiting wendigo is allowed to be from its target before
	// just despawning outright instead of continuing to tick/pathfind toward it from very far away -
	// see tickOrbitingEntity. Comfortably beyond MASSIVE's own 40-block max orbit distance so it never
	// fights ordinary orbiting, well inside NEAREST_PLAYER_RADIUS (64, SemanticBands).
	private static final double ORBIT_DESPAWN_DISTANCE = 48.0;
	// Throttles tryEnterOrbit's own dark-spot search while entity == null - see WaveState.nextRespawnSearchTick.
	private static final int ORBIT_SPAWN_SEARCH_INTERVAL_TICKS = 20; // ~1s

	private void tickLevel(ServerLevel level, WaveState state) {
		int now = level.getServer().getTickCount();

		// Unconditional grab_distance override - checked before either dispatch branch below, since
		// it needs to interrupt orbiting OR mid-plan alike. If it fires, state.entity's own orbiting/
		// mid-plan status has already changed by the time the checks below run, so they naturally
		// pick up the right branch for whatever just started.
		if (state.entity != null && state.entity.isAlive()) {
			checkUnconditionalGrab(level, state, now);
		}

		if (state.entity != null && state.entity.isAlive()
				&& (state.entity.isOrbiting() || state.entity.isReturningToOrbit() || state.entity.isApproachingEngageSpot())) {
			tickOrbitingEntity(level, state, now);
			return;
		}

		if (state.entity != null) {
			String forcedEndReason = checkForcedWaveEnd(state, now);
			if (!state.entity.isAlive() || state.entity.isWaveComplete() || forcedEndReason != null) {
				int elapsedTicks = now - state.waveStartTick;
				if (forcedEndReason != null) {
					// A forced backstop discard used to eject a still-forced rider with no damage,
					// unconditionally (see WendigoEntity.remove) - now resolved exactly like any other
					// wave-ending path (see PlanRunner.resolveRiderOnEnd): dark enough right now, and
					// carried long enough, still lands the despawn damage even though this wasn't a
					// clean arrival at a chosen despawn point.
					state.entity.resolveRiderOnEnd();
					WendigoDebug.say(level, "wave force-ended (" + forcedEndReason + ") after " + elapsedTicks
						+ " ticks at " + state.entity.blockPosition().toShortString()
						+ " - relocating back into orbit - outcome: " + state.entity.getOutcome());
				} else {
					WendigoDebug.say(level, "wave complete after " + elapsedTicks + " ticks at "
						+ state.entity.blockPosition().toShortString() + " - returning to orbit - outcome: " + state.entity.getOutcome());
				}
				// Debug-forced waves (wave/wavetest) never update real encounter history - a showcase
				// or a debug-triggered test run shouldn't be told back to the model as if it were a
				// real thing that happened to this player.
				if (!state.debugForced && state.context != null) {
					this.encounterHistory.record(state.context.player(), state.entity.getOutcome(), now);
				}
				int severityPercent = state.context != null && state.context.severityCap() > 0
					? 100 * state.context.severity() / state.context.severityCap() : 0;
				// A debug-forced wave (wave/wavetest) shouldn't leave the automatic system primed to
				// fire a real LLM wave moments later just because the player is still under y=0 -
				// exactly the condition someone testing would be standing in. That reads as "the
				// despawned wendigo turned around and walked back", when it's really a second,
				// unrelated wendigo from a genuine severity-triggered wave.
				state.cooldownUntilTick = now + (state.debugForced ? this.config.debugCooldownTicks : this.config.dynamicCooldownTicks(severityPercent));
				state.debugForced = false;
				if (!state.entity.isAlive()) {
					state.entity = null;
					state.context = null;
				} else if (forcedEndReason != null) {
					// "Despawn when trapped/can't move" - an explicit teleport-relocation, not the
					// ordinary walked retreat a clean plan completion already resolves through below.
					relocateOrDiscard(level, state, now);
				} else if (state.entity.getOutcome().hitLanded()) {
					// A grab landed this encounter - the drop already happened at the despawn spot
					// PlanRunner's own fallback chain resolved through (the "harm and drop" spot), but
					// per the user's own two-spot design that location shouldn't double as the resume
					// point too - walk to a SECOND, distinct dark spot first (see startReturnToOrbit),
					// only entering orbit once there (or once giving up trying).
					ServerPlayer target = state.lockedTarget != null && state.lockedTarget.isAlive() ? state.lockedTarget : null;
					BlockPos returnSpot = findNearbyDarkSpot(level, state.entity.blockPosition(), target);
					if (returnSpot != null) {
						state.entity.startReturnToOrbit(returnSpot, target);
					} else {
						// Nothing else dark reachable from here either - same "genuinely nowhere to go"
						// fallback relocateOrDiscard uses, just already having tried the walked option.
						relocateOrDiscard(level, state, now);
					}
				} else {
					// No grab this encounter - ordinary completion already parked the entity at a good
					// dark spot via PlanRunner's own despawn-move fallback chain (unchanged), so it can
					// just resume orbiting from right here, no extra travel needed.
					state.entity.startOrbit(state.lockedTarget);
				}
			} else if (WendigoDebug.anyEnabled() && state.context != null) {
				if (now % this.config.debugParticleIntervalTicks == 0) {
					WendigoDebug.showSpots(level, state.context.spots(), state.context.dimSpotsPerSpot());
				}
				if (now % this.config.debugContextIntervalTicks == 0) {
					logContextSnapshot(level, state, now);
				}
			}
			return;
		}

		tryEnterOrbit(level, state, now);
	}

	/** Per-tick supervision while state.entity is alive and orbiting (no active plan) - see
	 * PlanRunner.tickOrbit for the actual movement logic this just watches over. Handles the two
	 * conditions PlanRunner can detect but can't resolve on its own (needs WendigoManager's own
	 * dark-spot search/discard authority): a fully lost target (see clarification: treated the same
	 * as nowhere-dark-to-go, not held in place) and being genuinely stuck trying to reach a waypoint.
	 * Once orbit itself is confirmed healthy, checks whether it's time to start a new plan - the
	 * exact same severity-scaled cooldown that used to be the only way a wendigo ever spawned at all
	 * now gates starting a plan on the entity that's already here instead. */
	private void tickOrbitingEntity(ServerLevel level, WaveState state, int now) {
		WendigoEntity entity = state.entity;
		if (entity.isReturningToOrbit() || entity.isApproachingEngageSpot()) {
			return; // mid-transit toward a return point or a pending plan's engage spot - PlanRunner resolves these on its own
		}
		if (entity.isOrbitTargetLost()) {
			WendigoDebug.say(level, "orbiting wendigo lost its target entirely - discarding, will search for a new one");
			entity.discard();
			state.entity = null;
			state.context = null;
			state.lockedTarget = null;
			return;
		}
		if (entity.isOrbitTrapped()) {
			WendigoDebug.say(level, "orbiting wendigo stuck trying to reach its waypoint - relocating");
			relocateOrDiscard(level, state, now);
			return;
		}
		ServerPlayer lockedTarget = state.lockedTarget;
		if (lockedTarget != null && lockedTarget.isAlive() && entity.distanceTo(lockedTarget) > ORBIT_DESPAWN_DISTANCE) {
			// Performance cap, not a normal orbit-band condition - comfortably beyond even MASSIVE's
			// own max orbit distance, so this never fights ordinary in-band/reposition orbiting; it
			// only fires when something (a long chase, a relocate, the player teleporting/fast-
			// traveling) has left the wendigo tracking a target from genuinely far away, which isn't
			// worth continuing to tick/pathfind toward - discard and let tryEnterOrbit pick a fresh,
			// closer spawn near wherever the target actually is now.
			WendigoDebug.say(level, "orbiting wendigo too far from its target (" + ORBIT_DESPAWN_DISTANCE
				+ "+ blocks, performance cap) - discarding, will search for a closer spawn");
			entity.discard();
			state.entity = null;
			state.context = null;
			return;
		}
		if (checkOrbitTooClose(level, state)) {
			return;
		}
		if (state.requestPending || now < state.cooldownUntilTick) {
			return;
		}
		ServerPlayer target = state.lockedTarget;
		if (target == null || !target.isAlive()) {
			return; // shouldn't happen (isOrbitTargetLost would have already caught it) - defensive only
		}
		beginEngagement(level, state, target, this.severityTracker.severityOf(target));
	}

	// Below this percent, a player getting too close while orbiting just spooks the wendigo off
	// (real discard + re-search) rather than provoking a lunge - matches DarknessOverstayTracker's
	// own AMBUSH_MIN_PERCENT-style tiering philosophy, this specific number given directly by the user.
	private static final int ORBIT_TOO_CLOSE_LUNGE_MIN_PERCENT = 40;
	// Flat, not cave-scaled or fraction-of-band - the user's own explicit call after the original
	// "lower quarter of the current band" version was landing too close to (or even inside) typical
	// spawn distance and reacting the instant a fresh orbit spawn's first tick ran.
	private static final double ORBIT_TOO_CLOSE_DISTANCE = 10.0;

	/** Reaction to any player (not just state.lockedTarget - whoever's actually closest) coming
	 * within ORBIT_TOO_CLOSE_DISTANCE - not gated by cooldown, same "this always wins outright"
	 * philosophy as checkUnconditionalGrab. Below ORBIT_TOO_CLOSE_LUNGE_MIN_PERCENT (that closest
	 * player's own severity) the wendigo just flees/despawns and re-searches for a new spawn; at/above
	 * it, it commits to a bounded combat.lunge_attack pursuit of that player instead (retargeting
	 * lockedTarget to them - overrideIntoLunge does this itself). Deliberately a lunge, not
	 * overrideIntoChaseUntilLight's internal.chase_until_light - a player actively placing torches
	 * while backing away can keep the immediate area "not dark enough" just often enough that a
	 * light-seeking chase never naturally resolves, chasing indefinitely; combat.lunge_attack's own
	 * resolution is bounded by construction regardless (isLungeResolved ends the instant the pathfind
	 * either reaches melee range or finishes/gets stuck, see overrideIntoLunge's own comment). Returns
	 * true if either reaction fired, so the caller knows not to also fall through to the ordinary
	 * cooldown-gated engagement trigger this tick. */
	private boolean checkOrbitTooClose(ServerLevel level, WaveState state) {
		WendigoEntity entity = state.entity;
		double thresholdSqr = ORBIT_TOO_CLOSE_DISTANCE * ORBIT_TOO_CLOSE_DISTANCE;
		ServerPlayer closest = null;
		double closestDistSqr = Double.MAX_VALUE;
		for (ServerPlayer player : level.players()) {
			double distSqr = entity.distanceToSqr(player);
			if (distSqr < thresholdSqr && distSqr < closestDistSqr) {
				closest = player;
				closestDistSqr = distSqr;
			}
		}
		if (closest == null) {
			return false;
		}
		int percent = this.severityTracker.severityCap() > 0
			? 100 * this.severityTracker.severityOf(closest) / this.severityTracker.severityCap() : 0;
		if (percent < ORBIT_TOO_CLOSE_LUNGE_MIN_PERCENT) {
			WendigoDebug.say(level, closest.getGameProfile().name() + " got too close while orbiting ("
				+ percent + "% - below lunge threshold) - discarding, will search for a new spawn spot");
			entity.discard();
			state.entity = null;
			state.context = null;
			return true;
		}
		WendigoDebug.say(level, closest.getGameProfile().name() + " got too close while orbiting ("
			+ percent + "%) - lunging");
		overrideIntoLunge(level, closest);
		return true;
	}

	/** Relocates an alive entity (currently trapped mid-orbit, or a plan that just force-ended via
	 * timeout/extreme-proximity) to a fresh dark spot near its own current position, via TELEPORT
	 * rather than a walked retreat - trapped/can't-move relocation is meant to be instant, not
	 * something that could itself get stuck trying to walk there. Falls back to a plain
	 * findDarkestAwayFrom search if nothing in-band is flood-reachable, and to real discard
	 * (entering the no-entity search state) if genuinely nothing dark is reachable at all - see the
	 * user's own "if it genuinely cannot find a dark place, it despawns and keeps searching" rule. */
	private void relocateOrDiscard(ServerLevel level, WaveState state, int now) {
		WendigoEntity entity = state.entity;
		BlockPos selfPos = entity.blockPosition();
		ServerPlayer target = state.lockedTarget != null && state.lockedTarget.isAlive() ? state.lockedTarget : null;
		BlockPos spot = findNearbyDarkSpot(level, selfPos, target);
		if (spot == null) {
			WendigoDebug.say(level, "nowhere dark reachable to relocate to - discarding, will keep searching for a new spawn spot");
			entity.discard();
			state.entity = null;
			state.context = null;
			return;
		}
		entity.snapTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, entity.getYRot(), 0f);
		entity.syncPoseToSpawnPosition();
		entity.nudgeTowardAttachedSurface(Direction.UP);
		entity.startOrbit(target);
	}

	/** Shared by relocateOrDiscard (teleport case) and the post-grab return-to-orbit case (walked
	 * case, see the "ordinary completion" branch of tickLevel) - a dark spot reachable from selfPos,
	 * within the orbit band of target if target is still valid, falling back to a plain
	 * away-from-target search, or null if genuinely nothing dark is reachable at all. */
	private static BlockPos findNearbyDarkSpot(ServerLevel level, BlockPos selfPos, ServerPlayer target) {
		CaveScale caveScale = CaveScaleScanner.classify(level, selfPos);
		double maxDistance = orbitMaxDistance(caveScale);
		BlockPos spot = target != null
			? DarkSpotScanner.findOrbitWaypoint(level, selfPos, target.blockPosition(), orbitMinDistance(caveScale), maxDistance)
			: null;
		if (spot == null) {
			spot = DarkSpotScanner.findDarkestAwayFrom(level, selfPos, maxDistance, target != null ? target.blockPosition() : null);
		}
		return spot;
	}

	/** entity == null: spawn a fresh wendigo directly into orbit - no LLM call, no cooldown consumed
	 * (only starting a PLAN is cooldown-gated - see the trigger check tickOrbitingEntity will add in
	 * a later step; spawning/orbiting itself isn't). Retries near the previous target first if one's
	 * still valid (a relocate/discard cycle keeps state.lockedTarget unless the loss WAS the target
	 * itself - see tickOrbitingEntity), otherwise runs the normal proximity-group selection fresh.
	 * Throttled via WaveState.nextRespawnSearchTick - a dark-spot scan isn't free, no need to retry
	 * every single tick while waiting for somewhere valid to appear. */
	private void tryEnterOrbit(ServerLevel level, WaveState state, int now) {
		if (state.requestPending || now < state.nextRespawnSearchTick) {
			return;
		}
		state.nextRespawnSearchTick = now + ORBIT_SPAWN_SEARCH_INTERVAL_TICKS;

		ServerPlayer target = state.lockedTarget != null && state.lockedTarget.isAlive() ? state.lockedTarget : null;
		if (target == null) {
			PlayerSeverityTracker.TargetSelection selection = this.severityTracker.selectTarget(level);
			if (selection == null) {
				return;
			}
			target = selection.target();
		}
		// Spawn already inside the orbit band, not just "somewhere dark nearby" - a spawn that
		// ignores the band (as a flat nearest-dark-spot search would) very often lands inside the
		// too-close threshold, triggering an immediate despawn/chase the moment orbit's first tick
		// runs. Falls back to a plain nearest-dark-spot search (within max band distance) if nothing
		// flood-reachable in-band was found - some darkness beats none at all.
		CaveScale caveScale = CaveScaleScanner.classify(level, target.blockPosition());
		double maxDistance = orbitMaxDistance(caveScale);
		BlockPos spawnPos = DarkSpotScanner.findOrbitWaypoint(level, target.blockPosition(), target.blockPosition(),
			orbitMinDistance(caveScale), maxDistance);
		if (spawnPos == null) {
			spawnPos = DarkSpotScanner.findDarkest(level, target.blockPosition(), maxDistance);
		}
		if (spawnPos == null) {
			return; // nothing dark near this target yet either - try again next throttled search
		}

		WendigoEntity wendigo = new WendigoEntity(ModEntities.WENDIGO, level);
		wendigo.snapTo(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5, 0f, 0f);
		wendigo.syncPoseToSpawnPosition();
		wendigo.nudgeTowardAttachedSurface(Direction.UP);
		level.addFreshEntity(wendigo);
		WendigoSounds.play(level, spawnPos, WendigoSounds.Type.SPAWN);
		wendigo.startOrbit(target);

		state.entity = wendigo;
		state.lockedTarget = target;
		WendigoDebug.say(level, "spawned into orbit near " + target.getGameProfile().name() + " at " + spawnPos.toShortString());
	}

	/** Bypasses cooldown/eligibility and calls the real LLM - used by the /wendigo wave debug command.
	 * Targets exactly the given player (their own individual severity, not a group's) rather than
	 * going through the automatic multiplayer group-selection - a deliberate test target shouldn't be
	 * second-guessed by who else happens to be nearby. */
	public void forceWave(ServerLevel level, ServerPlayer target) {
		WaveState state = this.waves.computeIfAbsent(level, l -> new WaveState());
		if (state.requestPending) {
			return;
		}
		// A persistent, already-orbiting entity doesn't need a fresh spawn - engage it directly
		// (same as the automatic trigger would) rather than requiring a manual /wendigo reset first.
		// Still requires a full reset for a MID-PLAN entity, same as before - this debug command
		// shouldn't interrupt an active plan on its own.
		if (state.entity != null && state.entity.isAlive() && state.entity.isOrbiting()) {
			state.debugForced = true;
			beginEngagement(level, state, target, this.severityTracker.severityOf(target));
			return;
		}
		if (state.entity != null) {
			return;
		}
		state.debugForced = true;
		beginWave(level, state, target, this.severityTracker.severityOf(target));
	}

	/**
	 * Testing convenience: immediately discards this level's active/pending wave (if any) and zeroes
	 * its cooldown, so a fresh /wendigo wave can fire right away instead of waiting for the current
	 * encounter to run its natural course (which can easily take tens of seconds to several minutes)
	 * or for dynamicCooldownTicks to lapse afterward. Not itself an LLM-call rate limit - there isn't
	 * one - this clears the two things that actually block a follow-up: an entity/request already in
	 * flight, and the post-wave cooldown.
	 */
	public void resetForTesting(ServerLevel level) {
		WaveState state = this.waves.get(level);
		if (state == null) {
			return;
		}
		if (state.entity != null && state.entity.isAlive()) {
			state.entity.discard();
		}
		state.entity = null;
		state.context = null;
		state.lockedTarget = null;
		state.requestPending = false;
		state.cooldownUntilTick = 0;
		state.debugForced = false;
	}

	/**
	 * Bypasses cooldown/eligibility AND the LLM call - runs a hand-authored plan (spawn_at/plan/
	 * global_rules, same shape the model would return; despawning is always engine-ranked, not part
	 * of the plan) through the real spawn/despawn lifecycle. Used by the /wendigo wavetest debug
	 * command to iterate on plans for free.
	 */
	public void forceWaveWithPlan(ServerLevel level, ServerPlayer target, JsonObject plan) {
		WaveState state = this.waves.computeIfAbsent(level, l -> new WaveState());
		if (state.requestPending) {
			return;
		}
		state.debugForced = true;
		WaveContext context = buildContext(level, target, this.severityTracker.severityOf(target));
		if (context == null) {
			return;
		}
		// Same "engage the existing orbiting entity instead of requiring a reset" treatment forceWave
		// itself gets - still requires a reset for a genuinely mid-plan entity.
		if (state.entity != null && state.entity.isAlive() && state.entity.isOrbiting()) {
			// Hand-authored showcase/test plans shouldn't be second-guessed by tier gating meant to
			// keep an LLM honest - bypass it (severityPercent=100 unlocks everything).
			engageExistingWendigo(level, state, context, plan, true);
			return;
		}
		if (state.entity != null) {
			return;
		}
		spawnWave(level, state, context, plan, true);
	}

	/**
	 * Punishment for lingering too long in darkness (see DarknessOverstayTracker, which owns the
	 * timer this fires from) - a hardcoded plan the LLM never sees or authors, reusing this class's
	 * normal wave lifecycle (one wendigo at a time, real encounter-history afterward - NOT
	 * debugForced, unlike /wendigo wave's test commands, since this is a genuine gameplay event) just
	 * to spawn/run/despawn it. No-ops quietly if a wave is already active/pending - the tracker will
	 * simply try again on its own next tick if the overstay condition still holds then.
	 */
	public void triggerDarknessAmbush(ServerLevel level, ServerPlayer target) {
		WaveState state = this.waves.computeIfAbsent(level, l -> new WaveState());
		// Deliberately NOT gated by state.cooldownUntilTick (unlike the automatic severity-triggered
		// spawner) - that field is shared with /wendigo wave/wavetest's debugCooldownTicks (5 whole
		// minutes), which would silently swallow every ambush trigger for the rest of a testing
		// session after a single debug wave. Its own breathing room already comes from
		// DarknessOverstayTracker itself: darkTicks/rolledThresholdTicks are cleared right before this
		// is called, so a fresh multi-second dark stay has to accumulate again before this can fire a
		// second time regardless.
		if (state.requestPending) {
			return;
		}
		// A persistent wendigo already exists (orbiting OR mid-plan) - redirect it straight into the
		// chase instead of trying to spawn a second one. overrideIntoChaseUntilLight already handles
		// "already forcing a ride" (no-op) and builds its own context if this entity hasn't been
		// engaged yet (e.g. still on its very first orbit, never yet given a real plan).
		if (state.entity != null && state.entity.isAlive()) {
			overrideIntoChaseUntilLight(level, target);
			return;
		}
		if (state.entity != null) {
			return; // dead, not yet cleaned up this tick - tickLevel will clear it; try again next time
		}
		WaveContext context = buildContext(level, target, this.severityTracker.severityOf(target));
		if (context != null) {
			// Engine-authored, not model-authored - same "don't second-guess a deliberately built
			// plan" bypass as /wendigo wavetest's hand-authored content.
			spawnWave(level, state, context, buildDarknessAmbushPlan(), true);
		}
	}

	/** True if this level currently has a real, alive wendigo mid-encounter - DarknessOverstayTracker
	 * uses this to decide whether a darkness-overstay trigger should spawn a fresh ambush
	 * (triggerDarknessAmbush, which just silently no-ops while one's already active anyway) or
	 * redirect the existing one instead (overrideIntoChaseUntilLight). */
	public boolean hasActiveWave(ServerLevel level) {
		WaveState state = this.waves.get(level);
		return state != null && state.entity != null && state.entity.isAlive();
	}

	/**
	 * Darkness-overstay trigger for when a wendigo is already active (see DarknessOverstayTracker,
	 * which uses a much shorter fixed threshold for this case than the tiered one that spawns a fresh
	 * ambush): rather than trying to spawn a second one, interrupts whatever it's currently doing -
	 * an LLM-authored plan it may be mid-way through - and redirects it straight into
	 * internal.chase_until_light instead, same "get out of the dark or get grabbed" payoff, just
	 * without a spawn step. Reuses the active wave's own already-scanned spots as despawn candidates
	 * rather than rescanning. No-ops quietly if there's no active wave after all (a race between the
	 * tracker's check and this call is possible but harmless).
	 * <p>Also no-ops while the wendigo already has the player as a forced rider (see
	 * WendigoEntity.isForcingRide): real bug found from a play-session log - the player stays "in
	 * darkness" (dark enough to keep tripping this trigger) the entire time they're being carried
	 * toward a despawn point, so without this guard the tracker's own 5s re-fire kept restarting
	 * internal.chase_until_light from scratch on someone already caught, and isChaseUntilLightResolved
	 * unconditionally calls beginForcedRide again the instant it sees them in melee range (true
	 * immediately, since they're literally riding). Each restart re-rolled a fresh escape threshold and
	 * MIN_RIDE_TICKS grace period and threw out however far the previous despawn attempt had already
	 * traveled, reading as the wendigo endlessly zigzagging across the cave instead of ever finishing
	 * the withdrawal. Once truly caught, the existing plan (retreat_with_fallback/despawn fallback
	 * chain) is already exactly "get them out of the dark or get grabbed" playing out - nothing left
	 * for this trigger to add.
	 * <p>Also generalizes to interrupt ORBIT, not just an already-active plan - state.entity.startWave
	 * (via PlanRunner.start's own orbiting=false reset, see that method) already ends orbit mode
	 * cleanly on its own; the only thing orbit-vs-mid-plan changes here is that a still-orbiting,
	 * never-yet-engaged entity may not have a state.context yet, so one gets built fresh in that case
	 * rather than requiring one to already exist.
	 */
	public void overrideIntoChaseUntilLight(ServerLevel level, ServerPlayer target) {
		WaveState state = this.waves.get(level);
		if (state == null || state.entity == null || !state.entity.isAlive() || state.entity.isForcingRide()) {
			return;
		}
		WaveContext context = state.context != null ? state.context
			: buildContext(level, target, this.severityTracker.severityOf(target));
		if (context == null) {
			return;
		}
		List<BlockPos> despawnCandidates = rankDespawnCandidates(context.spots(), level.players());
		state.entity.startWave(buildChaseUntilLightOverridePlan(), despawnCandidates, context.spots(), 100, true,
			context.caveScale(), context.torchSpotPerLabel());
		state.context = context;
		state.lockedTarget = target;
		state.waveStartTick = level.getServer().getTickCount();
		state.extremeProximityTicks = 0;
		WendigoDebug.say(level, "darkness overstay while already active - overriding into internal.chase_until_light");
	}

	/** checkOrbitTooClose's own >=ORBIT_TOO_CLOSE_LUNGE_MIN_PERCENT reaction - same guard/context-
	 * building shape as overrideIntoChaseUntilLight, just starting a bounded combat.lunge_attack
	 * pursuit (buildTooCloseLungePlan) instead of an internal.chase_until_light one. Deliberately a
	 * separate method rather than a parameter on overrideIntoChaseUntilLight - that one stays exactly
	 * as DarknessOverstayTracker needs it (a genuinely open-ended hunt is the right call for "lingered
	 * in darkness too long"; "got too close" is a different, more bounded provocation). */
	private void overrideIntoLunge(ServerLevel level, ServerPlayer target) {
		WaveState state = this.waves.get(level);
		if (state == null || state.entity == null || !state.entity.isAlive() || state.entity.isForcingRide()) {
			return;
		}
		WaveContext context = state.context != null ? state.context
			: buildContext(level, target, this.severityTracker.severityOf(target));
		if (context == null) {
			return;
		}
		List<BlockPos> despawnCandidates = rankDespawnCandidates(context.spots(), level.players());
		state.entity.startWave(buildTooCloseLungePlan(), despawnCandidates, context.spots(), 100, true,
			context.caveScale(), context.torchSpotPerLabel());
		state.context = context;
		state.lockedTarget = target;
		state.waveStartTick = level.getServer().getTickCount();
		state.extremeProximityTicks = 0;
		WendigoDebug.say(level, "target got too close while orbiting - overriding into a bounded lunge pursuit");
	}

	/** "chase cue -> bounded lunge pursuit (catches them, or gives up on its own once the pathfind
	 * finishes/gets stuck - see PlanRunner.isLungeResolved) -> flee cue -> flee". See
	 * overrideIntoLunge's own comment for why this is a lunge rather than an
	 * internal.chase_until_light hunt. */
	private static JsonObject buildTooCloseLungePlan() {
		JsonObject chaseCue = new JsonObject();
		chaseCue.addProperty("type", "sound.ambient_cue");
		chaseCue.addProperty("cue", "chase");

		JsonObject lunge = new JsonObject();
		lunge.addProperty("type", "combat.lunge_attack");

		JsonObject fleeCue = new JsonObject();
		fleeCue.addProperty("type", "sound.ambient_cue");
		fleeCue.addProperty("cue", "flee");

		JsonObject flee = new JsonObject();
		flee.addProperty("type", "movement.retreat_with_fallback");
		flee.addProperty("speed", "fast");

		JsonArray steps = new JsonArray();
		steps.add(chaseCue);
		steps.add(lunge);
		steps.add(fleeCue);
		steps.add(flee);

		JsonObject plan = new JsonObject();
		plan.add("plan", steps);
		plan.add("global_rules", new JsonArray());
		return plan;
	}

	/** Shared by both darkness-overstay plans - "chase cue -> chase until player reaches light (or
	 * gets caught) -> flee cue -> flee". See PlanRunner's internal.chase_until_light for the one
	 * genuinely new primitive here; every other step reuses existing action types. */
	private static JsonArray buildDangerChaseFleeSteps() {
		JsonObject chaseCue = new JsonObject();
		chaseCue.addProperty("type", "sound.ambient_cue");
		chaseCue.addProperty("cue", "chase");

		JsonObject chase = new JsonObject();
		chase.addProperty("type", "internal.chase_until_light");

		JsonObject fleeCue = new JsonObject();
		fleeCue.addProperty("type", "sound.ambient_cue");
		fleeCue.addProperty("cue", "flee");

		JsonObject flee = new JsonObject();
		flee.addProperty("type", "movement.retreat_with_fallback");
		flee.addProperty("speed", "fast");

		JsonArray steps = new JsonArray();
		steps.add(chaseCue);
		steps.add(chase);
		steps.add(fleeCue);
		steps.add(flee);
		return steps;
	}

	/** spawn_at is always the unwatched default (this should read as a sudden ambush, not something
	 * the player could have seen coming) - despawning is entirely engine-ranked now (see
	 * rankDespawnCandidates), nothing for this hand-built plan to specify. */
	private static JsonObject buildDarknessAmbushPlan() {
		JsonObject plan = new JsonObject();
		plan.addProperty("spawn_at", "no_players_looking");
		plan.add("plan", buildDangerChaseFleeSteps());
		plan.add("global_rules", new JsonArray());
		return plan;
	}

	/** Same shape as buildDarknessAmbushPlan minus spawn_at - PlanRunner.start only ever
	 * reads "plan"/"global_rules" from a plan object (spawn/despawn resolution happens externally,
	 * before startWave is called), and overrideIntoChaseUntilLight's entity already exists/has
	 * despawn candidates from its current wave, so there's nothing to resolve here. */
	private static JsonObject buildChaseUntilLightOverridePlan() {
		JsonObject plan = new JsonObject();
		plan.add("plan", buildDangerChaseFleeSteps());
		plan.add("global_rules", new JsonArray());
		return plan;
	}

	/** Unconditional grab_distance override - the instant the target comes within grab_distance
	 * (ProximityBands, 4 blocks) of an entity that isn't already forcing a ride, this catches them
	 * immediately and runs the flee/damage/move-away sequence, interrupting whatever else was
	 * happening (orbiting OR mid-plan, doesn't matter). Not gated by cooldown/severity/tier at all -
	 * "in reach" is meant to always win outright, unlike combat.lunge_attack's own gated
	 * precondition (a nearby safe-retreat-spot check) which this deliberately bypasses by calling
	 * WendigoEntity.forceGrabNow directly instead of routing through a combat.lunge_attack plan
	 * step, which could otherwise silently skip the catch and go straight to fleeing empty-handed. */
	// Flat time floor on top of grabGraceActive's distance-based grace - see WaveState.grabCooldownUntilTick.
	private static final int GRAB_ESCAPE_COOLDOWN_TICKS = 200; // 10s

	private void checkUnconditionalGrab(ServerLevel level, WaveState state, int now) {
		WendigoEntity entity = state.entity;
		if (entity.isForcingRide() || entity.isReturningToOrbit() || entity.isApproachingEngageSpot()) {
			return; // already caught, or already mid-transit somewhere - let that resolve first
		}
		if (entity.consumeFreshEscape()) {
			state.grabGraceActive = true;
			state.grabCooldownUntilTick = now + GRAB_ESCAPE_COOLDOWN_TICKS;
		}
		ServerPlayer target = state.lockedTarget != null && state.lockedTarget.isAlive() ? state.lockedTarget : null;
		if (target == null) {
			return;
		}
		double distance = entity.distanceTo(target);
		if (distance > ProximityBands.blocks("grab_distance")) {
			state.grabGraceActive = false;
			return;
		}
		if (state.grabGraceActive || now < state.grabCooldownUntilTick) {
			// Still within grab range - withhold the re-grab until they've actually gotten away at
			// least once (grabGraceActive, cleared by the branch above once they do) AND the flat
			// cooldown has elapsed, whichever takes longer.
			return;
		}
		WaveContext context = state.context != null ? state.context
			: buildContext(level, target, this.severityTracker.severityOf(target));
		if (context == null) {
			return;
		}
		List<BlockPos> despawnCandidates = rankDespawnCandidates(context.spots(), level.players());
		entity.forceGrabNow(target);
		entity.startWave(buildUnconditionalGrabFleePlan(), despawnCandidates, context.spots(), 100, true,
			context.caveScale(), context.torchSpotPerLabel());
		state.context = context;
		state.waveStartTick = now;
		state.extremeProximityTicks = 0;
		WendigoDebug.say(level, "target within grab range - grabbing unconditionally");
	}

	/** Just the flee - the catch itself already happened via WendigoEntity.forceGrabNow before this
	 * plan ever starts, unlike buildChaseUntilLightOverridePlan/buildDarknessAmbushPlan's own
	 * chase-until-caught shape. */
	private static JsonObject buildUnconditionalGrabFleePlan() {
		JsonObject flee = new JsonObject();
		flee.addProperty("type", "movement.retreat_with_fallback");
		flee.addProperty("speed", "fast");
		JsonArray steps = new JsonArray();
		steps.add(flee);
		JsonObject plan = new JsonObject();
		plan.add("plan", steps);
		plan.add("global_rules", new JsonArray());
		return plan;
	}

	private void beginWave(ServerLevel level, WaveState state, ServerPlayer target, int effectiveSeverity) {
		WaveContext context = buildContext(level, target, effectiveSeverity);
		if (context == null) {
			return; // nowhere sensible found near the player at all - see buildContext's own debug logging; try again next tick
		}
		int percent = context.severityCap() > 0 ? 100 * context.severity() / context.severityCap() : 0;
		boolean torchSpawnAvailable = hasEligibleTorchSpawnCandidate(context, percent);

		state.requestPending = true;
		MinecraftServer server = level.getServer();
		WendigoMod.llmClient.requestPlan(buildSystemPrompt(percent), context.toPromptText(),
				SchemaBuilder.forSeverity(percent, context.caveScale(), torchSpawnAvailable))
			.whenComplete((plan, error) -> server.execute(() -> {
				state.requestPending = false;
				if (error != null) {
					WendigoMod.LOGGER.error("Wendigo wave plan request failed", error);
					state.cooldownUntilTick = level.getServer().getTickCount() + this.config.dynamicCooldownTicks(percent);
					return;
				}
				// This completion can be stale by the time it runs - resetForTesting() clears
				// requestPending/entity without any way to actually cancel the in-flight LLM call, so a
				// reset (or another wave spawned in the meantime some other way) followed by this request
				// finally resolving can otherwise leave two live wendigos: this one, plus whatever already
				// took state.entity. Only the request that's still first to land gets to spawn.
				if (state.entity != null && state.entity.isAlive()) {
					WendigoMod.LOGGER.warn("Discarding a stale wendigo plan - a wave was already active by the time it resolved: {}", plan);
					return;
				}
				spawnWave(level, state, context, plan, false);
			}));
	}

	/** The automatic severity-triggered path once a wendigo is already spawned and orbiting - same
	 * LLM-request plumbing as beginWave, just engaging the entity that's already here
	 * (engageExistingWendigo) once the plan comes back, instead of constructing a new one via
	 * spawnWave. Called from tickOrbitingEntity's own cooldown check, mirroring exactly how beginWave
	 * used to be the only way a wendigo ever came to exist at all. */
	private void beginEngagement(ServerLevel level, WaveState state, ServerPlayer target, int effectiveSeverity) {
		WaveContext context = buildContext(level, target, effectiveSeverity);
		if (context == null) {
			return; // nowhere sensible found near the player right now - try again next tick
		}
		int percent = context.severityCap() > 0 ? 100 * context.severity() / context.severityCap() : 0;
		boolean torchSpawnAvailable = hasEligibleTorchSpawnCandidate(context, percent);

		state.requestPending = true;
		MinecraftServer server = level.getServer();
		WendigoMod.llmClient.requestPlan(buildSystemPrompt(percent), context.toPromptText(),
				SchemaBuilder.forSeverity(percent, context.caveScale(), torchSpawnAvailable))
			.whenComplete((plan, error) -> server.execute(() -> {
				state.requestPending = false;
				if (error != null) {
					WendigoMod.LOGGER.error("Wendigo engagement plan request failed", error);
					state.cooldownUntilTick = level.getServer().getTickCount() + this.config.dynamicCooldownTicks(percent);
					return;
				}
				// Same staleness guard beginWave's own completion handler has, just checking
				// alive-and-still-orbiting instead of entirely-absent - the entity could have died,
				// gotten relocated, or already been engaged by something else while this was in flight.
				if (state.entity == null || !state.entity.isAlive() || !state.entity.isOrbiting()) {
					WendigoMod.LOGGER.warn("Discarding a stale wendigo engagement plan - no longer a valid orbiting entity: {}", plan);
					return;
				}
				engageExistingWendigo(level, state, context, plan, false);
			}));
	}

	/** Starts a plan on an already-alive, already-orbiting entity - the engage-existing-entity
	 * counterpart to spawnWave (which constructs a brand new one). Unlike a brand-new entity's
	 * spawn_at (a plain teleport at construction, since there's nothing to walk FROM yet), this
	 * entity already exists somewhere else in the cave (wherever orbit left it), so the chosen spot
	 * is walked to instead (see PlanRunner.startWithApproach) - skipped automatically if it's
	 * already close by. */
	private void engageExistingWendigo(ServerLevel level, WaveState state, WaveContext context, JsonObject plan, boolean bypassTierGating) {
		int percent = context.severityCap() > 0 ? 100 * context.severity() / context.severityCap() : 0;
		int gatingPercent = bypassTierGating ? 100 : percent;
		List<BlockPos> despawnCandidates = rankDespawnCandidates(context.spots(), level.players());
		BlockPos engageSpot = resolveSpawnSpot(context, plan, percent, bypassTierGating);
		state.entity.startWithApproach(engageSpot, plan, despawnCandidates, context.spots(), gatingPercent, bypassTierGating,
			context.caveScale(), context.torchSpotPerLabel());
		state.context = context;
		state.waveStartTick = level.getServer().getTickCount();
		state.extremeProximityTicks = 0;
		WendigoDebug.say(level, "engaging existing wendigo - aggression: " + context.severity() + "/" + context.severityCap()
			+ " (" + percent + "%), caveScale=" + context.caveScale() + ", spots=" + context.spots().size()
			+ ", plan: " + plan);
	}

	/**
	 * effectiveSeverity is what actually drives tier/schema/prompt for this encounter - usually just
	 * target's own severity, but for an automatically-selected multiplayer target it's the whole
	 * proximity group's worst (highest) member (see PlayerSeverityTracker.selectTarget) - the wendigo
	 * shows up at the full intensity that group's most-established member has earned, even if the
	 * player it actually grabs happens to have less severity of their own.
	 * <p>
	 * DarkSpotScanner.findWaveSpots does one flood-fill from the player's position across actually-
	 * connected standable columns - every spot (and every spawn_on_torch candidate) it returns is
	 * reachable from the player by construction, not by a separate probe-based check afterward (the
	 * earlier design, which repeatedly proved unreliable - crevices, rails, fence-post gaps, search
	 * budget were each their own source of "engine says unreachable, live wendigo proves otherwise").
	 * Returns null only if the flood found neither a usable spot nor a torch-spawn candidate at all.
	 */
	// Floor/ceiling split of the six spot_a..spot_f slots - see DarkSpotScanner.findCeilingSpots'
	// own comment for the actual root cause of ceiling spots being rare (a single vertical probe
	// directly above the player, not path-cost bias) and its fix (a wider seed search); this 3/3
	// split plus the mutual backfill below is the other half - previously a fixed reserve count
	// (2 ceiling + 2 wall) meant floor got whatever was left over and never had to share evenly.
	// No dedicated wall reserve anymore (findWallSpots isn't called here at all) - wall attachment
	// is left to emerge naturally from pathfinding between floor/ceiling spots instead of being a
	// separately-scanned category; it was always the least rigorous of the three scans anyway (ring-
	// sampled, not flood-verified reachable, see findWallSpots' own doc comment). Tune by feel.
	private static final int CEILING_SPOT_TARGET = 3;

	private WaveContext buildContext(ServerLevel level, ServerPlayer target, int effectiveSeverity) {
		BlockPos playerPos = target.blockPosition();
		int ceilingTarget = Math.min(CEILING_SPOT_TARGET, this.config.contextSpotCount);
		int floorTarget = this.config.contextSpotCount - ceilingTarget;
		// Ceiling/floor spots are appended in scan order below, not interleaved by distance - a
		// minor, cosmetic looseness in the "nearest to furthest" ordering WaveContext's prompt text
		// describes, traded for reusing the existing label-index-based tier gating unchanged rather
		// than needing spot-type-aware gating logic. Ceiling's own torch-spawn candidates are
		// deliberately not merged into torchSpawnCandidates below - spawn_on_torch's destroy-on-
		// arrival mechanic hasn't been designed for a ceiling-attached torch/lantern, out of scope
		// for this pass.
		DarkSpotScanner.WaveSpotScan ceilingScan = ceilingTarget > 0
			? DarkSpotScanner.findCeilingSpots(level, playerPos, ceilingTarget)
			: new DarkSpotScanner.WaveSpotScan(List.of(), List.of());
		// A ceiling shortfall (nothing suitable within reach at all) gets backfilled by extra floor
		// slots, same "don't let a reserved category's own scarcity shrink the total context" idea
		// as before - floor is far more reliably available than ceiling, so this direction covers
		// the realistic common case.
		int ceilingShortfall = ceilingTarget - ceilingScan.spots().size();
		DarkSpotScanner.WaveSpotScan floorScan = DarkSpotScanner.findWaveSpots(level, playerPos, floorTarget + ceilingShortfall);
		// The rarer opposite case (floor itself came up short too - a genuinely cramped or already
		// heavily-scanned area) gets one more chance: ask ceiling to make up whatever floor still
		// couldn't fill, on top of what it already found - a genuinely mutual backfill in EITHER
		// direction, not just floor rescuing ceiling.
		int floorShortfall = (floorTarget + ceilingShortfall) - floorScan.spots().size();
		if (floorShortfall > 0 && ceilingScan.spots().size() < this.config.contextSpotCount) {
			DarkSpotScanner.WaveSpotScan extraCeiling = DarkSpotScanner.findCeilingSpots(level, playerPos,
				ceilingScan.spots().size() + floorShortfall);
			if (extraCeiling.spots().size() > ceilingScan.spots().size()) {
				ceilingScan = extraCeiling;
			}
		}
		List<DarkSpotScanner.WaveSpot> waveSpots = new ArrayList<>(floorScan.spots());
		waveSpots.addAll(ceilingScan.spots());
		List<DarkSpotScanner.WaveSpot> torchSpawnCandidates = floorScan.torchSpawnCandidates();
		if (waveSpots.isEmpty() && torchSpawnCandidates.isEmpty()) {
			WendigoDebug.say(level, "issue: no dark spots or torch-adjacent positions found at all near "
				+ target.getName().getString() + " - can't build a wave context");
			return null;
		}
		List<BlockPos> spots = new ArrayList<>();
		List<Direction> spotNormals = new ArrayList<>();
		Map<BlockPos, BlockPos> torchLinkedSpots = new HashMap<>();
		for (DarkSpotScanner.WaveSpot spot : waveSpots) {
			spots.add(spot.position());
			spotNormals.add(spot.surfaceNormal());
			if (spot.isTorchLinked()) {
				torchLinkedSpots.put(spot.position(), spot.linkedTorch());
			}
		}
		// spawn_on_torch candidates share the same destroy-on-arrival lookup as regular torch-linked
		// spots (see spawnWave's linkedTorch resolution) - registered here too so resolving one needs
		// no special-casing there, only in resolveSpawnSpot.
		for (DarkSpotScanner.WaveSpot candidate : torchSpawnCandidates) {
			torchLinkedSpots.put(candidate.position(), candidate.linkedTorch());
		}
		List<List<BlockPos>> dimSpotsPerSpot = new ArrayList<>();
		List<List<BlockPos>> torchesPerSpot = new ArrayList<>();
		for (BlockPos spot : spots) {
			// Torches are derived by climbing from each dim spot to the light source causing it
			// (see DarkSpotScanner.findSpotDimSpots), not an independent scan - a dim spot is dim
			// precisely because some light source is nearby, so this reuses the one ring-sampling
			// pass instead of running a second scan. Omnidirectional and multi-radius around the dark
			// spot itself, not biased toward the player - a far spot (e.g. spot_d) gets credit for
			// its own nearby torches regardless of which side of it they're on. Still floor-oriented
			// (ring-samples for standable positions) even for a ceiling spot - not generalized this
			// pass, so results near a ceiling spot may be sparser/less meaningful, but degrade
			// gracefully (empty lists) rather than breaking.
			DarkSpotScanner.RelevantSpots relevant = DarkSpotScanner.findSpotDimSpots(level, spot);
			dimSpotsPerSpot.add(relevant.dimSpots());
			torchesPerSpot.add(relevant.lightSources());
		}
		CaveScale caveScale = CaveScaleScanner.classify(level, playerPos);
		// One torch-spot pairing per labeled slot (spot_a..spot_f), independent of whether that same
		// slot's own dark spot was found this scan - see WaveContext.torchSpotForLabel's own comment
		// for why (the "spot doesn't exist, but its torch spot does" teleport-substitute case).
		// Nearest-to-furthest by distanceFromOrigin, same convention spots() itself already uses -
		// reuses torchSpawnCandidates verbatim, no separate scan needed.
		List<DarkSpotScanner.WaveSpot> sortedTorchCandidates = new ArrayList<>(torchSpawnCandidates);
		sortedTorchCandidates.sort(Comparator.comparingDouble(DarkSpotScanner.WaveSpot::distanceFromOrigin));
		List<BlockPos> torchSpotPerLabel = new ArrayList<>();
		for (DarkSpotScanner.WaveSpot candidate : sortedTorchCandidates) {
			if (torchSpotPerLabel.size() >= WaveContext.SPOT_LABEL_COUNT) {
				break;
			}
			torchSpotPerLabel.add(candidate.position());
		}
		return new WaveContext(target, effectiveSeverity, this.config.severityCap, spots, spotNormals,
			dimSpotsPerSpot, torchesPerSpot, torchSpawnCandidates, torchSpotPerLabel,
			this.encounterHistory.of(target), level.getServer().getTickCount(), caveScale, torchLinkedSpots);
	}

	private void spawnWave(ServerLevel level, WaveState state, WaveContext context, JsonObject plan, boolean bypassTierGating) {
		int percent = context.severityCap() > 0 ? 100 * context.severity() / context.severityCap() : 0;
		// Stage 1 always gets the safest possible first appearance, regardless of what spawn_at the
		// model actually picked - prose alone proved unreliable for this kind of hard requirement
		// (see TierGates), so it's forced here rather than hoped for. Not forced for bypassTierGating
		// (hand-authored /wendigo wavetest content) - a showcase's deliberately chosen spawn_at
		// shouldn't be second-guessed just because the tester's own severity happens to be low.
		BlockPos spawnPos = !bypassTierGating && percent < 20
			? resolveUnwatchedSpot(context)
			: resolveSpawnSpot(context, plan, percent, bypassTierGating);
		// Some resolved spots aren't genuinely dark on their own - they're "torch-linked" (see
		// DarkSpotScanner.WaveSpot) or a spawn_on_torch pick, too lit but near a breakable torch,
		// invisible to the model as a distinction for regular spot labels (spawn_at just offered the
		// label like any other spot). Destroying that torch on arrival is what makes spawning there
		// sensible. context.spots() legitimately CAN be empty here now (a spawn_on_torch-only
		// context, nothing genuinely dark found at all) - spawnPos being resolved is the only real
		// failure signal, not spots() being empty.
		BlockPos linkedTorch = spawnPos != null ? context.linkedTorchFor(spawnPos) : null;
		if (spawnPos == null) {
			WendigoMod.LOGGER.warn("Wendigo wave plan missing a resolvable spawn spot, skipping: {}", plan);
			state.cooldownUntilTick = level.getServer().getTickCount() + this.config.dynamicCooldownTicks(percent);
			return;
		}
		// despawn_at is no longer a model choice at all (see rankDespawnCandidates) - the engine always
		// tries the farthest/most-hidden-from-any-player spot first, falling back down the ranked list
		// only if a candidate turns out unreachable when the wendigo actually goes to flee there (see
		// PlanRunner.beginNextDespawnAttempt, unchanged). A close spot is never off the table entirely -
		// it's just always tried last, not gated away by severity/cave-scale like it used to be.
		List<BlockPos> despawnCandidates = rankDespawnCandidates(context.spots(), level.players());

		int gatingPercent = bypassTierGating ? 100 : percent;

		WendigoEntity wendigo = new WendigoEntity(ModEntities.WENDIGO, level);
		wendigo.snapTo(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5, 0f, 0f);
		wendigo.syncPoseToSpawnPosition();
		// See WendigoEntity.nudgeTowardAttachedSurface's own doc comment - mitigates a live-confirmed
		// bug where spawning directly onto a ceiling spot falls straight off it instead of sticking.
		// A no-op for every ordinary floor spawn (normalFor defaults to UP when spot isn't one of the
		// labeled spot_a..spot_f positions, or genuinely is a floor spot).
		wendigo.nudgeTowardAttachedSurface(context.normalFor(spawnPos));
		level.addFreshEntity(wendigo);
		WendigoSounds.play(level, spawnPos, WendigoSounds.Type.SPAWN);
		if (linkedTorch != null) {
			LightSourceScanner.destroyByWendigo(level, linkedTorch, wendigo);
		}
		wendigo.startWave(plan, despawnCandidates, context.spots(), gatingPercent, bypassTierGating,
			context.caveScale(), context.torchSpotPerLabel());

		state.entity = wendigo;
		state.context = context;
		// Keeps every spawnWave-constructed entity (debug/ambush paths - the automatic trigger now
		// spawns via tryEnterOrbit instead) consistent with the locked-target model too, so if this
		// entity's plan later completes normally, resuming orbit afterward has a real target to lock
		// onto instead of null.
		state.lockedTarget = context.player();
		state.waveStartTick = level.getServer().getTickCount();
		state.extremeProximityTicks = 0;
		WendigoDebug.say(level, "wave started - spawned at " + spawnPos.toShortString()
			+ ", aggression: " + context.severity() + "/" + context.severityCap() + " (" + percent + "%)"
			+ ", caveScale=" + context.caveScale() + ", spots=" + context.spots().size()
			+ ", plan: " + plan);
	}

	/** Periodic mid-wave state dump (see WendigoWaveConfig.debugContextIntervalTicks) - not tied to any
	 * one action's own logging (see PlanRunner), a standing snapshot so trends across a whole wave -
	 * repeated stuck navigation, staying lit too long, drifting far from the player - are visible from
	 * chat/log history without needing to catch the moment something goes wrong live. */
	private void logContextSnapshot(ServerLevel level, WaveState state, int now) {
		WendigoEntity entity = state.entity;
		ServerPlayer player = state.context.player();
		int light = level.getMaxLocalRawBrightness(entity.blockPosition());
		WendigoDebug.say(level, "context: self=" + entity.blockPosition().toShortString()
			+ " distanceToPlayer=" + String.format("%.1f", entity.distanceTo(player))
			+ " light=" + light
			+ " staring=" + entity.isStaring()
			+ " crawling=" + entity.isCrawling()
			+ " navFailed=" + entity.isNavigationFailed()
			+ " navStuck=" + entity.getNavigation().isStuck()
			+ " onGround=" + entity.onGround()
			// Always false here in practice (this snapshot only fires from the mid-plan branch of
			// tickLevel - an orbiting entity takes a different dispatch path entirely, see
			// tickOrbitingEntity) - included anyway for a consistent, greppable field across both.
			+ " orbiting=" + entity.isOrbiting()
			+ " waveElapsedTicks=" + (now - state.waveStartTick));
	}

	/**
	 * Whether this wave should be forced to end right now, and why - null if not. Two narrow triggers
	 * left: a rare hard backstop (waveTimeoutTicks, sized to almost never fire on its own), and the
	 * wendigo being stuck at extreme close range with the player for far longer than any legitimate
	 * hold/combat exchange would take. The player having simply moved far away used to also force the
	 * whole wave to end here, but that could cut short a plan that still had something else useful to
	 * do (combat.break_torch, a sound cue) just because the one thing waiting on the player - a
	 * control.while stare-hold - had nowhere left to go; that's now handled at the plan level instead
	 * (see PlanRunner's control.while handling), where only the actual staring/waiting step ends
	 * early and the rest of the plan still gets to run. See PlanRunner's own stuck-in-light detector
	 * too, for per-action failure handling that ends just the current action rather than the whole
	 * wave.
	 */
	private String checkForcedWaveEnd(WaveState state, int now) {
		if (now - state.waveStartTick > this.config.waveTimeoutTicks) {
			return "hard backstop timeout, " + this.config.waveTimeoutTicks + " ticks";
		}
		if (state.context == null) {
			return null;
		}
		double distance = state.entity.distanceTo(state.context.player());
		if (distance <= EXTREME_PROXIMITY_DISTANCE) {
			state.extremeProximityTicks++;
		} else {
			state.extremeProximityTicks = 0;
		}
		if (state.extremeProximityTicks > EXTREME_PROXIMITY_GIVEUP_TICKS) {
			return "stuck at extreme close range with the player for too long";
		}
		return null;
	}

	/** Resolves spawn_at to a position, falling back to a fresh scan rather than ever rejecting the
	 * whole wave over one bad field - same philosophy the schema itself documents. Re-checks
	 * SchemaBuilder.isSpawnSpotAllowed as defense-in-depth (bypassed for bypassTierGating content) -
	 * the schema already keeps a real LLM call from offering a disallowed spot in the first place,
	 * same precedent as everywhere else that re-checks rather than trusting the schema filter alone. */
	private BlockPos resolveSpawnSpot(WaveContext context, JsonObject plan, int percent, boolean bypassTierGating) {
		String label = plan.has("spawn_at") ? plan.get("spawn_at").getAsString() : null;
		boolean torchSpawnAvailable = hasEligibleTorchSpawnCandidate(context, percent);
		boolean allowed = label != null && (bypassTierGating || SchemaBuilder.isSpawnSpotAllowed(label, percent, context.caveScale(), torchSpawnAvailable));
		BlockPos resolved;
		if ("no_players_looking".equals(label)) {
			resolved = resolveUnwatchedSpot(context);
		} else if ("spawn_on_torch".equals(label)) {
			resolved = allowed ? resolveTorchSpawnSpot(context, percent, bypassTierGating) : null;
		} else if (allowed) {
			resolved = context.resolve(label);
		} else {
			resolved = null;
		}
		if (resolved == null) {
			resolved = DarkSpotScanner.findDarkest(context.player().level(), context.player().blockPosition(), 16);
		}
		return resolved;
	}

	/** Whether at least one of this wave's spawn_on_torch candidates clears the current severity's
	 * distance floor - see SchemaBuilder.minTorchSpawnDistance. Shared between beginWave's schema-gate
	 * computation and resolveSpawnSpot's own defensive re-check so the two can't drift apart. */
	private static boolean hasEligibleTorchSpawnCandidate(WaveContext context, int percent) {
		double minDistance = SchemaBuilder.minTorchSpawnDistance(percent);
		return context.torchSpawnCandidates().stream().anyMatch(c -> c.distanceFromOrigin() >= minDistance);
	}

	/** Random pick among this wave's spawn_on_torch candidates that clear the current severity's
	 * distance floor (bypassed entirely for hand-authored debug content, same as every other tier
	 * gate) - deliberately not "furthest" or "nearest" among the eligible ones, just any of them,
	 * since the whole point is "somewhere already exposed to commit from", not a particular geometry.
	 * Null if none qualify (shouldn't happen if this was actually offered/chosen, but resolveSpawnSpot's
	 * own fallback covers it either way). */
	private static BlockPos resolveTorchSpawnSpot(WaveContext context, int percent, boolean bypassTierGating) {
		double minDistance = bypassTierGating ? 0.0 : SchemaBuilder.minTorchSpawnDistance(percent);
		List<DarkSpotScanner.WaveSpot> candidates = context.torchSpawnCandidates().stream()
			.filter(c -> c.distanceFromOrigin() >= minDistance)
			.toList();
		return candidates.isEmpty() ? null : candidates.get(ThreadLocalRandom.current().nextInt(candidates.size())).position();
	}

	// Caps the distance component of despawnHiddenness so a very distant spot doesn't dwarf the
	// obstruction component entirely - beyond this, "farther" stops mattering more than "hidden".
	private static final double DESPAWN_DISTANCE_SCORE_CAP = 64.0;

	/** Orders scanned spots best-first for fleeing to, per explicit design: distance from the nearest
	 * player and obstruction-from-any-player are weighted equally, no severity/cave-scale gating at
	 * all (a close spot is always a valid last resort, just never preferred while a better one
	 * exists). This ranked list is just the fallback-chain input - PlanRunner still walks down it one
	 * candidate at a time, unchanged, if a chosen spot turns out unreachable when actually needed. */
	private static List<BlockPos> rankDespawnCandidates(List<BlockPos> spots, List<ServerPlayer> players) {
		if (players.isEmpty() || spots.size() <= 1) {
			return new ArrayList<>(spots);
		}
		List<BlockPos> ranked = new ArrayList<>(spots);
		ranked.sort(Comparator.comparingDouble((BlockPos spot) -> despawnHiddenness(spot, players)).reversed());
		return ranked;
	}

	/** Higher is better-hidden: distance to the nearest player (capped/normalized) plus the fraction
	 * of players who have no clear line of sight to this spot, equal weight per explicit design. */
	private static double despawnHiddenness(BlockPos spot, List<ServerPlayer> players) {
		Vec3 spotCenter = Vec3.atCenterOf(spot);
		double nearestDistance = players.stream()
			.mapToDouble(p -> p.position().distanceTo(spotCenter))
			.min().orElse(0.0);
		double distanceScore = Math.min(nearestDistance, DESPAWN_DISTANCE_SCORE_CAP) / DESPAWN_DISTANCE_SCORE_CAP;
		long obstructedCount = players.stream().filter(p -> isObstructedFrom(p, spotCenter)).count();
		double obstructionScore = (double) obstructedCount / players.size();
		return distanceScore + obstructionScore;
	}

	/** True if this player has no clear line of sight to the spot (blocks in the way) - collision-only
	 * raytrace, fluids never obstruct a despawn hiding spot. */
	private static boolean isObstructedFrom(ServerPlayer player, Vec3 spotCenter) {
		BlockHitResult result = player.level().clip(new ClipContext(player.getEyePosition(), spotCenter,
			ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		return result.getType() != HitResult.Type.MISS;
	}

	/** Picks a random scanned spot the target player isn't currently looking toward, or the furthest
	 * scanned spot if every one of them happens to be in view right now - backs spawn_at's
	 * "no_players_looking" option, and the forced stage-1 spawn override in spawnWave. */
	private BlockPos resolveUnwatchedSpot(WaveContext context) {
		List<BlockPos> unwatched = new ArrayList<>();
		for (BlockPos spot : context.spots()) {
			if (!isPlayerLookingToward(context.player(), spot)) {
				unwatched.add(spot);
			}
		}
		if (!unwatched.isEmpty()) {
			return unwatched.get(ThreadLocalRandom.current().nextInt(unwatched.size()));
		}
		// Every scanned spot is currently in view - furthest is the documented, safest fallback.
		return context.spots().isEmpty() ? null : context.spots().get(context.spots().size() - 1);
	}

	/** Angle-only approximation (no line-of-sight/occlusion check, unlike the live in-game stare
	 * predicate) of whether the player is currently facing toward a candidate spawn position - good
	 * enough for "don't spawn somewhere already in view", not meant to be as precise as
	 * predicate.player_looking_at_self. Same corner_of_eye threshold (~60 degrees) as that predicate. */
	private static boolean isPlayerLookingToward(ServerPlayer player, BlockPos pos) {
		Vec3 toPos = Vec3.atCenterOf(pos).subtract(player.getEyePosition()).normalize();
		double alignment = player.getLookAngle().normalize().dot(toPos);
		return alignment >= Math.cos(Math.toRadians(60.0));
	}

	private static final class WaveState {
		WendigoEntity entity;
		// Retained across orbit transitions now, not just for one wave's duration - despawn-candidate
		// ranking data stays available for the next engagement instead of needing a full rescan every
		// time, and /wendigo debug can keep re-drawing spot/dim-spot particles continuously. Only
		// cleared when entity itself is genuinely discarded (see relocateOrDiscard/tickOrbitingEntity).
		WaveContext context;
		boolean requestPending;
		int waveStartTick;
		int cooldownUntilTick;
		// The single player/group member this level's wendigo is currently committed to - mirrors
		// WendigoEntity's own lockedTarget (kept in sync whenever a new one is spawned/re-targeted),
		// but also needed here directly since relocateOrDiscard/tryEnterOrbit run when entity may be
		// momentarily null (between a discard and the next respawn search).
		ServerPlayer lockedTarget;
		// Throttles tryEnterOrbit's own dark-spot search while entity == null - a flood-fill isn't
		// free, no need to re-run it every single tick while waiting for somewhere valid to appear.
		int nextRespawnSearchTick;
		// Set by forceWave/forceWaveWithPlan; makes the completion handler apply
		// config.debugCooldownTicks instead of the normal cooldown, so a debug/test wave doesn't
		// leave the automatic severity-triggered spawner armed to fire moments later.
		boolean debugForced;
		// Consecutive ticks the wendigo has been at extreme close range with the player - see
		// checkForcedWaveEnd. Reset at the start of each wave and whenever the condition lapses.
		int extremeProximityTicks;
		// True from the tick a forced ride ends via a genuine dismount-threshold escape (see
		// WendigoEntity.consumeFreshEscape) until the target has put actual distance between
		// themselves and the wendigo - see checkUnconditionalGrab, which must not re-grab someone
		// who just escaped while they're still standing right where they were caught.
		boolean grabGraceActive;
		// Flat time floor on top of grabGraceActive's distance-based grace - covers a player who
		// escaped but is stuck somewhere (a dead end) they genuinely can't put grab_distance of real
		// space between themselves and the wendigo. Set alongside grabGraceActive, on the same
		// consumeFreshEscape() trigger.
		int grabCooldownUntilTick;
	}
}
