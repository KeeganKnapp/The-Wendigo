package com.wendigo.sound;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * Every distinct situation the wendigo makes noise for, in one place - each Type now maps to a
 * distinguishable vanilla sound (verified present via javap against the mapped jar; no custom sound
 * pack yet) rather than all six sharing the same generic AMBIENT_CAVE, which made a genuine
 * engine-triggered event (e.g. the darkness-overstay ambush actually firing) audibly indistinguishable
 * from routine background noise. Borrows from EnderMan/Warden since the wendigo is itself built on an
 * EnderMan (see WendigoEntity) - reusing those cues keeps the palette thematically coherent instead of
 * grabbing arbitrary hostile-mob sounds. Declaring a Type here doesn't require it to already be wired
 * up somewhere - CAUGHT/JUMPSCARE are model-chosen (see sound.ambient_cue in action_schema.json),
 * SPAWNED/DANGER/WARNING are engine-triggered.
 */
public final class WendigoSounds {
	public enum Type {
		/** Low, unplaced background presence - the model's default sound.ambient_cue choice. */
		AMBIENCE,
		/** Model-chosen sound.ambient_cue - the moment a held stare/trap flips to "spotted". */
		CAUGHT,
		/** Model-chosen sound.ambient_cue - paired with a reveal (lunge/chase) at high severity. */
		JUMPSCARE,
		/** Played once when a wave's wendigo actually spawns into the world. */
		SPAWNED,
		/** Played once when the hardcoded darkness-overstay ambush plan begins. */
		DANGER,
		/** Played periodically as a player lingers continuously in darkness (see DarknessOverstayTracker). */
		WARNING
	}

	private WendigoSounds() {
	}

	/** Public so vanilla-called hooks (e.g. Entity.getAmbientSound, not something this mod invokes
	 * directly) can still resolve a type from the same single source of truth as play(). */
	public static SoundEvent eventFor(Type type) {
		return switch (type) {
			// Was AMBIENT_CAVE - vanilla plays that exact sound on its own as random environmental
			// ambience, so a player had no way to tell an engine-triggered cue apart from ordinary cave
			// noise (reported as "doesn't seem to be making ambient noises" even though the debug log
			// confirmed it was firing). Warden's own idle groan reads as "a presence", not weather.
			case AMBIENCE -> SoundEvents.AMBIENT_CAVE.value();
			// The vanilla "you looked at an enderman" cue - exact thematic match for a held stare/trap
			// flipping to "spotted".
			case CAUGHT -> SoundEvents.ENDERMAN_STARE;
			// Aggressive reveal, paired with a lunge/chase at high severity.
			case JUMPSCARE -> SoundEvents.ENDERMAN_SCREAM;
			// A materializing/arrival cue distinct from ambience - "something just appeared".
			case SPAWNED -> SoundEvents.ENDERMAN_TELEPORT;
			// Was WARDEN_NEARBY_CLOSEST - in vanilla that's a subtle proximity hint, not a stinger, so
			// it read as barely-there rather than "a sudden, escalating" cue despite firing correctly
			// (confirmed via debug log - the trigger wasn't the problem, the sound choice was too
			// quiet). WARDEN_ANGRY is a genuine loud roar, matching what this cue is meant to feel like.
			case DANGER -> SoundEvents.WARDEN_ANGRY;
			// The creature's own low vocalization, repeated periodically while lingering in darkness -
			// distinct from generic cave ambience so it reads as "something is aware of you", not just
			// background noise.
			case WARNING -> SoundEvents.ENDERMAN_AMBIENT;
		};
	}

	/** Same volume/pitch/source every call - matches what this mod already used before this class existed. */
	public static void play(ServerLevel level, BlockPos pos, Type type) {
		level.playSound(null, pos, eventFor(type), SoundSource.HOSTILE, 2.0F, 0.5F);
	}
}
