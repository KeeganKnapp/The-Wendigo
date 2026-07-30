package com.wendigo.sound;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
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
 * does now. CHASE/FLEE/STARE are heavily encouraged but model-chosen (see sound.ambient_cue in
 * action_schema.json - AMBIENT is offered there too, for anytime use); SPAWN is engine-triggered
 * only, and AMBIENT is also played occasionally by the engine itself while orbiting (see
 * PlanRunner.tickOrbit) - there's no model-authored plan running then to place a cue in.
 * <p>
 * play() no longer plays immediately - with this many independent triggers (model-chosen cues,
 * severity-milestone/darkness-overstay nags, orbit's own occasional ambience, spawn) all capable of
 * firing close together, back-to-back real playSound calls were overlapping into a wall of noise,
 * especially mid-chase. Every call instead enqueues per-level (see QUEUES) and a single per-tick
 * pump (tick(), registered in init()) drains at most one per MIN_SOUND_INTERVAL_TICKS - a real
 * queue, not a debounce that drops the extras, so a burst of legitimate cues still all eventually
 * play, just spaced out.
 */
public final class WendigoSounds {
	public enum Type {
		/** Heavily encouraged right before committing to combat.lunge_attack or combat.chase -
		 * announces the hunt is on. */
		CHASE,
		/** Heavily encouraged right before movement.retreat_with_fallback - announces the withdrawal
		 * back into darkness. */
		FLEE,
		/** Low, unplaced background presence - safe anytime, including from a distance. Also what
		 * the periodic "still lingering"/severity-milestone nags (DarknessOverstayTracker,
		 * PlayerSeverityTracker) use, and what orbit itself plays occasionally on its own. */
		AMBIENT,
		/** Heavily encouraged right when posture.stare(enabled=true) starts a held stare. */
		STARE,
		/** Played once when a wave's wendigo actually spawns/relocates into the world. */
		SPAWN
	}

	// Registered eagerly (see init(), called from WendigoMod.onInitialize) rather than left as bare
	// Identifiers - same ResourceKey/Registry.register pattern ModEntities.WENDIGO already uses, so
	// these show up as real registry entries before the registry freezes, not lazily on first play().
	private static final SoundEvent CHASE_EVENT = register("chase");
	private static final SoundEvent FLEE_EVENT = register("flee");
	private static final SoundEvent AMBIENT_EVENT = register("ambient");
	private static final SoundEvent STARE_EVENT = register("stare");
	private static final SoundEvent SPAWN_EVENT = register("spawn");

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

	private record QueuedSound(BlockPos pos, Type type) {
	}

	private static final Map<ServerLevel, Deque<QueuedSound>> QUEUES = new HashMap<>();
	private static final Map<ServerLevel, Integer> NEXT_ALLOWED_TICK = new HashMap<>();

	private WendigoSounds() {
	}

	/** Registers the per-tick queue pump - also what actually forces the *_EVENT static fields to
	 * register, via ordinary Java class-initialization-on-first-reference (same reason ModEntities
	 * has its own init()). Called from WendigoMod.onInitialize. */
	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(WendigoSounds::tick);
	}

	private static void tick(MinecraftServer server) {
		for (Map.Entry<ServerLevel, Deque<QueuedSound>> entry : QUEUES.entrySet()) {
			Deque<QueuedSound> queue = entry.getValue();
			if (queue.isEmpty()) {
				continue;
			}
			ServerLevel level = entry.getKey();
			int now = server.getTickCount();
			if (now < NEXT_ALLOWED_TICK.getOrDefault(level, 0)) {
				continue;
			}
			QueuedSound next = queue.poll();
			level.playSound(null, next.pos(), eventFor(next.type()), SoundSource.HOSTILE, 2.0F, 1.0F);
			NEXT_ALLOWED_TICK.put(level, now + MIN_SOUND_INTERVAL_TICKS);
		}
	}

	/** Public so vanilla-called hooks (e.g. Entity.getAmbientSound, not something this mod invokes
	 * directly) can still resolve a type from the same single source of truth as tick(). */
	public static SoundEvent eventFor(Type type) {
		return switch (type) {
			case CHASE -> CHASE_EVENT;
			case FLEE -> FLEE_EVENT;
			case AMBIENT -> AMBIENT_EVENT;
			case STARE -> STARE_EVENT;
			case SPAWN -> SPAWN_EVENT;
		};
	}

	/** Enqueues this sound (see the class doc comment) rather than playing it immediately - actual
	 * playback (same volume/pitch/source this mod always used) happens from tick() once the queue
	 * reaches it and the minimum gap has elapsed. */
	public static void play(ServerLevel level, BlockPos pos, Type type) {
		QUEUES.computeIfAbsent(level, l -> new ArrayDeque<>()).add(new QueuedSound(pos, type));
	}
}
