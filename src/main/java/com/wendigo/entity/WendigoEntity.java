package com.wendigo.entity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.goal.FloatGoal;
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
    // Width is deliberately narrow (well under a full block), NOT a "spider-ish" wide footprint --
    // this hitbox is invisible and purely physical (the crawl *look* comes entirely from
    // WendigoVisual's animation matrices, which don't read these dimensions at all), and this
    // entity is in the crawling pose for essentially all of its real movement (tick() switches to
    // it the instant horizontal velocity is nonzero). A hitbox wider than one block routinely
    // overlaps the neighboring column at any ledge, so at a drop edge it would physically catch the
    // block's corner -- collision shoves it back, the path follower reissues the same node, and it
    // visibly spins in place until it wiggles free. A width that fits within a single column avoids
    // that entirely, same as every vanilla ground mob.
    private static final EntityDimensions CRAWLING_DIMENSIONS = EntityDimensions.scalable(0.6F, 0.6F);
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
        if (this.desiredPoseStableTicks >= POSE_SWITCH_DEBOUNCE_TICKS && this.getPose() != desired) {
            this.setPose(desired);
            this.refreshDimensions();
        }

        if (!this.level().isClientSide()) {
            this.planRunner.tick();
        }
    }

    /** Starts a wave's plan body, running to completion and then to despawnTarget - see PlanRunner. */
    public void startWave(JsonArray planBody, BlockPos despawnTarget) {
        this.planRunner.start(planBody, despawnTarget);
    }

    /** True once the current wave's plan body and despawn move have both finished. */
    public boolean isWaveComplete() {
        return this.planRunner.isWaveComplete();
    }

    /** Entry point for WendigoCommands' {@code /wendigo plantest} debug command - runs the plan
     * body with no despawn phase, independent of the wave system. */
    public void debugInjectPlan(JsonObject plan) {
        this.planRunner.start(plan.getAsJsonArray("plan"), null);
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
