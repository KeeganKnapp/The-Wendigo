package com.wendigo.wave;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.wendigo.debug.WendigoDebug;
import com.wendigo.entity.WendigoEntity;

/**
 * Tracks cumulative time each player has spent below y=0, world-wide, never resetting when they
 * resurface - this is the wendigo's targeting signal (whoever's been in the depths longest,
 * total) and, longer-term, a severity value meant to gate escalating behavior once a "defeat the
 * dweller" progression exists. Deliberately not a reward for spelunking: it only ever goes up.
 * In-memory only - resets on server restart.
 *
 * Also owns two related per-player mechanics that piggyback on the same once-a-second tick: a
 * warmup buffer before a player becomes targetable at all (see warmupUntilTick), and clearing
 * ordinary hostile mobs out of the area around an eligible player (see despawnNearbyHostileMobs) -
 * both keyed off the same "below y=0, past warmup" eligibility this class already computes.
 */
public final class PlayerSeverityTracker {
	// Sampled once per second (not every tick) so the cap represents a sane amount of wall-clock
	// time rather than raw ticks.
	private static final int SAMPLE_INTERVAL_TICKS = 20;
	// Hostile mobs are cleared out of this ring around an eligible player - not the inner radius
	// (an active fight or nearby mob shouldn't just vanish out from under a player), only the wider
	// area, so the wendigo reads as the sole presence without visibly despawning something right in
	// front of someone.
	private static final double MOB_CLEAR_INNER_RADIUS = 16.0;
	private static final double MOB_CLEAR_OUTER_RADIUS = 64.0;
	// Wider than MOB_CLEAR_OUTER_RADIUS on purpose - clearing already-spawned mobs still lets one
	// spawn, get noticed for a moment, then vanish as the player wanders toward it (or the periodic
	// sweep catches up); blocking natural spawns further out in the first place means nothing ever
	// pops into view distance at all, no visible "mobs in the distance just disappear" moment to
	// begin with.
	private static final double MOB_SPAWN_PREVENT_RADIUS = 96.0;
	// Don't discard a mob the player could actually watch vanish - only clear ones outside this
	// forward view cone (and only when there's also clear line of sight, same idiom as
	// PlanPredicates.isLookingAtSelf - a mob hidden behind a wall inside the cone is still safe to
	// discard, since the player can't actually see it happen).
	private static final double MOB_DESPAWN_VIEW_CONE_DEGREES = 70.0;

	private final WendigoWaveConfig config;
	private final Map<UUID, Integer> secondsUnderY0 = new HashMap<>();
	private final Map<UUID, Boolean> wasUnderY0 = new HashMap<>();
	// Server tick count at which each player stops warming up - (re)started on the y=0 rising edge
	// and on rejoin (see register()). Absent/expired means eligible.
	private final Map<UUID, Integer> warmupUntilTick = new HashMap<>();
	private int ticksSinceSample;

	public PlayerSeverityTracker(WendigoWaveConfig config) {
		this.config = config;
	}

	public void register() {
		ServerTickEvents.END_SERVER_TICK.register(this::onEndServerTick);
		// Rejoining always restarts the warmup, even if they're already below y=0 the instant they
		// reconnect (e.g. they logged out mid-descent) - the whole point is a buffer right after
		// (re)appearing, not just after freshly crossing y=0.
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> startWarmup(handler.player, server.getTickCount()));
		ServerEntityEvents.ALLOW_LOAD.register(this::allowLoad);
	}

	/** Blocks natural hostile-mob (and bat) spawns within MOB_SPAWN_PREVENT_RADIUS of any eligible
	 * player - see MOB_SPAWN_PREVENT_RADIUS. Only EntitySpawnReason.NATURAL is gated - spawners,
	 * summons, structures, breeding etc. are all deliberate and left alone; reloading an
	 * already-existing entity from disk (reason LOAD) is untouched too, so this can't eat a saved
	 * world's mobs on chunk load. */
	private boolean allowLoad(Entity entity, ServerLevel level, EntitySpawnReason reason, boolean bl) {
		if (reason != EntitySpawnReason.NATURAL || !isClearableMob(entity)) {
			return true;
		}
		double preventSq = MOB_SPAWN_PREVENT_RADIUS * MOB_SPAWN_PREVENT_RADIUS;
		int now = level.getServer().getTickCount();
		for (ServerPlayer player : level.players()) {
			if (isEligible(player, now) && entity.distanceToSqr(player) <= preventSq) {
				return false;
			}
		}
		return true;
	}

	/** Below y=0, past their warmup buffer (or debugging, which bypasses it) - the same condition
	 * mostSevereEligiblePlayer/onEndServerTick's mob-clearing already use. */
	private boolean isEligible(ServerPlayer player, int now) {
		if (player.isSpectator() || player.getY() >= 0) {
			return false;
		}
		return now >= this.warmupUntilTick.getOrDefault(player.getUUID(), 0) || WendigoDebug.isEnabled(player);
	}

	private void onEndServerTick(MinecraftServer server) {
		this.ticksSinceSample++;
		if (this.ticksSinceSample < SAMPLE_INTERVAL_TICKS) {
			return;
		}
		this.ticksSinceSample = 0;
		int now = server.getTickCount();

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			// Same reasoning as WendigoManager.onEndServerTick - this hook fires every real server
			// tick regardless of /tick freeze, but severity accrual/warmup/mob-clearing are world state
			// that should only advance when the player's level is actually ticking.
			if (player.isSpectator() || !player.level().tickRateManager().runsNormally()) {
				continue;
			}
			boolean underY0 = player.getY() < 0;
			if (underY0) {
				this.secondsUnderY0.merge(player.getUUID(), 1, (current, one) -> Math.min(this.config.severityCap, current + one));
				if (!this.wasUnderY0.getOrDefault(player.getUUID(), false)) {
					startWarmup(player, now); // rising edge - freshly descended below y=0
				}
				if (isEligible(player, now)) {
					despawnNearbyHostileMobs(player);
				}
			}
			this.wasUnderY0.put(player.getUUID(), underY0);
		}
	}

	private void startWarmup(ServerPlayer player, int now) {
		int percent = severityCap() > 0 ? 100 * severityOf(player) / severityCap() : 0;
		this.warmupUntilTick.put(player.getUUID(), now + this.config.dynamicWarmupTicks(percent));
	}

	/** Clears ordinary hostile mobs and bats (never the wendigo itself) out of the ring between
	 * MOB_CLEAR_INNER_RADIUS and MOB_CLEAR_OUTER_RADIUS around the player - so the wendigo reads as
	 * the sole presence without visibly despawning something right next to them. Skips anything
	 * currently in the player's own view cone (see MOB_DESPAWN_VIEW_CONE_DEGREES) - it'll get caught
	 * by a later sweep once it's no longer being watched. */
	private void despawnNearbyHostileMobs(ServerPlayer player) {
		ServerLevel level = player.level();
		double innerSq = MOB_CLEAR_INNER_RADIUS * MOB_CLEAR_INNER_RADIUS;
		double outerSq = MOB_CLEAR_OUTER_RADIUS * MOB_CLEAR_OUTER_RADIUS;
		double cosViewCone = Math.cos(Math.toRadians(MOB_DESPAWN_VIEW_CONE_DEGREES));
		AABB area = player.getBoundingBox().inflate(MOB_CLEAR_OUTER_RADIUS);
		for (Mob mob : level.getEntitiesOfClass(Mob.class, area)) {
			if (!isClearableMob(mob)) {
				continue;
			}
			double distSq = mob.distanceToSqr(player);
			if (distSq < innerSq || distSq > outerSq) {
				continue;
			}
			if (isInViewCone(player, mob, cosViewCone)) {
				continue;
			}
			mob.discard();
		}
	}

	/** Monster (hostile), Bat, or Slime - never the wendigo itself. Slime extends Mob directly
	 * (implements the Enemy marker interface rather than extending Monster), so it needs the same
	 * explicit callout as Bat. Shared by both the spawn-prevention gate and the despawn sweep so the
	 * two mechanisms can't drift apart on what counts as "clearable". */
	private static boolean isClearableMob(Entity entity) {
		return (entity instanceof Monster || entity instanceof Bat || entity instanceof Slime) && !(entity instanceof WendigoEntity);
	}

	/** Same alignment/line-of-sight idiom as PlanPredicates.isLookingAtSelf - true only if the mob is
	 * both within the angular cone AND actually visible (not behind a wall), since a mob hidden
	 * behind a wall inside the cone is still safe to discard. */
	private static boolean isInViewCone(ServerPlayer player, Mob mob, double cosThreshold) {
		Vec3 toMob = mob.getEyePosition().subtract(player.getEyePosition()).normalize();
		double alignment = player.getLookAngle().normalize().dot(toMob);
		return alignment >= cosThreshold && player.hasLineOfSight(mob);
	}

	public int severityOf(ServerPlayer player) {
		return this.secondsUnderY0.getOrDefault(player.getUUID(), 0);
	}

	public int severityCap() {
		return this.config.severityCap;
	}

	/** Directly sets a player's severity (clamped to [0, cap]) - /wendigo aggression set, for jumping
	 * straight to a tier during testing instead of grinding real time below y=0. */
	public void setSeverity(ServerPlayer player, int value) {
		this.secondsUnderY0.put(player.getUUID(), Math.clamp(value, 0, this.config.severityCap));
	}

	/** Highest-severity player currently below y=0 and past their warmup buffer in this level, or
	 * null if nobody qualifies right now. */
	public ServerPlayer mostSevereEligiblePlayer(ServerLevel level) {
		int now = level.getServer().getTickCount();
		ServerPlayer best = null;
		int bestSeverity = -1;
		for (ServerPlayer player : level.players()) {
			if (!isEligible(player, now)) {
				continue;
			}
			int severity = severityOf(player);
			if (severity > bestSeverity) {
				bestSeverity = severity;
				best = player;
			}
		}
		return best;
	}
}
