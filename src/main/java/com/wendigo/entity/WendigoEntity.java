package com.wendigo.entity;

import java.util.List;

import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import com.wendigo.plan.PlanRunner;
import com.wendigo.spatial.DarkSpotScanner;

/**
 * Reuses vanilla Enderman for its class (attributes, teleport/fall-damage immunity etc.), but is
 * always invisible, has no AI Goals of its own beyond basic swim/float safety, and swaps in a low
 * horizontal "crawling" hitbox/pose whenever it's moving, or whenever its current position simply
 * doesn't have room to stand (see updatePose) - which, combined with DarkSpotScanner's spawn scan
 * and DarknessNodeEvaluator's pathfinding, lets it spawn in and route through single-block
 * crevices a standing hitbox never could. All visuals come from the {@link WendigoVisual} rig
 * attached in {@code WendigoMod}'s ServerEntityEvents hooks, not from this entity's own
 * (suppressed) model.
 */
public class WendigoEntity extends EnderMan {
    // Enderman's own tall/narrow hitbox doesn't suit a low horizontal crawl -- swap to a low
    // profile while crawling. Reuses Pose.SWIMMING as the "crawling" signal since Enderman never
    // naturally enters it (no swim/sneak AI), rather than trying to swap the entity's actual Java
    // class, which Minecraft has no runtime support for.
    //
    // This hitbox is invisible and purely physical (the crawl *look* comes entirely from
    // WendigoVisual's animation matrices, which don't read these dimensions at all), and this entity
    // is in the crawling pose for essentially all of its real movement (tick() switches to it the
    // instant horizontal velocity is nonzero). A width at or near a full block (>=~0.9) can straddle
    // two neighboring columns depending on fractional position (no margin at all), which is exactly
    // what caused a reported ledge-catching/spin-in-place stall earlier - the entity would collide
    // with both columns near an inside corner and repeatedly re-turn to route around a collision the
    // pathfinder's own node graph (which reasons in whole blocks) never saw. If that symptom
    // reappears, a too-wide value here is the first thing to check.
    //
    // Height is 0.9F, not 1.0F - deliberately just under a hard cutoff in vanilla's own pathfinding
    // math (NodeEvaluator.prepare derives its required vertical clearance as
    // Mth.floor(getBbHeight() + 1), verified via bytecode), where 1.0F would round up to needing a
    // genuine 2-tall gap instead of 1. Crawl pose exists specifically to fit through single-block
    // crevices - crossing that line would silently break that while still reading as "about 1 block
    // tall", so it's pinned just below it on purpose, not an oversight.
    private static final EntityDimensions CRAWLING_DIMENSIONS = EntityDimensions.scalable(0.6F, 0.9F);
    // Deliberately shorter than the model's real proportions (which stand about 3 blocks tall) - this
    // is the physical collision box only, sized for easy pathfinding through ordinary 2-tall corridors
    // (a mineshaft's own typical clearance) while standing, not for visually matching the model.
    // DarkSpotScanner.hasStandingClearance intentionally still requires a genuine 3-tall gap to enter/
    // stay in this pose at all (unrelated to this constant's own value, not derived from it) - so
    // whenever this hitbox actually is standing, there's always at least a block of headroom above it
    // for the taller model, and a merely-2-tall space (not enough for the model, even though this
    // hitbox alone would fit) still forces crawl pose instead of letting the model clip the ceiling.
    private static final EntityDimensions STANDING_DIMENSIONS = EntityDimensions.scalable(0.6F, 2.0F);
    private static final double CRAWL_SPEED_THRESHOLD_SQR = 1.0E-4;
    // Flipping pose (and its bounding-box resize via refreshDimensions()) on every single tick's
    // raw velocity reading flickers rapidly while walking down slopes/stairs. Requiring the desired
    // pose to be stable for a few consecutive ticks before actually committing to it damps that
    // flicker.
    private static final int POSE_SWITCH_DEBOUNCE_TICKS = 5;

    private WendigoVisual visual;
    private boolean staring;
    private Pose desiredPose = Pose.STANDING;
    private int desiredPoseStableTicks;
    private final PlanRunner planRunner = new PlanRunner(this);
    private BlockPos storedDarkLocation;
    private boolean navigationFailed;

    public WendigoEntity(EntityType<? extends EnderMan> entityType, Level level) {
        super(entityType, level);
        // Every sound this entity makes is meant to be deliberate, engine-triggered (see
        // WendigoSounds) - vanilla's own automatic ambient/hurt/death sound-emission (gated on
        // isSilent(), confirmed via javap against LivingEntity) would otherwise layer an uncontrolled
        // extra idle noise on top of that. Silencing at the entity level, rather than overriding
        // getAmbientSound() to a custom value, since WendigoSounds.play() goes through
        // ServerLevel.playSound directly - unaffected by isSilent() either way.
        this.setSilent(true);
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        return new DarknessAwareNavigation(this, level);
    }

    /** Cobwebs shouldn't slow this thing down any more than they slow down a spider - a cave-horror
     * mob getting caught fumbling in a web it walked into on purpose would undercut the whole point.
     * Mirrors Spider's own override exactly (verified via javap: Spider.makeStuckInBlock skips the
     * super call specifically for Blocks.COBWEB, calling it normally for every other stuck-in block
     * like honey or powder snow) - cobwebs have no special PathType/pathfinding malus of their own
     * either, so this alone is enough; nothing about route-planning needs to change to match. */
    @Override
    public void makeStuckInBlock(BlockState state, Vec3 motionMultiplier) {
        if (!state.is(Blocks.COBWEB)) {
            super.makeStuckInBlock(state, motionMultiplier);
        }
    }

    /** See DarknessNodeEvaluator.lightTolerant - PlanRunner toggles this around the two primitives
     * (combat.lunge_attack, combat.break_torch) that are meant to cross into light on purpose. */
    public void setLightTolerantPathing(boolean tolerant) {
        if (this.getNavigation() instanceof DarknessAwareNavigation navigation) {
            navigation.setLightTolerant(tolerant);
        }
    }

    /** See DarknessNodeEvaluator.severityPercent - PlanRunner.start calls this with the current
     * wave's severity so early-stage pathfinding can hard-refuse routes through real light. */
    public void setSeverityPercent(int severityPercent) {
        if (this.getNavigation() instanceof DarknessAwareNavigation navigation) {
            navigation.setSeverityPercent(severityPercent);
        }
    }

    @Override
    protected void registerGoals() {
        // Deliberately NOT calling super.registerGoals() -- Enderman's own goal set (teleport
        // toward target, melee attack, block pickup/place, stare-based aggro) isn't wanted here.
        // Baseline drowning prevention only; movement is driven externally.
        this.goalSelector.addGoal(0, new FloatGoal(this));
    }

    @Override
    public void tick() {
        super.tick();
        updatePose();

        if (!this.level().isClientSide()) {
            this.planRunner.tick();
        }
    }

    /** Whether the current position physically has room to stand - see DarkSpotScanner's own
     * layered spawn scan, which now accepts a single-block crevice as a valid spot (no more requiring
     * the block above to be passable too) precisely because this can shrink to fit one. */
    private boolean hasStandingClearanceHere() {
        return DarkSpotScanner.hasStandingClearance(this.level(), this.blockPosition());
    }

    /** Crawl pose is forced by two independent triggers - moving (the original mechanic) or simply
     * not having room to stand at all right now (new: DarknessNodeEvaluator's entityHeight override
     * lets pathfinding route through a 1-block gap, and the spawn scan can now place it in one - so
     * physically fitting there has to be automatic, not just a byproduct of already moving). */
    private void updatePose() {
        boolean moving = isMoving();
        boolean noRoomToStand = !hasStandingClearanceHere();
        Pose desired = (moving || noRoomToStand) ? Pose.SWIMMING : Pose.STANDING;
        if (desired == this.desiredPose) {
            this.desiredPoseStableTicks++;
        } else {
            this.desiredPose = desired;
            this.desiredPoseStableTicks = 0;
        }
        // Debounce is only needed going crawling -> standing (stairs/slopes cause brief mid-walk
        // velocity dips that would otherwise flicker the pose back and forth). Starting to crawl
        // from a genuine standstill has no such ambiguity, and waiting the full debounce there was
        // making a sudden move (e.g. combat.lunge_attack out of a held stare) visibly float for a
        // few ticks before the crawl animation caught up - switch to it immediately instead. A
        // sudden loss of standing room (arriving at a crevice) gets the same instant treatment, for
        // the same reason - waiting out a debounce while physically wedged makes no sense either.
        boolean startingToCrawl = desired == Pose.SWIMMING && this.getPose() == Pose.STANDING;
        boolean debounceSatisfied = this.desiredPoseStableTicks >= POSE_SWITCH_DEBOUNCE_TICKS;
        if ((startingToCrawl || debounceSatisfied) && this.getPose() != desired) {
            this.setPose(desired);
            this.refreshDimensions();
        }
    }

    /** Syncs pose/hitbox to whatever's actually at the current position right now, bypassing the
     * debounce entirely - called once, right after snapTo places this entity at its resolved spawn
     * spot and before addFreshEntity starts ticking it, so a spawn landing in a single-block crevice
     * never spends even one tick with a too-tall standing hitbox jammed into it (which tick()'s own
     * debounced version, running after physics for that first tick has already happened, would be
     * one tick too late to prevent). */
    public void syncPoseToSpawnPosition() {
        Pose desired = hasStandingClearanceHere() ? Pose.STANDING : Pose.SWIMMING;
        this.desiredPose = desired;
        this.desiredPoseStableTicks = 0;
        this.setPose(desired);
        this.refreshDimensions();
    }

    /**
     * Starts a wave's plan body, running to completion and then attempting despawnCandidates in
     * order (falling back to a live scan if all fail) - see PlanRunner. allSpots is the full
     * labeled spot_a..spot_f list (not just the despawn candidates) so movement.approach_spot can
     * resolve a label mid-plan.
     */
    public void startWave(JsonObject plan, List<BlockPos> despawnCandidates, List<BlockPos> allSpots, int severityPercent, boolean tierGatingBypassed) {
        this.planRunner.start(plan, despawnCandidates, allSpots, severityPercent, tierGatingBypassed);
    }

    /** True once the current wave's plan body and despawn move have both finished. */
    public boolean isWaveComplete() {
        return this.planRunner.isWaveComplete();
    }

    /** What actually happened this wave so far - see PlanRunner.EncounterOutcome/EncounterHistory. */
    public PlanRunner.EncounterOutcome getOutcome() {
        return this.planRunner.outcome();
    }

    /** True while a player is currently a forced rider - see PlanRunner.isForcingRide. */
    public boolean isForcingRide() {
        return this.planRunner.isForcingRide();
    }

    /** Resolves a still-forced rider (damage or a clean release) before this entity is discarded -
     * see PlanRunner.resolveRiderOnEnd. Called both internally (any normal wave-ending path) and
     * externally by WendigoManager right before a forced backstop discard. */
    public void resolveRiderOnEnd() {
        this.planRunner.resolveRiderOnEnd();
    }

    /** Entry point for WendigoCommands' {@code /wendigo plantest} debug command - runs the plan
     * body with no despawn phase, independent of the wave system. Tier gating bypassed since this
     * is a raw debug tool, not a real wave. */
    public void debugInjectPlan(JsonObject plan) {
        this.planRunner.start(plan, null, null, 100, true);
    }

    public boolean isCrawling() {
        return this.getPose() == Pose.SWIMMING;
    }

    /** Real horizontal-velocity movement check, independent of pose - see WendigoVisual's three-way
     * animation pick (crawl while moving, a held crawl-idle pose while crawling but stopped, or the
     * plain rest pose while standing and stopped). Same threshold updatePose() itself uses to decide
     * whether to force the crawl hitbox. */
    public boolean isMoving() {
        return this.getDeltaMovement().horizontalDistanceSqr() > CRAWL_SPEED_THRESHOLD_SQR;
    }

    /** Real horizontal speed in blocks/tick - see WendigoVisual's movement-speed-scaled crawl
     * playback, which buckets this against the entity's own base MOVEMENT_SPEED attribute rather
     * than tracking which PlanRunner call site (or semantic speed band) requested the current move,
     * so it stays correct regardless of which movement primitive is driving it. */
    public double horizontalSpeed() {
        return Math.sqrt(this.getDeltaMovement().horizontalDistanceSqr());
    }

    @Override
    protected EntityDimensions getDefaultDimensions(Pose pose) {
        if (pose == Pose.SWIMMING) {
            return CRAWLING_DIMENSIONS;
        }
        if (pose == Pose.STANDING) {
            return STANDING_DIMENSIONS;
        }
        return super.getDefaultDimensions(pose);
    }

    @Override
    public boolean isInvisible() {
        return true;
    }

    /** Base Entity.remove doesn't eject passengers on its own (confirmed via bytecode - neither it
     * nor Mob does this). Without this override, a player still forcibly mounted (see
     * PlanRunner.beginForcedRide) when the wave gets discarded via a backstop path that doesn't go
     * through PlanRunner.completeWave (see WendigoManager.checkForcedWaveEnd) would be left riding an
     * entity that no longer exists. Runs for every removal path, not just that one, as a blanket
     * guarantee. */
    @Override
    public void remove(Entity.RemovalReason reason) {
        this.ejectPassengers();
        super.remove(reason);
    }

    /**
     * Whether {@link WendigoVisual} should render the glowing-eyes overlay right now -- a plain
     * on/off toggle with no logic of its own about when to use it.
     */
    public void setStaring(boolean staring) {
        this.staring = staring;
    }

    public boolean isStaring() {
        return this.staring;
    }

    /** Set by memory.store_dark_location, read by predicate.dark_location_stored and retreat_to_dark(source=stored). */
    public BlockPos getStoredDarkLocation() {
        return this.storedDarkLocation;
    }

    public void setStoredDarkLocation(BlockPos pos) {
        this.storedDarkLocation = pos;
    }

    /** Set by PlanRunner when a movement action can't find/keep a path, read by predicate.player_unreachable. */
    public boolean isNavigationFailed() {
        return this.navigationFailed;
    }

    public void setNavigationFailed(boolean navigationFailed) {
        this.navigationFailed = navigationFailed;
    }

    public WendigoVisual getVisual() {
        return this.visual;
    }

    public void setVisual(WendigoVisual visual) {
        this.visual = visual;
    }
}
