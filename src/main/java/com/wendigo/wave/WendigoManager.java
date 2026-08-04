package com.wendigo.wave;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import com.wendigo.WendigoMod;
import com.wendigo.debug.WendigoDebug;
import com.wendigo.entity.ModEntities;
import com.wendigo.entity.WendigoEntity;
import com.wendigo.plan.PositionBands;
import com.wendigo.plan.ProximityBands;
import com.wendigo.plan.SchemaBuilder;
import com.wendigo.sound.WendigoSounds;
import com.wendigo.spatial.CaveScaleScanner;
import com.wendigo.spatial.CaveScaleScanner.CaveScale;
import com.wendigo.spatial.DarkSpotScanner;

/**
 * Owns the wendigo's spawn/despawn lifecycle: at most one per level, spawned onto whichever
 * eligible player/group is due for a fresh run (or has an unfinished one to resume - see
 * WendigoProgressionTracker.selectTarget), running a single LLM-authored plan from spawn to
 * despawn with no mid-wave re-planning. See PlanRunner for how the plan body itself executes once
 * the entity exists. Every position a plan resolves against (spawn_at, movement.approach_band,
 * despawn/retreat) is resolved LIVE at the moment it's actually needed (see
 * DarkSpotScanner.findLiveBandPosition) - this class never pre-scans and hands off a set of
 * frozen positions the way it once did.
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
		+ "target player, their dweller severity, and a live distance-band ladder describing how "
		+ "far things currently are from them. Positioning works entirely through that ladder, not "
		+ "fixed coordinates: spawn_at picks a live band to position at before 'plan' starts "
		+ "(resolved fresh at that exact moment - walked there if not already close, or not moved "
		+ "at all if the wendigo is already at a good distance for what comes next, appearing fresh "
		+ "only for a genuine first encounter). Where it withdraws to afterward IS your choice - "
		+ "despawn_band picks a live band the engine resolves a position in once it's actually time "
		+ "to flee (whenever 'plan' finishes, or whenever a movement.retreat_with_fallback step "
		+ "runs), re-resolving fresh if the first attempt fails. Choose it deliberately, not always "
		+ "the maximum: a brief, subtle beat might only warrant backing off to medium before "
		+ "resuming a quiet watch, while committing to a chase or a grab plausibly earns real "
		+ "distance (farther/farthest) before showing itself again. medium/far/farther/farthest are "
		+ "all always available for this regardless of severity - retreating modestly is never the "
		+ "bold half of a decision - but medium is as close as it goes; a real withdrawal never lands "
		+ "any nearer than that. "
		+ "The 6 positioning bands, nearest to furthest: close_as_possible, close, medium, far, "
		+ "farther, farthest - see the prompt's own numbers for exact block ranges, and don't "
		+ "confuse these with predicate.player_distance's own, differently-scaled ladder "
		+ "(grab_distance/lunge_distance/close_quarters/medium/far, used only for combat/predicate "
		+ "checks, never for positioning) - same-sounding names like 'medium'/'far' mean different "
		+ "ranges in each ladder. close_as_possible only exists in a tight, mineshaft-like space "
		+ "(see the prompt's caving-scenario note) - there's nowhere far to go anyway in a cramped "
		+ "space; close/medium/far/farther otherwise unlock progressively closer to the player as "
		+ "severity climbs - which bands are even offered already reflects how bold the wendigo is "
		+ "allowed to be right now. farthest is always available. "
		+ "Everywhere a band is used - spawn_at, movement.approach_band - it's resolved LIVE, "
		+ "against wherever the player actually is at that exact moment, never a position frozen "
		+ "from earlier in the request: there's no mid-engagement re-planning, so the whole plan is "
		+ "decided once, up front, but each step that moves somewhere waits until it actually runs "
		+ "before deciding exactly where that is. "
		+ "Use movement.approach_band to explicitly reposition mid-plan (e.g. creep from far to "
		+ "close once undetected, once that's unlocked); omit any movement step entirely if the "
		+ "wendigo's current live distance already fits what comes next - there is no obligation to "
		+ "always move first. "
		+ "combat.break_torch destroys a torch: always the single nearest known light source from "
		+ "wherever the wendigo ITSELF currently is, no band or player-relative choice - the prompt's "
		+ "own live torches_at_<band>_distance counts are informational context (is there likely "
		+ "anything worth this at all), not something break_torch lets you target by. If nothing is "
		+ "nearby this is a no-op and the plan continues, never a hard failure. "
		+ "Not every action needs to avoid light - decide per action whether darkness matters for "
		+ "what it's doing, and wrap movement in control.while checking predicate.self_in_darkness "
		+ "when you want it to stop rather than commit further once it's no longer hidden. "
		+ "Orchestrate a hunt out of these pieces yourself: approach a band and stare, wait "
		+ "(control.while farther_than lunge_distance) until the player closes in, then commit with "
		+ "combat.lunge_attack (the one primitive allowed to cross into light - catching the player "
		+ "grabs them, forcing them to ride along until they struggle free or the wendigo reaches "
		+ "wherever it's headed next), then movement.retreat_with_fallback to reliably get back into "
		+ "hiding, carrying a still-grabbed player along with it. Or stay cautious and hold at "
		+ "close_quarters distance, retreating the moment they close past that instead of ever "
		+ "committing to a lunge. Pick whichever posture fits the moment - bold or cautious - "
		+ "there's no single right sequence. "
		+ "spawn_at also sometimes offers spawn_on_torch - only when there's at least one live "
		+ "torch far enough from the player for the current severity to allow (the same "
		+ "farther/far/medium/close progression, applied to torches instead of empty ground) - "
		+ "spawns already exposed at that torch's position, destroying it the instant it appears, "
		+ "no hiding or staring involved. Below 80% severity treat it as an ordinary spawn location "
		+ "choice like any other - no obligation to immediately chase, plan it however the moment "
		+ "calls for. At 80%+ it means something different: choosing it there is a commitment to a "
		+ "direct hunt, and the plan needs to follow through on that (sound.ambient_cue(chase), "
		+ "straight into combat.chase) since there's nothing subtle left to do once spawning fully "
		+ "exposed at that range. Never pick it as a substitute for a genuine dark position when "
		+ "one's actually available and would fit the moment better. "
		+ "control.despawn ends this engagement by vanishing right where the wendigo stands, instead "
		+ "of walking to a hiding spot first - it still retreats into darkness afterward and keeps "
		+ "watching from a distance either way, this only changes whether that withdrawal is instant "
		+ "or a visible walk. The engine only allows the instant version below 20% severity (or when "
		+ "nothing else is configured to fall back on) since vanishing suddenly reads as jarring once "
		+ "the wendigo is established enough to be more than a faint presence; a control.despawn "
		+ "attempted above that gets automatically redirected into a real flee instead, so don't rely "
		+ "on it for the ending of a higher-severity plan - use movement.retreat_with_fallback there "
		+ "directly. "
		+ "IMPORTANT - steps do not wait for anything on their own: only control.while (and "
		+ "timing.wait/movement while it's still resolving) actually consumes time. Every other kind "
		+ "of step, including posture.stare, runs and then immediately falls through to whatever comes "
		+ "next in the SAME tick. A plan that does posture.stare(enabled=true) followed directly by "
		+ "movement.retreat_with_fallback flees the instant the wendigo appears, whether or not the "
		+ "player ever looked at it - that is almost never what you want. There are two distinct kinds "
		+ "of wait, and they don't mix: "
		+ "STARE-HOLD - posture.stare(enabled=true) followed by a control.while gated purely on "
		+ "predicate.player_looking_at_self (whichever band fits) or predicate.player_undetected - the "
		+ "hold ends only once the player actually notices (or stops being undetected), never on "
		+ "distance. predicate.player_distance is NOT valid here at all, any band - staring is the eyes-"
		+ "locked reveal moment; gating it on distance means the player can just never approach and "
		+ "stall the hold forever, and it also stares the whole time regardless of whether they're even "
		+ "close, which reads wrong. The engine rejects it and substitutes a look-based hold "
		+ "automatically if it slips in anyway. A held stare doesn't need an exact dead-on look to end, "
		+ "either: the engine also treats a sustained near-miss (in_view held for a few seconds when "
		+ "dead_stare was asked for, or corner_of_eye held for in_view) as good enough, so a stare-hold "
		+ "reliably resolves either way without needing the player to look exactly at it. "
		+ "AMBUSH WAIT - no posture.stare at all: hold quietly (face not lit up, nothing revealed) near "
		+ "a lit spot until the player comes into range, via control.while(predicate.player_distance) "
		+ "using grab_distance/lunge_distance/close_quarters only (medium/far get narrowed to "
		+ "lunge_distance automatically - too wide to mean 'close enough to act', and the player could "
		+ "just never close that much ground), THEN commit - posture.stare immediately followed by "
		+ "combat.lunge_attack/combat.chase once the wait ends. This is the actual trap: staying dark "
		+ "and unnoticed right up until the reveal, instead of giving the face away the whole time it's "
		+ "waiting for the player to even get close. Prefer this pattern whenever the point is closing "
		+ "distance for a combat commit; save the stare-hold pattern for when the point is specifically "
		+ "waiting to be noticed. "
		+ "sound.ambient_cue(stare) is heavily encouraged right when a held stare begins. "
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
		+ "loop started - it only measures change from the moment the loop itself began. "
		+ "predicate.target_moving/predicate.target_is_stopped read the player's own current real "
		+ "movement, independent of distance or being noticed - not logical negations of each other, "
		+ "both simply read false with no player to check at all. Use them both as a control.while "
		+ "gate (e.g. control.while(target_moving) { movement.approach... } to keep tailing only while "
		+ "they're actually walking) and as an ordinary control.if/control.while branch condition (e.g. "
		+ "control.if(target_moving) { movement.approach... } control.if(target_is_stopped) { "
		+ "posture.stare... }) to react differently depending on whether they're currently on the move "
		+ "or holding still. "
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
		+ "many separate encounters with this player over time, so one wave is one beat in a much "
		+ "longer story. You'll be told which stage that percentage falls "
		+ "into below, along with what's unlocked and what the moment should feel like - the engine "
		+ "also enforces the unlocks as a hard limit (anything not yet earned isn't even offered in this "
		+ "request's schema), so treat the stage text as atmosphere/intent to hit, not a checklist of "
		+ "restrictions to police yourself. If the wendigo is already active right now rather than a "
		+ "fresh spawn, the prompt reports its own current live distance and whether it's already "
		+ "perched above the player - decide for yourself whether that position already works for "
		+ "what this plan is about to do. The prompt also reports what happened during the wendigo's "
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
		+ "regardless of what you pick, so choose it deliberately rather than a specific band). Once "
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
		+ "appearing and vanishing, though only the farther/farthest bands are unlocked yet - genuine "
		+ "closeness isn't earned until later stages, so this is about presence, not confrontation. "
		+ "combat.break_torch just unlocked this stage - no further threshold above this one, so use "
		+ "it whenever the prompt's torch counts show something worth targeting, every wave that "
		+ "offers the chance, not as an occasional flourish. sound.ambient_cue(ambient) can accompany the "
		+ "stalking for a low, unplaced presence cue. movement.retreat_with_fallback (a real, visible "
		+ "flight into darkness) is now available as an alternative ending to control.despawn (still "
		+ "allowed below 20%, but redirected to a flee above it) - withdrawing into the dark reads "
		+ "better than vanishing once it's been this bold, and sound.ambient_cue(flee) right "
		+ "beforehand is heavily encouraged to announce the withdrawal. Example: spawn at farther, "
		+ "combat.break_torch, sound.ambient_cue(flee), movement.retreat_with_fallback before despawning.";

	private static final String STAGE_40_59 =
		"CURRENT STAGE: 40-59%, prey-driven and starting to plan. The wendigo has stopped just "
		+ "reacting to the player's presence and started treating them as something to be hunted "
		+ "deliberately - this is the first stage where it sets real traps instead of just observing, "
		+ "and where the far band unlocks (in addition to farther/farthest). "
		+ "memory.store_dark_location is available for remembering a fallback retreat point before "
		+ "committing to something riskier. A good trap shape (the AMBUSH WAIT pattern - see above): "
		+ "movement.approach_band(far) to close in a bit, then control.while(predicate.player_distance "
		+ "farther_than lunge_distance) - no posture.stare yet, stay dark and unnoticed - to wait for "
		+ "them to close the rest of the gap on their own, THEN posture.stare(enabled=true) "
		+ "(sound.ambient_cue(stare) heavily encouraged right here) immediately followed by "
		+ "combat.lunge_attack once the wait ends, so the reveal and the strike land together instead "
		+ "of staring the whole time it's still waiting. combat.break_torch remains a strong opener "
		+ "wherever the prompt's torch counts are high. Example: spawn at farther, approach_band(far), "
		+ "wait quietly via a lunge_distance while loop, then stare and lunge once they're close.";

	private static final String STAGE_60_79 =
		"CURRENT STAGE: 60-79%, openly aggressive. Subtlety is mostly gone - the wendigo commits now, "
		+ "and the medium band unlocks this stage (in addition to far/farther/farthest) for closing "
		+ "distance before committing. combat.lunge_attack (catching the player grabs them - see its "
		+ "own description) is available, and sound.ambient_cue(chase) unlocks alongside it - pair the "
		+ "two for the reveal (heavily encouraged right before the lunge) rather than always retreating "
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
		+ "Example: spawn at medium, stalk while undetected, sound.ambient_cue(chase), combat.lunge_attack "
		+ "once close, sound.ambient_cue(flee), movement.retreat_with_fallback afterward. "
		+ "spawn_at/movement.approach_band's 'spot_above' band and movement.drop also both unlock here - "
		+ "three independent primitives, not one combined move, so they mix into whatever plan actually "
		+ "fits: position at spot_above (directly on the ceiling above the player), movement.drop to let "
		+ "go and fall, then combat.lunge_attack once close enough to finish the catch - an overhead "
		+ "ambush the player won't see coming from a ground-level glance, worth reaching for specifically "
		+ "when they haven't noticed a ceiling presence at all.";

	private static final String STAGE_80_PLUS =
		"CURRENT STAGE: 80% and up, restless. The wendigo is done pretending to be subtle - it wants "
		+ "direct contact and isn't holding back to get it, and every positioning band is unlocked now, "
		+ "including close (and close_as_possible in a tight space). combat.chase unlocks here for the "
		+ "first time (it wasn't available at all below 80%) - a sustained pursuit rather than lunge's "
		+ "single commit, chasing the player down until it catches them (grabbing them, same as a lunge "
		+ "does - see combat.lunge_attack) or genuinely can't reach them anymore, and always passively "
		+ "destroys any torch within 10 blocks as it goes - a chase at this stage leaves real wreckage "
		+ "behind it, every time, not just sometimes. Close the distance directly; combat.chase is the "
		+ "expected move once seen, not a rare escalation, and retreating only makes sense after "
		+ "catching them or being genuinely forced to. combat.chase should almost always be preceded by a "
		+ "posture.stare, whether in the plan body or via a global_rule - it's the payoff for being "
		+ "noticed while staring, not something to trigger with no reveal moment first. A reliable way to "
		+ "guarantee the transition happens the instant it's spotted, regardless of what step the plan was "
		+ "on: a global_rule with condition predicate.player_looking_at_self at whichever band fits and "
		+ "action combat.chase. Example: spawn at close, stalk and stare, a global_rule turns a spotted "
		+ "stare straight into combat.chase, no explicit ending needed after it - the chase resolves on "
		+ "its own once it can't reach the player anymore. combat.teleport_behind also unlocks here for "
		+ "the first time (below 90% it doesn't exist at all) - an instant relocation into the player's "
		+ "own blind spot, no travel time, no warning, nothing to path around. Use it when they've broken "
		+ "line of sight or are about to, right before a posture.stare or combat.lunge_attack - the one "
		+ "tool in this creature's kit that closes distance without ever being seen doing it. spot_above + "
		+ "movement.drop (both unlocked since 60%, see that stage's own note) pair naturally with "
		+ "combat.chase at this stage too, not just combat.lunge_attack - drop from directly overhead into "
		+ "a sustained chase instead of a single lunge.";

	private static String buildSystemPrompt(int severityPercent) {
		String stage = severityPercent < 20 ? STAGE_UNDER_20
			: severityPercent < 40 ? STAGE_20_39
			: severityPercent < 60 ? STAGE_40_59
			: severityPercent < 80 ? STAGE_60_79
			: STAGE_80_PLUS;
		return SYSTEM_PROMPT_GENERIC + stage;
	}

	private final WendigoWaveConfig config;
	private final WendigoProgressionTracker progressionTracker;
	private final EncounterHistory encounterHistory;
	private final Map<ServerLevel, WaveState> waves = new java.util.HashMap<>();

	public WendigoManager(WendigoWaveConfig config, WendigoProgressionTracker progressionTracker, EncounterHistory encounterHistory) {
		this.config = config;
		this.progressionTracker = progressionTracker;
		this.encounterHistory = encounterHistory;
	}

	public void register() {
		ServerTickEvents.END_SERVER_TICK.register(this::onEndServerTick);
		// A player who logs out (or the server shuts down) while still a forced rider (see
		// PlanRunner.beginForcedRide) would otherwise get reconnected to it automatically on their
		// next login - vanilla's own "quit while riding a vehicle" handling saves that vehicle's full
		// NBT directly into the PLAYER's own save data (a "RootVehicle" tag, separate from ordinary
		// chunk-based entity persistence) specifically so quitting mid-boat-ride doesn't strand the
		// player, and reconnects it on rejoin - a mechanic WendigoEntity.shouldBeSaved()=false doesn't
		// touch at all, since it's a different serialization path entirely. Real playtesting found
		// exactly this: an old wendigo still forcing a ride reappearing after a server restart.
		// Resolving the ride here (same fair-chance/darkness damage gating and actual drop as any
		// other wave-ending path - see PlanRunner.resolveRiderOnEnd) before the disconnect's own save
		// happens means there's no forced-ride state left to serialize in the first place. Deliberately
		// still deals the despawn damage if darkness/fair-chance both hold - quitting shouldn't be a
		// free way to cheese out of a grab's consequence.
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			if (handler.player.getVehicle() instanceof WendigoEntity wendigo) {
				wendigo.resolveRiderOnEnd();
			}
		});
		// Defensive backstop for the same scenario, in case some disconnect path skips the DISCONNECT
		// event above (a hard crash rather than a clean quit) - a stray reconnected-on-rejoin wendigo
		// is untracked by this manager's own in-memory WaveState (reset every restart) regardless, so
		// just discard it outright rather than trying to fold it back into a real wave.
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (handler.player.getVehicle() instanceof WendigoEntity wendigo) {
				wendigo.resolveRiderOnEnd();
				wendigo.discard();
			}
		});
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
			case MASSIVE -> 24.0;
			default -> 16.0; // NORMAL
		};
	}

	private static double orbitMaxDistance(CaveScale caveScale) {
		return switch (caveScale) {
			case TIGHT -> 18.0;
			case MASSIVE -> 34.0;
			default -> 24.0; // NORMAL
		};
	}

	// Flat performance cap on how far an orbiting wendigo is allowed to be from its target before
	// relocating straight back inside the orbit band instead of continuing to tick/pathfind toward it
	// from very far away - see tickOrbitingEntity. Comfortably beyond MASSIVE's own 34-block max orbit
	// distance so it never fights ordinary orbiting, well inside NEAREST_PLAYER_RADIUS (64, SemanticBands).
	private static final double ORBIT_DESPAWN_DISTANCE = 40.0;
	// The user's own explicit invariant: every cave-scale's own max orbit distance must stay strictly
	// below ORBIT_DESPAWN_DISTANCE, or a wendigo legitimately orbiting at the outer edge of its own
	// band could trip the "too far, relocate" check above while doing nothing wrong. A plain comment
	// already asserted this by hand (see ORBIT_DESPAWN_DISTANCE's own comment above); this backs that
	// claim with a real check that fails loudly at class-load time instead of silently drifting true
	// the next time either side's numbers get tuned. Runs regardless of assertions being enabled
	// (unlike a bare `assert`) since this is a real invariant, not just a debug-build sanity check.
	static {
		for (CaveScale caveScale : CaveScale.values()) {
			double maxOrbitDistance = orbitMaxDistance(caveScale);
			if (maxOrbitDistance >= ORBIT_DESPAWN_DISTANCE) {
				throw new IllegalStateException("orbitMaxDistance(" + caveScale + ") = " + maxOrbitDistance
					+ " must stay below ORBIT_DESPAWN_DISTANCE (" + ORBIT_DESPAWN_DISTANCE
					+ ") or a legitimately in-band orbiting wendigo could trip the too-far relocate check");
			}
		}
	}
	// Throttles tryEnterOrbit's own dark-spot search while entity == null - see WaveState.nextRespawnSearchTick.
	private static final int ORBIT_SPAWN_SEARCH_INTERVAL_TICKS = 20; // ~1s

	private void tickLevel(ServerLevel level, WaveState state) {
		int now = level.getServer().getTickCount();
		updateStage5BossBar(state);

		// Checked before absolutely everything else, including the drowning check right below - a
		// live-reported bug: a stage-5 wendigo that took a killing blow but hadn't finished dying yet
		// respawned with FULL health on its next spawn instead of the sliver it actually had left.
		// Root cause: Entity.isAlive() only checks !isRemoved() (confirmed via decompile) - it says
		// nothing about health at all. A fatal hit can leave getHealth() at/below 0 for several ticks
		// (vanilla's own death animation window) before the entity is actually removed, and every one
		// of this class's own cosmetic discard/relocate triggers below only guards on isAlive(), which
		// still reads true that whole time - so one of them (too far, too close, out of air, this
		// same tick's own new fire-damage check, whatever fires first) could win the race and
		// discard() the entity itself, pre-empting vanilla's own natural KILLED removal with a
		// DISCARDED one instead. Two knock-on problems from that: stage 5's own endStage5Hunt (which
		// only fires by detecting a genuine !isAlive() transition) never sees it as the kill it really
		// was, and saveStage5HealthIfApplicable's own save - though it DOES correctly capture whatever
		// near-zero health was left - gets treated as an ordinary mid-hunt relocate checkpoint rather
		// than the run actually ending, which isn't what should have happened to a mortally wounded
		// entity in the first place. Simplest fix: just don't touch it while it's already dying -
		// every trigger below can wait one tick for vanilla's own removal to land on its own, and
		// respawns fresh next hunt with health null/gone dark cleanly from tryEnterOrbit either way.
		if (state.entity != null && state.entity.isAlive() && state.entity.getHealth() <= 0.0F) {
			return;
		}

		// Checked before either dispatch branch below (orbiting OR mid-plan alike) and before the
		// grab override, same priority as every other "this physical state is wrong, bail out" check
		// in this method - a wendigo that's pathed/fallen into water deep enough to fully drain its
		// air supply is about to start taking real drowning damage every tick, which nothing in this
		// mod's plan system is meant to survive or react to. Discards outright rather than trying to
		// path it back out (the same "genuinely wrong place, just leave" reasoning relocateOrDiscard's
		// own last-resort branch already uses) - lockedTarget/stage deliberately left intact so the
		// run resumes on the same player once a real, dry spot is found, same as the too-far/too-close
		// discards below.
		if (state.entity != null && state.entity.isAlive() && state.entity.getAirSupply() <= 0) {
			WendigoDebug.say(level, "wendigo out of air (about to take drowning damage) - discarding, will search for a new valid spawn area");
			saveStage5HealthIfApplicable(state);
			state.entity.discard();
			state.entity = null;
			state.context = null;
			return;
		}

		// Same "this physical state is wrong, bail out" priority as the drowning check above - the
		// user's own explicit request: a fire-type hit landing (see WendigoEntity.hurtServer/
		// consumeTookFireDamage) despawns outright and delegates to the ordinary respawn search,
		// rather than letting the plan/orbit system react to it (or the entity just standing there
		// burning). lockedTarget/stage left intact, same as every other cosmetic discard here.
		if (state.entity != null && state.entity.isAlive() && state.entity.consumeTookFireDamage()) {
			WendigoDebug.say(level, "wendigo took fire damage - discarding, will search for a new valid spawn area");
			saveStage5HealthIfApplicable(state);
			state.entity.discard();
			state.entity = null;
			state.context = null;
			return;
		}

		// Unconditional grab_distance override - checked before either dispatch branch below, since
		// it needs to interrupt orbiting OR mid-plan alike. If it fires, state.entity's own orbiting/
		// mid-plan status has already changed by the time the checks below run, so they naturally
		// pick up the right branch for whatever just started.
		if (state.entity != null && state.entity.isAlive()) {
			checkUnconditionalGrab(level, state, now);
		}

		if (state.entity != null && state.entity.isAlive()
				&& (state.entity.isOrbiting() || state.entity.isReturningToOrbit())) {
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
				// Set only when a run genuinely, permanently ends this tick (completeRun/endStage5Hunt
				// below) - the SPAWN cue's own despawn-side bookend (see the user's own explicit "the
				// spawn sound should only happen... when he despawns for the last time of a run") and
				// the trigger for force-discarding a still-alive entity right below, rather than letting
				// it keep orbiting under a now-stale state.stage (only completeRun/endStage5Hunt ever
				// advance/reset that).
				boolean runJustEnded = false;
				ServerPlayer finishedRunPlayer = null;
				if (!state.debugForced && state.context != null) {
					var outcome = state.entity.getOutcome();
					this.encounterHistory.record(state.context.player(), outcome, now);
					// Hidden stage-goal progress (see PlanRunner's own goal-progress fields) - never a
					// debug-forced wave, same guard as encounterHistory above, since a showcase/test run
					// shouldn't silently advance a real player's progression. Stage is state.stage, fixed
					// at whenever this run actually started (tryEnterOrbit/spawnWave), not re-derived -
					// it can't have changed mid-run since only completing the goal ever advances it.
					ServerPlayer progressPlayer = state.context.player();
					if (state.stage == 5) {
						// Stage 5's own stop condition (see WendigoProgressionTracker.isGoalMet's own
						// comment) - genuinely dying here (not merely outlasting/escaping) is the only
						// thing that ends this hunt. wasAlive is read from the SAME isAlive() this whole
						// block's own entry condition already checked - a true kill, not some other
						// discard path (those never reach this branch of tickLevel at all - see
						// tickOrbitingEntity's own discards, none of which fall through to here).
						if (!state.entity.isAlive()) {
							this.progressionTracker.endStage5Hunt(progressPlayer);
							runJustEnded = true;
							finishedRunPlayer = progressPlayer;
							WendigoDebug.say(level, progressPlayer.getGameProfile().name()
								+ " killed the stage-5 wendigo - hunt over, eligibility timer restarting");
						}
					} else {
						int progressAmount = switch (state.stage) {
							case 1 -> outcome.successfulStareCount();
							case 2 -> outcome.successfulStareCount() + outcome.torchBreakCount();
							case 3 -> outcome.lungeAttemptCount();
							case 4 -> outcome.hitLanded() ? 1 : 0;
							default -> 0;
						};
						if (progressAmount > 0) {
							this.progressionTracker.addProgress(progressPlayer, progressAmount);
						}
						if (this.progressionTracker.isGoalMet(progressPlayer)) {
							this.progressionTracker.completeRun(progressPlayer);
							runJustEnded = true;
							finishedRunPlayer = progressPlayer;
							WendigoDebug.say(level, progressPlayer.getGameProfile().name() + " completed stage "
								+ state.stage + "'s goal - run over, eligibility timer restarting");
						}
					}
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
				if (runJustEnded) {
					// The run is genuinely, permanently over (completeRun/endStage5Hunt just fired) -
					// the SPAWN cue's own despawn-side bookend, then a clean break: force-discard a
					// still-alive entity right here rather than letting it keep orbiting under a
					// now-stale state.stage, and clear lockedTarget/stage so the NEXT tryEnterOrbit call
					// goes through selectTarget's own fresh-eligibility check instead of treating this
					// same, no-longer-active player as still locked in (which would bypass the 2000-tick
					// timer completeRun/endStage5Hunt just restarted).
					WendigoSounds.play(level, finishedRunPlayer, WendigoSounds.Type.SPAWN);
					if (state.entity.isAlive()) {
						saveStage5HealthIfApplicable(state);
						state.entity.discard();
					}
					state.entity = null;
					state.context = null;
					state.lockedTarget = null;
					state.stage = 0;
				} else if (!state.entity.isAlive()) {
					state.entity = null;
					state.context = null;
				} else if (forcedEndReason != null) {
					// "Despawn when trapped/can't move" - an explicit teleport-relocation, not the
					// ordinary walked retreat a clean plan completion already resolves through below.
					relocateOrDiscard(level, state, now);
				} else if (state.entity.getOutcome().hitLanded()) {
					// A grab landed this encounter - the drop already happened wherever PlanRunner's own
					// carry-flee sequence ended up (see PlanRunner.startCarryFlee/resolveRiderOnEnd), but
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
				if (now % this.config.debugContextIntervalTicks == 0) {
					logContextSnapshot(level, state, now);
				}
			}
			return;
		}

		tryEnterOrbit(level, state, now);
	}

	/** Per-tick supervision while state.entity is alive and orbiting (no active plan) - see
	 * PlanRunner.tickOrbit for the actual movement logic this just watches over. Handles conditions
	 * PlanRunner can detect but can't resolve on its own (needs WendigoManager's own dark-spot
	 * search/discard authority): a fully lost target (see clarification: treated the same as
	 * nowhere-dark-to-go, not held in place), and being genuinely stuck trying to reach a waypoint.
	 * Deliberately does NOT force a discard just because the player spots/stares at an idly-orbiting
	 * wendigo anymore (an earlier version of this method did, modeled as a hardcoded "spooked, flee
	 * and respawn elsewhere" reaction) - now that a cosmetic despawn/relocate no longer ends the
	 * player's run (see WendigoProgressionTracker), there's no engine-side need to yank the entity
	 * away the instant it's seen; whether/how to react to being watched is left entirely to the plan
	 * the model produces once a real engagement actually triggers (predicate.player_looking_at_self,
	 * global_rules, etc.) rather than forced ahead of that. Once orbit itself is confirmed healthy,
	 * checks whether it's time to start a new plan - the exact same cooldown that used to be the only
	 * way a wendigo ever spawned at all now gates starting a plan on the entity that's already here
	 * instead. */
	private void tickOrbitingEntity(ServerLevel level, WaveState state, int now) {
		WendigoEntity entity = state.entity;
		if (entity.isReturningToOrbit()) {
			return; // mid-transit toward a return point - PlanRunner resolves this on its own
		}
		if (entity.isOrbitTargetLost()) {
			WendigoDebug.say(level, "orbiting wendigo lost its target entirely - discarding, will search for a new one");
			saveStage5HealthIfApplicable(state);
			entity.discard();
			state.entity = null;
			state.context = null;
			state.lockedTarget = null;
			state.stage = 0;
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
			// own max orbit distance (see the static invariant check below ORBIT_DESPAWN_DISTANCE's
			// own declaration), so this never fights ordinary in-band/reposition orbiting; it only
			// fires when something (a long chase, a relocate, the player teleporting/fast-traveling)
			// has left the wendigo tracking a target from genuinely far away. Relocates straight back
			// inside the normal orbit band instead of a full discard-and-research cycle - the user's
			// own explicit request, same TELEPORT-not-walk reasoning relocateOrDiscard already uses
			// for the trapped case (see its own doc comment for why it seeds the search from the
			// target's position rather than the entity's own all-the-way-out-here one).
			WendigoDebug.say(level, "orbiting wendigo too far from its target (" + ORBIT_DESPAWN_DISTANCE
				+ "+ blocks, performance cap) - relocating back inside orbit range");
			relocateOrDiscard(level, state, now);
			return;
		}
		if (checkOrbitTooClose(level, state, now)) {
			return;
		}
		if (checkOrbitLightExposure(level, state, now)) {
			return;
		}
		if (state.requestPending || now < state.cooldownUntilTick) {
			return;
		}
		ServerPlayer target = state.lockedTarget;
		if (target == null || !target.isAlive()) {
			return; // shouldn't happen (isOrbitTargetLost would have already caught it) - defensive only
		}
		if (isNearSoulLight(level, target.blockPosition())) {
			return; // user's own explicit rule - still spawns/orbits near soul light, just can't engage
		}
		beginEngagement(level, state, target, this.progressionTracker.representativePercent(state.stage));
	}

	// Below this percent, a player getting too close while orbiting just spooks the wendigo off
	// (real discard + re-search) rather than provoking a lunge - matches DarknessOverstayTracker's
	// own AMBUSH_MIN_PERCENT-style tiering philosophy, this specific number given directly by the user.
	private static final int ORBIT_TOO_CLOSE_LUNGE_MIN_PERCENT = 40;
	// Flat, not cave-scaled or fraction-of-band - the user's own explicit call after the original
	// "lower quarter of the current band" version was landing too close to (or even inside) typical
	// spawn distance and reacting the instant a fresh orbit spawn's first tick ran. Must stay strictly
	// below SemanticBands.orbitMinDistance's own smallest value (TIGHT, currently 12.0) so the wendigo
	// can act directly from orbit without walking anywhere first - real playtesting found a TIGHT-cave
	// ceiling vantage point that legitimately cleared the (then-8-block) orbit-hold floor (tickOrbit
	// accepts it, settles there) could still sit under an earlier flat 10 here, discarding and
	// respawning the entity in an infinite loop the instant it settled onto exactly the position orbit
	// itself had just chosen as acceptable. 6.0 leaves a real margin below TIGHT's own floor rather
	// than merely ducking under it by a hair.
	private static final double ORBIT_TOO_CLOSE_DISTANCE = 6.0;

	/** Reaction to any player (not just state.lockedTarget - whoever's actually closest) coming
	 * within ORBIT_TOO_CLOSE_DISTANCE - not gated by the ordinary engagement cooldown, same "this
	 * always wins outright" philosophy as checkUnconditionalGrab. IS gated by the same just-released
	 * grace/cooldown checkUnconditionalGrab itself arms (state.grabGraceActive/grabCooldownUntilTick,
	 * set whenever PlanRunner.consumeRideJustEnded() reports a forced ride just ended, whether via a
	 * spammed-shift escape or a carry-flee timer drop) - real playtesting found the wendigo re-
	 * grabbing a player within a tick or two of legitimately releasing them: the moment it re-enters
	 * orbit standing right where the carry ended (or right where an escapee just dismounted), this
	 * check saw them well within range and immediately lunged again, completely bypassing the grace
	 * period that only ever guarded checkUnconditionalGrab's own direct grab path. Below
	 * ORBIT_TOO_CLOSE_LUNGE_MIN_PERCENT (that closest player's own severity) the wendigo just
	 * flees/despawns and re-searches for a new spawn; at/above it, it commits to a bounded
	 * combat.lunge_attack pursuit of that player instead (retargeting lockedTarget to them -
	 * overrideIntoLunge does this itself). Deliberately a lunge, not overrideIntoChaseUntilLight's
	 * internal.chase_until_light - a player actively placing torches while backing away can keep the
	 * immediate area "not dark enough" just often enough that a light-seeking chase never naturally
	 * resolves, chasing indefinitely; combat.lunge_attack's own resolution is bounded by construction
	 * regardless (isLungeResolved ends the instant the pathfind either reaches melee range or
	 * finishes/gets stuck, see overrideIntoLunge's own comment). Returns true if either reaction
	 * fired, so the caller knows not to also fall through to the ordinary cooldown-gated engagement
	 * trigger this tick. */
	private boolean checkOrbitTooClose(ServerLevel level, WaveState state, int now) {
		if (state.grabGraceActive || now < state.grabCooldownUntilTick) {
			return false;
		}
		WendigoEntity entity = state.entity;
		double thresholdSqr = ORBIT_TOO_CLOSE_DISTANCE * ORBIT_TOO_CLOSE_DISTANCE;
		ServerPlayer closest = null;
		double closestDistSqr = Double.MAX_VALUE;
		for (ServerPlayer player : level.players()) {
			// Never reacts to a player at/above y=0 - same "can't follow back above ground" rule
			// Targeting.nearestPlayer/nearbyPlayers already enforce for everything mid-plan; this
			// unconditional override reads level.players() directly instead, so it needs its own check.
			if (player.getY() >= 0) {
				continue;
			}
			double distSqr = entity.distanceToSqr(player);
			if (distSqr < thresholdSqr && distSqr < closestDistSqr) {
				closest = player;
				closestDistSqr = distSqr;
			}
		}
		if (closest == null) {
			return false;
		}
		int percent = this.progressionTracker.percentOf(closest);
		if (percent < ORBIT_TOO_CLOSE_LUNGE_MIN_PERCENT) {
			WendigoDebug.say(level, closest.getGameProfile().name() + " got too close while orbiting ("
				+ percent + "% - below lunge threshold) - discarding, will search for a new spawn spot");
			saveStage5HealthIfApplicable(state);
			// Remembered so tryEnterOrbit's next spawn attempt doesn't just land back on this exact
			// spot - the live-band search is randomized (shuffledFloodDirections) but can still
			// converge on the same cheapest-to-reach column repeatedly in a simple cave, which would
			// otherwise immediately re-trigger this same too-close discard in a tight loop.
			state.avoidSpawnPos = entity.blockPosition();
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

	// Same fastest tier PlanRunner's own LUNGE_CHASE_SPEED_MULTIPLIER uses (SemanticBands.
	// speedMultiplier("fast") = 1.75) - hardcoded rather than reached for directly since SemanticBands
	// is package-private to com.wendigo.plan. A reposition attempt trying to break light exposure
	// should move with real urgency, not an ordinary stalking pace.
	private static final double LIGHT_EXPOSURE_REPOSITION_SPEED_MULTIPLIER = 1.75;
	// How much extra light-exposure tolerance (see checkOrbitLightExposure) each stage above 1 gets,
	// in ticks - the user's own explicit numbers: stage 1 gets none at all (instant, matching
	// STAGE_UNDER_20's own "must not be caught doing anything" framing), then +2 real seconds per
	// stage climbed (stage 2 = 2s, stage 3 = 4s, stage 4 = 6s, stage 5 = 8s) - a more established
	// relationship with the dark can afford to be seen a little longer before it gives up and leaves.
	private static final int LIGHT_EXPOSURE_TICKS_PER_STAGE = 40; // 2s

	private static int lightExposureToleranceTicks(int stage) {
		return (stage - 1) * LIGHT_EXPOSURE_TICKS_PER_STAGE;
	}

	/** Generalizes what used to be a stage-1-only reaction (checkStage1Exposure) to every stage - the
	 * user's own explicit request: a time limit on how long the orbiting wendigo can stay standing
	 * somewhere too lit (light above DarkSpotScanner.MAX_DARK_LIGHT) before it gives up and despawns,
	 * scaled by stage (see lightExposureToleranceTicks). The instant exposure starts, REGARDLESS of
	 * how much tolerance this stage actually has (even stage 1's own zero), it always tries to
	 * reposition to the nearest dark, unwatched spot first (findNearestUnwatchedDarkSpot) - the user's
	 * own explicit "should always try and path out of light... starting as soon as he enters light"
	 * requirement. Purely light-based now, not also gated on being looked at (an earlier stage-1-only
	 * version of this also checked dead_stare - dropped here since the user's own description of this
	 * generalized version is entirely about time spent in light, and findNearestUnwatchedDarkSpot's
	 * own unwatched-spot bias already keeps the reposition itself from walking somewhere still in
	 * view anyway). If exposure is still continuous once tolerance runs out, gives up and despawns
	 * right where it stands - a cosmetic discard, same shape as checkOrbitTooClose's own
	 * below-lunge-threshold case above: the run itself isn't over, lockedTarget/stage are left alone,
	 * tryEnterOrbit just finds this same player a fresh spot to appear from next. */
	private boolean checkOrbitLightExposure(ServerLevel level, WaveState state, int now) {
		WendigoEntity entity = state.entity;
		ServerPlayer target = state.lockedTarget;
		if (target == null || !target.isAlive()) {
			state.orbitExposedTicks = 0;
			return false;
		}
		boolean exposed = level.getMaxLocalRawBrightness(entity.blockPosition()) > DarkSpotScanner.MAX_DARK_LIGHT;
		if (!exposed) {
			state.orbitExposedTicks = 0;
			return false;
		}
		if (state.orbitExposedTicks == 0) {
			BlockPos hideSpot = DarkSpotScanner.findNearestUnwatchedDarkSpot(level, target);
			if (hideSpot != null) {
				entity.getNavigation().moveTo(hideSpot.getX() + 0.5, hideSpot.getY(), hideSpot.getZ() + 0.5,
					LIGHT_EXPOSURE_REPOSITION_SPEED_MULTIPLIER);
			}
		}
		state.orbitExposedTicks++;
		if (state.orbitExposedTicks <= lightExposureToleranceTicks(state.stage)) {
			return false;
		}
		WendigoDebug.say(level, "stage " + state.stage + " wendigo stood in light for "
			+ (state.orbitExposedTicks / 20.0) + "s straight while orbiting - despawning outright");
		state.avoidSpawnPos = entity.blockPosition();
		entity.discard();
		state.entity = null;
		state.context = null;
		state.orbitExposedTicks = 0;
		return true;
	}

	/** Relocates an alive entity (currently trapped mid-orbit, too far from its target, or a plan
	 * that just force-ended via timeout/extreme-proximity) to a fresh dark spot back inside the
	 * normal orbit band, via TELEPORT rather than a walked retreat - trapped/can't-move/too-far
	 * relocation is meant to be instant, not something that could itself get stuck trying to walk
	 * there. Seeds the flood search from the TARGET's position when one exists, not the entity's own
	 * current position - same reasoning tryEnterOrbit's fresh-spawn search already uses (see its own
	 * findLiveBandPosition call): a stuck-but-nearby entity and a genuinely-far-away one both want a
	 * spot that's actually reachable and in-band relative to the PLAYER, and seeding from way out at
	 * the entity's own far-away position (the too-far case specifically) risks the flood-fill never
	 * making it back to the target's vicinity at all. Falls back to the entity's own position only
	 * when there's no live target to seed from. Falls back further to a plain findDarkestAwayFrom
	 * search if nothing in-band is flood-reachable, and to real discard (entering the no-entity
	 * search state) if genuinely nothing dark is reachable at all - see the user's own "if it
	 * genuinely cannot find a dark place, it despawns and keeps searching" rule. */
	private void relocateOrDiscard(ServerLevel level, WaveState state, int now) {
		WendigoEntity entity = state.entity;
		ServerPlayer target = state.lockedTarget != null && state.lockedTarget.isAlive() ? state.lockedTarget : null;
		BlockPos seedPos = target != null ? target.blockPosition() : entity.blockPosition();
		BlockPos spot = findNearbyDarkSpot(level, seedPos, target);
		if (spot == null) {
			WendigoDebug.say(level, "nowhere dark reachable to relocate to - discarding, will keep searching for a new spawn spot");
			saveStage5HealthIfApplicable(state);
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

	/** Persists the current entity's health for later restoration (see
	 * WendigoProgressionTracker.saveStage5Health/stage5HealthOf) - called right before every cosmetic
	 * discard of a stage-5 entity, so none of the various despawn/relocate paths can be used to heal
	 * back to full by teleporting away; only an actual kill (see the wave-end handling in tickLevel)
	 * resets it. No-op for every other stage, where health always resets to full on the next spawn,
	 * unchanged. */
	private void saveStage5HealthIfApplicable(WaveState state) {
		if (state.stage == 5 && state.lockedTarget != null && state.entity != null && state.entity.isAlive()) {
			this.progressionTracker.saveStage5Health(state.lockedTarget, state.entity.getHealth());
		}
	}

	/** Creates/updates/removes state.stage5BossBar to match whether a live stage-5 wendigo currently
	 * exists - the user's own explicit request: a visible health indicator, but only for stage 5,
	 * where health actually matters (it's the whole stop condition - see endStage5Hunt). Called once
	 * per tick from tickLevel's own entry point, before any of this tick's own discard/kill handling
	 * runs - a one-tick lag hiding the bar right after a kill/discard is imperceptible. */
	private void updateStage5BossBar(WaveState state) {
		boolean shouldShow = state.stage == 5 && state.entity != null && state.entity.isAlive() && state.lockedTarget != null;
		if (!shouldShow) {
			if (state.stage5BossBar != null) {
				state.stage5BossBar.removeAllPlayers();
				state.stage5BossBar = null;
			}
			return;
		}
		if (state.stage5BossBar == null) {
			state.stage5BossBar = new ServerBossEvent(UUID.randomUUID(), Component.literal("Wendigo"),
				BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS);
		}
		state.stage5BossBar.addPlayer(state.lockedTarget);
		float maxHealth = state.entity.getMaxHealth();
		state.stage5BossBar.setProgress(maxHealth > 0.0F ? Math.clamp(state.entity.getHealth() / maxHealth, 0.0F, 1.0F) : 0.0F);
	}

	// Mirrors PlanRunner's own ORBIT_SURFACE_NORMALS/randomOrbitSurfaceNormal exactly (com.wendigo.plan,
	// private to that class) - duplicated here rather than widening its visibility, same tradeoff
	// already accepted elsewhere in this codebase (e.g. SemanticBands.DARKNESS_LIGHT_THRESHOLD vs
	// DarkSpotScanner's own darkness cutoff).
	private static final Direction[] ORBIT_SURFACE_NORMALS =
		{Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

	private static Direction randomOrbitSurfaceNormal() {
		return ORBIT_SURFACE_NORMALS[ThreadLocalRandom.current().nextInt(ORBIT_SURFACE_NORMALS.length)];
	}

	/** Shared by relocateOrDiscard (teleport case) and the post-grab return-to-orbit case (walked
	 * case, see the "ordinary completion" branch of tickLevel) - a dark spot within the orbit band of
	 * target if target is still valid, falling back to a plain away-from-target search, or null if
	 * genuinely nothing dark is reachable at all.
	 * <p>
	 * Two live-reported bugs fixed together here: (1) this used the old flood-based
	 * findLiveBandPosition hardcoded to Direction.UP (floor only) - same systematic wall/ceiling
	 * under-representation bug tickOrbit's own wander/waypoint picking already had fixed via
	 * findLiveBandPosition3D + a random normal (see that method's own history), just never carried
	 * over to this shared helper - a relocate (too far, trapped, or a forced backstop) never had a
	 * real shot at landing anywhere but the ground. (2) relocating used the FULL orbit band up to
	 * maxDistance - for the specific "too far from target" trigger, that could land the entity right
	 * back out near the far edge of the band, close to ORBIT_DESPAWN_DISTANCE's own threshold all
	 * over again. The user's own explicit request: aim for medium distance instead of far - half the
	 * band's own width, not the full min-max range. */
	private static BlockPos findNearbyDarkSpot(ServerLevel level, BlockPos selfPos, ServerPlayer target) {
		CaveScale caveScale = CaveScaleScanner.classify(level, selfPos);
		double minDistance = orbitMinDistance(caveScale);
		double maxDistance = orbitMaxDistance(caveScale);
		double mediumDistance = minDistance + (maxDistance - minDistance) / 2.0;
		BlockPos spot = target != null
			? DarkSpotScanner.findLiveBandPosition3D(level, target.blockPosition(), minDistance, mediumDistance, randomOrbitSurfaceNormal())
			: null;
		if (spot == null) {
			spot = DarkSpotScanner.findDarkestAwayFrom(level, selfPos, maxDistance, target != null ? target.blockPosition() : null);
		}
		return spot;
	}

	/** entity == null: spawn a fresh wendigo directly into orbit - no LLM call, no cooldown consumed
	 * (only starting a PLAN is cooldown-gated - see tickOrbitingEntity's own trigger check; spawning/
	 * orbiting itself isn't). Retries near the previous target first if one's still valid (a
	 * relocate/discard cycle keeps state.lockedTarget unless the loss WAS the target itself - see
	 * tickOrbitingEntity), otherwise runs the normal proximity-group selection fresh. Throttled via
	 * WaveState.nextRespawnSearchTick - a dark-spot scan isn't free, no need to retry every single
	 * tick while waiting for somewhere valid to appear. */
	private void tryEnterOrbit(ServerLevel level, WaveState state, int now) {
		if (state.requestPending || now < state.nextRespawnSearchTick) {
			return;
		}
		state.nextRespawnSearchTick = now + ORBIT_SPAWN_SEARCH_INTERVAL_TICKS;

		ServerPlayer target = state.lockedTarget != null && state.lockedTarget.isAlive() ? state.lockedTarget : null;
		if (target == null) {
			WendigoProgressionTracker.TargetSelection selection = this.progressionTracker.selectTarget(level);
			if (selection == null) {
				return;
			}
			target = selection.target();
		}
		int stage = this.progressionTracker.stageOf(target);
		// Stage 5 no longer spawns unconditionally the instant it's eligible - the user's own
		// explicit "make it a random chance" request, checked fresh on every attempt (not just once
		// per hunt) so it stays unpredictable throughout, not just at the very first appearance. A
		// missed roll costs nothing - no cooldown/timer consumed, ORBIT_SPAWN_SEARCH_INTERVAL_TICKS's
		// own throttle already retries shortly on its own.
		if (stage == 5 && ThreadLocalRandom.current().nextDouble() >= STAGE5_SPAWN_CHANCE) {
			return;
		}
		// Spawn already inside the orbit band, not just "somewhere dark nearby" - a spawn that
		// ignores the band (as a flat nearest-dark-spot search would) very often lands inside the
		// too-close threshold, triggering an immediate despawn/chase the moment orbit's first tick
		// runs. Falls back to a plain nearest-dark-spot search (within max band distance) if nothing
		// flood-reachable in-band was found - some darkness beats none at all.
		CaveScale caveScale = CaveScaleScanner.classify(level, target.blockPosition());
		double maxDistance = orbitMaxDistance(caveScale);
		BlockPos spawnPos = DarkSpotScanner.findLiveBandPosition(level, target.blockPosition(), target.blockPosition(),
			orbitMinDistance(caveScale), maxDistance, Direction.UP);
		if (spawnPos == null) {
			spawnPos = DarkSpotScanner.findDarkest(level, target.blockPosition(), maxDistance);
		}
		if (spawnPos == null) {
			return; // nothing dark near this target yet either - try again next throttled search
		}
		if (state.avoidSpawnPos != null && spawnPos.distSqr(state.avoidSpawnPos) < ORBIT_TOO_CLOSE_DISTANCE * ORBIT_TOO_CLOSE_DISTANCE) {
			// Landed back on (or right next to) the exact spot a too-close discard just fired from -
			// see checkOrbitTooClose's own comment. Skip this attempt rather than immediately
			// re-triggering the same reaction the instant it settles in; the throttled retry above
			// re-rolls the randomized flood search fresh next time.
			return;
		}

		WendigoEntity wendigo = new WendigoEntity(ModEntities.WENDIGO, level);
		if (stage == 5) {
			// Restore whatever health was left from the last encounter - see saveStage5HealthIfApplicable,
			// called right before every cosmetic pause - so teleporting away can't be used to heal back
			// to full; only actually killing it (see the wave-end handling in tickLevel) resets this.
			wendigo.setHealth(this.progressionTracker.stage5HealthOf(target, wendigo.getMaxHealth()));
		}
		wendigo.snapTo(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5, 0f, 0f);
		wendigo.syncPoseToSpawnPosition();
		wendigo.nudgeTowardAttachedSurface(Direction.UP);
		// Set here rather than left for a real plan's own startWave call - the orbit light-exposure
		// reaction (see checkOrbitLightExposure) and the stage-1 despawn-effect scaling (see
		// WendigoEntity.remove) both need to know this entity's stage from the moment it enters orbit,
		// which can be long before any plan actually starts (or, for a wendigo that never gets
		// engaged this run, ever).
		wendigo.setSeverityPercent(WendigoProgressionTracker.representativePercent(stage));
		level.addFreshEntity(wendigo);
		// "As soon as a wendigo spawns on the player their 2000 ticks get reset to 0" - fires here
		// unconditionally (fresh spawn or resuming an already-active run alike - startRun's own
		// computeIfAbsent leaves an existing run's progress completely untouched either way). The
		// SPAWN cue itself is NOT unconditional though - the user's own explicit request: it should
		// only announce the true first spawn of a run, not every cosmetic mid-run relocate (too-close
		// flee, lost target, dead-stare, etc. all funnel back through this exact same spawn path).
		boolean isFreshRun = this.progressionTracker.startRun(target);
		if (isFreshRun) {
			WendigoSounds.play(level, target, WendigoSounds.Type.SPAWN);
		}
		wendigo.startOrbit(target);

		state.entity = wendigo;
		state.lockedTarget = target;
		state.stage = stage;
		WendigoDebug.say(level, "spawned into orbit near " + target.getGameProfile().name() + " at " + spawnPos.toShortString()
			+ " (stage " + state.stage + ")");
	}

	// Rolled fresh on every stage-5 spawn attempt, not just the hunt's first appearance - see
	// tryEnterOrbit's own call site comment. First-pass 50%, tune by feel like everything else here.
	private static final double STAGE5_SPAWN_CHANCE = 0.5;

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
			beginEngagement(level, state, target, this.progressionTracker.percentOf(target));
			return;
		}
		if (state.entity != null) {
			return;
		}
		state.debugForced = true;
		beginWave(level, state, target, this.progressionTracker.percentOf(target));
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
		state.stage = 0;
		state.requestPending = false;
		state.cooldownUntilTick = 0;
		state.debugForced = false;
	}

	/**
	 * Bypasses cooldown/eligibility AND the LLM call - runs a hand-authored plan (spawn_at/plan/
	 * global_rules, same shape the model would return; despawning is always engine-resolved, not part
	 * of the plan) through the real spawn/despawn lifecycle. Used by the /wendigo wavetest debug
	 * command to iterate on plans for free.
	 */
	public void forceWaveWithPlan(ServerLevel level, ServerPlayer target, JsonObject plan) {
		WaveState state = this.waves.computeIfAbsent(level, l -> new WaveState());
		if (state.requestPending) {
			return;
		}
		state.debugForced = true;
		// Same "engage the existing orbiting entity instead of requiring a reset" treatment forceWave
		// itself gets - still requires a reset for a genuinely mid-plan entity.
		if (state.entity != null && state.entity.isAlive() && state.entity.isOrbiting()) {
			WaveContext context = buildContext(level, target, this.progressionTracker.percentOf(target), state.entity);
			// Hand-authored showcase/test plans shouldn't be second-guessed by tier gating meant to
			// keep an LLM honest - bypass it (severityPercent=100 unlocks everything).
			engageExistingWendigo(level, state, context, plan, true);
			return;
		}
		if (state.entity != null) {
			return;
		}
		WaveContext context = buildContext(level, target, this.progressionTracker.percentOf(target), null);
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
		// Engine-authored, not model-authored - same "don't second-guess a deliberately built plan"
		// bypass as /wendigo wavetest's hand-authored content.
		WaveContext context = buildContext(level, target, this.progressionTracker.percentOf(target), null);
		spawnWave(level, state, context, buildDarknessAmbushPlan(), true);
	}

	/** True if this level currently has a real, alive wendigo mid-encounter - DarknessOverstayTracker
	 * uses this to decide whether a darkness-overstay trigger should spawn a fresh ambush
	 * (triggerDarknessAmbush, which just silently no-ops while one's already active anyway) or
	 * redirect the existing one instead (overrideIntoChaseUntilLight). */
	public boolean hasActiveWave(ServerLevel level) {
		WaveState state = this.waves.get(level);
		return state != null && state.entity != null && state.entity.isAlive();
	}

	/** True if this level's wendigo is currently alive, specifically locked onto this player, AND
	 * actually mid-plan rather than just idly orbiting - DarknessOverstayTracker uses this to gate its
	 * own periodic "still lingering in the dark" warning noise: the user's own explicit request to
	 * only play it while the wendigo is genuinely active on this player right now, not just because
	 * they happen to be sitting in darkness with no wendigo (or a wendigo busy with someone else in a
	 * multiplayer group) anywhere near them. The orbiting/returningToOrbit exclusion is a real bug fix
	 * on top of that original intent, not a new requirement of its own - a locked-target orbiting
	 * wendigo is the default, near-constant idle state for most of a run, so without it this "genuinely
	 * active" warning fired on a routine 5s timer basically any time the player merely stood in the
	 * dark near an idle wendigo, which is exactly the "random noises while in orbit" this was
	 * supposed to avoid in the first place. */
	public boolean isActiveOn(ServerPlayer player) {
		WaveState state = this.waves.get(player.level());
		return state != null && state.entity != null && state.entity.isAlive() && state.lockedTarget == player
			&& !state.entity.isOrbiting() && !state.entity.isReturningToOrbit();
	}

	/**
	 * Darkness-overstay trigger for when a wendigo is already active (see DarknessOverstayTracker,
	 * which uses a much shorter fixed threshold for this case than the tiered one that spawns a fresh
	 * ambush): rather than trying to spawn a second one, interrupts whatever it's currently doing -
	 * an LLM-authored plan it may be mid-way through - and redirects it straight into
	 * internal.chase_until_light instead, same "get out of the dark or get grabbed" payoff, just
	 * without a spawn step.
	 * <p>Also no-ops while the wendigo already has the player as a forced rider (see
	 * WendigoEntity.isForcingRide): real bug found from a play-session log - the player stays "in
	 * darkness" (dark enough to keep tripping this trigger) the entire time they're being carried
	 * toward a despawn point, so without this guard the tracker's own 5s re-fire kept restarting
	 * internal.chase_until_light from scratch on someone already caught, and isChaseUntilLightResolved
	 * unconditionally calls beginForcedRide again the instant it sees them in melee range (true
	 * immediately, since they're literally riding). Once truly caught, the existing plan (retreat_
	 * with_fallback/despawn fallback chain) is already exactly "get them out of the dark or get
	 * grabbed" playing out - nothing left for this trigger to add.
	 * <p>Also no-ops while the wendigo is already chasing (see WendigoEntity.isChasing - true for
	 * either combat.chase or an internal.chase_until_light already in progress): the same class of
	 * bug as the forced-rider case above, just for the plain chase stretch before a catch. The player
	 * staying "in darkness" is the whole POINT of an ongoing chase_until_light - they haven't reached
	 * light yet, so DarknessOverstayTracker's 5s re-fire would otherwise keep calling this again and
	 * again for as long as the chase itself lasts, each call restarting internal.chase_until_light
	 * from scratch (via startWave -> PlanRunner.start's own state reset) instead of just letting the
	 * chase already in progress keep running uninterrupted - the user's own explicit request. Doesn't
	 * suppress the very first redirect out of some OTHER action (posture.stare, an AI-authored
	 * combat.lunge_attack, plain orbiting, etc.) - isChasing() is false in all of those, so this only
	 * ever blocks a chase from re-triggering itself.
	 */
	public void overrideIntoChaseUntilLight(ServerLevel level, ServerPlayer target) {
		WaveState state = this.waves.get(level);
		if (state == null || state.entity == null || !state.entity.isAlive() || state.entity.isForcingRide()
				|| state.entity.isChasing()) {
			return;
		}
		WaveContext context = state.context != null ? state.context
			: buildContext(level, target, this.progressionTracker.percentOf(target), state.entity);
		state.entity.startWave(buildChaseUntilLightOverridePlan(), 100, true);
		state.context = context;
		state.lockedTarget = target;
		state.stage = this.progressionTracker.stageOf(target);
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
			: buildContext(level, target, this.progressionTracker.percentOf(target), state.entity);
		state.entity.startWave(buildTooCloseLungePlan(), 100, true);
		state.context = context;
		state.lockedTarget = target;
		state.stage = this.progressionTracker.stageOf(target);
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
	 * the player could have seen coming) - despawning is entirely engine-resolved now (see
	 * PlanRunner's live despawn resolution), nothing for this hand-built plan to specify. */
	private static JsonObject buildDarknessAmbushPlan() {
		JsonObject plan = new JsonObject();
		plan.addProperty("spawn_at", "no_players_looking");
		plan.add("plan", buildDangerChaseFleeSteps());
		plan.add("global_rules", new JsonArray());
		return plan;
	}

	/** Same shape as buildDarknessAmbushPlan minus spawn_at - PlanRunner.start only ever
	 * reads "plan"/"global_rules" from a plan object (spawn/despawn resolution happens externally,
	 * before startWave is called), and overrideIntoChaseUntilLight's entity already exists, so
	 * there's nothing to resolve here. */
	private static JsonObject buildChaseUntilLightOverridePlan() {
		JsonObject plan = new JsonObject();
		plan.add("plan", buildDangerChaseFleeSteps());
		plan.add("global_rules", new JsonArray());
		return plan;
	}

	/** Unconditional grab_distance override - the instant the target comes within grab_distance
	 * (ProximityBands, 3 blocks) of an entity that isn't already forcing a ride, this catches them
	 * immediately, interrupting whatever else was happening (orbiting OR mid-plan, doesn't matter).
	 * Not gated by cooldown/severity/tier at all - "in reach" is meant to always win outright, unlike
	 * combat.lunge_attack's own gated precondition (a nearby safe-retreat-spot check) which this
	 * deliberately bypasses by calling WendigoEntity.forceGrabNow directly instead of routing through
	 * a combat.lunge_attack plan step, which could otherwise silently skip the catch and go straight
	 * to fleeing empty-handed. The flee/damage/move-away sequence itself is entirely self-contained
	 * inside forceGrabNow now (see PlanRunner.startCarryFlee) - no synthetic follow-up plan needed
	 * here anymore. That carry-flee sequence ends with the wendigo standing right on top of the player
	 * it just released (it walked them there together) - real playtesting found this spammed an
	 * instant re-grab the very next tick without the grace/cooldown check below. */
	// Flat time floor on top of grabGraceActive's distance-based grace - see WaveState.grabCooldownUntilTick.
	private static final int GRAB_RELEASE_COOLDOWN_TICKS = 200; // 10s

	private void checkUnconditionalGrab(ServerLevel level, WaveState state, int now) {
		WendigoEntity entity = state.entity;
		if (entity.isForcingRide() || entity.isReturningToOrbit()) {
			return; // already caught, or already mid-transit somewhere - let that resolve first
		}
		if (entity.consumeRideJustEnded()) {
			state.grabGraceActive = true;
			state.grabCooldownUntilTick = now + GRAB_RELEASE_COOLDOWN_TICKS;
		}
		ServerPlayer target = state.lockedTarget != null && state.lockedTarget.isAlive() ? state.lockedTarget : null;
		if (target == null) {
			return;
		}
		// Never grabs a player at/above y=0 - same "can't follow back above ground" rule
		// Targeting.nearestPlayer already enforces for everything mid-plan; this unconditional
		// override reads state.lockedTarget directly instead, so it needs its own check.
		if (target.getY() >= 0) {
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
			: buildContext(level, target, this.progressionTracker.representativePercent(state.stage), entity);
		entity.forceGrabNow(target);
		if (entity.consumeSpearRepelJustHappened()) {
			// A repel, not a real grab - forceGrabNow never starts a ride here, so nothing else would
			// otherwise stop this method calling forceGrabNow again next tick and repelling a second
			// time (the reported "spear sound spammed" bug: a player holding a charged spear aimed at
			// the wendigo inside grab_distance got re-repelled every tick they kept holding it). Arm
			// the exact same grace/cooldown a real grab's own release does (see consumeRideJustEnded's
			// own call site above) so a repel buys the same breathing room a completed grab-and-drop
			// already gets.
			state.grabGraceActive = true;
			state.grabCooldownUntilTick = now + GRAB_RELEASE_COOLDOWN_TICKS;
			WendigoDebug.say(level, "target within grab range but repelled it with a spear - backing off");
			return;
		}
		state.context = context;
		state.waveStartTick = now;
		state.extremeProximityTicks = 0;
		WendigoDebug.say(level, "target within grab range - grabbing unconditionally");
	}

	private void beginWave(ServerLevel level, WaveState state, ServerPlayer target, int effectiveSeverity) {
		WaveContext context = buildContext(level, target, effectiveSeverity, null);
		int percent = context.severityCap() > 0 ? 100 * context.severity() / context.severityCap() : 0;
		boolean torchSpawnAvailable = hasEligibleTorchSpawnCandidate(level, context, percent);

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
		WaveContext context = buildContext(level, target, effectiveSeverity, state.entity);
		int percent = context.severityCap() > 0 ? 100 * context.severity() / context.severityCap() : 0;
		boolean torchSpawnAvailable = hasEligibleTorchSpawnCandidate(level, context, percent);

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
	 * counterpart to spawnWave (which constructs a brand new one). Unlike spawnWave's spawn_at (a
	 * plain teleport at construction, since there's nothing to walk FROM yet), this entity already
	 * exists somewhere else in the cave (wherever orbit left it) - repositioning, if the plan's own
	 * first step even calls for any, is just an ordinary live movement.approach_band resolved once
	 * the plan actually starts running, not a separate pre-plan walk this method has to orchestrate. */
	private void engageExistingWendigo(ServerLevel level, WaveState state, WaveContext context, JsonObject plan, boolean bypassTierGating) {
		int percent = context.severityCap() > 0 ? 100 * context.severity() / context.severityCap() : 0;
		int gatingPercent = bypassTierGating ? 100 : percent;
		state.entity.startWave(plan, gatingPercent, bypassTierGating);
		state.context = context;
		state.waveStartTick = level.getServer().getTickCount();
		state.extremeProximityTicks = 0;
		WendigoDebug.say(level, "engaging existing wendigo - aggression: " + context.severity() + "/" + context.severityCap()
			+ " (" + percent + "%), caveScale=" + context.caveScale() + ", plan: " + plan);
	}

	// Cap on how far to search for torches per positioning band - matches LightSourceScanner's own
	// effective range (block light propagates ~15 blocks, and its own radii list tops out at 35), so
	// nothing beyond this could ever be found anyway regardless of a band's own, wider distanceMax.
	private static final double TORCH_SEARCH_RADIUS = 40.0;
	// How close the wendigo needs to be to DarkSpotScanner.findCeilingVantagePoint's own resolved
	// position (directly above the player) to count as "currently perched above them" for the
	// prompt's isOnTopPlayer context - not an exact match, some slack for the vantage point having
	// shifted slightly since the wendigo actually arrived there.
	private static final double ON_TOP_PLAYER_TOLERANCE = 6.0;

	/**
	 * effectiveSeverity is what actually drives tier/schema/prompt for this encounter - usually just
	 * target's own stage-derived percent, but for an automatically-selected multiplayer target it's
	 * the whole proximity group's furthest-along member's stage (see
	 * WendigoProgressionTracker.selectTarget) - the wendigo shows up at the full intensity that
	 * group's most-established member has earned, even if the player it actually grabs happens to
	 * have fewer completed runs of their own. engagingEntity is the
	 * already-alive entity being engaged, or null for a fresh spawn - only affects whether the
	 * resulting context carries current-position info for the prompt (see WaveContext.CurrentPosition).
	 * Never fails/returns null - a live torch-count scan can legitimately come back all zeros, that's
	 * not a failure, just a fact resolveSpawnSpot's own live resolution deals with when it actually
	 * tries to find a position.
	 */
	private WaveContext buildContext(ServerLevel level, ServerPlayer target, int effectiveSeverity, WendigoEntity engagingEntity) {
		BlockPos playerPos = target.blockPosition();
		Map<String, Integer> torchCountsByBand = new LinkedHashMap<>();
		for (String band : WaveContext.BAND_LABELS) {
			double minDistance = PositionBands.distanceMin(band);
			double maxDistance = Math.min(PositionBands.distanceMax(band), TORCH_SEARCH_RADIUS);
			int count = DarkSpotScanner.findTorchesInBand(level, playerPos, minDistance, maxDistance).size();
			torchCountsByBand.put(band, count);
		}
		WaveContext.CurrentPosition currentPosition = null;
		if (engagingEntity != null && engagingEntity.isAlive()) {
			double distance = engagingEntity.distanceTo(target);
			BlockPos ceilingVantage = DarkSpotScanner.findCeilingVantagePoint(level, playerPos);
			boolean isOnTopPlayer = ceilingVantage != null
				&& engagingEntity.blockPosition().distSqr(ceilingVantage) <= ON_TOP_PLAYER_TOLERANCE * ON_TOP_PLAYER_TOLERANCE;
			currentPosition = new WaveContext.CurrentPosition(distance, isOnTopPlayer);
		}
		CaveScale caveScale = CaveScaleScanner.classify(level, playerPos);
		// Always 100 now - effectiveSeverity is already a fixed representative percent per stage (see
		// WendigoProgressionTracker.representativePercent), not a cumulative score with a real cap to
		// report anymore. Keeping the severity/severityCap field shape in WaveContext unchanged (rather
		// than replacing it with a bare percent) means every existing "100 * context.severity() /
		// context.severityCap()" call site elsewhere in this class keeps working unchanged too.
		return new WaveContext(target, effectiveSeverity, 100, torchCountsByBand, currentPosition,
			this.encounterHistory.of(target), level.getServer().getTickCount(), caveScale);
	}

	private void spawnWave(ServerLevel level, WaveState state, WaveContext context, JsonObject plan, boolean bypassTierGating) {
		int percent = context.severityCap() > 0 ? 100 * context.severity() / context.severityCap() : 0;
		if (!canSpawnNear(context.player())) {
			// Hard, unconditional block - checked here rather than folded into severity/eligibility
			// gating (which debug paths like /wendigo wave/wavetest deliberately bypass for testing
			// convenience) specifically so nothing can skip it: never above y=0 no matter how dark the
			// area reads, and never onto a player with real night vision. Soul light no longer blocks
			// spawning at all - see isNearSoulLight, checked instead at engagement time.
			WendigoMod.LOGGER.info("Wendigo spawn blocked for {} (above y=0 or night vision) - skipping",
				context.player().getGameProfile().name());
			state.cooldownUntilTick = level.getServer().getTickCount() + this.config.dynamicCooldownTicks(percent);
			return;
		}
		// Stage 1 always gets the safest possible first appearance, regardless of what spawn_at the
		// model actually picked - prose alone proved unreliable for this kind of hard requirement
		// (see TierGates), so it's forced here rather than hoped for. Not forced for bypassTierGating
		// (hand-authored /wendigo wavetest content) - a showcase's deliberately chosen spawn_at
		// shouldn't be second-guessed just because the tester's own severity happens to be low.
		BlockPos spawnPos = !bypassTierGating && percent < 20
			? resolveUnwatchedSpot(level, context)
			: resolveSpawnSpot(level, context, plan, percent, bypassTierGating);
		if (spawnPos == null) {
			WendigoMod.LOGGER.warn("Wendigo wave plan missing a resolvable spawn spot, skipping: {}", plan);
			state.cooldownUntilTick = level.getServer().getTickCount() + this.config.dynamicCooldownTicks(percent);
			return;
		}

		int gatingPercent = bypassTierGating ? 100 : percent;

		WendigoEntity wendigo = new WendigoEntity(ModEntities.WENDIGO, level);
		wendigo.snapTo(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5, 0f, 0f);
		wendigo.syncPoseToSpawnPosition();
		wendigo.nudgeTowardAttachedSurface(Direction.UP);
		level.addFreshEntity(wendigo);
		// Same fresh-run gate tryEnterOrbit's own SPAWN cue uses (see its own comment) - the user's own
		// explicit rule: the cue announces a run actually starting, not every individual materialization.
		// spawnWave is only ever reached with state.entity == null (see its 3 call sites' own guards), so
		// every call here IS a real entity appearing, but that doesn't mean a fresh run - a darkness
		// ambush or a debug force can just as easily be resuming a run whose entity was previously
		// discarded mid-way (a cosmetic relocate, not a completion). Also fixes a real, previously
		// unnoticed gap: without this call, a player whose very first-ever encounter happened to arrive
		// via triggerDarknessAmbush rather than the routine tryEnterOrbit path never got their own
		// "2000 ticks reset to 0" eligibility-timer reset (see startRun's own comment) at all.
		boolean isFreshRun = this.progressionTracker.startRun(context.player());
		if (isFreshRun) {
			WendigoSounds.play(level, context.player(), WendigoSounds.Type.SPAWN);
		}
		playSpawnDespawnEffect(level, spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5);
		wendigo.startWave(plan, gatingPercent, bypassTierGating);

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
			+ ", caveScale=" + context.caveScale() + ", plan: " + plan);
	}

	// (dx, dy, dz) random-offset box each particle is scattered within around (x,y,z) - user's own
	// explicit numbers, not derived from anything.
	private static final double SPAWN_DESPAWN_PARTICLE_DX = 0.5;
	private static final double SPAWN_DESPAWN_PARTICLE_DY = 1.0;
	private static final double SPAWN_DESPAWN_PARTICLE_DZ = 0.5;
	private static final int SPAWN_DESPAWN_PARTICLE_COUNT = 1000;

	/** Visual/audio beat for the wendigo actually materializing or vanishing - see spawnWave's own
	 * call site for the spawn half; WendigoEntity.remove() calls the same effect for the despawn half
	 * (kept as a separate, duplicated 2-liner there rather than reused across packages - entity
	 * depending on wave would invert this project's existing layering, where wave orchestrates entity/
	 * plan, never the reverse). User's own explicit choice of particle/sound/numbers throughout. */
	private static void playSpawnDespawnEffect(ServerLevel level, double x, double y, double z) {
		level.sendParticles(ParticleTypes.SMOKE, x, y, z, SPAWN_DESPAWN_PARTICLE_COUNT,
			SPAWN_DESPAWN_PARTICLE_DX, SPAWN_DESPAWN_PARTICLE_DY, SPAWN_DESPAWN_PARTICLE_DZ, 0.0);
		level.playSound(null, x, y, z, SoundEvents.WARDEN_ATTACK_IMPACT, SoundSource.HOSTILE, 1.0F, 0.0F);
	}

	/** Hard, unconditional spawn eligibility - see spawnWave's own call site comment for why this is
	 * checked directly rather than folded into the existing severity/eligibility gating (which debug
	 * paths bypass on purpose). Never above y=0 regardless of darkness; never onto a player with real
	 * night vision. Proximity to a soul light source (see isNearSoulLight) does NOT block spawning at
	 * all anymore - the wendigo still spawns and orbits near soul light, it's just gated out of
	 * engagement (see tickOrbitingEntity's own call to isNearSoulLight, right before beginEngagement)
	 * so it can't actually run a plan on a player standing in it. The night-vision half is skipped for
	 * a player with /wendigo debug enabled specifically - WendigoCommands.toggleDebug applies real
	 * Night Vision for the whole debug session (so a tester can actually see what's happening), which
	 * would otherwise permanently block their own testing under this exact rule. y=0 still applies
	 * even while debugging - that isn't something debug mode itself causes. */
	private static boolean canSpawnNear(ServerPlayer player) {
		boolean blockedByNightVision = player.hasEffect(MobEffects.NIGHT_VISION) && !WendigoDebug.isEnabled(player);
		return player.getY() < 0 && !blockedByNightVision;
	}

	private static final double SOUL_LIGHT_SUPPRESSION_RADIUS = 8.0;

	/** Whether the player is currently close enough to a soul-fire-family light source (soul torch,
	 * soul wall torch, soul lantern, soul campfire, or bare soul fire) that the wendigo should hold
	 * off engaging them - the user's own explicit "safe zone" replacement for the old inventory-based
	 * soul lantern check: it now has to actually be lit and nearby, not just carried. Radius matches
	 * roughly how far a light source's own glow reaches, per the user's own "the radius light reaches,
	 * like 7-8" wording. Deliberately NOT checked by tryEnterOrbit (the wendigo still spawns/orbits
	 * near soul light) - only by tickOrbitingEntity's own engagement trigger, right before
	 * beginEngagement, so the wendigo just can't run a plan on a target standing in it. */
	private static boolean isNearSoulLight(ServerLevel level, BlockPos center) {
		int radius = (int) Math.ceil(SOUL_LIGHT_SUPPRESSION_RADIUS);
		double radiusSq = SOUL_LIGHT_SUPPRESSION_RADIUS * SOUL_LIGHT_SUPPRESSION_RADIUS;
		for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius))) {
			if (pos.distSqr(center) > radiusSq) {
				continue;
			}
			if (isSoulLightBlock(level.getBlockState(pos))) {
				return true;
			}
		}
		return false;
	}

	private static boolean isSoulLightBlock(BlockState state) {
		return state.is(Blocks.SOUL_TORCH) || state.is(Blocks.SOUL_WALL_TORCH)
			|| state.is(Blocks.SOUL_LANTERN) || state.is(Blocks.SOUL_CAMPFIRE) || state.is(Blocks.SOUL_FIRE);
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
		ServerPlayer target = state.context.player();
		// Same "can't follow back above ground" rule Targeting/every spawn-eligibility check already
		// enforces - but mid-plan there's no per-action equivalent (each primitive just silently
		// no-ops once Targeting.nearestPlayer starts returning null for this player), so without this
		// check a wave could otherwise sit here, entity alive, for up to waveTimeoutTicks (a real 4
		// minutes) before the hard backstop above ever caught it - blocking tryEnterOrbit (which only
		// ever runs while state.entity == null) from spawning a fresh wendigo near this same player OR
		// anyone else the entire time. A real bug found live. Immediate, no grace ticks - crossing
		// y=0 is already a hard, instant cutoff everywhere else in this codebase.
		if (target.getY() >= 0) {
			return "target left y=0 mid-plan";
		}
		double distance = state.entity.distanceTo(target);
		// Same performance-cap reasoning as tickOrbitingEntity's own ORBIT_DESPAWN_DISTANCE check,
		// just for the mid-plan case that check doesn't cover - a player who wanders far away (or
		// teleports/fast-travels) without ever crossing y=0 could otherwise leave a mid-plan wendigo
		// stranded near wherever it last was for the same up-to-4-minute stretch. Immediate as well,
		// not a forced-ride false positive risk - a carried player is always ~0 blocks away by
		// definition, so this can only ever fire when they've genuinely wandered off on their own.
		if (distance > ORBIT_DESPAWN_DISTANCE) {
			return "target too far away mid-plan (" + ORBIT_DESPAWN_DISTANCE + "+ blocks)";
		}
		if (distance <= EXTREME_PROXIMITY_DISTANCE) {
			state.extremeProximityTicks++;
		} else {
			state.extremeProximityTicks = 0;
		}
		if (state.extremeProximityTicks > EXTREME_PROXIMITY_GIVEUP_TICKS) {
			return "stuck at extreme close range with the player for too long";
		}
		// Same "genuinely wedged against the same geometry, don't wait for the 4-minute hard timeout"
		// reasoning tickOrbitingEntity's own isOrbitTrapped check already uses during orbit - see
		// PlanRunner.isRepeatedlyStuck's own comment for the real bug (a narrow trench with a
		// climbable-in-theory ledge that AWCAPI didn't reliably climb over in a live test) this backstops.
		if (state.entity.isRepeatedlyStuck()) {
			return "repeatedly stuck making no progress mid-plan";
		}
		return null;
	}

	/** Resolves spawn_at to a live position for a genuinely fresh spawn - only ever called from
	 * spawnWave, so self is always the player's own position (there's no wendigo yet to flood from
	 * a different point). Falls back to a fresh scan rather than ever rejecting the whole wave over
	 * one bad field, same philosophy the schema itself documents. Re-checks SchemaBuilder.
	 * isBandAllowed as defense-in-depth (bypassed for bypassTierGating content) - the schema already
	 * keeps a real LLM call from offering a disallowed band in the first place, same precedent as
	 * everywhere else that re-checks rather than trusting the schema filter alone. */
	private static BlockPos resolveSpawnSpot(ServerLevel level, WaveContext context, JsonObject plan, int percent, boolean bypassTierGating) {
		ServerPlayer player = context.player();
		String band = plan.has("spawn_at") ? plan.get("spawn_at").getAsString() : null;
		boolean torchSpawnAvailable = hasEligibleTorchSpawnCandidate(level, context, percent);
		boolean allowed = band != null && (bypassTierGating || SchemaBuilder.isBandAllowed(band, percent, context.caveScale(), torchSpawnAvailable));
		BlockPos resolved;
		if ("no_players_looking".equals(band)) {
			resolved = resolveUnwatchedSpot(level, context);
		} else if ("spawn_on_torch".equals(band)) {
			resolved = allowed ? resolveTorchSpawnSpot(level, player, percent, bypassTierGating) : null;
		} else if ("spot_above".equals(band)) {
			// Same straight-up ceiling probe movement.approach_band's own "spot_above" case uses -
			// gated the same way every other band is (allowed, tier-checked above), not a special
			// case like no_players_looking's own unconditional availability.
			resolved = allowed ? DarkSpotScanner.findCeilingVantagePoint(level, player.blockPosition()) : null;
		} else if (allowed) {
			resolved = DarkSpotScanner.findLiveBandPosition(level, player.blockPosition(), player.blockPosition(),
				PositionBands.distanceMin(band), PositionBands.distanceMax(band), Direction.UP);
		} else {
			resolved = null;
		}
		if (resolved == null) {
			resolved = DarkSpotScanner.findDarkest(level, player.blockPosition(), 16);
		}
		return resolved;
	}

	/** Whether at least one live torch clears the current severity's distance floor - see
	 * SchemaBuilder.minTorchSpawnDistance. Shared between beginWave/beginEngagement's schema-gate
	 * computation and resolveSpawnSpot's own defensive re-check so the two can't drift apart. Always
	 * a fresh live scan, never a stored/cached answer - by design, since a live torch that existed
	 * when the prompt was built could easily be gone (or a new one appeared) by resolution time. */
	private static boolean hasEligibleTorchSpawnCandidate(ServerLevel level, WaveContext context, int percent) {
		double minDistance = SchemaBuilder.minTorchSpawnDistance(percent);
		return !DarkSpotScanner.findTorchesInBand(level, context.player().blockPosition(), minDistance, TORCH_SEARCH_RADIUS).isEmpty();
	}

	/** Random pick among the live torches that clear the current severity's distance floor (bypassed
	 * entirely for hand-authored debug content, same as every other tier gate) - deliberately not
	 * "furthest" or "nearest" among the eligible ones, just any of them, since the whole point is
	 * "somewhere already exposed to commit from", not a particular geometry. Null if none qualify
	 * (shouldn't happen if this was actually offered/chosen, but resolveSpawnSpot's own fallback
	 * covers it either way). */
	private static BlockPos resolveTorchSpawnSpot(ServerLevel level, ServerPlayer player, int percent, boolean bypassTierGating) {
		double minDistance = bypassTierGating ? 0.0 : SchemaBuilder.minTorchSpawnDistance(percent);
		List<BlockPos> candidates = DarkSpotScanner.findTorchesInBand(level, player.blockPosition(), minDistance, TORCH_SEARCH_RADIUS);
		return candidates.isEmpty() ? null : candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
	}

	/** Live position, roughly at the "farther" band, that the target player isn't currently looking
	 * toward - backs spawn_at's "no_players_looking" option, and the forced stage-1 spawn override in
	 * spawnWave. Only ever called for a genuinely fresh spawn, so self is always the player's own
	 * position (see DarkSpotScanner.findUnwatchedPosition, the shared primitive this and
	 * PlanRunner's movement.approach_band(no_players_looking) both delegate to now - self differs per
	 * caller, but the retry/fallback logic is identical either way). */
	private static BlockPos resolveUnwatchedSpot(ServerLevel level, WaveContext context) {
		ServerPlayer player = context.player();
		return DarkSpotScanner.findUnwatchedPosition(level, player.blockPosition(), player,
			PositionBands.distanceMin("farther"), PositionBands.distanceMax("farther"), Direction.UP);
	}

	private static final class WaveState {
		WendigoEntity entity;
		// Retained across orbit transitions now, not just for one wave's duration - /wendigo debug can
		// keep reporting continuously. Only cleared when entity itself is genuinely discarded (see
		// relocateOrDiscard/tickOrbitingEntity).
		WaveContext context;
		// Only ever non-null while stage == 5 and entity is alive - see updateStage5BossBar, which
		// owns this field's whole lifecycle (create/update/remove). The user's own explicit request:
		// a visible health indicator, but only for the one stage that actually has a health-based
		// stop condition.
		ServerBossEvent stage5BossBar;
		boolean requestPending;
		int waveStartTick;
		int cooldownUntilTick;
		// The single player/group member this level's wendigo is currently committed to - mirrors
		// WendigoEntity's own lockedTarget (kept in sync whenever a new one is spawned/re-targeted),
		// but also needed here directly since relocateOrDiscard/tryEnterOrbit run when entity may be
		// momentarily null (between a discard and the next respawn search).
		ServerPlayer lockedTarget;
		// Which stage this run belongs to - fixed the moment lockedTarget is (re)set to a real
		// target (tryEnterOrbit/spawnWave/override*), same lifecycle. Doesn't change mid-run (only
		// WendigoProgressionTracker.completeRun ever advances a player's stage, and that only happens
		// between runs, never while one's still active) - see the wave-end goal-progress handling in
		// tickLevel, which reads this rather than re-deriving live.
		int stage;
		// Throttles tryEnterOrbit's own dark-spot search while entity == null - a flood-fill isn't
		// free, no need to re-run it every single tick while waiting for somewhere valid to appear.
		int nextRespawnSearchTick;
		// Set by checkOrbitTooClose's own below-lunge-threshold discard - see tryEnterOrbit's own
		// check against it. Left set (not explicitly cleared) once a fresh spawn actually lands
		// somewhere else; harmlessly stale after that; just overwritten the next time it's needed.
		BlockPos avoidSpawnPos;
		// Set by forceWave/forceWaveWithPlan; makes the completion handler apply
		// config.debugCooldownTicks instead of the normal cooldown, so a debug/test wave doesn't
		// leave the automatic severity-triggered spawner armed to fire moments later.
		boolean debugForced;
		// Consecutive ticks the wendigo has been at extreme close range with the player - see
		// checkForcedWaveEnd. Reset at the start of each wave and whenever the condition lapses.
		int extremeProximityTicks;
		// True from the tick a forced ride ends - a genuine dismount-threshold escape, or a carry-flee
		// resolving into a drop (see WendigoEntity.consumeRideJustEnded) - until the target has put
		// actual distance between themselves and the wendigo - see checkUnconditionalGrab, which must
		// not re-grab someone who was just released while they're still standing right where the ride
		// ended (an escapee never went anywhere; a drop is literally carried there by the wendigo).
		boolean grabGraceActive;
		// Flat time floor on top of grabGraceActive's distance-based grace - covers a player who was
		// just released but is stuck somewhere (a dead end) they genuinely can't put grab_distance of
		// real space between themselves and the wendigo. Set alongside grabGraceActive, on the same
		// consumeRideJustEnded() trigger.
		int grabCooldownUntilTick;
		// Consecutive ticks the orbiting wendigo has been standing somewhere too lit - see
		// checkOrbitLightExposure. Reset the instant it's no longer exposed.
		int orbitExposedTicks;
	}
}
