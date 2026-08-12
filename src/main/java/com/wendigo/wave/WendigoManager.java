package com.wendigo.wave;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.pathfinder.Path;

import com.wendigo.WendigoMod;
import com.wendigo.advancement.WendigoAdvancements;
import com.wendigo.debug.WendigoDebug;
import com.wendigo.entity.ModEntities;
import com.wendigo.entity.WendigoEntity;
import com.wendigo.plan.PlanPredicates;
import com.wendigo.plan.PositionBands;
import com.wendigo.plan.ProximityBands;
import com.wendigo.plan.SchemaBuilder;
import com.wendigo.plan.SemanticBands;
import com.wendigo.sound.WendigoSounds;
import com.wendigo.spatial.CaveScaleScanner;
import com.wendigo.spatial.CaveScaleScanner.CaveScale;
import com.wendigo.spatial.DarkSpotScanner;
import com.wendigo.spatial.LightSourceScanner;
import com.wendigo.spatial.SoulLightScanner;

/**
 * Owns the wendigo's spawn/despawn lifecycle: at most one per level, spawned onto whichever
 * eligible player/group is due for a fresh run (or has an unfinished one to resume - see
 * WendigoProgressionTracker.selectTarget), running a single LLM-authored plan from spawn to
 * despawn with no mid-wave re-planning. See PlanRunner for how the plan body itself executes once
 * the entity exists. Every position a plan resolves against (movement.approach_spot, teleporting,
 * despawn/retreat) is resolved LIVE at the moment it's actually needed - this class never
 * pre-scans and hands off a set of frozen positions the way it once did.
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
		+ "target player, their dweller severity, and a live account of the surrounding cave and "
		+ "how many torches sit nearby. Positioning works entirely through semantic destination "
		+ "types (see below), never fixed coordinates - but where 'plan' STARTS is not your choice: "
		+ "it just begins from "
		+ "wherever the wendigo already is right now. If it's already active, that's exactly wherever "
		+ "orbit left it; for a genuinely fresh appearance, the engine places it somewhere unwatched "
		+ "on its own, before you're ever asked for a plan - there's nothing to pick here, and no "
		+ "field for it. If you want to reposition before doing anything else, that's what "
		+ "movement.approach_spot is for (see below) - use it as your own plan's first step, or skip "
		+ "it entirely and let the plan's first real action happen right where the wendigo already "
		+ "stands. Where it withdraws to afterward is NOT your choice, unlike positioning - a flee "
		+ "(whenever 'plan' finishes, or whenever a movement.retreat_with_fallback step runs) always "
		+ "heads back toward the wendigo's own ordinary orbit range for its current stage, "
		+ "reachability-verified, re-resolving fresh if the first attempt fails. It'll certainly "
		+ "return to that same range on its own regardless, so there's no field for this - a flee is "
		+ "always a genuine trip home, never a distance you pick. "
		+ "Positioning throughout the rest of the plan works through two actions instead of a "
		+ "distance-band ladder: movement.approach_spot walks there, combat.teleport instantly "
		+ "relocates there, both resolved against a TYPE of spot rather than a distance - behind (the "
		+ "player's own blind spot - could be on the floor, a wall, or the ceiling behind them, "
		+ "whichever is actually there), eyeline (dark, but genuinely inline with wherever they're "
		+ "looking right now), above (a ceiling vantage point), in_view (dark, but somewhere they CAN "
		+ "currently see - a deliberate reveal), torch (the nearest live torch - teleport only, "
		+ "approach_spot has no use for it since combat.break_torch already targets the nearest one "
		+ "from wherever the wendigo ends up), ahead (a guess at where they're actually heading, "
		+ "landing somewhere dim rather than fully dark - both actions support this one too), and "
		+ "unwatched (approach_spot only - the plain 'go somewhere they're not looking' option, no "
		+ "other constraint, the standard first step for repositioning before the rest of a plan "
		+ "runs). How far away the resolved spot actually sits is never something you choose - it's "
		+ "entirely driven by the size of the surrounding cave (see the prompt's own report of it "
		+ "below), not by severity at all. Which of these types is even offered right now depends on "
		+ "stage - see the per-stage note below for what's unlocked so far. Everywhere a destination "
		+ "type is used it's resolved LIVE, against wherever the player actually is at that exact "
		+ "moment, never a position frozen from earlier in the request: there's no mid-engagement "
		+ "re-planning (aside from control.re_evaluate - see its own description), so the whole plan "
		+ "is decided once, up front, but each step that moves somewhere waits until it actually runs "
		+ "before deciding exactly where that is. "
		+ "Use movement.approach_spot to explicitly reposition mid-plan; omit any movement step "
		+ "entirely if the wendigo's current live position already fits what comes next - there is no "
		+ "obligation to always move first. "
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
		+ "waiting to be noticed. Works especially well from a wall/ceiling perch (60%+, once "
		+ "destination=above/movement.drop unlock - see that stage's own note): predicate.player_distance reads horizontal "
		+ "distance straight down the wendigo's own column while it's attached above someone, not the "
		+ "raw diagonal, so the exact same control.while(lunge_distance){movement.hold} wait resolves the "
		+ "instant the player walks underneath - then movement.drop right before the stare/lunge/chase "
		+ "instead of walking there. A patient ceiling ambush, not just a ground-level one. "
		+ "sound.ambient_cue(stare) is heavily encouraged right when a held stare begins. "
		+ "sound.breathe is a single, deliberate breathing sound played right at the wendigo's own real "
		+ "position (not the unplaced cinematic cues sound.ambient_cue uses) - NOT available at the very "
		+ "first, faintest stage, unlocks once the relationship with this player is established enough to "
		+ "be bold. Get as close as possible before using it; the closer it is when "
		+ "this plays, the more it reads as genuinely, physically right there beside them. It pairs "
		+ "especially well right after a close instant reposition (combat.teleport(destination=behind), "
		+ "or (once unlocked) destination=above) or an ordinary close approach, and again "
		+ "right before escalating into whatever comes next for the current stage - "
		+ "movement.retreat_with_fallback/control.despawn early on, combat.lunge_attack/combat.chase/"
		+ "another teleport once those unlock. Lean on it often, not just occasionally. "
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
		+ "global_rules (a separate top-level field alongside plan) back hard "
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
		+ "approach rather than repeating the same sequence again. "
		+ "combat.teleport (see its own schema description for the full mechanic) is available at "
		+ "every stage, not something that unlocks later - and the set of destination TYPES it can "
		+ "offer GROWS with stage rather than being replaced: everything unlocked at an earlier stage "
		+ "stays available at every later one too, alongside whatever's new - each stage's own text "
		+ "below names the full current menu. No source-position precondition anymore, unlike the old "
		+ "band system - it's pullable off from wherever the wendigo currently is, regardless of "
		+ "type. Reach for it specifically when the player is currently sprinting - a sudden, silent "
		+ "reposition reads as unsettling rather than redundant right as they've committed to moving "
		+ "fast - ideally as the very FIRST step of the plan, teleporting straight into position "
		+ "rather than pathing there normally. destination=behind/eyeline/above/in_view all require "
		+ "landing somewhere completely unlit; destination=torch is the one exception (a light source "
		+ "by definition), and destination=ahead deliberately lands somewhere dim instead. If the "
		+ "chosen type can't actually be found live right now, this is a clean no-op and the plan just "
		+ "continues (ahead is the one exception - it always resolves to SOME placement, falling back "
		+ "to an ordinary in_view reveal if the player isn't moving or nothing dim turns up near the "
		+ "prediction) - you never need to plan around that yourself. "
		+ "destination=in_view is a deliberate reveal - a spot the player CAN currently see rather "
		+ "than one they're blind to. Pair it with an immediate posture.stare right after landing "
		+ "early on; later on, let that stare turn straight into combat.chase/combat.lunge_attack "
		+ "instead of holding it. The engine also enforces a minimum here: a posture.stare(enabled="
		+ "true) session that starts immediately after a combat.teleport(destination=in_view) keeps "
		+ "holding for at least 3 real seconds no matter what the rest of the plan/control.while says "
		+ "- the reveal needs actual screen time to land, not an instant blink-and-gone. Nothing to "
		+ "plan around deliberately, just don't be surprised if a short hold reads as longer than "
		+ "authored right after this specific pairing. "
		+ "destination=eyeline is a more unsettling sibling of in_view - dark AND genuinely inline "
		+ "with wherever the player is looking right now, not just broadly in their field of view - "
		+ "something already sitting exactly where their gaze already rests, barely visible, rather "
		+ "than an obvious in-view reveal. Same pairing advice as in_view (an immediate posture.stare "
		+ "early on; later, straight into combat.chase/combat.lunge_attack). "
		+ "destination=ahead is a different kind of teleport entirely - not a spot around the "
		+ "player's current position, but a guess at where they're actually going: if they're moving, "
		+ "it predicts a point ahead along their live path and lands somewhere dim (faintly lit, not "
		+ "pitch black - a player is more likely to actually walk near their own torches than through "
		+ "genuine darkness) close to that predicted point, so it reads as already lying in wait along "
		+ "their real route rather than reacting to where they currently stand. This one rewards "
		+ "patience more than the others: land ahead of them, then either hold a stare and let a "
		+ "control.while on predicate.player_approaching do the waiting (the distance closing on its "
		+ "own is the whole point), or let the stare turn into combat.chase/combat.lunge_attack once "
		+ "they're actually close. "
		+ "movement.approach_spot works the same way but walks there instead of blinking - same "
		+ "destination types minus torch, plus unwatched (the plain 'go somewhere they're not "
		+ "looking' option, no other constraint), and reachability-verified before committing (a real "
		+ "path, not just a geometric guess) since getting there matters for a walk in a way it never "
		+ "does for an instant teleport.\n\n";

	private static final String STAGE_UNDER_20 =
		"CURRENT STAGE: under 20%, barely a presence. This is the very first, faintest phase of this "
		+ "player's relationship with the dark - they likely have no conscious reason yet to believe "
		+ "anything down here is watching them. The wendigo's entire job this wave is to plant one seed "
		+ "of unease and nothing more: it must not risk real confrontation, must not linger once the "
		+ "beat is over. The wendigo already appears unwatched by default here (every fresh spawn does, "
		+ "not something you pick - see the generic note above), so the plan can just start straight "
		+ "into whatever reveal fits. The shape stays simple - a reveal, a hold until noticed or "
		+ "approached, then a clean withdrawal - but nothing else about it is fixed, and this stage in "
		+ "particular is worth actively varying wave to wave: hold duration, whether the reveal is the "
		+ "ordinary stare-in-place, a combat.teleport(destination=in_view) glimpse-and-vanish, or the "
		+ "even more unsettling destination=eyeline, how long it lingers before the STARE-HOLD ends. "
		+ "The prompt's own account of past encounters with this player (see the generic note above) is "
		+ "exactly what should drive that variation - if the last few waves all read the same, that's "
		+ "the signal to do something different this time, not a reason to repeat what already worked "
		+ "mechanically. Ending: control.despawn (the instant, in-place vanish) is the RECOMMENDED "
		+ "ending here, not a hard requirement - it fits this stage's barely-a-presence feel best, so "
		+ "reach for it by default - but movement.retreat_with_fallback (a real, visible flight into "
		+ "the dark) is also offered as an alternative if a genuine flee reads better for a particular "
		+ "wave, e.g. right after the player closed in unexpectedly fast. Vary between the two rather "
		+ "than defaulting to the same one every single time. sound.ambient_cue is now available at "
		+ "this stage too - lean on it, especially sound.ambient_cue(stare) right when a held stare "
		+ "begins (see the generic STARE-HOLD note above) - a faint noise is exactly the kind of small, "
		+ "unplaced unease this stage is built around, more so than at any later stage where the "
		+ "wendigo has other, bolder tools to lean on instead. sound.breathe is NOT available yet at "
		+ "this stage (unlocks next stage) - too concrete and physically-there a cue for how deniable "
		+ "this very first phase is meant to stay. "
		+ "Still nothing else belongs in the plan: no torches, no traps, no approaching the player, no "
		+ "chase, no destination=above/behind for the teleport/approach actions - those aren't even "
		+ "offered here. Example shapes, pick whichever fits the moment rather than defaulting to the "
		+ "first one every time: spawn unseen, sound.ambient_cue(stare), stare until noticed or "
		+ "approached, control.despawn - OR combat.teleport(destination=in_view), "
		+ "sound.ambient_cue(ambient), a brief posture.stare, sound.ambient_cue(flee), "
		+ "movement.retreat_with_fallback.";

	private static final String STAGE_20_39 =
		"CURRENT STAGE: 20-39%, curious. Enough has happened that outright caution is giving way to "
		+ "curiosity - the wendigo is starting to test the edges of the player's space instead of just "
		+ "appearing and vanishing, though genuine closeness (combat.teleport(destination=behind)) "
		+ "isn't earned until stage 5, so this is about presence, not confrontation. "
		+ "combat.break_torch just unlocked this stage - no further threshold above this one, so use "
		+ "it whenever the prompt's torch counts show something worth targeting, every wave that "
		+ "offers the chance, not as an occasional flourish. sound.ambient_cue(ambient) can accompany the "
		+ "stalking for a low, unplaced presence cue. movement.retreat_with_fallback (a real, visible "
		+ "flight into darkness) is now available as an alternative ending to control.despawn (still "
		+ "allowed below 20%, but redirected to a flee above it) - withdrawing into the dark reads "
		+ "better than vanishing once it's been this bold, and sound.ambient_cue(flee) right "
		+ "beforehand is heavily encouraged to announce the withdrawal. movement.approach_spot's own "
		+ "destination=behind unlocks this stage - a genuine walked approach to the player's blind "
		+ "spot, well ahead of when the instant teleport version of the same idea becomes available "
		+ "(stage 5) - a good opener for this stage's own curious-but-still-cautious feel: creep in "
		+ "unseen, then decide what comes next once actually close, rather than either staying distant "
		+ "or blinking straight into their blind spot. If the player ever closes in close enough on "
		+ "their own, sound.breathe (which just unlocked this stage) right then makes that moment land - "
		+ "get as close as possible first rather than playing it from wherever it happens to already "
		+ "be. The example below is a starting point, not a script - vary the opener, whether it leans "
		+ "on combat.break_torch or a plain reveal, how it withdraws, and let the prompt's own account "
		+ "of past encounters with this player (see the generic note above) push toward doing something "
		+ "different than however the last few waves went, not the same shape every time. Example: "
		+ "movement.approach_spot(destination=behind), "
		+ "combat.break_torch, sound.ambient_cue(flee), movement.retreat_with_fallback before despawning.";

	private static final String STAGE_40_59 =
		"CURRENT STAGE: 40-59%, prey-driven and starting to plan. The wendigo has stopped just "
		+ "reacting to the player's presence and started treating them as something to be hunted "
		+ "deliberately - this is the first stage where it sets real traps instead of just observing. "
		+ "memory.store_dark_location is available for remembering a fallback retreat point before "
		+ "committing to something riskier. A good trap shape (the AMBUSH WAIT pattern - see above): "
		+ "movement.approach_spot(destination=unwatched) to close in a bit, then control.while("
		+ "predicate.player_distance farther_than lunge_distance) - no posture.stare yet, stay dark "
		+ "and unnoticed - to wait for them to close the rest of the gap on their own, THEN "
		+ "posture.stare(enabled=true) (sound.ambient_cue(stare) heavily encouraged right here) "
		+ "immediately followed by combat.lunge_attack once the wait ends, so the reveal and the "
		+ "strike land together instead of staring the whole time it's still waiting - sound.breathe "
		+ "fits right in that same moment too, right as they close the final stretch and before the "
		+ "lunge (see the generic note above). combat.break_torch remains a strong opener wherever the "
		+ "prompt's torch counts are high. combat.teleport's own destination=torch unlocks this stage "
		+ "- an instant relocation right to the nearest live torch, always follow it with something "
		+ "(combat.break_torch, posture.stare, combat.lunge_attack once that's unlocked) rather than "
		+ "leaving it as the whole beat on its own. The AMBUSH WAIT pattern above is the strongest "
		+ "option when it fits, not the only one worth reaching for every wave - combat.break_torch "
		+ "alone, a plain reveal-and-stare, or skipping the ambush entirely all read as legitimate "
		+ "too, and the prompt's own account of past encounters with this player (see the generic note "
		+ "above) should push toward variety wave to wave rather than reproducing the same shape every "
		+ "time. Example: movement.approach_spot(destination=unwatched), "
		+ "wait quietly via a lunge_distance while loop, then stare and lunge once they're close.";

	private static final String STAGE_60_79 =
		"CURRENT STAGE: 60-79%, openly aggressive. Subtlety is mostly gone - the wendigo commits now. "
		+ "combat.lunge_attack (catching the player grabs them - see its own description) is available, "
		+ "and sound.ambient_cue(chase) unlocks alongside it - pair the two for the reveal (heavily "
		+ "encouraged right before the lunge) rather than always retreating the moment the player closes "
		+ "in. combat.chase is not available yet - it's reserved for 80%+ (see next stage), so a lunge "
		+ "is still the sole point of contact here. What does change within this stage: how far from "
		+ "real darkness it's willing to commit to that lunge keeps widening as severity climbs - "
		+ "tightest right at 60%, loosest approaching 80% - so leaning into a lunge from a position "
		+ "that isn't perfectly safe to retreat from afterward reads as more in-character the higher "
		+ "into this range you are, not something to always play cautiously. combat.lunge_attack should "
		+ "almost always be preceded by a posture.stare, whether in the plan body or via a global_rule - "
		+ "it's the payoff for being noticed while staring, not something to trigger with no reveal "
		+ "moment first. A reliable way to guarantee the transition happens the instant it's spotted, "
		+ "regardless of what step the plan was on: a global_rule with condition "
		+ "predicate.player_looking_at_self at whichever band fits and action combat.lunge_attack. "
		+ "Example: movement.approach_spot(destination=unwatched), stalk while undetected, "
		+ "sound.ambient_cue(chase), combat.lunge_attack once close, sound.ambient_cue(flee), "
		+ "movement.retreat_with_fallback afterward. "
		+ "destination=above unlocks this stage for both combat.teleport and movement.approach_spot, "
		+ "alongside movement.drop - three independent primitives, not one combined move, so they mix "
		+ "into whatever plan actually fits: position at destination=above (directly on the ceiling "
		+ "above the player, walked via approach_spot or blinked via teleport), movement.drop to let go "
		+ "and fall, then combat.lunge_attack once close enough to finish the catch - an overhead "
		+ "ambush the player won't see coming from a ground-level glance, worth reaching for "
		+ "specifically when they haven't noticed a ceiling presence at all. combat.teleport(destination"
		+ "=above) specifically is worth opening the plan with when the player's sprinting, straight "
		+ "into posture.stare/sound.breathe/combat.lunge_attack from there instead of climbing up "
		+ "normally. movement.drop isn't reserved for that one scripted setup, though - it's a safe "
		+ "no-op whenever the wendigo happens to already be on the floor, so it can show up anywhere "
		+ "later in a plan too, wherever ordinary climbing has already put it on a wall/ceiling: a "
		+ "posture.stare from wherever it currently is, then movement.drop, then combat.lunge_attack "
		+ "reads just as naturally as the scripted destination=above version, without needing to plan "
		+ "the overhead ambush deliberately from the start. The lunge pattern described above is the "
		+ "strongest option when it fits, not a script to reproduce unchanged every wave - vary the "
		+ "opener (a plain approach, a teleport, a torch break first), how long it stalks before "
		+ "revealing itself, and how it closes afterward, using the prompt's own account of past "
		+ "encounters with this player (see the generic note above) to avoid repeating the same shape "
		+ "wave to wave.";


	private static final String STAGE_80_PLUS =
		"CURRENT STAGE: 80% and up, restless. The wendigo is done pretending to be subtle - it wants "
		+ "direct contact and isn't holding back to get it. combat.chase unlocks here for the first "
		+ "time (it wasn't available at all below 80%) - a sustained pursuit rather than lunge's single "
		+ "commit, chasing the player down until it catches them (grabbing them, same as a lunge does - "
		+ "see combat.lunge_attack) or genuinely can't reach them anymore, and always passively destroys "
		+ "any torch within 10 blocks as it goes - a chase at this stage leaves real wreckage behind "
		+ "it, every time, not just sometimes. Close the distance directly; combat.chase is the "
		+ "expected move once seen, not a rare escalation, and retreating only makes sense after "
		+ "catching them or being genuinely forced to. combat.chase should almost always be preceded by "
		+ "a posture.stare, whether in the plan body or via a global_rule - it's the payoff for being "
		+ "noticed while staring, not something to trigger with no reveal moment first. A reliable way "
		+ "to guarantee the transition happens the instant it's spotted, regardless of what step the "
		+ "plan was on: a global_rule with condition predicate.player_looking_at_self at whichever band "
		+ "fits and action combat.chase. Example: movement.approach_spot(destination=unwatched), stalk "
		+ "and stare, a global_rule turns a spotted stare straight into combat.chase, no explicit ending "
		+ "needed after it - the chase resolves on its own once it can't reach the player anymore. "
		+ "combat.teleport reaches its own peak here too: destination=behind unlocks, the single most "
		+ "dramatic option this ability offers - an instant relocation right into the player's own "
		+ "blind spot, no travel time, no warning, nothing to path around, could land on the floor, a "
		+ "wall, or the ceiling behind them, whichever is actually there. Every other destination type "
		+ "is still available too - above, torch, in_view, eyeline, ahead. Use behind when they've "
		+ "broken line of sight or are about to, right before a posture.stare or combat.chase - the one "
		+ "tool in this creature's kit that closes distance without ever being seen doing it. "
		+ "sound.breathe pairs especially well right after landing destination=behind/above or once "
		+ "movement.drop has closed in - already as close as this stage ever gets, so right before the "
		+ "stare/chase/lunge that follows is exactly the moment for it (see the generic note above). "
		+ "Use torch specifically when the prompt's own torch counts suggest a nearby target and a "
		+ "sudden, exposed appearance right at it fits the moment better than staying hidden. "
		+ "combat.teleport(destination=in_view) is this stage's own boldest reveal option - blink into "
		+ "plain view and let a stare turn straight into combat.chase or combat.lunge_attack, the "
		+ "boldest version of the reveal-then-strike pairing this ability offers at any stage. "
		+ "destination=above + movement.drop (both unlocked since 60%, see that stage's own note) pair "
		+ "naturally with combat.chase at this stage too, not just combat.lunge_attack - drop from "
		+ "directly overhead into a sustained chase instead of a single lunge. And since movement.drop "
		+ "is a safe no-op whenever it's already on the floor, it doesn't have to follow destination="
		+ "above specifically here either - a posture.stare wherever it's currently perched, then "
		+ "movement.drop, then combat.chase works just as well mid-plan as the deliberate overhead-"
		+ "ambush version does. The chase pattern described above is the strongest option when it "
		+ "fits, not a script to run unchanged every wave - vary the opener (a teleport straight to "
		+ "destination=behind/above, a plain approach, a torch break first), how long it stalks before "
		+ "revealing itself, and whether it ends in a lunge or a full chase, using the prompt's own "
		+ "account of past encounters with this player (see the generic note above) to avoid repeating "
		+ "the same shape wave to wave.";


	// Package-private (not private) specifically so a same-package GameTest can build the exact
	// same system prompt/context a real wave request would use, without duplicating this logic -
	// see WendigoConvergenceTests, a standalone (not suite-registered) harness that calls these two
	// plus SchemaBuilder.forSeverity and WendigoMod.llmClient.requestPlan directly to sample the
	// model's real output across many scenarios.
	static String buildSystemPrompt(int severityPercent) {
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
	// The user's own explicit "include context about... was mining something" request - there's no
	// public "is this player mid-swing right now" signal on the server (ServerPlayerGameMode's own
	// isDestroyingBlock/destroyPos tracking is private, no accessor), so this approximates it as
	// "broke a block recently" instead (see MINING_RECENCY_TICKS/recentlyMined below) - close enough
	// for a prompt hint, not a precise mechanical gate. Server tick count at the moment of the last
	// real break, per player - never cleared, just superseded by a later break; recentlyMined reads it
	// relative to "now" rather than needing its own expiry housekeeping.
	private final Map<UUID, Integer> lastBlockBreakTick = new java.util.HashMap<>();

	public WendigoManager(WendigoWaveConfig config, WendigoProgressionTracker progressionTracker, EncounterHistory encounterHistory) {
		this.config = config;
		this.progressionTracker = progressionTracker;
		this.encounterHistory = encounterHistory;
		// The user's own explicit invariant: every cave-scale's own max orbit distance must stay
		// strictly below orbitDespawnDistance(), or a wendigo legitimately orbiting at the outer edge
		// of its own band could trip the "too far, relocate" check in tickOrbitingEntity while doing
		// nothing wrong. Checked here (constructor time), not a static initializer - now that the
		// despawn distance itself is config-driven (WendigoTuningConfig.orbitDespawnDistance), a
		// class-load-time static block could run before WendigoMod.tuningConfig is even set; this
		// still fails loudly, just as soon as this instance is actually constructed (WendigoMod.
		// onInitialize already loads tuningConfig before constructing this), rather than silently
		// letting a misconfigured value drift true.
		double despawnDistance = orbitDespawnDistance();
		for (CaveScale caveScale : CaveScale.values()) {
			double maxOrbitDistance = SemanticBands.orbitMaxDistance(caveScale);
			if (maxOrbitDistance >= despawnDistance) {
				throw new IllegalStateException("orbitMaxDistance(" + caveScale + ") = " + maxOrbitDistance
					+ " must stay below orbitDespawnDistance (" + despawnDistance
					+ ") or a legitimately in-band orbiting wendigo could trip the too-far relocate check");
			}
		}
	}

	// The user's own explicit "too much fluff" request - see WendigoDebug.verboseEnabled's own doc
	// comment and PlanRunner's own matching debugSay gate. This class has no PlanRunner-style single
	// entry point of its own (every call site used to hit WendigoDebug.say directly), so this wrapper
	// exists purely to gate all of them at once without touching each site individually. Deliberately
	// NOT used by applyPreviousEncounterRecap's own AI-recap print - that one calls WendigoDebug.say
	// directly, staying always-on the same way PlanRunner.logPlanStructure does.
	private static void debugSay(ServerLevel level, String message) {
		if (WendigoDebug.verboseEnabled()) {
			WendigoDebug.say(level, message);
		}
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
			// See endEngagementIfTarget's own doc comment - the same "don't linger on someone no longer
			// meaningfully here" reasoning handleTargetDeath already established, extended to leaving
			// the game entirely, not just dying. Run after resolveRiderOnEnd above (let an in-progress
			// ride resolve properly first - by the time this runs, isForcingRide() correctly reads
			// false either way, so this naturally still fires right afterward if there's nothing left
			// to abruptly interrupt).
			endEngagementIfTarget(handler.player, "disconnected");
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
			// Defensive backstop for recentlyDied - if a player somehow rejoins without AFTER_RESPAWN
			// ever having fired for them (dying then disconnecting from the death screen without ever
			// clicking respawn, if that turns out not to auto-respawn them on rejoin the way ordinary
			// vanilla behavior is expected to), this guarantees they can never get PERMANENTLY excluded
			// from being a valid wendigo target just from an edge-case event ordering issue.
			this.progressionTracker.markRespawned(handler.player.getUUID());
		});
		// See lastBlockBreakTick's own field comment - fires on the logical server for any real block
		// break regardless of cause (survival mining, creative instant-break, a tool with efficiency,
		// etc.), which is exactly "was mining something" in the broad sense the prompt context needs.
		PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
			if (player instanceof ServerPlayer serverPlayer) {
				this.lastBlockBreakTick.put(serverPlayer.getUUID(), serverPlayer.level().getServer().getTickCount());
			}
		});
		// The user's own explicit death-handling request, plus a real underlying reference-staleness
		// bug it surfaced: "after I died and came back... he kept on respawning and restarting a
		// chase" - root cause, confirmed via decompile, PlayerList.respawn always hands back a
		// genuinely NEW ServerPlayer instance (never the same object with health just reset), so
		// state.lockedTarget (and WendigoEntity's own separate lockedTarget field - two independent
		// references that both need to follow the same real player) silently went stale the instant
		// anyone died and respawned, with nothing to ever refresh either one. Additionally drives the
		// "hand off to the next eligible player, or disappear" behavior - see handleTargetDeath's own
		// doc comment. Never vetoes death itself (always returns true). ServerLivingEntityEvents (not
		// the player-specific ServerPlayerEvents.ALLOW_DEATH, deprecated in favor of exactly this) -
		// confirmed via javap against the real fabric-entity-events-v1 jar.
		ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
			if (entity instanceof ServerPlayer player) {
				handleTargetDeath(player);
			}
			return true;
		});
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			this.progressionTracker.markRespawned(oldPlayer.getUUID());
			for (WaveState state : this.waves.values()) {
				if (state.lockedTarget == oldPlayer) {
					state.lockedTarget = newPlayer;
				}
				if (state.entity != null && state.entity.getLockedTarget() == oldPlayer) {
					state.entity.setLockedTarget(newPlayer);
				}
			}
		});
	}

	/**
	 * ServerLivingEntityEvents.ALLOW_DEATH reaction - the user's own explicit request: "after a
	 * target dies the wendigo should either pick the next player in the group to start a run on with
	 * their stage or disappear if there's no one else in the group." markDied first (see its own
	 * field comment for why - selectTarget needs this exclusion, since a dying player stays in
	 * level.players() and stays nominally "resumable"/"under y=0" through the death screen, unlike a
	 * disconnect), then the actual shared engagement-ending logic - see endEngagementIfTarget's own
	 * doc comment for the rest.
	 */
	private void handleTargetDeath(ServerPlayer player) {
		this.progressionTracker.markDied(player);
		endEngagementIfTarget(player, "died");
	}

	/**
	 * Shared by handleTargetDeath and the DISCONNECT handler right above - same "the wendigo shouldn't
	 * keep lingering on someone who's no longer meaningfully here" reasoning applies to a player
	 * leaving the game entirely, not just dying. Real, closely related gap this closes: the orbit-only
	 * case (WendigoEntity.isOrbitTargetLost, checked every tick via tickOrbitingEntity) already
	 * reacted to a disconnected target reasonably promptly on its own (Targeting.nearestPlayer simply
	 * stops finding them, since a disconnected player isn't in level.players() at all) - but a MID-PLAN
	 * wave had no equivalent: checkForcedWaveEnd's own target.getY()/distance checks read whatever
	 * position the now-removed player entity was last at, which doesn't change on disconnect, so
	 * neither check would ever fire - a disconnected mid-plan target could otherwise linger for the
	 * full waveTimeoutTicks hard backstop (4 real minutes) before anything noticed at all. No
	 * recentlyDied-style exclusion needed for THIS case specifically - a disconnected player is
	 * already absent from level.players() entirely, so selectTarget can't re-pick them regardless.
	 * <p>
	 * A no-op for anyone who isn't the CURRENT wave's own locked target on their level (someone else
	 * leaving, or a level with no active wave at all, changes nothing here) - and a no-op while a
	 * forced ride is already in progress on them (see beginForcedRide/resolveRiderOnEnd's own
	 * carry-to-despawn-point flow, called separately, right before this for the disconnect case - that
	 * has its own resolution and shouldn't be abruptly short-circuited mid-carry).
	 * <p>
	 * Ends the current engagement outright (discard, clear entity/context/lockedTarget/stage) - same
	 * shape as the "runJustEnded" branch in tickLevel, except the player's own ActiveRun in
	 * WendigoProgressionTracker is deliberately left completely untouched (this isn't a completed or
	 * abandoned run, just a paused one, same philosophy as the drowning/fire/lush-caves pauses
	 * elsewhere in this class - it'll resume automatically, from wherever it left off, the next time
	 * they're eligible - including simply reconnecting and going back into darkness). No cooldown set
	 * here, so the very next tryEnterOrbit call - already running on its own short throttle - genuinely
	 * finds the NEXT eligible player in the group if one exists, or comes up empty and stays
	 * disappeared if not.
	 */
	private void endEngagementIfTarget(ServerPlayer player, String reason) {
		WaveState state = this.waves.get(player.level());
		if (state == null || state.lockedTarget != player || (state.entity != null && state.entity.isForcingRide())) {
			return;
		}
		debugSay(player.level(), player.getGameProfile().name() + " " + reason
			+ " - ending this engagement (hand off to another eligible group member, or disappear if none)");
		if (state.entity != null && state.entity.isAlive()) {
			saveStage5HealthIfApplicable(state);
			state.entity.discard();
		}
		state.entity = null;
		state.context = null;
		state.lockedTarget = null;
		state.stage = 0;
	}

	// How recently a real block break still counts as "was mining something" for prompt context
	// purposes - long enough to span the gap between the actual swing and whenever a wave-start
	// request happens to fire, short enough that it still reads as "just now" rather than some
	// unrelated break minutes ago.
	private static final int MINING_RECENCY_TICKS = 100; // 5s

	private boolean recentlyMined(ServerPlayer player, int nowTick) {
		Integer lastBreak = this.lastBlockBreakTick.get(player.getUUID());
		return lastBreak != null && nowTick - lastBreak <= MINING_RECENCY_TICKS;
	}

	private void onEndServerTick(MinecraftServer server) {
		tickSoulLightParticles(server);
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

	// The user's own explicit reference command (revised once, to sit a little below eyeline): "/execute
	// as @a at @s run particle minecraft:soul_fire_flame ~ ~0.5 ~4 0.25 0.4 0.25 0.01 10 normal", every
	// 5 ticks - a real, always-on environmental effect (not gated on an active wendigo encounter at
	// all - any player standing in a soul light's own safe zone gets it, engagement or not), so this
	// lives in the same unconditional onEndServerTick every level already ticks through, not inside
	// tickLevel's own per-wave logic.
	private static final int SOUL_LIGHT_PARTICLE_INTERVAL_TICKS = 5;
	private int soulLightParticleTickCounter;

	private void tickSoulLightParticles(MinecraftServer server) {
		this.soulLightParticleTickCounter++;
		if (this.soulLightParticleTickCounter < SOUL_LIGHT_PARTICLE_INTERVAL_TICKS) {
			return;
		}
		this.soulLightParticleTickCounter = 0;
		for (ServerLevel level : server.getAllLevels()) {
			if (!level.tickRateManager().runsNormally()) {
				continue;
			}
			for (ServerPlayer player : level.players()) {
				if (player.isSpectator() || !SoulLightScanner.isNearSoulLight(level, player.blockPosition())) {
					continue;
				}
				// "~ ~0.5 ~4" (the command's own reference position) - a little below eyeline, not
				// straight above the player's own feet anymore.
				level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, player.getX(), player.getY() + 0.5, player.getZ(),
					2, 0.25, 0.4, 0.25, 0.01);
			}
		}
	}

	// Point-blank, sustained a long time - real combat/close holds can legitimately last a while, so
	// this tolerance is generous; it's a backstop against a genuinely stuck state, not a normal-play trigger.
	private static final double EXTREME_PROXIMITY_DISTANCE = 5.0;
	private static final int EXTREME_PROXIMITY_GIVEUP_TICKS = 60; // 20s sustained
	// Flat performance cap on how far an orbiting wendigo is allowed to be from its target before
	// relocating straight back inside the orbit band instead of continuing to tick/pathfind toward it
	// from very far away - see tickOrbitingEntity. Comfortably beyond MASSIVE's own 30-block max orbit
	// distance so it never fights ordinary orbiting. User's own explicit "bump it up to like 64" call
	// (was 40) - both usages (tickOrbitingEntity, checkForcedWaveEnd's mid-plan case) check distance
	// directly against an already-tracked target (state.lockedTarget), never through a
	// Targeting.nearestPlayer-style radius-limited search, so this being equal to
	// SemanticBands.NEAREST_PLAYER_RADIUS (64) rather than comfortably under it doesn't create any real
	// boundary conflict the way it might if either check depended on that search radius to even find
	// the target in the first place. See WendigoTuningConfig.orbitDespawnDistance, editable at
	// config/wendigo-tuning.json - read via orbitDespawnDistance() below, not a plain constant anymore,
	// since the invariant check the constant used to back (see the constructor) now has to run AFTER
	// config load, not at class-load time.
	private static double orbitDespawnDistance() {
		return WendigoMod.tuningConfig.orbitDespawnDistance;
	}
	// Throttles tryEnterOrbit's own dark-spot search while entity == null - see WaveState.nextRespawnSearchTick.
	private static final int ORBIT_SPAWN_SEARCH_INTERVAL_TICKS = 20; // ~1s

	/** The user's own explicit stage-5 request: the lower his health, the sooner the NEXT LLM plan
	 * call is allowed to fire - a dying wendigo gets more desperate/aggressive instead of pacing
	 * itself the same as a full-health one. Purely how long the gap BETWEEN calls is (this fires
	 * once a wave has already fully ended - see its own call site in tickLevel, right where the
	 * ordinary dynamicCooldownTicks value gets set - never while a plan is actively running, the
	 * user's own explicit clarification), not a call frequency independent of the normal cooldown
	 * system: still starts from the exact same config.dynamicCooldownTicks(severityPercent) baseline
	 * every other stage already uses, just scaled down further for stage 5 specifically. Four flat
	 * brackets, not a continuous scale - the user's own explicit "3/4 health -> normal, 1/2-3/4 ->
	 * 3/4 interval, 1/4-1/2 -> 1/2 interval, 0-1/4 -> 1/4 interval" spec. No-ops (returns the plain
	 * baseline unchanged) for every stage but 5, and if there's no live entity to read health from
	 * (defensive only - state.entity is always non-null at this method's own real call site). */
	private int stage5HealthScaledCooldownTicks(WaveState state, int severityPercent) {
		int base = this.config.dynamicCooldownTicks(severityPercent);
		if (state.stage != 5 || state.entity == null || state.entity.getMaxHealth() <= 0.0F) {
			return base;
		}
		float healthFraction = state.entity.getHealth() / state.entity.getMaxHealth();
		double multiplier;
		if (healthFraction >= 0.75F) {
			multiplier = 1.0;
		} else if (healthFraction >= 0.5F) {
			multiplier = 0.75;
		} else if (healthFraction >= 0.25F) {
			multiplier = 0.5;
		} else {
			multiplier = 0.25;
		}
		return (int) Math.round(base * multiplier);
	}

	private void tickLevel(ServerLevel level, WaveState state) {
		int now = level.getServer().getTickCount();
		updateStage5BossBar(state);
		// Also independent of wave/entity state - applies to every player in the level, not just
		// whoever this level's own single WaveState currently has locked, so it can't reuse
		// state.lockedTarget/state.context the way most of this method does.
		checkSoulLightAchievement(level, now);

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
			debugSay(level, "wendigo out of air (about to take drowning damage) - discarding, will search for a new valid spawn area");
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
			debugSay(level, "wendigo took fire damage - discarding, will search for a new valid spawn area");
			saveStage5HealthIfApplicable(state);
			state.entity.discard();
			state.entity = null;
			state.context = null;
			return;
		}

		// Same "this physical state is wrong, bail out" priority as the two checks above - the user's
		// own explicit "runs pause when the player is in lush caves biome, meaning the wendigo despawns
		// and does not try to continue his run" request. lockedTarget/stage deliberately left intact
		// (same as drowning/fire damage above) so the run resumes the instant they leave - and, unlike
		// completeRun/endStage5Hunt, nothing here ever touches the progressionTracker's own eligibility
		// timer or active-run state, so this is a genuine pause, not a reset. tryEnterOrbit's own resume
		// path is ALSO gated on the same isInLushCaves check (see its own call site) so this doesn't
		// just immediately respawn right back the next throttled attempt.
		if (state.entity != null && state.entity.isAlive() && state.lockedTarget != null
				&& isInLushCaves(level, state.lockedTarget)) {
			debugSay(level, "target entered lush caves - discarding, run paused until they leave (eligibility timer untouched)");
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
					debugSay(level, "wave force-ended (" + forcedEndReason + ") after " + elapsedTicks
						+ " ticks at " + state.entity.blockPosition().toShortString()
						+ " - relocating back into orbit - outcome: " + state.entity.getOutcome());
				} else {
					debugSay(level, "wave complete after " + elapsedTicks + " ticks at "
						+ state.entity.blockPosition().toShortString() + " - returning to orbit - outcome: " + state.entity.getOutcome());
				}
				// Always-on (not debugSay/verbose-gated) - the user's own explicit "list what tasks have
				// been completed per run in the debug, non-verbose mode" request, same "always show this"
				// treatment plan structure/the AI's own recap already get, right below. Cave type
				// (TIGHT/NORMAL/MASSIVE) is a later, separate always-on addition to the same line - the
				// user's own explicit "tell me what type of cave I'm in" ask.
				String caveScaleLabel = state.context != null ? state.context.caveScale().toString() : "unknown";
				WendigoDebug.say(level, "tasks completed this run: " + state.entity.getOutcome().describeCompletedTasks()
					+ " | cave type: " + caveScaleLabel);
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
				if (!state.debugForced && state.context != null) {
					var outcome = state.entity.getOutcome();
					this.encounterHistory.record(state.context.player(), outcome, now, forcedEndReason);
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
							// Group-wide demotion to stage 2, not "no completedRuns change" - see
							// endStage5Hunt's own doc comment for the redesign this is part of.
							this.progressionTracker.endStage5Hunt(level, progressPlayer);
							runJustEnded = true;
							debugSay(level, progressPlayer.getGameProfile().name()
								+ " killed the stage-5 wendigo - hunt over, group demoted to stage 2, "
								+ "eligibility timer restarting");
							// The user's own explicit "It lurks..." advancement chain - "killing a wendigo in
							// stage 6" per their own wording, but stage 6 doesn't exist (WendigoProgressionTracker.
							// stageFor caps at 5, "6+ -> stage 5 (permanent)") - granted here, the one real,
							// confirmed "genuinely killed, not despawned" branch, at the actual max stage.
							WendigoAdvancements.grant(progressPlayer, WendigoAdvancements.STAGE5_KILL);
						}
					} else {
						int progressAmount = switch (state.stage) {
							case 1 -> outcome.successfulStareCount();
							case 2 -> outcome.successfulStareCount() + outcome.torchBreakCount();
							case 3 -> outcome.lungeAttemptCount();
							case 4 -> outcome.grabbedSuccessfully() ? 1 : 0;
							default -> 0;
						};
						if (progressAmount > 0) {
							this.progressionTracker.addProgress(progressPlayer, progressAmount);
						}
						// Stage 1's own second axis - "4 stares AND 4 noises" (see
						// WendigoProgressionTracker.isGoalMet) - every sound.ambient_cue that actually
						// played this wave, independent of the stare count above.
						if (state.stage == 1 && outcome.soundCueCount() > 0) {
							this.progressionTracker.addSecondaryProgress(progressPlayer, outcome.soundCueCount());
						}
						// Every stage 1-4's own third axis (this whole branch is already stages 1-4 only,
						// see the enclosing else) - "1 successful breathe" (see
						// WendigoProgressionTracker.isGoalMet), independent of whatever else this stage's
						// own progressAmount above already tracks.
						if (outcome.successfulBreatheCount() > 0) {
							this.progressionTracker.addBreatheProgress(progressPlayer, outcome.successfulBreatheCount());
						}
						// The soul-light progression redesign - see WendigoProgressionTracker
						// .resolveRunOutcome's own doc comment. Fed regardless of whether isGoalMet is
						// about to fire this exact tick - the tally needs to accumulate across every wave
						// the run takes, not just the last one.
						if (outcome.tasksNearSoulLight() > 0 || outcome.tasksNotNearSoulLight() > 0) {
							this.progressionTracker.addSoulLightTally(progressPlayer,
								outcome.tasksNearSoulLight(), outcome.tasksNotNearSoulLight());
						}
						if (this.progressionTracker.isGoalMet(progressPlayer)) {
							boolean favorable = this.progressionTracker.resolveRunOutcome(level, progressPlayer);
							runJustEnded = true;
							debugSay(level, progressPlayer.getGameProfile().name() + " completed stage "
								+ state.stage + "'s goal - run "
								+ (favorable ? "advancing" : "REGRESSING (spent too much time near soul light)")
								+ " - eligibility timer restarting");
							// The user's own explicit stage-completion advancement chain - a genuine forward
							// advance only, not a soul-light regression (that isn't "completing" the stage).
							if (favorable) {
								WendigoAdvancements.grant(progressPlayer, WendigoAdvancements.stageComplete(state.stage));
							}
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
				state.cooldownUntilTick = now + (state.debugForced ? this.config.debugCooldownTicks
					: stage5HealthScaledCooldownTicks(state, severityPercent));
				state.debugForced = false;
				if (runJustEnded) {
					// The run is genuinely, permanently over (completeRun/endStage5Hunt just fired) -
					// the SPAWN cue's own despawn-side bookend, then a clean break: force-discard a
					// still-alive entity right here rather than letting it keep orbiting under a
					// now-stale state.stage, and clear lockedTarget/stage so the NEXT tryEnterOrbit call
					// goes through selectTarget's own fresh-eligibility check instead of treating this
					// same, no-longer-active player as still locked in (which would bypass the 2000-tick
					// timer completeRun/endStage5Hunt just restarted).
					WendigoSounds.play(level, state.entity, WendigoSounds.Type.SPAWN);
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
				} else if (state.entity.getOutcome().grabbedSuccessfully()) {
					// A grab landed this encounter - the drop already happened wherever PlanRunner's own
					// carry-flee sequence ended up (see PlanRunner.startCarryFlee/resolveRiderOnEnd), but
					// per the user's own two-spot design that location shouldn't double as the resume
					// point too - walk to a SECOND, distinct dark spot first (see startReturnToOrbit),
					// only entering orbit once there (or once giving up trying).
					ServerPlayer target = state.lockedTarget != null && state.lockedTarget.isAlive() ? state.lockedTarget : null;
					BlockPos selfPos = state.entity.blockPosition();
					CaveScale returnCaveScale = CaveScaleScanner.classify(level, selfPos);
					// A WALK, not a teleport (startReturnToOrbit calls the ordinary moveTo(x,y,z,speed)
					// internally, no snap) - verify each candidate via a real path from the entity's own
					// actual current position, same SemanticBands.isAcceptableOrbitPath judgment every
					// other orbit-position search now uses, same bounded retry budget.
					BlockPos returnSpot = null;
					for (int attempt = 0; target != null && attempt < WendigoMod.tuningConfig.orbitReachRetryAttempts; attempt++) {
						BlockPos candidate = findNearbyBandDarkSpot(level, target, returnCaveScale);
						if (candidate == null) {
							continue;
						}
						// distance=1, not 0 - see PlanRunner.findReachableOrbitPath's own comment: a raw
						// BlockPos target (unlike pathToTarget's entity-target convention) needs
						// distance=1 to reliably resolve canReach()=true for legitimately close
						// candidates, confirmed live via a GameTest failure on a trivially-reachable
						// same-room candidate.
						Path path = state.entity.getNavigation().createPath(candidate, 1);
						if (SemanticBands.isAcceptableOrbitPath(path, returnCaveScale)) {
							returnSpot = candidate;
							break;
						}
					}
					if (returnSpot == null) {
						returnSpot = findNearbyDarkSpotFallback(level, selfPos, target, returnCaveScale);
					}
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
			} else {
				// Still genuinely running (not force-ended, not complete) - see if the plan itself
				// asked for a fresh sub-plan (control.re_evaluate). requestPending doubles as the guard
				// here, same flag tryEnterOrbit already checks, so this can't race a spawn/engagement
				// attempt trying to start on the same wave slot at the same time.
				if (!state.requestPending && state.entity.isReEvaluateRequested()) {
					beginReEvaluate(level, state, now);
				}
				if (WendigoDebug.anyEnabled() && state.context != null) {
					if (now % this.config.debugContextIntervalTicks == 0) {
						logContextSnapshot(level, state, now);
					}
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
			debugSay(level, "orbiting wendigo lost its target entirely - discarding, will search for a new one");
			saveStage5HealthIfApplicable(state);
			entity.discard();
			state.entity = null;
			state.context = null;
			state.lockedTarget = null;
			state.stage = 0;
			return;
		}
		if (entity.isOrbitTrapped()) {
			debugSay(level, "orbiting wendigo stuck trying to reach its waypoint - relocating");
			relocateOrDiscard(level, state, now);
			return;
		}
		ServerPlayer lockedTarget = state.lockedTarget;
		double despawnDistance = orbitDespawnDistance();
		if (lockedTarget != null && lockedTarget.isAlive() && entity.distanceTo(lockedTarget) > despawnDistance) {
			// Performance cap, not a normal orbit-band condition - comfortably beyond even MASSIVE's
			// own max orbit distance (see the invariant check in the constructor), so this never
			// fights ordinary in-band/reposition orbiting; it only fires when something (a long chase,
			// a relocate, the player teleporting/fast-traveling) has left the wendigo tracking a
			// target from genuinely far away. Relocates straight back inside the normal orbit band
			// instead of a full discard-and-research cycle - the user's own explicit request, same
			// TELEPORT-not-walk reasoning relocateOrDiscard already uses for the trapped case (see its
			// own doc comment for why it seeds the search from the target's position rather than the
			// entity's own all-the-way-out-here one).
			debugSay(level, "orbiting wendigo too far from its target (" + despawnDistance
				+ "+ blocks, performance cap) - relocating back inside orbit range");
			relocateOrDiscard(level, state, now);
			return;
		}
		if (checkOrbitTooClose(level, state, now)) {
			return;
		}
		if (checkOrbitExposure(level, state, now)) {
			return;
		}
		if (state.requestPending || now < state.cooldownUntilTick) {
			return;
		}
		ServerPlayer target = state.lockedTarget;
		if (target == null || !target.isAlive()) {
			return; // shouldn't happen (isOrbitTargetLost would have already caught it) - defensive only
		}
		// Soul light no longer blocks engagement at all - the user's own explicit redesign: it's a
		// "mask," not a wall. A plan runs exactly the same near soul light as anywhere else now; only
		// whether each completed task counts FOR or AGAINST the run's own stage progression changes
		// (see PlanRunner.recordSoulLightTally/WendigoProgressionTracker.resolveRunOutcome).
		beginEngagement(level, state, target, this.progressionTracker.representativePercent(state.stage));
	}

	// Below this percent, a player getting too close while orbiting just spooks the wendigo off
	// (real discard + re-search) rather than provoking a lunge - matches DarknessOverstayTracker's
	// own AMBUSH_MIN_PERCENT-style tiering philosophy, this specific number given directly by the user.
	private static final int ORBIT_TOO_CLOSE_LUNGE_MIN_PERCENT = 40;
	// Flat, not cave-scaled or fraction-of-band - the user's own explicit call after the original
	// "lower quarter of the current band" version was landing too close to (or even inside) typical
	// spawn distance and reacting the instant a fresh orbit spawn's first tick ran. Must stay strictly
	// below SemanticBands.orbitMinDistance's own smallest value (TIGHT, currently 8.0, after the same
	// "push in the orbit distances a little bit" pass that also pulled this down from 6.0) so the
	// wendigo can act directly from orbit without walking anywhere first - real playtesting found a
	// TIGHT-cave ceiling vantage point that legitimately cleared the (then-8-block) orbit-hold floor
	// (tickOrbit accepts it, settles there) could still sit under an earlier flat 10 here, discarding
	// and respawning the entity in an infinite loop the instant it settled onto exactly the position
	// orbit itself had just chosen as acceptable. 5.0 still leaves a real margin below TIGHT's own
	// floor rather than merely ducking under it by a hair.
	private static final double ORBIT_TOO_CLOSE_DISTANCE = 5.0;

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
			debugSay(level, closest.getGameProfile().name() + " got too close while orbiting ("
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
		debugSay(level, closest.getGameProfile().name() + " got too close while orbiting ("
			+ percent + "%) - lunging");
		overrideIntoLunge(level, closest);
		return true;
	}

	// Same fastest tier PlanRunner's own LUNGE_CHASE_SPEED_MULTIPLIER uses (SemanticBands.
	// speedMultiplier("fast") = 1.75) - hardcoded rather than reached for directly since SemanticBands
	// is package-private to com.wendigo.plan. A reposition attempt trying to break light exposure
	// should move with real urgency, not an ordinary stalking pace.
	private static final double LIGHT_EXPOSURE_REPOSITION_SPEED_MULTIPLIER = 1.75;
	// How often (real ticks) checkOrbitExposure re-issues the reposition-away moveTo while STILL stuck
	// in light, not just once when exposure first begins - the user's own explicit "he actively avoids
	// being in light... he should pathfind out of the light" request now that stages 2-5 never despawn
	// as a backstop (see checkOrbitExposure's own doc comment): without a retry, a single failed/stuck
	// attempt (nothing unwatched-and-dark found yet, or the path got interrupted) would otherwise just
	// sit there doing nothing for the rest of a potentially-unbounded exposure. 1s - frequent enough to
	// read as genuinely trying, not so frequent it thrashes a moveTo that's already in progress.
	private static final int LIGHT_REPOSITION_RETRY_INTERVAL_TICKS = 20;

	// The user's own explicit "despawn after 3 seconds of dead stare" number, stage 1 only - a real,
	// fixed grace window, independent of the light trigger's own now stage-1-exclusive instant despawn
	// (see checkOrbitExposure's own doc comment for why the two triggers stay separate).
	private static final int DEAD_STARE_DESPAWN_TICKS = 60; // 3s

	/** Generalizes what used to be a stage-1-only reaction (checkStage1Exposure) to every stage - the
	 * user's own explicit request: while orbiting, the wendigo actively avoids standing somewhere too
	 * lit (light above DarkSpotScanner.MAX_DARK_LIGHT, the same bar for every stage), REGARDLESS of
	 * stage. The instant exposure starts, it tries to reposition to the nearest dark, unwatched spot
	 * (findUnwatchedPosition3D) - and, unlike the original version of this, keeps retrying that
	 * reposition every LIGHT_REPOSITION_RETRY_INTERVAL_TICKS for as long as it's still stuck in light,
	 * not just once when exposure first began - the user's own explicit "he actively avoids being in
	 * light... he should pathfind out of the light" request. Only STAGE 1 actually despawns over this -
	 * the user's own explicit "stage 1 is just the only one that despawns if in light" correction: every
	 * other stage just keeps trying to path out indefinitely, with no despawn backstop for light alone.
	 * <p>
	 * ALSO checks a SEPARATE, second exposure trigger - stage 1 only - when ANY player is dead-staring
	 * at it right now (PlanPredicates.isDeadStare): the user's own "bring back" request for a mechanic
	 * an earlier version of this (back when it was still stage-1-only, named checkStage1Exposure)
	 * apparently had, dropped when this method was generalized to every stage on the (correct, at the
	 * time) reasoning that the generalized version was meant to be entirely about time spent in light.
	 * Tries the exact same reposition-first move as the light trigger, but on its OWN counter
	 * (orbitDeadStareTicks, not orbitExposedTicks) and its OWN fixed tolerance
	 * (DEAD_STARE_DESPAWN_TICKS, 3 real seconds) - the user's own explicit correction once the first
	 * version of this despawned too fast: "we need the despawn to happen after 3 seconds of trying to
	 * reposition while staring." Mutually exclusive per tick with the light trigger (checked only once
	 * light itself isn't the reason) rather than double-counted together - being "in light" and
	 * "dead-stared" are different exposure reasons worth their own independent grace windows, not a
	 * combined one. This is a real guarantee, distinct from (and stronger than) whatever a currently-
	 * running plan's own dead_stare reaction might already be doing (e.g. the model choosing
	 * movement.retreat_with_fallback as its own stare-hold ending) - findUnwatchedPosition3D's own
	 * unwatched-spot bias already keeps the reposition attempt from walking somewhere still in view
	 * anyway, so a genuinely sustained stare still ends in a real despawn either way once the grace
	 * window runs out.
	 * <p>
	 * Either trigger, once it actually despawns, does so right where the wendigo stands - a cosmetic
	 * discard, same shape as checkOrbitTooClose's own below-lunge-threshold case above: the run itself
	 * isn't over, lockedTarget/stage are left alone, tryEnterOrbit just finds this same player a fresh
	 * spot to appear from next. No sound cue accompanies either despawn - deliberately, the user's own
	 * explicit "no sound this time for stage 1" instruction when the stricter stage-1 light threshold
	 * was added, kept consistent for the dead-stare trigger too. */
	private boolean checkOrbitExposure(ServerLevel level, WaveState state, int now) {
		WendigoEntity entity = state.entity;
		ServerPlayer target = state.lockedTarget;
		if (target == null || !target.isAlive()) {
			state.orbitExposedTicks = 0;
			state.orbitDeadStareTicks = 0;
			return false;
		}
		// Same light bar for every stage, including stage 1.
		boolean tooLit = level.getMaxLocalRawBrightness(entity.blockPosition()) > DarkSpotScanner.MAX_DARK_LIGHT;
		if (tooLit) {
			state.orbitDeadStareTicks = 0; // a different exposure reason - not simultaneously counting
			if (state.orbitExposedTicks % LIGHT_REPOSITION_RETRY_INTERVAL_TICKS == 0) {
				repositionAwayFromOrbitExposure(level, entity, target);
			}
			state.orbitExposedTicks++;
			// Stage 1 only - the user's own explicit "stage 1 is just the only one that despawns if in
			// light" correction. Every other stage just keeps retrying the reposition above forever.
			if (state.stage != 1) {
				return false;
			}
			despawnFromOrbitExposure(level, state, entity,
				"in light for " + (state.orbitExposedTicks / 20.0) + "s straight");
			return true;
		}
		state.orbitExposedTicks = 0;

		boolean deadStared = state.stage == 1 && PlanPredicates.isDeadStare(entity);
		if (!deadStared) {
			state.orbitDeadStareTicks = 0;
			return false;
		}
		if (state.orbitDeadStareTicks == 0) {
			repositionAwayFromOrbitExposure(level, entity, target);
		}
		state.orbitDeadStareTicks++;
		if (state.orbitDeadStareTicks <= DEAD_STARE_DESPAWN_TICKS) {
			return false;
		}
		despawnFromOrbitExposure(level, state, entity,
			"dead-stared for " + (state.orbitDeadStareTicks / 20.0) + "s straight");
		return true;
	}

	/** Shared first-response for either checkOrbitExposure trigger - "should always try and path out
	 * of light/being seen, starting the instant exposure begins" (the user's own explicit requirement
	 * for the light trigger, now shared by the dead-stare one too). */
	private static void repositionAwayFromOrbitExposure(ServerLevel level, WendigoEntity entity, ServerPlayer target) {
		CaveScale caveScale = CaveScaleScanner.classify(level, target.blockPosition());
		BlockPos hideSpot = DarkSpotScanner.findUnwatchedPosition3D(level, target,
			SemanticBands.actionSearchMinDistance(caveScale), WendigoMod.tuningConfig.orbitDespawnDistance);
		if (hideSpot != null) {
			entity.getNavigation().moveTo(hideSpot.getX() + 0.5, hideSpot.getY(), hideSpot.getZ() + 0.5,
				LIGHT_EXPOSURE_REPOSITION_SPEED_MULTIPLIER);
		}
	}

	/** Shared give-up path for either checkOrbitExposure trigger, once its own tolerance runs out. */
	private void despawnFromOrbitExposure(ServerLevel level, WaveState state, WendigoEntity entity, String reasonDetail) {
		debugSay(level, "stage " + state.stage + " wendigo was " + reasonDetail
			+ " while orbiting - despawning outright");
		// Real bug, live-reported: this was the one cosmetic discard path in this whole class that
		// never called this - every other one (drowning, fire damage, lush caves, checkOrbitTooClose,
		// relocateOrDiscard, etc.) already does. Before the light trigger became stage-1-only (see this
		// method's own caller), a stage-5 wendigo COULD still reach this exact path via sustained light
		// exposure, silently healing back to full on its next appearance - the user's own live "place a
		// light on him... he despawns, then respawns at 100% again" report. Kept here regardless of that
		// fix (this path is now only ever reached at stage 1, where it's a no-op) as basic defensive
		// correctness - every discard in this class should behave the same way.
		saveStage5HealthIfApplicable(state);
		state.avoidSpawnPos = entity.blockPosition();
		entity.discard();
		state.entity = null;
		state.context = null;
		state.orbitExposedTicks = 0;
		state.orbitDeadStareTicks = 0;
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
	 * making it back to the target's vicinity at all. Tries up to orbitReachRetryAttempts fresh
	 * candidates, each verified with a real pathfind (SemanticBands.isAcceptableOrbitPath) before
	 * accepting one - the user's own explicit "if he can't reach the player in a spot he
	 * spawns/teleports to, try again" request; previously this only tried once before committing
	 * regardless. Falls back to findNearbyDarkSpotFallback (plain away-from-target darkest-spot
	 * search, no reachability guarantee) only once that budget is exhausted, and to real discard
	 * (entering the no-entity search state) if genuinely nothing dark is reachable at all - see the
	 * user's own "if it genuinely cannot find a dark place, it despawns and keeps searching" rule. */
	private void relocateOrDiscard(ServerLevel level, WaveState state, int now) {
		WendigoEntity entity = state.entity;
		ServerPlayer target = state.lockedTarget != null && state.lockedTarget.isAlive() ? state.lockedTarget : null;
		BlockPos seedPos = target != null ? target.blockPosition() : entity.blockPosition();
		CaveScale caveScale = CaveScaleScanner.classify(level, seedPos);

		BlockPos spot = null;
		for (int attempt = 0; target != null && attempt < WendigoMod.tuningConfig.orbitReachRetryAttempts; attempt++) {
			BlockPos candidate = findNearbyBandDarkSpot(level, target, caveScale);
			if (candidate == null) {
				continue;
			}
			entity.snapTo(candidate.getX() + 0.5, candidate.getY(), candidate.getZ() + 0.5, entity.getYRot(), 0f);
			entity.syncPoseToSpawnPosition();
			entity.nudgeTowardAttachedSurface(Direction.UP);
			if (SemanticBands.isAcceptableOrbitPath(pathToTarget(entity, target), caveScale)) {
				spot = candidate;
				break;
			}
		}
		if (spot == null) {
			spot = findNearbyDarkSpotFallback(level, seedPos, target, caveScale);
		}
		if (spot == null) {
			debugSay(level, "nowhere dark reachable to relocate to - discarding, will keep searching for a new spawn spot");
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

	// Duplicated from ModEntities' own FabricDefaultAttributeRegistry.register call (EnderMan.
	// createAttributes().add(Attributes.MAX_HEALTH, 50.0)) rather than reached for via an entity
	// instance - see updateStage5BossBar's own doc comment for why this needs a max-health value even
	// while no entity currently exists. Same small-constant-duplication tradeoff this codebase already
	// accepts elsewhere (e.g. SemanticBands.DARKNESS_LIGHT_THRESHOLD vs DarkSpotScanner's own cutoff).
	private static final float WENDIGO_MAX_HEALTH = 50.0F;

	/** Creates/updates/removes state.stage5BossBar to match whether a stage-5 hunt is currently active
	 * for state.lockedTarget - the user's own explicit "the health bar should be present for a WHOLE
	 * stage 5 run" correction: this used to also require state.entity != null && isAlive(), so the bar
	 * vanished during every ordinary cosmetic gap between appearances (light/stare exposure, too-close,
	 * stuck/trapped relocation, etc. - none of which end the hunt itself, see checkOrbitExposure/
	 * checkOrbitTooClose/relocateOrDiscard), reappearing only once a fresh entity spawned back in - read
	 * as the bar itself "going away" rather than a real gap in the fight. state.stage is what actually
	 * tracks "is a stage-5 hunt in progress" (set once at spawn, left alone by every cosmetic discard,
	 * only ever reset to 0 by a genuine hunt end - see endStage5Hunt/tickLevel's own runJustEnded
	 * branch), independent of whether state.entity currently exists - so this now keys off that instead.
	 * Progress reads the entity's own live health while one exists and is alive, falling back to the
	 * PERSISTED health (progressionTracker.stage5HealthOf - the same value tryEnterOrbit's own stage-5
	 * spawn restores from) during a gap, so the bar reflects reality throughout, not just while a real
	 * entity happens to be standing there. Called once per tick from tickLevel's own entry point, before
	 * any of this tick's own discard/kill handling runs - a one-tick lag hiding the bar right after a
	 * kill is imperceptible. */
	private void updateStage5BossBar(WaveState state) {
		boolean shouldShow = state.stage == 5 && state.lockedTarget != null && state.lockedTarget.isAlive();
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
		boolean entityPresent = state.entity != null && state.entity.isAlive();
		float maxHealth = entityPresent ? state.entity.getMaxHealth() : WENDIGO_MAX_HEALTH;
		float health = entityPresent ? state.entity.getHealth()
			: this.progressionTracker.stage5HealthOf(state.lockedTarget, maxHealth);
		state.stage5BossBar.setProgress(maxHealth > 0.0F ? Math.clamp(health / maxHealth, 0.0F, 1.0F) : 0.0F);
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
	 * case, see the "ordinary completion" branch of tickLevel) - one band candidate near target, or
	 * null if the sample missed. Split from the old findNearbyDarkSpot (which baked the unverified
	 * findDarkestAwayFrom fallback in unconditionally) specifically so a bounded retry loop can call
	 * just this half repeatedly, verifying each candidate with a real pathfind (see
	 * SemanticBands.isAcceptableOrbitPath) before ever reaching for findNearbyDarkSpotFallback below -
	 * the user's own explicit "if he can't reach the player in a spot he spawns/teleports to, try
	 * again" request, previously only a single unverified attempt. Aims for medium distance (half the
	 * band's own width, not the full min-max range) - the user's own explicit request, so a relocate
	 * doesn't land right back out near the far edge of the band, close to orbitDespawnDistance()'s own
	 * threshold all over again. */
	private static BlockPos findNearbyBandDarkSpot(ServerLevel level, ServerPlayer target, CaveScale caveScale) {
		if (target == null) {
			return null;
		}
		double minDistance = SemanticBands.orbitMinDistance(caveScale);
		double maxDistance = SemanticBands.orbitMaxDistance(caveScale);
		double mediumDistance = minDistance + (maxDistance - minDistance) / 2.0;
		return DarkSpotScanner.findLiveBandPosition3D(level, target.blockPosition(), minDistance, mediumDistance, randomOrbitSurfaceNormal());
	}

	/** The old findNearbyDarkSpot's own unverified last resort (plain away-from-target darkest-spot
	 * search, no reachability guarantee at all) - now only reached once a bounded retry loop over
	 * findNearbyBandDarkSpot above has already exhausted WendigoTuningConfig.orbitReachRetryAttempts
	 * real, verified tries. Same "some darkness beats none" philosophy as before, just genuinely a
	 * last resort now instead of the first (and only) thing tried. */
	private static BlockPos findNearbyDarkSpotFallback(ServerLevel level, BlockPos selfPos, ServerPlayer target, CaveScale caveScale) {
		double maxDistance = SemanticBands.orbitMaxDistance(caveScale);
		return DarkSpotScanner.findDarkestAwayFrom(level, selfPos, maxDistance, target != null ? target.blockPosition() : null);
	}
	/** Real, live AWCAPI pathfind - not the flood-fill/ring-sample heuristic DarkSpotScanner's own
	 * candidate searches already used to find this spot in the first place. Those searches are
	 * explicitly documented as connectivity APPROXIMATIONS (see findLiveBandPosition/
	 * findLiveBandPosition3D's own doc comments: "reachability true by construction," "deliberately
	 * NOT flood-verified"), not a guarantee that matches AWCAPI's own real climbing/pathing rules - the
	 * user's own explicit request, after this whole session's ceiling-flip investigation already
	 * showed those two things CAN silently drift apart. Returns the raw Path (not just a boolean) so
	 * callers can also judge it by node count, not just Path.canReach() - see
	 * SemanticBands.isAcceptableOrbitPath, the shared judgment every orbit-position search now uses.
	 * Called right after the entity is actually placed at the candidate spot (snapTo already ran), so
	 * this is a real pathfind from where it genuinely is, no hypothetical-position trickery needed.
	 * onGround is forced true for the duration - PathNavigation.createPath's own canUpdatePath() gate
	 * (confirmed via decompile) requires it, and a freshly snapTo'd entity hasn't had a real physics
	 * tick yet to resolve that naturally - restored immediately after, before the entity's own first
	 * real tick ever observes it. */
	private static Path pathToTarget(WendigoEntity entity, ServerPlayer target) {
		boolean savedOnGround = entity.onGround();
		entity.setOnGround(true);
		Path path = entity.getNavigation().createPath(target, 0);
		entity.setOnGround(savedOnGround);
		return path;
	}

	/** Keeps WaveState's own lockedTarget and the entity's separate WendigoEntity.lockedTarget field
	 * (the one Targeting.nearestPlayer, and therefore every action's own target resolution - orbit,
	 * chase, lunge, all of it - actually reads) in sync whenever the real target changes. The user's
	 * own explicit "if there's a group with multiple players, changing targets randomly should also
	 * change who the wendigo is orbiting around" request, confirmed as a genuinely broader gap while
	 * tracing it: WendigoEntity.startOrbit already keeps these two in sync on its own (it calls
	 * setLockedTarget directly), but overrideIntoChaseUntilLight/overrideIntoLunge/spawnWave's own
	 * debug/ambush path all used to reassign state.lockedTarget directly without it, leaving
	 * Targeting still pointed at whoever the entity was PREVIOUSLY locked onto (or nobody at all, for
	 * a fresh spawn) regardless of what WendigoManager itself now considers the real target - not
	 * just an orbit-specific bug, since Targeting.nearestPlayer backs action target resolution too. */
	private static void setLockedTarget(WaveState state, ServerPlayer target) {
		state.lockedTarget = target;
		if (state.entity != null) {
			state.entity.setLockedTarget(target);
		}
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
		int stage;
		if (target == null) {
			WendigoProgressionTracker.TargetSelection selection = this.progressionTracker.selectTarget(level);
			if (selection == null) {
				return;
			}
			target = selection.target();
			// Real bug found while fixing the group-stage derivation itself: selection.stage() used to
			// be computed (see selectTarget's own fresh-branch doc comment) and then silently thrown
			// away here in favor of independently re-deriving via stageOf(target) - the target's own
			// individual stage, completely ignoring whichever group-based value selectTarget just
			// worked out. That's WHY the "furthest-along group member" framing in this class's own
			// buildContext doc comment was never actually true live - group consideration was dead code
			// the whole time. Now genuinely wired through.
			stage = selection.stage();
		} else {
			// Already had a locked target (a relocate/respawn retry, not a fresh selection) - state.stage
			// already holds the correct value from whenever this run actually started; re-deriving via
			// stageOf(target) here is equivalent (a stage can't change mid-run) and avoids needing to
			// thread state.stage through as its own parameter.
			stage = this.progressionTracker.stageOf(target);
		}
		// See isInLushCaves's own doc comment - a paused run (or a fresh target who happens to already
		// be standing in lush caves) just doesn't spawn/resume here at all; the throttle above already
		// retries this same check shortly on its own once they leave.
		if (isInLushCaves(level, target)) {
			return;
		}
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
		// runs. Tries up to orbitReachRetryAttempts fresh flood candidates, each verified with a real
		// pathfind (SemanticBands.isAcceptableOrbitPath) before accepting one - the user's own
		// explicit "if he can't reach the player in a spot he spawns/teleports to, try again" request;
		// previously this only tried once (via a different, unverified fallback search) before
		// committing regardless of whether even that fallback was actually reachable.
		CaveScale caveScale = CaveScaleScanner.classify(level, target.blockPosition());
		double minDistance = SemanticBands.orbitMinDistance(caveScale);
		double maxDistance = SemanticBands.orbitMaxDistance(caveScale);

		WendigoEntity wendigo = new WendigoEntity(ModEntities.WENDIGO, level);
		if (stage == 5) {
			// Restore whatever health was left from the last encounter - see saveStage5HealthIfApplicable,
			// called right before every cosmetic pause - so teleporting away can't be used to heal back
			// to full; only actually killing it (see the wave-end handling in tickLevel) resets this.
			wendigo.setHealth(this.progressionTracker.stage5HealthOf(target, wendigo.getMaxHealth()));
		}

		BlockPos spawnPos = null;
		for (int attempt = 0; attempt < WendigoMod.tuningConfig.orbitReachRetryAttempts; attempt++) {
			BlockPos candidate = DarkSpotScanner.findLiveBandPosition(level, target.blockPosition(), target.blockPosition(),
				minDistance, maxDistance, Direction.UP);
			if (candidate == null) {
				continue;
			}
			if (state.avoidSpawnPos != null
					&& candidate.distSqr(state.avoidSpawnPos) < ORBIT_TOO_CLOSE_DISTANCE * ORBIT_TOO_CLOSE_DISTANCE) {
				// Landed back on (or right next to) the exact spot a too-close discard just fired from -
				// see checkOrbitTooClose's own comment. Still counts against the retry budget - a bad
				// neighborhood should exhaust it, not loop forever - but tries a fresh candidate rather
				// than aborting this whole attempt immediately, unlike before.
				continue;
			}
			wendigo.snapTo(candidate.getX() + 0.5, candidate.getY(), candidate.getZ() + 0.5, 0f, 0f);
			wendigo.syncPoseToSpawnPosition();
			wendigo.nudgeTowardAttachedSurface(Direction.UP);
			if (SemanticBands.isAcceptableOrbitPath(pathToTarget(wendigo, target), caveScale)) {
				spawnPos = candidate;
				break;
			}
		}
		if (spawnPos == null) {
			// Bounded retry budget exhausted (or the flood found nothing in-band at all) - genuine last
			// resort now, not the first thing tried: same "some darkness beats none" philosophy as
			// before, via a different, unverified search strategy.
			spawnPos = DarkSpotScanner.findDarkest(level, target.blockPosition(), maxDistance);
			if (spawnPos == null || (state.avoidSpawnPos != null
					&& spawnPos.distSqr(state.avoidSpawnPos) < ORBIT_TOO_CLOSE_DISTANCE * ORBIT_TOO_CLOSE_DISTANCE)) {
				return; // nothing dark/usable near this target yet either - try again next throttled search
			}
			wendigo.snapTo(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5, 0f, 0f);
			wendigo.syncPoseToSpawnPosition();
			wendigo.nudgeTowardAttachedSurface(Direction.UP);
		}
		// Set here rather than left for a real plan's own startWave call - the orbit exposure
		// reaction (see checkOrbitExposure) and the stage-1 despawn-effect scaling (see
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
			WendigoSounds.play(level, wendigo, WendigoSounds.Type.SPAWN);
		}
		wendigo.startOrbit(target);

		state.entity = wendigo;
		state.lockedTarget = target;
		state.stage = stage;
		debugSay(level, "spawned into orbit near " + target.getGameProfile().name() + " at " + spawnPos.toShortString()
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
	 * Bypasses cooldown/eligibility AND the LLM call - runs a hand-authored plan (plan/global_rules,
	 * same shape the model would return; spawn positioning and despawning are always engine-resolved,
	 * not part of the plan) through the real spawn/despawn lifecycle. Used by the /wendigo wavetest
	 * debug command to iterate on plans for free.
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
	 * <p>Also no-ops while isFleeingFromSpectralHit() OR isCurrentlyGlowing() - the user's own
	 * explicit request: a forced flee from a landed spectral hit must run to completion before
	 * anything else (this trigger included) can start a new plan on him, and while he's still
	 * genuinely glowing afterward (PlanRunner.tickFleeWhileGlowing's own continuous flee - a longer,
	 * separate window than the one-shot isFleeingFromSpectralHit lock, which only covers the initial
	 * despawn/flee sequence, not however much longer the glow itself lasts) he keeps running instead
	 * of being redirected into anything else either.
	 */
	public void overrideIntoChaseUntilLight(ServerLevel level, ServerPlayer target) {
		WaveState state = this.waves.get(level);
		if (state == null || state.entity == null || !state.entity.isAlive() || state.entity.isForcingRide()
				|| state.entity.isChasing() || state.entity.isFleeingFromSpectralHit() || state.entity.isCurrentlyGlowing()) {
			return;
		}
		// Real, live-reported bug: this used to hardcode 100 here regardless of the player's actual
		// stage - bypassTierGating=true is correct (internal.chase_until_light isn't a model-schema
		// action, nothing to gate), but severityPercent ALSO drives PlanRunner.rideFairChanceTicks/
		// rideEscapeThreshold once a grab actually lands mid-chase, so hardcoding 100 there silently
		// clamped every catch reached via this path (this trigger fires on its own, independent of
		// what the model's plan was doing) to stage 5's own shortest carry time - the user's own
		// "seems to be a flat rate now" report, since this override (and overrideIntoLunge's matching
		// bug right below) is a very common real path to a catch, not a rare edge case.
		int severityPercent = this.progressionTracker.percentOf(target);
		WaveContext context = state.context != null ? state.context
			: buildContext(level, target, severityPercent, state.entity);
		// startWaveOverride, not startWave - the user's own explicit "hardcoded, circumstance-triggered
		// plans shouldn't affect the current run's tasks" request (see PlanRunner.startOverride's own
		// doc comment for the real bug an ordinary startWave here caused: this wave's own already-
		// earned task progress was silently wiped every time this trigger fired).
		state.entity.startWaveOverride(buildChaseUntilLightOverridePlan(), severityPercent);
		state.context = context;
		setLockedTarget(state, target);
		state.stage = this.progressionTracker.stageOf(target);
		state.waveStartTick = level.getServer().getTickCount();
		state.extremeProximityTicks = 0;
		debugSay(level, "darkness overstay while already active - overriding into internal.chase_until_light");
	}

	/** checkOrbitTooClose's own >=ORBIT_TOO_CLOSE_LUNGE_MIN_PERCENT reaction - same guard/context-
	 * building shape as overrideIntoChaseUntilLight, just starting a bounded combat.lunge_attack
	 * pursuit (buildTooCloseLungePlan) instead of an internal.chase_until_light one. Deliberately a
	 * separate method rather than a parameter on overrideIntoChaseUntilLight - that one stays exactly
	 * as DarknessOverstayTracker needs it (a genuinely open-ended hunt is the right call for "lingered
	 * in darkness too long"; "got too close" is a different, more bounded provocation). */
	private void overrideIntoLunge(ServerLevel level, ServerPlayer target) {
		WaveState state = this.waves.get(level);
		if (state == null || state.entity == null || !state.entity.isAlive() || state.entity.isForcingRide()
				|| state.entity.isFleeingFromSpectralHit() || state.entity.isCurrentlyGlowing()) {
			return;
		}
		// See overrideIntoChaseUntilLight's own comment - same real bug, same fix: hardcoding 100 here
		// silently clamped every grab reached via this path to stage 5's own shortest carry time
		// regardless of the player's real stage.
		int severityPercent = this.progressionTracker.percentOf(target);
		WaveContext context = state.context != null ? state.context
			: buildContext(level, target, severityPercent, state.entity);
		// startWaveOverride, not startWave - see overrideIntoChaseUntilLight's own matching comment.
		state.entity.startWaveOverride(buildTooCloseLungePlan(), severityPercent);
		state.context = context;
		setLockedTarget(state, target);
		state.stage = this.progressionTracker.stageOf(target);
		state.waveStartTick = level.getServer().getTickCount();
		state.extremeProximityTicks = 0;
		debugSay(level, "target got too close while orbiting - overriding into a bounded lunge pursuit");
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

	/** Spawn positioning is always the unwatched default for a fresh spawn now regardless of what a
	 * plan does or doesn't specify (see spawnWave) - this should read as a sudden ambush, not
	 * something the player could have seen coming anyway. Despawning is entirely engine-resolved too
	 * (see PlanRunner's live despawn resolution), nothing for this hand-built plan to specify either. */
	private static JsonObject buildDarknessAmbushPlan() {
		JsonObject plan = new JsonObject();
		plan.add("plan", buildDangerChaseFleeSteps());
		plan.add("global_rules", new JsonArray());
		return plan;
	}

	/** Same shape as buildDarknessAmbushPlan - PlanRunner.start only ever reads "plan"/"global_rules"
	 * from a plan object (spawn/despawn resolution happens externally, before startWave is called),
	 * and overrideIntoChaseUntilLight's entity already exists, so there's nothing to resolve here. */
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
		if (entity.isForcingRide() || entity.isReturningToOrbit() || entity.isFleeingFromSpectralHit()
				|| entity.isCurrentlyGlowing()) {
			// Already caught, already mid-transit somewhere, fleeing a landed spectral hit, or still
			// genuinely glowing from one (PlanRunner.tickFleeWhileGlowing's own continuous flee) - the
			// user's own explicit request: he keeps running from a hit, an unconditional grab included,
			// until he's no longer glowing. Let it resolve first.
			return;
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
		state.context = context;
		state.waveStartTick = now;
		state.extremeProximityTicks = 0;
		debugSay(level, "target within grab range - grabbing unconditionally");
	}

	/** Extracts previous_encounter_recap from a fresh plan response and, if there was a real previous
	 * encounter for this request to have recapped in the first place (context.previousEncounter()
	 * non-null - see that method's own doc comment), stores it against that same Entry via
	 * EncounterHistory.updateRecap. Deliberately unconditional on whether the plan itself turns out
	 * stale/discarded below (a dead wave, an already-engaged entity, etc.) - the recap describes a
	 * PAST encounter, entirely unrelated to whether this particular response's own new plan ends up
	 * used, so there's no reason to throw it away just because the rest of the response was. Called
	 * from both beginWave and beginEngagement's own completion handlers, right after the error check,
	 * before either one's own staleness guard. */
	private void applyPreviousEncounterRecap(ServerLevel level, ServerPlayer target, WaveContext context, JsonObject plan) {
		EncounterHistory.Entry previous = context.previousEncounter();
		if (previous == null || !plan.has("previous_encounter_recap")) {
			return;
		}
		String recap = plan.get("previous_encounter_recap").getAsString();
		this.encounterHistory.updateRecap(target, previous.waveCount(), recap);
		// Always-on (not gated on WendigoDebug.verboseEnabled()) - see PlanRunner.logPlanStructure's
		// own doc comment for the matching reasoning. The user's own explicit request: print the
		// model's own interpretation the moment it comes back, not buried behind the verbose flag
		// with the rest of the chat commentary that just got quieted down.
		if (!recap.isBlank()) {
			WendigoDebug.say(level, "AI recap of encounter #" + previous.waveCount() + ": " + recap);
		}
	}

	private void beginWave(ServerLevel level, WaveState state, ServerPlayer target, int effectiveSeverity) {
		WaveContext context = buildContext(level, target, effectiveSeverity, null);
		int percent = context.severityCap() > 0 ? 100 * context.severity() / context.severityCap() : 0;

		state.requestPending = true;
		MinecraftServer server = level.getServer();
		WendigoMod.llmClient.requestPlan(buildSystemPrompt(percent), context.toPromptText(),
				SchemaBuilder.forSeverity(percent, context.caveScale()))
			.whenComplete((plan, error) -> server.execute(() -> {
				state.requestPending = false;
				if (error != null) {
					WendigoMod.LOGGER.error("Wendigo wave plan request failed", error);
					state.cooldownUntilTick = level.getServer().getTickCount() + this.config.dynamicCooldownTicks(percent);
					return;
				}
				applyPreviousEncounterRecap(level, target, context, plan);
				// This completion can be stale by the time it runs - resetForTesting() clears
				// requestPending/entity without any way to actually cancel the in-flight LLM call, so a
				// reset (or another wave spawned in the meantime some other way) followed by this request
				// finally resolving can otherwise leave two live wendigos: this one, plus whatever already
				// took state.entity. Only the request that's still first to land gets to spawn. Also
				// discarded if the target died while this request was in flight (see
				// WendigoProgressionTracker.isRecentlyDied's own doc comment for why this checks the
				// specific target rather than comparing against state.lockedTarget - the latter would
				// misfire on a legitimate /wendigo wave debug redirect onto a different player) - real
				// bug this fixes: without it, a pending plan request completing after its own target
				// already died would spawn a wendigo onto them anyway, exactly the "kept on respawning"
				// symptom the user reported.
				if ((state.entity != null && state.entity.isAlive()) || this.progressionTracker.isRecentlyDied(target)) {
					WendigoMod.LOGGER.warn("Discarding a stale wendigo plan - a wave was already active, or the target died, by the time it resolved: {}", plan);
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

		state.requestPending = true;
		MinecraftServer server = level.getServer();
		WendigoMod.llmClient.requestPlan(buildSystemPrompt(percent), context.toPromptText(),
				SchemaBuilder.forSeverity(percent, context.caveScale()))
			.whenComplete((plan, error) -> server.execute(() -> {
				state.requestPending = false;
				if (error != null) {
					WendigoMod.LOGGER.error("Wendigo engagement plan request failed", error);
					state.cooldownUntilTick = level.getServer().getTickCount() + this.config.dynamicCooldownTicks(percent);
					return;
				}
				applyPreviousEncounterRecap(level, target, context, plan);
				// Same staleness guard beginWave's own completion handler has, just checking
				// alive-and-still-orbiting instead of entirely-absent - the entity could have died,
				// gotten relocated, or already been engaged by something else while this was in flight.
				// Also discarded if the specific target this request was FOR has since died - see
				// beginWave's own matching comment for the full reasoning (isRecentlyDied, not a
				// state.lockedTarget identity comparison, specifically to not misfire on a legitimate
				// /wendigo wave debug redirect onto a different player).
				if (state.entity == null || !state.entity.isAlive() || !state.entity.isOrbiting()
						|| this.progressionTracker.isRecentlyDied(target)) {
					WendigoMod.LOGGER.warn("Discarding a stale wendigo engagement plan - no longer a valid orbiting entity, or the target died: {}", plan);
					return;
				}
				engageExistingWendigo(level, state, context, plan, false);
			}));
	}

	/** The user's own explicit control.re_evaluate request: mid-plan, the model asked for a fresh read
	 * and a new sub-plan to replace whatever's left. Same LLM-request plumbing as beginEngagement/
	 * beginWave, reusing the SAME severity this wave already committed to (state.entity.
	 * getSeverityPercent(), not a fresh progressionTracker lookup - this isn't a new stage decision,
	 * just continuing the one already in progress) and the SAME buildContext/buildSystemPrompt/
	 * SchemaBuilder calls every other request uses, plus PlanRunner's own step-so-far log appended to
	 * the prompt text. Deliberately does NOT call applyPreviousEncounterRecap - this isn't a new
	 * encounter, and whatever the model writes into previous_encounter_recap on this response (required
	 * by schema, but meaningless here) is simply ignored. */
	private void beginReEvaluate(ServerLevel level, WaveState state, int now) {
		ServerPlayer target = state.lockedTarget != null && state.lockedTarget.isAlive() ? state.lockedTarget : null;
		if (target == null) {
			// Genuinely nothing to build a fresh context against - give up cleanly rather than holding
			// the plan hostage on a request that could never resolve sensibly anyway.
			state.entity.cancelReEvaluate();
			return;
		}
		int percent = state.entity.getSeverityPercent();
		WaveContext context = buildContext(level, target, percent, state.entity);
		StringBuilder stepLog = new StringBuilder(
			"The plan is mid-execution and asked to re-evaluate here. Steps already run this wave so far, "
				+ "in order, with how long each one actually took: ");
		List<String> steps = state.entity.reEvaluateStepLog();
		for (int i = 0; i < steps.size(); i++) {
			if (i > 0) {
				stepLog.append(", ");
			}
			stepLog.append(steps.get(i));
		}
		stepLog.append(". Everything from here needs a fresh 'plan' (and, if it should change,"
			+ " 'global_rules') to replace what was left of the original.");

		state.requestPending = true;
		MinecraftServer server = level.getServer();
		WendigoMod.llmClient.requestPlan(buildSystemPrompt(percent), context.toPromptText() + "\n\n" + stepLog.toString(),
				SchemaBuilder.forSeverity(percent, context.caveScale()))
			.whenComplete((plan, error) -> server.execute(() -> {
				state.requestPending = false;
				if (state.entity == null || !state.entity.isAlive() || !state.entity.isReEvaluateRequested()) {
					if (error == null) {
						WendigoMod.LOGGER.warn("Discarding a stale wendigo re-evaluate plan - no longer a valid re-evaluating entity: {}", plan);
					}
					return;
				}
				if (error != null) {
					WendigoMod.LOGGER.error("Wendigo re-evaluate plan request failed", error);
					state.entity.cancelReEvaluate();
					return;
				}
				state.entity.resumeFromReEvaluate(plan);
			}));
	}

	/** Starts a plan on an already-alive, already-orbiting entity - the engage-existing-entity
	 * counterpart to spawnWave (which constructs a brand new one, positioned via resolveUnwatchedSpot
	 * since there's nothing to walk FROM yet). This entity already exists somewhere else in the cave
	 * (wherever orbit left it) - repositioning, if the plan's own first step even calls for any, is
	 * just an ordinary live movement.approach_spot resolved once the plan actually starts running, not
	 * a separate pre-plan walk this method has to orchestrate. */
	private void engageExistingWendigo(ServerLevel level, WaveState state, WaveContext context, JsonObject plan, boolean bypassTierGating) {
		int percent = context.severityCap() > 0 ? 100 * context.severity() / context.severityCap() : 0;
		int gatingPercent = bypassTierGating ? 100 : percent;
		state.entity.startWave(plan, gatingPercent, bypassTierGating);
		state.context = context;
		state.waveStartTick = level.getServer().getTickCount();
		state.extremeProximityTicks = 0;
		debugSay(level, "engaging existing wendigo - aggression: " + context.severity() + "/" + context.severityCap()
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
	 * target's own stage-derived percent, but for an automatically-selected fresh multiplayer target
	 * it's the whole proximity group's LEAST-established member's stage (see
	 * WendigoProgressionTracker.selectTarget's own fresh-branch doc comment for why this is min, not
	 * max/average) - the wendigo shows up at the intensity the group's newest member has actually
	 * earned, protecting them from a full-intensity haunting a more-established groupmate standing
	 * nearby hasn't earned on their behalf, even if the player it actually grabs happens to have more
	 * completed runs of their own. engagingEntity is the
	 * already-alive entity being engaged, or null for a fresh spawn - only affects whether the
	 * resulting context carries current-position info for the prompt (see WaveContext.CurrentPosition).
	 * Never fails/returns null - a live torch-count scan can legitimately come back all zeros, that's
	 * not a failure, just a fact resolveSpawnSpot's own live resolution deals with when it actually
	 * tries to find a position.
	 * Package-private (not private) for the same reason as buildSystemPrompt above - reused directly
	 * by WendigoConvergenceTests.
	 */
	WaveContext buildContext(ServerLevel level, ServerPlayer target, int effectiveSeverity, WendigoEntity engagingEntity) {
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
			// Direct live read of the entity's own real attachment state (see WendigoEntity.
			// getGroundSide's own doc comment for why this is now the reliable signal), not the
			// spatial proximity-to-a-resolved-vantage-point heuristic isOnTopPlayer above uses - a
			// wendigo attached to a side wall, not specifically above the player, still satisfies this
			// even though it wouldn't count as isOnTopPlayer.
			boolean onWallOrCeiling = engagingEntity.getGroundSide() != Direction.DOWN;
			// "in_view" - the same weakest noticed-band a held stare's own graduated-look tracking
			// already treats as "spotted, even peripherally" (see PlanRunner.isDeadStare's own
			// comment) - the user's own explicit request to tell the model whether the player is
			// already looking at an already-active wendigo before this plan has even started, since
			// re-engaging an orbiting entity (unlike a genuinely fresh spawn - always unwatched by
			// construction, see resolveUnwatchedSpot) can land it somewhere already in the player's view.
			boolean isPlayerLookingAtSelf = PlanPredicates.isLookingAtSelf(target, engagingEntity, "in_view");
			currentPosition = new WaveContext.CurrentPosition(distance, isOnTopPlayer, onWallOrCeiling,
				isPlayerLookingAtSelf, engagingEntity.playerDirection());
		}
		CaveScale caveScale = CaveScaleScanner.classify(level, playerPos);
		// containerMenu is always at least inventoryMenu (the player's own plain inventory screen,
		// which every player technically "has" open at all times whether or not a real screen is
		// visible) - only a genuine mismatch means some OTHER UI (chest, furnace, minecart chest,
		// crafting table, anvil, etc.) is actually open right now. The registry id (e.g.
		// "minecraft:generic_9x3" for a single chest/barrel/minecart chest alike, "minecraft:furnace")
		// is model-legible enough on its own - no need to hand-map it to a friendlier name.
		String openMenuId = target.containerMenu != target.inventoryMenu
			? BuiltInRegistries.MENU.getKey(target.containerMenu.getType()).toString()
			: null;
		boolean recentlyMining = recentlyMined(target, level.getServer().getTickCount());
		// Always 100 now - effectiveSeverity is already a fixed representative percent per stage (see
		// WendigoProgressionTracker.representativePercent), not a cumulative score with a real cap to
		// report anymore. Keeping the severity/severityCap field shape in WaveContext unchanged (rather
		// than replacing it with a bare percent) means every existing "100 * context.severity() /
		// context.severityCap()" call site elsewhere in this class keeps working unchanged too.
		return new WaveContext(target, effectiveSeverity, 100, torchCountsByBand, currentPosition,
			this.encounterHistory.all(target), level.getServer().getTickCount(), caveScale, openMenuId, recentlyMining);
	}

	private void spawnWave(ServerLevel level, WaveState state, WaveContext context, JsonObject plan, boolean bypassTierGating) {
		int percent = context.severityCap() > 0 ? 100 * context.severity() / context.severityCap() : 0;
		if (!canSpawnNear(context.player())) {
			// Hard, unconditional block - checked here rather than folded into severity/eligibility
			// gating (which debug paths like /wendigo wave/wavetest deliberately bypass for testing
			// convenience) specifically so nothing can skip it: never above y=0 no matter how dark the
			// area reads, and never onto a player with real night vision. Soul light was never checked
			// here (see SoulLightScanner) - it never blocked spawning, and no longer blocks engagement
			// either; it now only affects run progression (see resolveRunOutcome).
			WendigoMod.LOGGER.info("Wendigo spawn blocked for {} (above y=0 or night vision) - skipping",
				context.player().getGameProfile().name());
			state.cooldownUntilTick = level.getServer().getTickCount() + this.config.dynamicCooldownTicks(percent);
			return;
		}
		// Every genuinely fresh spawn appears unwatched, regardless of stage or bypassTierGating - the
		// model (or a wavetest author) no longer picks a spawn position at all (see the schema's own
		// removal of spawn_at); the plan just starts from here, and movement.approach_spot as its own
		// first step covers whatever bolder positioning is actually wanted (unrestricted for
		// bypassTierGating content, same as every other tier-gated primitive).
		BlockPos spawnPos = resolveUnwatchedSpot(level, context);
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
			WendigoSounds.play(level, wendigo, WendigoSounds.Type.SPAWN);
		}
		playSpawnDespawnEffect(level, spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5);
		wendigo.startWave(plan, gatingPercent, bypassTierGating);

		state.entity = wendigo;
		state.context = context;
		// Keeps every spawnWave-constructed entity (debug/ambush paths - the automatic trigger now
		// spawns via tryEnterOrbit instead) consistent with the locked-target model too, so if this
		// entity's plan later completes normally, resuming orbit afterward has a real target to lock
		// onto instead of null. setLockedTarget (not a plain field assignment) so the entity's own
		// separate WendigoEntity.lockedTarget field - the one Targeting.nearestPlayer actually reads,
		// for THIS wave's own actions too, not just a later orbit - is correct from the start rather
		// than defaulting to null until some later startOrbit call happens to fix it.
		setLockedTarget(state, context.player());
		state.waveStartTick = level.getServer().getTickCount();
		state.extremeProximityTicks = 0;
		debugSay(level, "wave started - spawned at " + spawnPos.toShortString()
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
	 * night vision. Soul light (see SoulLightScanner) was never checked here and still isn't - it
	 * affects run progression only now (see resolveRunOutcome), not spawning or engagement at all. The
	 * night-vision half is skipped for a player with /wendigo debug enabled specifically -
	 * WendigoCommands.toggleDebug applies real Night Vision for the whole debug session (so a tester
	 * can actually see what's happening), which would otherwise permanently block their own testing
	 * under this exact rule. y=0 still applies even while debugging - that isn't something debug mode
	 * itself causes. */
	private static boolean canSpawnNear(ServerPlayer player) {
		boolean blockedByNightVision = player.hasEffect(MobEffects.NIGHT_VISION) && !WendigoDebug.isEnabled(player);
		return player.getY() < 0 && !blockedByNightVision;
	}

	/** The user's own explicit "runs pause when the player is in lush caves biome" request - checked
	 * both by tickLevel's own discard trigger (for an already-active run) and tryEnterOrbit (so a
	 * paused run doesn't just immediately respawn right back on the very next throttled attempt). */
	private static boolean isInLushCaves(ServerLevel level, ServerPlayer player) {
		return level.getBiome(player.blockPosition()).is(Biomes.LUSH_CAVES);
	}

	// SoulLightScanner.isNearSoulLight's own 3D radius scan isn't free - every player, every tick would be wasteful for
	// a check that's purely cosmetic (an advancement, not gameplay-gating) - throttled the same way
	// ORBIT_SPAWN_SEARCH_INTERVAL_TICKS throttles its own scan, no per-player state needed since a
	// plain tick-modulo gate is enough here (award() itself is idempotent, so missing the exact tick
	// someone steps into range by up to this many ticks doesn't matter).
	private static final int SOUL_LIGHT_ACHIEVEMENT_CHECK_INTERVAL_TICKS = 20; // ~1s

	/** The user's own explicit "It lurks..." advancement chain: grants lurks/soul_light to any player
	 * standing under y=0 within SoulLightScanner.isNearSoulLight's own radius of a real soul-fire-
	 * family light source - the same "safe zone" concept resolveRunOutcome now uses for progression,
	 * reused here for the achievement instead of duplicating the block-family list. Applies to every
	 * player in the level, not just whoever a wave happens to be locked onto right now. */
	private static void checkSoulLightAchievement(ServerLevel level, int now) {
		if (now % SOUL_LIGHT_ACHIEVEMENT_CHECK_INTERVAL_TICKS != 0) {
			return;
		}
		for (ServerPlayer player : level.players()) {
			if (player.getY() < 0 && SoulLightScanner.isNearSoulLight(level, player.blockPosition())) {
				WendigoAdvancements.grant(player, WendigoAdvancements.SOUL_LIGHT);
			}
		}
	}

	/** Periodic mid-wave state dump (see WendigoWaveConfig.debugContextIntervalTicks) - not tied to any
	 * one action's own logging (see PlanRunner), a standing snapshot so trends across a whole wave -
	 * repeated stuck navigation, staying lit too long, drifting far from the player - are visible from
	 * chat/log history without needing to catch the moment something goes wrong live. */
	private void logContextSnapshot(ServerLevel level, WaveState state, int now) {
		WendigoEntity entity = state.entity;
		ServerPlayer player = state.context.player();
		int light = level.getMaxLocalRawBrightness(entity.blockPosition());
		debugSay(level, "context: self=" + entity.blockPosition().toShortString()
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
		// Same performance-cap reasoning as tickOrbitingEntity's own orbitDespawnDistance() check,
		// just for the mid-plan case that check doesn't cover - a player who wanders far away (or
		// teleports/fast-travels) without ever crossing y=0 could otherwise leave a mid-plan wendigo
		// stranded near wherever it last was for the same up-to-4-minute stretch. Immediate as well,
		// not a forced-ride false positive risk - a carried player is always ~0 blocks away by
		// definition, so this can only ever fire when they've genuinely wandered off on their own.
		double despawnDistance = orbitDespawnDistance();
		if (distance > despawnDistance) {
			return "target too far away mid-plan (" + despawnDistance + "+ blocks)";
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

	/** Live position, roughly at the "farther" band, that the target player isn't currently looking
	 * toward - every genuinely fresh spawn's own positioning now (see spawnWave - the model no longer
	 * picks a spawn band at all). Only ever called for a genuinely fresh spawn, so self is always the
	 * player's own position. Uses DarkSpotScanner.findUnwatchedPosition (the floor-only, flood-
	 * verified-reachable-from-self version) rather than the any-surface findUnwatchedPosition3D
	 * PlanRunner's own movement.approach_spot(destination=unwatched)/combat.teleport(destination=
	 * behind) use now - a fresh spawn genuinely needs to be reachable from wherever self (the
	 * player's own position) is, the same reachability guarantee this whole method's name promises,
	 * not just a geometrically-valid pick on some arbitrary surface. */
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
		// checkOrbitExposure. Reset the instant it's no longer exposed. A SEPARATE counter from
		// orbitDeadStareTicks below (not shared) since the two triggers now have genuinely different
		// tolerance windows - see checkOrbitExposure's own doc comment.
		int orbitExposedTicks;
		// Consecutive ticks the orbiting wendigo has been dead-stared at (stage 1 only - see
		// checkOrbitExposure). Reset the instant nobody's dead-staring at it (or it's no longer stage
		// 1/orbiting). Deliberately NOT the same counter as orbitExposedTicks above - the user's own
		// explicit "despawn after 3 seconds of dead stare" number is a real, fixed grace window,
		// unlike light's own stage-1 instant (0-tick) tolerance.
		int orbitDeadStareTicks;
	}
}
