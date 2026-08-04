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
import net.minecraft.sounds.SoundSource;

import com.wendigo.WendigoMod;

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
 * Every cue here is centered on the TARGET PLAYER, not the wendigo's own actual position - the
 * user's own explicit framing: these are cinematic cues (a horror movie's own sound design, not
 * something diegetically emitted from the wendigo's body that would naturally fall off with
 * distance/direction the way footsteps or a hurt sound do - see WendigoEntity.playHurtSound/
 * playStepSound and PlanRunner's own spear-hit sound, deliberately NOT changed by this, since those
 * really are meant to sound like they're coming from wherever the wendigo actually is). Playing at
 * the player's own position means it's always heard clearly regardless of where the wendigo is
 * hiding, which is the whole point of an unplaced dread cue.
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

	/** Plays immediately if the same MIN_SOUND_INTERVAL_TICKS gap every cue shares has already
	 * elapsed, or does nothing at all otherwise - a plain availability check (see the class doc
	 * comment), never held onto, never played late. Centered on target (see the class doc comment for
	 * why). */
	public static void play(ServerLevel level, ServerPlayer target, Type type) {
		int now = level.getServer().getTickCount();
		if (now < NEXT_ALLOWED_TICK.getOrDefault(level, 0)) {
			return;
		}
		level.playSound(null, target.blockPosition(), eventFor(type), SoundSource.HOSTILE, 2.0F, 1.0F);
		NEXT_ALLOWED_TICK.put(level, now + MIN_SOUND_INTERVAL_TICKS);
	}
}
