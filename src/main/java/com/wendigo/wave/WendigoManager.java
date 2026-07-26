package com.wendigo.wave;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import com.google.gson.JsonObject;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import com.wendigo.WendigoMod;
import com.wendigo.debug.WendigoDebug;
import com.wendigo.entity.ModEntities;
import com.wendigo.entity.WendigoEntity;
import com.wendigo.plan.SchemaBuilder;
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
		"You control a wendigo, a shadow-dwelling stalker creature in Minecraft. You are given a "
		+ "target player, their dweller severity, and a handful of scanned dark spots near them "
		+ "labeled spot_a through spot_f (nearest to furthest, or fewer if that's all that was "
		+ "found). Choose which spot to spawn at, a short plan (1-6 steps) of actions/predicates "
		+ "to run once spawned, and which spot to despawn at once the plan finishes."
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
		+ "combat.lunge_attack (the one primitive allowed to cross into light - it deals a real hit "
		+ "on contact), then movement.retreat_with_fallback to reliably get back into hiding. Or stay "
		+ "cautious and hold at close_quarters distance, retreating the moment they close past that "
		+ "instead of ever committing to a lunge. Pick whichever posture fits the moment - bold or "
		+ "cautious - there's no single right sequence. "
		+ "Each spot also lists a nearby torch count - light sources (torches, lanterns, etc.) found "
		+ "close to that specific spot, not just close to the player. A distant spot with its own "
		+ "nearby torch is still worth spawning at: combat.break_torch pathfinds to and destroys the "
		+ "nearest known light source live from wherever the wendigo currently is (no label needed, "
		+ "like combat.lunge_attack it's allowed to cross into light to reach its target), and will "
		+ "also take out up to a couple more torches immediately clustered around that one in the same "
		+ "motion - one break_torch against a lit passage or a ring of torches around a room can clear "
		+ "the whole cluster, not just the single one it pathfound to. E.g. spawn "
		+ "far away at a spot with a nearby torch, break_torch to snuff it (and its neighbors) out, "
		+ "then movement.retreat_with_fallback before despawning. Skip it entirely for a spot with zero "
		+ "nearby torches - it would have nothing to target. Treat a spot with a high torch count (3+) "
		+ "as a near-mandatory target once combat.break_torch is available (20%+ severity): being "
		+ "surrounded by that much light is exactly what it can't tolerate, so break torches there "
		+ "before doing anything else rather than treating it as a rare escalation move - err toward "
		+ "using it whenever a plausible target exists rather than saving it for a special moment. "
		+ "control.despawn ends the wave by vanishing right where the wendigo stands, instead of "
		+ "walking to despawn_at - the engine only allows this below 20% severity (or when nothing "
		+ "else is configured to fall back on) since vanishing suddenly reads as jarring once the "
		+ "wendigo is established enough to be more than a faint presence; a control.despawn attempted "
		+ "above that gets automatically redirected into a real flee instead, so don't rely on it for "
		+ "the ending of a higher-severity plan - use movement.retreat_with_fallback there directly. "
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
		+ "global_rules (a separate top-level field alongside spawn_at/plan/despawn_at) back hard "
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
		+ "sound.ambient_cue(ambience) can accompany the stalking for a low, unplaced presence cue. "
		+ "movement.retreat_with_fallback (a real, visible flight into darkness) is now available as an "
		+ "alternative ending to control.despawn (still allowed below 20%, but redirected to a flee "
		+ "above it) - withdrawing into the dark reads better than vanishing once it's been this bold. "
		+ "Example: spawn near a light source, combat.break_torch, movement.retreat_with_fallback "
		+ "before despawning.";

	private static final String STAGE_40_59 =
		"CURRENT STAGE: 40-59%, prey-driven and starting to plan. The wendigo has stopped just "
		+ "reacting to the player's presence and started treating them as something to be hunted "
		+ "deliberately - this is the first stage where it sets real traps instead of just observing. "
		+ "memory.store_dark_location is available for remembering a fallback retreat point before "
		+ "committing to something riskier. A good trap shape: approach one of a spot's dim spots, then "
		+ "control.while(predicate.player_distance farther_than lunge_distance) to bait them closer "
		+ "before deciding whether to press on or pull back - sound.ambient_cue(caught) fits the "
		+ "moment predicate.player_looking_at_self flips true at dead_stare mid-trap. combat.break_torch "
		+ "remains a strong opener wherever torch count is high. Example: spawn at a spot with dim "
		+ "spots, approach one, bait-and-decide via a lunge_distance while loop, retreat or press on "
		+ "based on how it resolves.";

	private static final String STAGE_60_79 =
		"CURRENT STAGE: 60-79%, openly aggressive. Subtlety is mostly gone - the wendigo commits now. "
		+ "combat.lunge_attack (real damage on contact) and sound.ambient_cue(jumpscare) are both "
		+ "available; pair a jumpscare cue with a lunge for the reveal rather than always retreating "
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
		+ "Example: stalk while undetected, combat.lunge_attack once close, movement.retreat_with_fallback "
		+ "afterward.";

	private static final String STAGE_80_PLUS =
		"CURRENT STAGE: 80% and up, restless. The wendigo is done pretending to be subtle - it wants "
		+ "direct contact and isn't holding back to get it. combat.chase unlocks here for the first time "
		+ "(it wasn't available at all below 80%) - a sustained pursuit, not a single commit-and-strike "
		+ "like lunge, that can strike repeatedly as it runs the player down, and always passively "
		+ "destroys any torch within 10 blocks as it goes - a chase at this stage leaves real wreckage "
		+ "behind it, every time, not just sometimes. Close the distance directly; combat.chase is the "
		+ "expected move once seen, not a rare escalation, and retreating only makes sense after landing "
		+ "something or being genuinely forced to. combat.chase should almost always be preceded by a "
		+ "posture.stare, whether in the plan body or via a global_rule - it's the payoff for being "
		+ "noticed while staring, not something to trigger with no reveal moment first. A reliable way to "
		+ "guarantee the transition happens the instant it's spotted, regardless of what step the plan was "
		+ "on: a global_rule with condition predicate.player_looking_at_self at whichever band fits and "
		+ "action combat.chase. Example: stalk and stare, a global_rule turns a spotted stare straight into "
		+ "combat.chase, no explicit ending needed after it - the chase resolves on its own once it can't "
		+ "reach the player anymore. When the player is in a tight, mineshaft-like "
		+ "space (see the caving-scenario note above), spawn_at gains a new option here: on_torch - "
		+ "spawns the wendigo directly on top of a torch near the player and destroys it immediately on "
		+ "arrival (no travel, no separate combat.break_torch needed), a sudden and aggressive reveal "
		+ "that fits a cramped corridor and won't be offered at all in a normal or massive cave. Pair "
		+ "it with entering combat.chase right away - "
		+ "either as the very next plan step, or via a global_rule so being noticed immediately turns "
		+ "into pursuit - this is meant to read as a hunt starting the instant the wendigo appears, not "
		+ "a stalk that escalates gradually. Example: spawn_at on_torch, then straight into combat.chase "
		+ "(or a global_rule keyed on being seen at all, since the reveal itself is the trigger).";

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

	// spawn_at's "on_torch" option - not an action_step, so not part of TierGates; matches
	// SchemaBuilder.SPAWN_ON_TORCH_MIN_PERCENT (kept in sync by hand, same tradeoff as everywhere
	// else two packages both need the same threshold).
	private static final String SPAWN_ON_TORCH = "on_torch";
	private static final int SPAWN_ON_TORCH_MIN_PERCENT = 80;
	private static final double SPAWN_ON_TORCH_SEARCH_RADIUS = 30.0;

	private void tickLevel(ServerLevel level, WaveState state) {
		int now = level.getServer().getTickCount();

		if (state.entity != null) {
			String forcedEndReason = checkForcedWaveEnd(state, now);
			if (!state.entity.isAlive() || state.entity.isWaveComplete() || forcedEndReason != null) {
				int elapsedTicks = now - state.waveStartTick;
				if (forcedEndReason != null) {
					WendigoDebug.say(level, "wave force-ended (" + forcedEndReason + ") after " + elapsedTicks
						+ " ticks at " + state.entity.blockPosition().toShortString()
						+ " - discarding wherever it ended up - outcome: " + state.entity.getOutcome());
				} else {
					WendigoDebug.say(level, "wave complete after " + elapsedTicks + " ticks at "
						+ state.entity.blockPosition().toShortString() + " - despawning - outcome: " + state.entity.getOutcome());
				}
				// Debug-forced waves (wave/wavetest) never update real encounter history - a showcase
				// or a debug-triggered test run shouldn't be told back to the model as if it were a
				// real thing that happened to this player.
				if (!state.debugForced && state.context != null) {
					this.encounterHistory.record(state.context.player(), state.entity.getOutcome(), now);
				}
				int severityPercent = state.context != null && state.context.severityCap() > 0
					? 100 * state.context.severity() / state.context.severityCap() : 0;
				if (state.entity.isAlive()) {
					state.entity.discard();
				}
				state.entity = null;
				state.context = null;
				// A debug-forced wave (wave/wavetest) shouldn't leave the automatic system primed to
				// fire a real LLM wave moments later just because the player is still under y=0 -
				// exactly the condition someone testing would be standing in. That reads as "the
				// despawned wendigo turned around and walked back", when it's really a second,
				// unrelated wendigo from a genuine severity-triggered wave.
				state.cooldownUntilTick = now + (state.debugForced ? this.config.debugCooldownTicks : this.config.dynamicCooldownTicks(severityPercent));
				state.debugForced = false;
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

		if (state.requestPending || now < state.cooldownUntilTick) {
			return;
		}

		ServerPlayer target = this.severityTracker.mostSevereEligiblePlayer(level);
		if (target != null) {
			beginWave(level, state, target);
		}
	}

	/** Bypasses cooldown/eligibility and calls the real LLM - used by the /wendigo wave debug command. */
	public void forceWave(ServerLevel level, ServerPlayer target) {
		WaveState state = this.waves.computeIfAbsent(level, l -> new WaveState());
		if (state.entity != null || state.requestPending) {
			return;
		}
		state.debugForced = true;
		beginWave(level, state, target);
	}

	/**
	 * Bypasses cooldown/eligibility AND the LLM call - runs a hand-authored plan (spawn_at/plan/
	 * despawn_at, same shape the model would return) through the real spawn/despawn lifecycle.
	 * Used by the /wendigo wavetest debug command to iterate on plans for free.
	 */
	public void forceWaveWithPlan(ServerLevel level, ServerPlayer target, JsonObject plan) {
		WaveState state = this.waves.computeIfAbsent(level, l -> new WaveState());
		if (state.entity != null || state.requestPending) {
			return;
		}
		state.debugForced = true;
		WaveContext context = buildContext(level, target);
		if (context != null) {
			// Hand-authored showcase/test plans shouldn't be second-guessed by tier gating meant to
			// keep an LLM honest - bypass it (severityPercent=100 unlocks everything).
			spawnWave(level, state, context, plan, true);
		}
	}

	private void beginWave(ServerLevel level, WaveState state, ServerPlayer target) {
		WaveContext context = buildContext(level, target);
		if (context == null) {
			return; // nowhere sensible to put it right now - try again next tick
		}
		int percent = context.severityCap() > 0 ? 100 * context.severity() / context.severityCap() : 0;

		state.requestPending = true;
		MinecraftServer server = level.getServer();
		WendigoMod.llmClient.requestPlan(buildSystemPrompt(percent), context.toPromptText(), SchemaBuilder.forSeverity(percent, context.caveScale()))
			.whenComplete((plan, error) -> server.execute(() -> {
				state.requestPending = false;
				if (error != null) {
					WendigoMod.LOGGER.error("Wendigo wave plan request failed", error);
					state.cooldownUntilTick = level.getServer().getTickCount() + this.config.dynamicCooldownTicks(percent);
					return;
				}
				spawnWave(level, state, context, plan, false);
			}));
	}

	private WaveContext buildContext(ServerLevel level, ServerPlayer target) {
		List<BlockPos> spots = DarkSpotScanner.findWaveSpots(level, target.blockPosition(), this.config.contextSpotCount);
		if (spots.isEmpty()) {
			return null;
		}
		BlockPos playerPos = target.blockPosition();
		List<List<BlockPos>> dimSpotsPerSpot = new ArrayList<>();
		List<List<BlockPos>> torchesPerSpot = new ArrayList<>();
		for (BlockPos spot : spots) {
			// Torches are derived by climbing from each dim spot to the light source causing it
			// (see DarkSpotScanner.findSpotDimSpots), not an independent scan - a dim spot is dim
			// precisely because some light source is nearby, so this reuses the one ring-sampling
			// pass instead of running a second scan. Omnidirectional and multi-radius around the dark
			// spot itself, not biased toward the player - a far spot (e.g. spot_d) gets credit for
			// its own nearby torches regardless of which side of it they're on.
			DarkSpotScanner.RelevantSpots relevant = DarkSpotScanner.findSpotDimSpots(level, spot);
			dimSpotsPerSpot.add(relevant.dimSpots());
			torchesPerSpot.add(relevant.lightSources());
		}
		List<List<Integer>> reachableSpotsPerSpot = SpotConnectivity.compute(level, spots);
		CaveScale caveScale = CaveScaleScanner.classify(level, playerPos);
		return new WaveContext(target, this.severityTracker.severityOf(target), this.config.severityCap, spots,
			dimSpotsPerSpot, torchesPerSpot, reachableSpotsPerSpot,
			this.encounterHistory.of(target), level.getServer().getTickCount(), caveScale);
	}

	private void spawnWave(ServerLevel level, WaveState state, WaveContext context, JsonObject plan, boolean bypassTierGating) {
		int percent = context.severityCap() > 0 ? 100 * context.severity() / context.severityCap() : 0;
		// Stage 1 always gets the safest possible first appearance, regardless of what spawn_at the
		// model actually picked - prose alone proved unreliable for this kind of hard requirement
		// (see TierGates), so it's forced here rather than hoped for. Not forced for bypassTierGating
		// (hand-authored /wendigo wavetest content) - a showcase's deliberately chosen spawn_at
		// shouldn't be second-guessed just because the tester's own severity happens to be low.
		// "on_torch" is only honored at the top tier in a tight/mineshaft-like cave (or for
		// bypassTierGating test content) - the schema itself already keeps a real LLM call from
		// offering it otherwise (see SchemaBuilder), this is just the same defense-in-depth
		// precedent as everywhere else that re-checks rather than trusting the schema filter alone.
		boolean spawnOnTorch = SPAWN_ON_TORCH.equals(plan.has("spawn_at") ? plan.get("spawn_at").getAsString() : null)
			&& (bypassTierGating || (percent >= SPAWN_ON_TORCH_MIN_PERCENT && context.caveScale() == CaveScale.TIGHT));
		BlockPos spawnPos = !bypassTierGating && percent < 20
			? resolveUnwatchedSpot(context)
			: spawnOnTorch ? resolveTorchSpot(context) : resolveSpawnSpot(context, plan, percent, bypassTierGating);
		if (spawnPos == null && spawnOnTorch) {
			spawnOnTorch = false; // no torch found nearby - fall back to a normal resolved spot instead
			spawnPos = resolveSpawnSpot(context, plan, percent, bypassTierGating);
		}
		BlockPos despawnPos = resolveDespawnSpot(context, plan, bypassTierGating);
		if (spawnPos == null || despawnPos == null) {
			WendigoMod.LOGGER.warn("Wendigo wave plan missing a resolvable spawn/despawn spot, skipping: {}", plan);
			state.cooldownUntilTick = level.getServer().getTickCount() + this.config.dynamicCooldownTicks(percent);
			return;
		}

		List<BlockPos> despawnCandidates = new ArrayList<>();
		despawnCandidates.add(despawnPos);
		for (BlockPos spot : context.spots()) {
			if (!spot.equals(despawnPos)) {
				despawnCandidates.add(spot);
			}
		}

		int gatingPercent = bypassTierGating ? 100 : percent;

		WendigoEntity wendigo = new WendigoEntity(ModEntities.WENDIGO, level);
		wendigo.snapTo(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5, 0f, 0f);
		level.addFreshEntity(wendigo);
		if (spawnOnTorch) {
			// No item drop (dropBlock=false) - same convention as combat.break_torch, still gets the
			// vanilla break particles/sound, just no free torch handed back.
			level.destroyBlock(spawnPos, false, wendigo, 512);
		}
		wendigo.startWave(plan, despawnCandidates, context.spots(), gatingPercent, bypassTierGating);

		state.entity = wendigo;
		state.context = context;
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
		BlockPos resolved;
		if ("no_players_looking".equals(label)) {
			resolved = resolveUnwatchedSpot(context);
		} else if (label != null && (bypassTierGating || SchemaBuilder.isSpawnSpotAllowed(label, percent, context.caveScale()))) {
			resolved = context.resolve(label);
		} else {
			resolved = null;
		}
		if (resolved == null) {
			resolved = DarkSpotScanner.findDarkest(context.player().level(), context.player().blockPosition(), 16);
		}
		return resolved;
	}

	/** Resolves despawn_at to a position - same fallback philosophy as resolveSpawnSpot, re-checking
	 * SchemaBuilder.isDespawnSpotAllowed as defense-in-depth. */
	private BlockPos resolveDespawnSpot(WaveContext context, JsonObject plan, boolean bypassTierGating) {
		String label = plan.has("despawn_at") ? plan.get("despawn_at").getAsString() : null;
		BlockPos resolved = label != null && (bypassTierGating || SchemaBuilder.isDespawnSpotAllowed(label, context.caveScale()))
			? context.resolve(label) : null;
		if (resolved == null) {
			resolved = DarkSpotScanner.findDarkest(context.player().level(), context.player().blockPosition(), 16);
		}
		return resolved;
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

	/** Nearest known light source to the player, or null if none is nearby - backs spawn_at's
	 * "on_torch" option. Reuses the same scanner combat.break_torch's live resolution does
	 * (LightSourceScanner), just centered on the player instead of the wendigo's own position since
	 * the wendigo doesn't exist yet at spawn-resolution time. */
	private BlockPos resolveTorchSpot(WaveContext context) {
		var torches = LightSourceScanner.findLightSources(context.player().level(), context.player().blockPosition(), SPAWN_ON_TORCH_SEARCH_RADIUS, 1);
		return torches.isEmpty() ? null : torches.get(0);
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
		// Retained for the wave's duration (cleared alongside entity) purely so /wendigo debug can
		// keep re-drawing its spot/dim-spot particles - not read for any gameplay logic.
		WaveContext context;
		boolean requestPending;
		int waveStartTick;
		int cooldownUntilTick;
		// Set by forceWave/forceWaveWithPlan; makes the completion handler apply
		// config.debugCooldownTicks instead of the normal cooldown, so a debug/test wave doesn't
		// leave the automatic severity-triggered spawner armed to fire moments later.
		boolean debugForced;
		// Consecutive ticks the wendigo has been at extreme close range with the player - see
		// checkForcedWaveEnd. Reset at the start of each wave and whenever the condition lapses.
		int extremeProximityTicks;
	}
}
