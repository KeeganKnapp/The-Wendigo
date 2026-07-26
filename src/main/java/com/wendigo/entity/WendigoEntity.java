package com.wendigo.entity;

import java.util.List;

import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.level.Level;

import com.wendigo.plan.PlanRunner;

/**
 * Reuses vanilla Enderman for its class (attributes, teleport/fall-damage immunity etc.), but is
 * always invisible, has no AI Goals of its own beyond basic swim/float safety, and swaps in a low
 * horizontal "crawling" hitbox/pose whenever it's moving. All visuals come from the
 * {@link WendigoVisual} rig attached in {@code WendigoMod}'s ServerEntityEvents hooks, not from
 * this entity's own (suppressed) model.
 */
public class WendigoEntity extends EnderMan {
    // Enderman's own tall/narrow hitbox doesn't suit a low horizontal crawl -- swap to a low
    // profile while crawling. Reuses Pose.SWIMMING as the "crawling" signal since Enderman never
    // naturally enters it (no swim/sneak AI), rather than trying to swap the entity's actual Java
    // class, which Minecraft has no runtime support for.
    //
    // Effectively one full block wide/tall per explicit user request - this hitbox is invisible and
    // purely physical (the crawl *look* comes entirely from WendigoVisual's animation matrices,
    // which don't read these dimensions at all), and this entity is in the crawling pose for
    // essentially all of its real movement (tick() switches to it the instant horizontal velocity
    // is nonzero). Width is 0.98, not a literal 1.0: at exactly one block, the hitbox can straddle
    // two neighboring columns depending on fractional position (no margin at all), which is exactly
    // what caused the reported ledge-catching/spin-in-place stalls - the entity would collide with
    // both columns near an inside corner and repeatedly re-turn to route around a collision the
    // pathfinder's own node graph (which reasons in whole blocks) never saw. 0.98 leaves enough
    // margin to always fit inside a single column while still reading as "1 block" for any practical
    // purpose. Confirmed via this file's own prior comment flagging this exact tradeoff as "the first
    // thing to revisit" if the symptom reappeared - it did.
    private static final EntityDimensions CRAWLING_DIMENSIONS = EntityDimensions.scalable(0.6F, 0.4F);
    // Real Enderman standing height is 2.9 blocks -- shrunk to a reasonable "upright but not
    // freakishly tall" figure so it reliably fits normal 2-3 tall spaces in either pose, not just
    // while crawling.
    private static final EntityDimensions STANDING_DIMENSIONS = EntityDimensions.scalable(0.9F, 1.95F);
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
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        return new DarknessAwareNavigation(this, level);
    }

    /** See DarknessNodeEvaluator.lightTolerant - PlanRunner toggles this around the two primitives
     * (combat.lunge_attack, combat.break_torch) that are meant to cross into light on purpose. */
    public void setLightTolerantPathing(boolean tolerant) {
        if (this.getNavigation() instanceof DarknessAwareNavigation navigation) {
            navigation.setLightTolerant(tolerant);
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
        boolean moving = this.getDeltaMovement().horizontalDistanceSqr() > CRAWL_SPEED_THRESHOLD_SQR;
        Pose desired = moving ? Pose.SWIMMING : Pose.STANDING;
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
        // few ticks before the crawl animation caught up - switch to it immediately instead.
        boolean startingToCrawl = desired == Pose.SWIMMING && this.getPose() == Pose.STANDING;
        boolean debounceSatisfied = this.desiredPoseStableTicks >= POSE_SWITCH_DEBOUNCE_TICKS;
        if ((startingToCrawl || debounceSatisfied) && this.getPose() != desired) {
            this.setPose(desired);
            this.refreshDimensions();
        }

        if (!this.level().isClientSide()) {
            this.planRunner.tick();
        }
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

    /** Entry point for WendigoCommands' {@code /wendigo plantest} debug command - runs the plan
     * body with no despawn phase, independent of the wave system. Tier gating bypassed since this
     * is a raw debug tool, not a real wave. */
    public void debugInjectPlan(JsonObject plan) {
        this.planRunner.start(plan, null, null, 100, true);
    }

    public boolean isCrawling() {
        return this.getPose() == Pose.SWIMMING;
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

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.AMBIENT_CAVE.value();
    }

    public WendigoVisual getVisual() {
        return this.visual;
    }

    public void setVisual(WendigoVisual visual) {
        this.visual = visual;
    }
}
