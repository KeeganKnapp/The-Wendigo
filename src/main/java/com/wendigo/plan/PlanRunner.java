package com.wendigo.plan;

import java.util.ArrayDeque;
import java.util.Deque;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.player.Player;

import com.wendigo.WendigoMod;
import com.wendigo.entity.WendigoEntity;

/**
 * Per-entity interpreter for a WendigoActionPlan (see action_schema.json). Advances at most one
 * action per tick; control.if/control.while are resolved into the run queue as they're reached,
 * never nested more than one level deep - the schema itself enforces that cap, so this doesn't
 * need to handle deeper recursion.
 */
public class PlanRunner {
	// Guards a single tick's cascade of zero-duration steps (control.if resolution, instant
	// actions like posture.stare). Not a normal code path - just a trip wire against a bug
	// turning into an infinite loop within one tick.
	private static final int MAX_STEPS_PER_TICK = 32;

	private final WendigoEntity self;

	private JsonArray topLevelSteps;
	private int topIndex;
	private final Deque<JsonObject> actionQueue = new ArrayDeque<>();

	// At most one active control.while at a time - a while body can't contain another while.
	private JsonObject activeWhile;
	private int whileIterationsRemaining;

	private JsonObject currentAction;
	private int actionDeadlineTick;

	// Where to move once the plan body is exhausted, and whether that final move has been kicked
	// off yet - null despawnTarget means "no despawn phase" (e.g. raw debug-injected plans).
	private BlockPos despawnTarget;
	private boolean despawnMovementStarted;
	private boolean waveComplete;

	public PlanRunner(WendigoEntity self) {
		this.self = self;
	}

	/**
	 * Replaces whatever's currently running with this newly received plan body. Once the body is
	 * exhausted, the runner automatically moves to despawnTarget (if given) before signaling
	 * completion via {@link #isWaveComplete()} - the caller (WendigoManager) polls that to know
	 * when it's safe to remove the entity.
	 */
	public void start(JsonArray planBody, BlockPos despawnTarget) {
		this.self.getNavigation().stop();
		this.self.setNavigationFailed(false);
		this.topLevelSteps = planBody;
		this.topIndex = 0;
		this.actionQueue.clear();
		this.activeWhile = null;
		this.currentAction = null;
		this.despawnTarget = despawnTarget;
		this.despawnMovementStarted = false;
		this.waveComplete = false;
	}

	/** True once the plan body (and any despawn move) has fully finished. */
	public boolean isWaveComplete() {
		return this.waveComplete;
	}

	public void tick() {
		if (this.topLevelSteps == null) {
			return;
		}
		if (this.currentAction != null) {
			if (!isActionDone()) {
				return;
			}
			this.currentAction = null;
		}
		advance();
	}

	/** Pulls and resolves steps until an action starts consuming ticks, or the plan runs out. */
	private void advance() {
		for (int guard = 0; guard < MAX_STEPS_PER_TICK; guard++) {
			JsonObject next = nextActionStep();
			if (next == null) {
				if (this.despawnTarget != null && !this.despawnMovementStarted) {
					this.despawnMovementStarted = true;
					next = despawnMoveStep();
				} else {
					this.topLevelSteps = null; // exhausted - a new wave is what starts a fresh plan
					this.waveComplete = true;
					return;
				}
			}
			if (!startAction(next)) {
				continue; // finished instantly (e.g. posture.stare) - keep draining this tick
			}
			this.currentAction = next;
			return;
		}
		WendigoMod.LOGGER.warn("Wendigo {} exceeded the per-tick plan step budget - possible malformed plan", this.self.getId());
	}

	private static JsonObject despawnMoveStep() {
		JsonObject step = new JsonObject();
		step.addProperty("type", "internal.despawn_move");
		return step;
	}

	/** Pulls the next action_step to run, expanding control.if/control.while as they're reached. */
	private JsonObject nextActionStep() {
		while (true) {
			if (!this.actionQueue.isEmpty()) {
				return this.actionQueue.poll();
			}
			if (this.activeWhile != null) {
				JsonObject condition = this.activeWhile.getAsJsonObject("condition");
				if (this.whileIterationsRemaining <= 0 || !PlanPredicates.evaluate(condition, this.self)) {
					this.activeWhile = null;
					continue;
				}
				this.whileIterationsRemaining--;
				for (var element : this.activeWhile.getAsJsonArray("body")) {
					this.actionQueue.add(element.getAsJsonObject());
				}
				continue;
			}
			if (this.topIndex >= this.topLevelSteps.size()) {
				return null;
			}
			JsonObject step = this.topLevelSteps.get(this.topIndex++).getAsJsonObject();
			String type = step.get("type").getAsString();
			if (type.equals("control.if")) {
				boolean condition = PlanPredicates.evaluate(step.getAsJsonObject("condition"), this.self);
				JsonArray branch = condition ? step.getAsJsonArray("then") : step.getAsJsonArray("else");
				if (branch != null) {
					for (var element : branch) {
						this.actionQueue.add(element.getAsJsonObject());
					}
				}
				continue;
			}
			if (type.equals("control.while")) {
				this.activeWhile = step;
				this.whileIterationsRemaining = SemanticBands.maxIterations(step.get("max_iterations").getAsString());
				continue;
			}
			return step;
		}
	}

	/** One-time setup for an action_step. Returns false if it already finished within this tick. */
	private boolean startAction(JsonObject step) {
		String type = step.get("type").getAsString();
		this.actionDeadlineTick = this.self.tickCount + SemanticBands.ACTION_TIMEOUT_TICKS;
		switch (type) {
			case "movement.approach" -> {
				Player target = Targeting.nearestPlayer(this.self);
				if (target == null) {
					return false;
				}
				boolean started = this.self.getNavigation().moveTo(target, SemanticBands.speedMultiplier(step.get("speed").getAsString()));
				this.self.setNavigationFailed(!started);
			}
			case "movement.retreat_to_dark" -> {
				BlockPos dest = retreatDestination(step);
				if (dest == null) {
					return false;
				}
				boolean started = this.self.getNavigation().moveTo(dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5, 1.0);
				this.self.setNavigationFailed(!started);
			}
			case "movement.reposition" -> {
				BlockPos dest = PlanGeometry.repositionTarget(step, this.self);
				if (dest == null) {
					return false;
				}
				boolean started = this.self.getNavigation().moveTo(dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5, 1.0);
				this.self.setNavigationFailed(!started);
			}
			case "internal.despawn_move" -> {
				BlockPos dest = this.despawnTarget;
				boolean started = this.self.getNavigation().moveTo(dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5, 1.0);
				this.self.setNavigationFailed(!started);
			}
			case "movement.hold" -> {
				this.self.getNavigation().stop();
				return false;
			}
			case "memory.store_dark_location" -> {
				double radius = SemanticBands.searchRadiusBlocks(step.get("search_radius").getAsString());
				this.self.setStoredDarkLocation(PlanGeometry.findDarkSpot(this.self, radius));
				return false;
			}
			case "posture.stare" -> {
				this.self.setStaring(step.get("enabled").getAsBoolean());
				return false;
			}
			case "timing.wait" -> {
				int[] range = SemanticBands.waitTicks(step.get("duration").getAsString());
				int ticks = range[0] + this.self.getRandom().nextInt(range[1] - range[0] + 1);
				this.actionDeadlineTick = this.self.tickCount + ticks;
			}
			case "sound.ambient_cue" -> {
				return false; // no sound system wired up yet - documented as a no-op in the schema
			}
			case "control.none" -> {
				return false;
			}
			default -> {
				WendigoMod.LOGGER.warn("Wendigo {} got unknown action type '{}', skipping", this.self.getId(), type);
				return false;
			}
		}
		return true;
	}

	/** Whether the in-progress action has reached its completion condition, or timed out. */
	private boolean isActionDone() {
		if (this.self.tickCount >= this.actionDeadlineTick) {
			this.self.getNavigation().stop();
			return true;
		}
		String type = this.currentAction.get("type").getAsString();
		return switch (type) {
			case "movement.approach" -> arrivedNearPlayer() || navigationFinished() || checkStuck();
			case "movement.retreat_to_dark", "movement.reposition", "internal.despawn_move" ->
				navigationFinished() || checkStuck();
			case "timing.wait" -> false; // only the deadline check above ends a wait
			default -> true;
		};
	}

	private boolean navigationFinished() {
		PathNavigation nav = this.self.getNavigation();
		return nav.isDone() && !nav.isInProgress();
	}

	/** Backs predicate.player_unreachable - a stuck navigator ends the action early so the plan can react. */
	private boolean checkStuck() {
		if (this.self.getNavigation().isStuck()) {
			this.self.setNavigationFailed(true);
			return true;
		}
		return false;
	}

	private boolean arrivedNearPlayer() {
		Player player = Targeting.nearestPlayer(this.self);
		return player != null && this.self.distanceTo(player) <= SemanticBands.ARRIVAL_DISTANCE;
	}

	private BlockPos retreatDestination(JsonObject step) {
		if ("stored".equals(step.get("source").getAsString()) && this.self.getStoredDarkLocation() != null) {
			return this.self.getStoredDarkLocation();
		}
		return PlanGeometry.findDarkSpot(this.self, SemanticBands.searchRadiusBlocks("near"));
	}
}
