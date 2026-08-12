package com.wendigo.sound;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

import com.wendigo.WendigoMod;
import com.wendigo.entity.WendigoEntity;
import com.wendigo.plan.PlanPredicates;

/**
 * Every distinct situation the wendigo makes noise for, in one place - laid out by WHEN each cue
 * belongs (CHASE/FLEE/STARE right before their matching action, SPAWN once on arrival, AMBIENT
 * anytime) rather than by intensity. Each Type is backed by its own hand-curated subset of vanilla's
 * ambient/cave/cave1..23 files (see assets/wendigo/sounds.json - a given cave file can and does
 * appear in more than one Type's pool where it fits both moods), referenced by ID rather than
 * duplicated into this mod's own resources. assets/minecraft/sounds.json separately silences the
 * real ambient.cave event so vanilla no longer plays cave ambience on its own - only the wendigo
 * does now. CHASE/FLEE/STARE/AMBIENT are heavily encouraged but model-chosen (see sound.ambient_cue
 * in action_schema.json); SPAWN is engine-triggered only. AMBIENT used to also play occasionally on
 * its own while orbiting with no active plan (see PlanRunner.tickOrbit's own history) - removed per
 * the user's own explicit request, so a wendigo that's just orbiting, not currently mid-plan, is
 * silent now.
 * <p>
 * play() is a plain availability check, not a queue - the user's own explicit request. Used to
 * enqueue every call per-level and drain at most one per MIN_SOUND_INTERVAL_TICKS via a dedicated
 * per-tick pump, so a burst of legitimate cues still all eventually played, just spaced out - but
 * that pump was its own separate background process, registered directly on
 * ServerTickEvents.END_SERVER_TICK independent of any single level's own freeze state, and it had to
 * keep running on its own to ever drain anything at all - exactly the mechanism behind a previously-
 * reported bug where queued sounds kept trickling out in real time even with ticks frozen for
 * debugging. Now every call to play() just checks "is a slot free right now" (the same
 * MIN_SOUND_INTERVAL_TICKS gap, tracked in NEXT_ALLOWED_TICK) and either plays immediately or drops
 * the request outright - nothing is ever held onto to play later, so there's no backlog and nothing
 * left running in the background to go wrong while frozen.
 * <p>
 * Every cue here is centered on EACH PLAYER, not the wendigo's own actual position - the user's own
 * explicit framing: these are cinematic cues (a horror movie's own sound design, not something
 * diegetically emitted from the wendigo's body that would naturally fall off with distance/
 * direction the way footsteps or a hurt sound do - see WendigoEntity.playHurtSound/playStepSound,
 * deliberately NOT changed by this, since those really are meant to sound like they're coming from
 * wherever the wendigo actually is). Playing near the
 * player's own position means it's always heard clearly regardless of where the wendigo is hiding,
 * which is the whole point of an unplaced dread cue.
 * <p>
 * Every Type - AMBIENT included, per the user's own explicit follow-up request reversing an earlier
 * "AMBIENT stays unplaced" carve-out - goes one step further than a plain "at the player" cue,
 * though: each is offset CINEMATIC_OFFSET_DISTANCE blocks out from every player, in the direction
 * of the wendigo actually causing this cue (see cinematicSoundPosition), mirroring "/execute as @a
 * at @s facing entity ... eyes run playsound wendigo player @a ^ ^ ^5" - a directional hint toward
 * where the dread is coming from without giving away its exact real position (5 blocks is nowhere
 * near where the wendigo actually is, just a plausible-sounding nearby source). A cue genuinely
 * emitted from the wendigo's own real position is reserved for hurt/death/despawn-style direct
 * feedback now, never a cinematic one - see WendigoEntity.playHurtSound/die, neither of which goes
 * through this class at all.
 */
public final class WendigoSounds {
	public enum Type {
		/** Heavily encouraged right before committing to combat.lunge_attack or combat.chase -
		 * announces the hunt is on. */
		CHASE,
		/** Heavily encouraged right before movement.retreat_with_fallback - announces the withdrawal
		 * back into darkness. */
		FLEE,
		/** Low, unplaced background presence - safe anytime, including from a distance. Also what the
		 * periodic "still lingering in the dark" warning noise (DarknessOverstayTracker) uses. */
		AMBIENT,
		/** Heavily encouraged right when posture.stare(enabled=true) starts a held stare. */
		STARE,
		/** Played once when a wave's wendigo actually spawns/relocates into the world. */
		SPAWN,
		/** Triggered the instant a grab lands (see PlanRunner.beginForcedRide) - lands right at that
		 * exact moment or not at all (see the class doc comment - every cue now behaves this way, a
		 * plain availability check, not a queue), which matters most for this one specifically: a
		 * jumpscare that played late, after whatever else happened to already play first (a chase cue
		 * right before the catch, commonly), isn't a jumpscare anymore. */
		JUMPSCARE
	}

	// Registered eagerly (see init(), called from WendigoMod.onInitialize) rather than left as bare
	// Identifiers - same ResourceKey/Registry.register pattern ModEntities.WENDIGO already uses, so
	// these show up as real registry entries before the registry freezes, not lazily on first play().
	private static final SoundEvent CHASE_EVENT = register("chase");
	private static final SoundEvent FLEE_EVENT = register("flee");
	private static final SoundEvent AMBIENT_EVENT = register("ambient");
	private static final SoundEvent STARE_EVENT = register("stare");
	private static final SoundEvent SPAWN_EVENT = register("spawn");
	private static final SoundEvent JUMPSCARE_EVENT = register("jumpscare");

	private static SoundEvent register(String name) {
		ResourceKey<SoundEvent> key = ResourceKey.create(Registries.SOUND_EVENT, WendigoMod.id(name));
		return Registry.register(BuiltInRegistries.SOUND_EVENT, key, SoundEvent.createVariableRangeEvent(WendigoMod.id(name)));
	}

	// Minimum real time between any two sounds this mod plays in the same level, so a burst of
	// triggers can never overlap into noise. Sized to the longest cave file actually referenced by
	// any of the pools above - cave17 (used by STARE), measured directly from its own Ogg Vorbis
	// granule position/sample rate (12.4s), not guessed - rounded up a little for a safety margin.
	// Global per-level rather than per-cue-type: two DIFFERENT cues overlapping is exactly as much of
	// a wall-of-noise problem as the same cue repeating.
	private static final int MIN_SOUND_INTERVAL_TICKS = 260; // 13s

	private static final Map<ServerLevel, Integer> NEXT_ALLOWED_TICK = new HashMap<>();

	private WendigoSounds() {
	}

	/** Forces the *_EVENT static fields above to register, via ordinary Java class-initialization-on-
	 * first-reference (same reason ModEntities has its own init()) - called from WendigoMod.onInitialize
	 * so every event exists in the registry before it freezes. Nothing else to set up now that play()
	 * is a plain per-call availability check with no background pump to register (see the class doc
	 * comment). */
	public static void init() {
	}

	/** Public so vanilla-called hooks (e.g. Entity.getAmbientSound, not something this mod invokes
	 * directly) can still resolve a type from the same single source of truth as play(). */
	public static SoundEvent eventFor(Type type) {
		return switch (type) {
			case CHASE -> CHASE_EVENT;
			case FLEE -> FLEE_EVENT;
			case AMBIENT -> AMBIENT_EVENT;
			case STARE -> STARE_EVENT;
			case SPAWN -> SPAWN_EVENT;
			case JUMPSCARE -> JUMPSCARE_EVENT;
		};
	}

	// The user's own explicit "5 blocks out" distance from the hardcoded command this mirrors - see
	// the class doc comment.
	private static final double CINEMATIC_OFFSET_DISTANCE = 14.0;

	/** Plays immediately if the same MIN_SOUND_INTERVAL_TICKS gap every cue shares has already
	 * elapsed, or does nothing at all otherwise - a plain availability check (see the class doc
	 * comment), never held onto, never played late. Centered on EVERY player currently in this level,
	 * not just whichever one triggered it - the user's own explicit multiplayer request. A single
	 * level.playSound(null, aPlayer.blockPosition(), ...) call only reaches players within ordinary
	 * positional falloff range of that one player's own position (vanilla's usual ~16-block-ish
	 * audible radius, scaled by volume) - fine for that player, whoever else happens to be nearby
	 * them, but silent for anyone elsewhere in the same cave/level, which defeats the point of an
	 * unplaced cinematic dread cue reaching the whole party. Loops ServerLevel.players() (confirmed
	 * via javap - only players actually in THIS dimension, which is correct: a hunt in one level
	 * shouldn't leak ambience into a different one) so every player hears it exactly as clearly as
	 * everyone else, regardless of where they each are relative to each other or the wendigo.
	 * <p>
	 * source is the WendigoEntity actually responsible for this cue - drives the directional offset
	 * every Type now gets (see cinematicSoundPosition/the class doc comment), including AMBIENT: the
	 * user's own explicit follow-up request, reversing the earlier "AMBIENT stays unplaced" carve-out
	 * - a raw self-position cue is reserved for hurt/death/despawn-style direct feedback now (see
	 * WendigoEntity.playHurtSound/die, neither of which goes through this class at all), never a
	 * cinematic cue. Still null-safe for AMBIENT specifically (callers with no entity handy, e.g.
	 * DarknessOverstayTracker's own "still lingering in the dark" warning, can just pass null there) -
	 * falls back to the player's own unplaced position rather than offsetting toward nothing. */
	public static void play(ServerLevel level, WendigoEntity source, Type type) {
		int now = level.getServer().getTickCount();
		if (now < NEXT_ALLOWED_TICK.getOrDefault(level, 0)) {
			return;
		}
		SoundEvent event = eventFor(type);
		boolean offset = source != null;
		for (ServerPlayer player : level.players()) {
			// STARE's own live "obstructed line of sight" gate - the user's own explicit request: a stare
			// sound should only actually reach a player who can genuinely see the wendigo's face right
			// now (same head-visibility check the stare DETECTION predicate itself uses, not a new one),
			// not everyone in the level regardless of a wall between them and it. Every other Type stays
			// unconditional - a chase/flee/spawn/jumpscare cue is meant to reach the whole party
			// regardless of sightline (see the class doc comment), only STARE is specifically about being
			// looked at.
			if (type == Type.STARE && source != null
					&& !PlanPredicates.hasLineOfSightToSelf(player, source, source.getVisualEyePosition())) {
				continue;
			}
			Vec3 pos = offset ? cinematicSoundPosition(player, source) : player.position();
			level.playSound(null, pos.x, pos.y, pos.z, event, SoundSource.HOSTILE, 1.0F, 1.0F);
		}
		NEXT_ALLOWED_TICK.put(level, now + MIN_SOUND_INTERVAL_TICKS);
	}

	/** CINEMATIC_OFFSET_DISTANCE blocks out from this player, toward source - mirrors "/execute as @a
	 * at @s facing entity ... eyes run playsound wendigo player @a ^ ^ ^5" (the user's own hardcoded
	 * reference command): start at the player's own position ("at @s"), aim at the wendigo's visual
	 * eye position ("facing entity ... eyes" - getVisualEyePosition() rather than the raw physical
	 * getEyePosition(), same substitution PlanPredicates.isLookingAtSelf's own doc comment explains,
	 * for the same reason: it's the real rendered head position, not the hitbox-derived default),
	 * then step CINEMATIC_OFFSET_DISTANCE forward along that full 3D direction ("^ ^ ^5" - genuinely
	 * 3D, not flattened to horizontal only, so a wendigo perched high above/below a player pulls the
	 * cue's position up/down too, not just sideways). Falls back to the player's own position
	 * unchanged in the degenerate case where source is (effectively) standing exactly on the player -
	 * a zero-length direction has nothing to normalize toward. */
	private static Vec3 cinematicSoundPosition(ServerPlayer player, WendigoEntity source) {
		Vec3 origin = player.position();
		Vec3 toSource = source.getVisualEyePosition().subtract(origin);
		if (toSource.lengthSqr() < 1.0E-4) {
			return origin;
		}
		return origin.add(toSource.normalize().scale(CINEMATIC_OFFSET_DISTANCE));
	}

	// The user's own explicit volume/pitch for sound.breathe - deliberately NOT the same 2.0F/1.0F
	// every Type above uses, so this gets its own dedicated method rather than folding into Type/play().
	private static final float BREATHE_VOLUME = 0.5F;
	private static final float BREATHE_PITCH = 0.65F;
	// vanilla's own registered id is "entity.player.breath" (no trailing "e", unlike e.g.
	// entity.horse.breathe - confirmed via decompile) - the user asked for
	// "minecraft:entity.player.breathe", which doesn't exist as a registered sound at all; this is the
	// obviously-intended real one, a believable player-style breathing/exertion noise.
	private static final SoundEvent BREATHE_EVENT = SoundEvents.PLAYER_BREATH;

	/** sound.breathe's own dedicated player - deliberately separate from play()/Type above rather than
	 * folded in as another enum value, for two reasons: it uses vanilla's own raw PLAYER_BREATH event
	 * (not one of this mod's own curated cave-ambience pools), and its position is genuinely diegetic -
	 * "at itself" (the user's own words), the wendigo's own real live position, not offset toward it
	 * from each player's own position the way CHASE/FLEE/STARE/SPAWN/JUMPSCARE all are (see the class
	 * doc comment on cinematicSoundPosition). Still loops every player in the level the same way play()
	 * does, for the same reason - a single playSound call only reliably reaches players within ordinary
	 * falloff range of that one position, and "to all players" (the user's own words) means everyone
	 * should hear it regardless of where they each stand relative to the wendigo. Shares play()'s own
	 * NEXT_ALLOWED_TICK throttle (not a separate timer) - deliberately, so a breathe issued right
	 * alongside another cue (e.g. right before a chase cue, exactly the pairing the model is encouraged
	 * toward - see WendigoManager's own prompt text) competes for the same slot rather than stacking on
	 * top of it, same "never a wall of noise" guarantee every other cue already has.
	 * <p>
	 * CORRECTION: that shared-throttle decision was wrong in practice - the user's own explicit
	 * "I cannot hear the breathe sound effect when I'm actually pretty close to him" bug report.
	 * Breathe is specifically recommended (see WendigoManager's own prompt text) right ALONGSIDE
	 * other cues - right after a close teleport, right before an escalation cue - which means it was
	 * routinely losing the shared MIN_SOUND_INTERVAL_TICKS (13s) slot to whichever cue fired first in
	 * the same sequence, getting silently dropped almost every time it mattered most. Now uses its own
	 * separate BREATHE_NEXT_ALLOWED_TICK timer with a much shorter BREATHE_MIN_INTERVAL_TICKS gap, so
	 * it no longer competes with CHASE/FLEE/STARE/SPAWN/JUMPSCARE/AMBIENT at all - it can play right
	 * alongside any of them, and (per the prompt's own "lean on it often, not just occasionally") can
	 * also repeat on its own reasonably soon after, independent of whatever else is happening. */
	private static final int BREATHE_MIN_INTERVAL_TICKS = 60; // 3s - short enough for frequent close-range use
	private static final Map<ServerLevel, Integer> BREATHE_NEXT_ALLOWED_TICK = new HashMap<>();

	public static void playBreathe(ServerLevel level, WendigoEntity source) {
		int now = level.getServer().getTickCount();
		if (now < BREATHE_NEXT_ALLOWED_TICK.getOrDefault(level, 0)) {
			return;
		}
		Vec3 pos = source.position();
		for (ServerPlayer player : level.players()) {
			level.playSound(null, pos.x, pos.y, pos.z, BREATHE_EVENT, SoundSource.HOSTILE, BREATHE_VOLUME, BREATHE_PITCH);
		}
		BREATHE_NEXT_ALLOWED_TICK.put(level, now + BREATHE_MIN_INTERVAL_TICKS);
	}
}
