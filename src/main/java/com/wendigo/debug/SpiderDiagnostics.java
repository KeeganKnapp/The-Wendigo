package com.wendigo.debug;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.phys.Vec3;

import win.demistorm.stormiespiders.common.entity.mob.IClimberEntity;
import win.demistorm.stormiespiders.common.entity.mob.Orientation;

import com.wendigo.WendigoMod;

/**
 * Per-tick physics dump for a real (Stormy's Spiders-enabled) vanilla spider, in a format directly
 * comparable to WendigoEntity.logDiagnostics' own "WDIAG" lines - the whole point of the live A/B
 * ceiling-climb comparison test this exists for. win.demistorm.stormiespiders.mixin.ClimberEntityMixin
 * is a @Mixin(Spider.class) that directly `implements IClimberEntity` (confirmed via javap -v against
 * the mixin's own class file - the mixin's own implements clause, not something injected separately),
 * so every real Spider genuinely IS an IClimberEntity at runtime once Stormy's Spiders is loaded -
 * getOrientation()/getGroundDirection() below read its own real attachment-orientation state, the same
 * signals WendigoEntity's own WDIAG line reads from AWCAPI's ClimberComponent, not just a raw physics
 * approximation anymore. Falls back to the plain vanilla-only fields if the cast fails (Stormy's
 * Spiders not actually loaded, or some future entity ever gets tracked that isn't a real Spider).
 *
 * <p>Not driven by this mod's own tick() the way WendigoEntity's diagnostics are - a tracked spider is
 * a completely independent entity this mod doesn't own or tick. Uses ServerTickEvents.END_SERVER_TICK
 * instead, the same "global per-server-tick hook" mechanism WendigoManager's own tickLevel runs from.
 *
 * <p>Also force-chases its own summoner every REPATH_INTERVAL_TICKS, the user's own explicit request
 * ("make the spider also chase me even though I'm in creative mode") - a plain vanilla Spider's own
 * AI never picks a creative player as a target in the first place (standard vanilla targeting excludes
 * creative/spectator - the same reason WendigoEntity needed its own explicit creative-immunity check
 * added elsewhere, just the inverse problem here: vanilla already refuses to engage a creative player
 * at all), so its own goal selector just leaves it idle near one. Driving navigation.moveTo(player, ...)
 * directly, from outside the goal system entirely, sidesteps that targeting check completely - it's a
 * plain pathfind-toward-a-position call, not a combat target selection, so creative mode has nothing to
 * do with whether it's allowed to fire.
 */
public final class SpiderDiagnostics {
	// Re-issued periodically rather than every tick - matches PlanRunner's own chase re-path cadence
	// order of magnitude (see CHASE_GIVE_UP_TICKS-adjacent logic) without needing to track path-lost
	// state for what's just a debug convenience, not a real combat AI.
	private static final int REPATH_INTERVAL_TICKS = 10; // 0.5s
	private static final double CHASE_SPEED_MODIFIER = 1.0;

	private record Tracked(ServerLevel level, UUID targetPlayer) {
	}

	private static final Map<UUID, Tracked> tracked = new ConcurrentHashMap<>();

	private SpiderDiagnostics() {
	}

	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (tracked.isEmpty()) {
				return;
			}
			// The chase itself is NOT gated on WendigoDebug.anyEnabled() - a real bug an earlier
			// version of this method had, live-caught by the user: debug items persist in inventory
			// independent of whatever a player's own debug session state happens to be, so gating the
			// chase behind it meant a spider summoned while debug was toggled off did nothing at all -
			// no logs AND no chase, since both were behind the same early return. Only the per-tick
			// SDIAG log line below is meant to depend on anyone actually watching.
			boolean loggingEnabled = WendigoDebug.anyEnabled();
			boolean repathTick = server.getTickCount() % REPATH_INTERVAL_TICKS == 0;
			tracked.entrySet().removeIf(entry -> {
				Tracked info = entry.getValue();
				Entity entity = info.level().getEntity(entry.getKey());
				if (!(entity instanceof Spider spider) || !spider.isAlive()) {
					return true;
				}
				if (loggingEnabled) {
					log(spider);
				}
				if (repathTick && info.level().getEntity(info.targetPlayer()) instanceof ServerPlayer target) {
					spider.getNavigation().moveTo(target, CHASE_SPEED_MODIFIER);
				}
				return false;
			});
		});
	}

	/** Starts tracking a freshly-summoned spider and makes it start force-chasing whoever summoned it
	 * - see WendigoDebugItems' spider-summoner item. */
	public static void track(Spider spider, ServerLevel level, ServerPlayer target) {
		tracked.put(spider.getUUID(), new Tracked(level, target.getUUID()));
	}

	private static void log(Spider spider) {
		Vec3 pos = spider.position();
		Vec3 vel = spider.getDeltaMovement();
		String climberFields = "";
		if (spider instanceof IClimberEntity climber) {
			Orientation orientation = climber.getOrientation();
			var groundDirection = climber.getGroundDirection();
			climberFields = String.format(" climberNormal=(%.3f,%.3f,%.3f) climberPitch=%.1f climberYaw=%.1f groundDir=%s",
				orientation.normal.x, orientation.normal.y, orientation.normal.z,
				orientation.pitch, orientation.yaw, groundDirection.getLeft());
		}
		WendigoMod.LOGGER.info("SDIAG id={} t={} pos=({},{},{}) yaw={} pitch={} onGround={} hColl={} vColl={} vCollBelow={} "
				+ "vel=({},{},{}) speed={}{}",
			spider.getId(),
			String.format("%.2f", spider.level().getServer().getTickCount() / 20.0),
			String.format("%.3f", pos.x), String.format("%.3f", pos.y), String.format("%.3f", pos.z),
			String.format("%.1f", spider.getYRot()), String.format("%.1f", spider.getXRot()),
			spider.onGround(), spider.horizontalCollision, spider.verticalCollision, spider.verticalCollisionBelow,
			String.format("%.3f", vel.x), String.format("%.3f", vel.y), String.format("%.3f", vel.z),
			String.format("%.3f", spider.getAttributeValue(Attributes.MOVEMENT_SPEED)),
			climberFields);
	}
}
