package com.wendigo.entity;

import java.util.List;

import com.google.gson.JsonObject;

import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import com.nyfaria.awcapi.ClimberHelper;
import com.nyfaria.awcapi.entity.ClimberComponent;
import com.nyfaria.awcapi.entity.IAdvancedClimber;

import com.wendigo.WendigoMod;
import com.wendigo.plan.PlanRunner;
import com.wendigo.spatial.CaveScaleScanner.CaveScale;
import com.wendigo.spatial.DarkSpotScanner;

/**
 * Reuses vanilla Enderman for its class (attributes, teleport/fall-damage immunity etc.), but is
 * always invisible, has no AI Goals of its own beyond basic swim/float safety, and swaps in a low
 * horizontal "crawling" hitbox/pose whenever it's moving, whenever its current position simply
 * doesn't have room to stand, or whenever it isn't resting on a horizontal floor at all (see
 * updatePose) - which, combined with DarkSpotScanner's spawn scan and
 * DarknessAwareClimberNodeEvaluator's pathfinding, lets it spawn in and route through single-block
 * crevices (and, via AWCAPI, up walls and across ceilings) a standing hitbox never could. All
 * visuals come from the {@link WendigoVisual} rig attached in {@code WendigoMod}'s
 * ServerEntityEvents hooks, not from this entity's own (suppressed) model.
 *
 * <p>Also implements {@link IAdvancedClimber} (from the AWCAPI mod dependency - see build.gradle)
 * for spider-style wall/ceiling climbing physics, always-on rather than a toggled mode (mirrors
 * how vanilla Spider is wired - a ClimberComponent decides ground vs. attached travel per tick on
 * its own; there's no separate "climbing mode" to switch into). See
 * DarknessAwareClimberNavigation's own doc comment for the pathfinding side.
 */
public class WendigoEntity extends EnderMan implements IAdvancedClimber {
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
    // A fast, jittery corner transition (e.g. an outside/convex ceiling-to-wall corner) can read as
    // briefly resting on a horizontal floor for a tick or two before AWCAPI's own attachment
    // settles onto the new surface - see isRestingOnFloor's own doc comment for why that's worth
    // debouncing here, not just smoothing downstream.
    private static final int RESTING_ON_FLOOR_DEBOUNCE_TICKS = 5;
    // Mirrors SpiderMixin's own FOLLOW_RANGE_INCREASE exactly (verified via javap: an ADD_VALUE +8.0
    // permanent modifier, applied once at construction) - a climbing route up/around a wall or
    // ceiling covers more real distance than a straight-line target-tracking check accounts for, so
    // without this a wendigo could lose track of a target it's genuinely still capable of reaching.
    private static final AttributeModifier FOLLOW_RANGE_CLIMB_BONUS = new AttributeModifier(
        WendigoMod.id("climb_follow_range_bonus"), 8.0, AttributeModifier.Operation.ADD_VALUE);
    // Attributes.WATER_MOVEMENT_EFFICIENCY (verified via javap against LivingEntity#travelInWater in
    // this MC version's own mapped jar - the modern, data-driven replacement for the old "override
    // getWaterSlowDown()" approach) blends water movement toward full out-of-water speed as it
    // approaches 1.0 - already part of every LivingEntity's base attribute set (createLivingAttributes,
    // confirmed via javap - EnderMan.createAttributes() builds on it, so no separate registration is
    // needed here), just defaulted to 0 (normal water drag) for a generic mob. A permanent +1.0
    // modifier, same pattern/timing as FOLLOW_RANGE_CLIMB_BONUS above, makes the wendigo swim at
    // essentially full speed instead of the usual heavy water slowdown - see isSensitiveToWater() for
    // the other half of "don't act like vanilla Enderman in water" (that one stops it taking damage
    // and being knocked into a panic-teleport by water contact; this one is the actual swim speed).
    private static final AttributeModifier FAST_SWIM_BONUS = new AttributeModifier(
        WendigoMod.id("fast_swim_bonus"), 1.0, AttributeModifier.Operation.ADD_VALUE);

    private WendigoVisual visual;
    private boolean staring;
    private Pose desiredPose = Pose.STANDING;
    private int desiredPoseStableTicks;
    private boolean restingOnFloorDebounced = true;
    private int restingOnFloorStableTicks;
    private final PlanRunner planRunner = new PlanRunner(this);
    private BlockPos storedDarkLocation;
    private boolean navigationFailed;
    // The single player/group member this wendigo is currently committed to - set once by
    // WendigoManager (on spawn, or when re-targeting after a fully lost target) and read by
    // Targeting.nearestPlayer, which checks this before falling back to a plain nearest-player
    // lookup. Locking here rather than continuously re-resolving "nearest player" is what makes
    // orbit (see PlanRunner.startOrbit) commit to one target instead of drifting toward whoever's
    // physically closest at any given moment - a deliberate design choice, not an oversight.
    private ServerPlayer lockedTarget;

    // AWCAPI climbing state - see the class doc comment. Wired exactly as verified via javap
    // against Nyf's Spiders' own SpiderMixin (the reference implementation): a single always-on
    // ClimberComponent, physics overrides below delegating to ClimberHelper's static glue methods
    // in the same call order/pre-post-super pattern the mixin uses, and four lerp fields AWCAPI's
    // own internal smoothing writes through setLerp*() - not rendering-visible here since this
    // entity is always invisible (see isInvisible()), but still required to implement the interface.
    private final ClimberComponent climberComponent = new ClimberComponent(this);
    private Float lerpYRot;
    private Float lerpXRot;
    private Float lerpYHeadRot;
    private int lerpHeadSteps;

    public WendigoEntity(EntityType<? extends EnderMan> entityType, Level level) {
        super(entityType, level);
        // Every sound this entity makes is meant to be deliberate, engine-triggered (see
        // WendigoSounds) - vanilla's own automatic ambient/hurt/death sound-emission (gated on
        // isSilent(), confirmed via javap against LivingEntity) would otherwise layer an uncontrolled
        // extra idle noise on top of that. Silencing at the entity level, rather than overriding
        // getAmbientSound() to a custom value, since WendigoSounds.play() goes through
        // ServerLevel.playSound directly - unaffected by isSilent() either way.
        this.setSilent(true);
        ClimberHelper.initClimber(this);
        this.getAttribute(Attributes.FOLLOW_RANGE).addPermanentModifier(FOLLOW_RANGE_CLIMB_BONUS);
        this.getAttribute(Attributes.WATER_MOVEMENT_EFFICIENCY).addPermanentModifier(FAST_SWIM_BONUS);
        // Tried raising STEP_HEIGHT here (initClimber sets it to 0.1) to speed up ordinary floor
        // ledge-crossing - reverted. It's the one thing that changed between a live test where wall/
        // ceiling attachment tilt worked and a later one where the rig stayed flat everywhere, and
        // AWCAPI's own walking-side probe (ClimberComponent.updateWalkingSide, which getGroundSide()
        // reads) scans nearby collision boxes relative to the mob's own bounding box - a much taller
        // allowed step plausibly changes how that geometry reads near a wall. Left at AWCAPI's own
        // default rather than guessing a "safer" intermediate value blind; the original "feels slow
        // climbing a block at a time" complaint needs revisiting only once attachment itself is
        // confirmed working correctly again.
        //
        // Widened from AWCAPI's own defaults (2.0/1.25, verified via javap against ClimberComponent's
        // constructor) - live testing found the wendigo sometimes losing its wall/ceiling attachment
        // entirely (falling) specifically crossing an outside/convex corner (e.g. ceiling wrapping
        // onto a wall around a protruding edge). These are exactly the search-range parameters
        // ClimberComponent.updateOffsetsAndOrientation uses (via CollisionSmoothingUtil.findClosestPoint)
        // to find a nearby surface to refine attachment against each tick - a convex corner is
        // precisely the geometry where a short search radius can come up empty on both adjoining
        // faces at once. First pass, adjust by feel - this is a genuine AWCAPI-side edge case being
        // mitigated, not something confirmed fixed outright (no way to test this live from here).
        this.setCollisionsInclusionRange(4.0F);
        this.setCollisionsSmoothingRange(2.5F);
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        return new DarknessAwareClimberNavigation(this, level);
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

    /** Vanilla Enderman returns true here (verified via javap), which is what actually drives the
     * "teleports away in water" behavior the user asked to remove - it's not a dedicated water-avoid
     * goal (this entity has none, see the class doc comment), it's LivingEntity's own baseTick
     * calling hurtServer(damageSources().drown(), 1.0F) every tick this is true while in water or
     * rain (confirmed via javap against LivingEntity), and EnderMan's own hurtServer override
     * (verified via javap) has a 1-in-10 chance to call teleport() on any hurtServer call whose
     * damage source isn't from a LivingEntity - which environmental drown damage always qualifies
     * as. Repeated water-damage ticks compound that into "teleports away almost immediately on
     * touching water". Returning false here removes the damage entirely, and with it this whole
     * teleport pathway - see FAST_SWIM_BONUS for the other half (making it actually swim well once
     * it's no longer being punished for being in water at all). */
    @Override
    public boolean isSensitiveToWater() {
        return false;
    }

    /** This entity is a WendigoManager-owned singleton (one per level, spawned/relocated/discarded
     * entirely under its own explicit control - see WaveState.entity) - never written to chunk NBT,
     * so a server restart never reloads a stale copy WendigoManager's own in-memory WaveState (reset
     * on restart) has no idea exists. Without this, a wendigo present in a loaded chunk at save time
     * would come back as an untracked duplicate alongside whatever the manager spawns fresh, since
     * vanilla persistence and this mod's own lifecycle management would then be running in parallel,
     * unaware of each other. Verified via javap: Entity.shouldBeSaved() (Mob doesn't override it). */
    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    /** Same reasoning as shouldBeSaved - this entity's removal is meant to be entirely deliberate
     * (WendigoManager's own distance/trapped/target-lost checks, not vanilla's own distance-based
     * despawn-when-far heuristic running in parallel and unpredictably discarding it out from under
     * the manager's WaveState). Verified via javap: Mob.removeWhenFarAway(double). */
    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    // --- IAdvancedClimber physics wiring -----------------------------------------------------
    // Every override below mirrors SpiderMixin's exact call order (verified via javap -c
    // disassembly of the actual compiled mixin, not guessed from documentation): ClimberHelper
    // decides per tick whether the mob is attached to a climbable surface and, if so, substitutes
    // its own surface-relative physics in place of (or alongside) the vanilla gravity-based path.

    @Override
    public void aiStep() {
        ClimberHelper.livingTickClimber(this);
        super.aiStep();
    }

    @Override
    public void move(MoverType type, Vec3 delta) {
        ClimberHelper.handleMove(this, type, delta, true);
        super.move(type, delta);
        ClimberHelper.handleMove(this, type, delta, false);
    }

    @Override
    public BlockPos getOnPos() {
        return ClimberHelper.getAdjustedOnPosition(this, super.getOnPos());
    }

    @Override
    public void travel(Vec3 relative) {
        if (!ClimberHelper.handleTravel(this, relative)) {
            super.travel(relative);
        }
        ClimberHelper.postTravel(this, relative);
    }

    @Override
    public void jumpFromGround() {
        if (!ClimberHelper.handleJump(this)) {
            super.jumpFromGround();
        }
    }

    @Override
    public void lookAt(EntityAnchorArgument.Anchor anchor, Vec3 target) {
        Vec3 relative = target.subtract(this.position());
        Vec3 local = this.getOrientation().getLocal(relative);
        super.lookAt(anchor, this.position().add(local));
    }

    @Override
    public ClimberComponent getClimberComponent() {
        return this.climberComponent;
    }

    /** AWCAPI's own default (true unless a deny-list tag is set - see IAdvancedClimber's own doc
     * comment, verified via javap) treats a fence post as just another climbable vertical surface,
     * the same as a real wall - reads wrong for a large creature climbing on a thin, spindly fence.
     * Excluded explicitly rather than via a deny-list tag since this is the only exclusion needed
     * so far - extends rather than replaces whatever the default already covers. */
    @Override
    public boolean canClimbOnBlock(BlockState state, BlockPos pos) {
        return IAdvancedClimber.super.canClimbOnBlock(state, pos) && !state.is(BlockTags.FENCES);
    }

    @Override
    public Mob asMob() {
        return this;
    }

    @Override
    public float getMovementSpeed() {
        return (float) this.getAttributeValue(Attributes.MOVEMENT_SPEED);
    }

    @Override
    public float getBlockSlipperiness(BlockPos pos) {
        return this.level().getBlockState(pos).getBlock().getFriction();
    }

    @Override
    public void setLerpYRot(Float lerpYRot) {
        this.lerpYRot = lerpYRot;
    }

    @Override
    public void setLerpXRot(Float lerpXRot) {
        this.lerpXRot = lerpXRot;
    }

    @Override
    public void setLerpYHeadRot(Float lerpYHeadRot) {
        this.lerpYHeadRot = lerpYHeadRot;
    }

    @Override
    public void setLerpHeadSteps(int lerpHeadSteps) {
        this.lerpHeadSteps = lerpHeadSteps;
    }

    // --- end IAdvancedClimber physics wiring --------------------------------------------------

    /** See DarknessMalus.lightTolerant - PlanRunner toggles this around the two primitives
     * (combat.lunge_attack, combat.break_torch) that are meant to cross into light on purpose. */
    public void setLightTolerantPathing(boolean tolerant) {
        if (this.getNavigation() instanceof DarknessAwareClimberNavigation navigation) {
            navigation.setLightTolerant(tolerant);
        }
    }

    /** See DarknessMalus.severityPercent - PlanRunner.start calls this with the current
     * wave's severity so early-stage pathfinding can hard-refuse routes through real light. */
    public void setSeverityPercent(int severityPercent) {
        if (this.getNavigation() instanceof DarknessAwareClimberNavigation navigation) {
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
        // Matches SpiderMixin's own injection point (verified via javap -v: @Inject(method="tick",
        // at=@At("RETURN"))) - ClimberHelper.tickClimber updates attachment/orientation state once
        // per tick, after the rest of this tick's physics have already run.
        ClimberHelper.tickClimber(this);
        updateRestingOnFloorDebounce();
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

    /** True only while resting on a genuine horizontal floor - false while attached to a wall or
     * ceiling. A 2.0F-tall standing hitbox only makes physical sense flat on the floor; off a wall it
     * would jut straight out into open air, so climbing forces the same low crawl hitbox a floor
     * crevice does (see updatePose). Also read by WendigoVisual to decide whether the rig needs a
     * wall/ceiling-tilted orientation this tick - deliberately reads getOrientation().normal (the
     * same signal WendigoVisual's own tilt composition is built from), not
     * ClimberComponent.getGroundSide(). Those two aren't the same thing (verified via javap):
     * getGroundSide() reads a separate "nearest walking surface" probe (updateWalkingSide) with only
     * a 0.1-block reach while not actively moving, which can disagree with the actual render-driving
     * attachment normal - using the same signal for both the pose switch and the tilt makes them
     * provably consistent with each other instead of two independent guesses that could drift apart.
     *
     * <p>Debounced (see updateRestingOnFloorDebounce), not the raw per-tick reading - this gates a
     * whole different WendigoVisual rendering codepath (plain standing/crawling vs. the tilted-rig
     * composition), not just a smoothed value, so a single noisy tick reading "on floor" mid-climb
     * (live testing: happens crossing certain outside/convex corners, e.g. ceiling to wall) would
     * otherwise cause a visible one-tick snap all the way back to plain floor rendering instead of a
     * smooth continuation of the climb. */
    public boolean isRestingOnFloor() {
        return this.restingOnFloorDebounced;
    }

    private boolean restingOnFloorRaw() {
        return this.getOrientation().normal.y > 0.9;
    }

    /** Symmetric: either direction only commits after RESTING_ON_FLOOR_DEBOUNCE_TICKS consecutive
     * readings that disagree with the currently-committed value - see isRestingOnFloor's own doc
     * comment for why a noisy blip shouldn't count. Used to be asymmetric (leaving the floor
     * committed instantly, on the theory that a genuine "just started climbing" transition should
     * never be delayed) - but a genuinely sloped floor sits right at the restingOnFloorRaw threshold
     * (normal.y just above/below 0.9) and AWCAPI's own collision-smoothing search jitters around it
     * tick to tick (the same noise WendigoVisual's ORIENTATION_LERP_FACTOR comment documents), so an
     * instant-leave reacted to every one of those jitters - which, combined with WendigoVisual
     * reading this same flag directly for its tilted-vs-flat rendering choice, produced a visible
     * three-way cycle while walking a slope (tilted crawl -> flat crawl -> standing -> back to
     * tilted) tracking the jitter far faster than a real wall/ceiling transition ever needs to be
     * caught. A few ticks of delay on a genuine climb-onto-a-wall transition is an acceptable
     * tradeoff for that no longer happening. */
    private void updateRestingOnFloorDebounce() {
        boolean raw = restingOnFloorRaw();
        if (raw == this.restingOnFloorDebounced) {
            this.restingOnFloorStableTicks = 0;
        } else {
            this.restingOnFloorStableTicks++;
            if (this.restingOnFloorStableTicks >= RESTING_ON_FLOOR_DEBOUNCE_TICKS) {
                this.restingOnFloorDebounced = raw;
                this.restingOnFloorStableTicks = 0;
            }
        }
    }

    /** Crawl pose is forced by three independent triggers - moving (the original mechanic), simply
     * not having room to stand at all right now (DarknessAwareClimberNodeEvaluator's entityHeight
     * override lets pathfinding route through a 1-block gap, and the spawn scan can now place it in one - so
     * physically fitting there has to be automatic, not just a byproduct of already moving), or not
     * resting on a horizontal floor at all (climbing a wall/ceiling - see isRestingOnFloor). */
    private void updatePose() {
        boolean moving = isMoving();
        boolean noRoomToStand = !hasStandingClearanceHere();
        boolean climbing = !isRestingOnFloor();
        Pose desired = (moving || noRoomToStand || climbing) ? Pose.SWIMMING : Pose.STANDING;
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
        //
        // "climbing" deliberately does NOT get that same instant treatment, unlike the other two -
        // on a sloped or naturally bumpy floor, isRestingOnFloor's underlying attachment-normal
        // reading is inherently noisy tick to tick (the same AWCAPI collision-smoothing noise
        // WendigoVisual's ORIENTATION_LERP_FACTOR comment documents), and letting a single noisy tick
        // instantly snap to crawl - while recovery back to standing still needed a full multi-tick
        // debounce - compounded into visible rapid flicker walking a slope. There's no real urgency
        // to react to "climbing" instantly the way there is for an actual lunge or a genuine crevice,
        // so debouncing it in both directions just makes it stop flickering, without giving up
        // standing pose in genuinely open, flat areas.
        boolean urgentCrawlTrigger = moving || noRoomToStand;
        boolean startingToCrawl = desired == Pose.SWIMMING && this.getPose() == Pose.STANDING && urgentCrawlTrigger;
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

    // How hard to nudge toward a ceiling/wall spawn spot's surface, in blocks/tick - just enough to
    // guarantee a real collision against it within the first tick or two, not a meaningful shove.
    private static final double SPAWN_SURFACE_NUDGE_SPEED = 0.1;

    /** Best-effort mitigation for a real, still only partly understood problem: a wendigo spawned
     * (teleported, not walked/climbed into place) directly onto a ceiling spot (see
     * DarkSpotScanner.findCeilingSpots) falls straight off it instead of sticking - live testing
     * confirmed this. AWCAPI's own per-tick travel logic (ClimberComponent.travelOnGround, verified
     * via javap to run every tick regardless of any cached attachment state - ClimberHelper always
     * passes isControlled=true) is genuinely reachable from tick one, but a freshly-spawned,
     * motionless entity has nothing to move toward yet - no plan step has issued a moveTo(), so
     * there's no real collision for its own surface-detection to react to before ordinary gravity
     * (which runs whenever ClimberComponent doesn't recognize an attachment) has already pulled it
     * away. A small, one-tick velocity straight into the surface (normal.getOpposite() - away from
     * the surface is normal's own direction, so toward it is the opposite) forces a genuine
     * collision on the very next physics step regardless of what the plan does first, giving AWCAPI
     * something concrete to react to. Called once, right after snapTo/addFreshEntity, same timing as
     * syncPoseToSpawnPosition - a no-op (normal.getOpposite() of UP is DOWN, and ordinary gravity
     * already provides that) for every ordinary floor spawn, so this can't regress the already-
     * working case. Unverified beyond that reasoning - flagged clearly as experimental, unlike this
     * feature's other, live-confirmed fixes. */
    public void nudgeTowardAttachedSurface(Direction normal) {
        if (normal == Direction.UP) {
            return;
        }
        nudgeTowardAttachedSurface(new Vec3(normal.getStepX(), normal.getStepY(), normal.getStepZ()));
    }

    /** Diagonal-normal overload of the above, for a test spawn deliberately placed at a convex block
     * corner (see /wendigo headtest's incline slot) where no single {@link Direction} describes the
     * attachment - AWCAPI's own collision smoothing (see the constructor's
     * setCollisionsInclusionRange/setCollisionsSmoothingRange comment) is what actually resolves the
     * blended corner normal from real geometry once nudged toward it; this just has to aim the nudge
     * roughly at the corner instead of a single face. */
    public void nudgeTowardAttachedSurface(Vec3 normal) {
        this.setDeltaMovement(normal.normalize().scale(-SPAWN_SURFACE_NUDGE_SPEED));
    }

    /**
     * Starts a wave's plan body, running to completion and then attempting despawnCandidates in
     * order (falling back to a live scan if all fail) - see PlanRunner. allSpots is the full
     * labeled spot_a..spot_f list (not just the despawn candidates) so movement.approach_spot can
     * resolve a label mid-plan.
     */
    public void startWave(JsonObject plan, List<BlockPos> despawnCandidates, List<BlockPos> allSpots, int severityPercent,
            boolean tierGatingBypassed, CaveScale caveScale, List<BlockPos> torchSpotPerLabel) {
        this.planRunner.start(plan, despawnCandidates, allSpots, severityPercent, tierGatingBypassed, caveScale, torchSpotPerLabel);
    }

    /** True once the current wave's plan body and despawn move have both finished. */
    public boolean isWaveComplete() {
        return this.planRunner.isWaveComplete();
    }

    /** Enters orbit mode (no active plan - see PlanRunner.startOrbit) around target. */
    public void startOrbit(ServerPlayer target) {
        this.planRunner.startOrbit(target);
    }

    public boolean isOrbiting() {
        return this.planRunner.isOrbiting();
    }

    public boolean isOrbitTargetLost() {
        return this.planRunner.isOrbitTargetLost();
    }

    public boolean isOrbitTrapped() {
        return this.planRunner.isOrbitTrapped();
    }

    /** Post-grab second phase: walk to destination, then enter orbit around target - see
     * PlanRunner.startReturnToOrbit. */
    public void startReturnToOrbit(BlockPos destination, ServerPlayer target) {
        this.planRunner.startReturnToOrbit(destination, target);
    }

    public boolean isReturningToOrbit() {
        return this.planRunner.isReturningToOrbit();
    }

    /** Starts a plan, walking to engageSpot first if not already close - see
     * PlanRunner.startWithApproach. */
    public void startWithApproach(BlockPos engageSpot, JsonObject plan, List<BlockPos> despawnCandidates,
            List<BlockPos> allSpots, int severityPercent, boolean tierGatingBypassed,
            CaveScale caveScale, List<BlockPos> torchSpotPerLabel) {
        this.planRunner.startWithApproach(engageSpot, plan, despawnCandidates, allSpots, severityPercent, tierGatingBypassed,
            caveScale, torchSpotPerLabel);
    }

    public boolean isApproachingEngageSpot() {
        return this.planRunner.isApproachingEngageSpot();
    }

    /** What actually happened this wave so far - see PlanRunner.EncounterOutcome/EncounterHistory. */
    public PlanRunner.EncounterOutcome getOutcome() {
        return this.planRunner.outcome();
    }

    /** True while a player is currently a forced rider - see PlanRunner.isForcingRide. */
    public boolean isForcingRide() {
        return this.planRunner.isForcingRide();
    }

    /** WendigoManager's grab_distance override - see PlanRunner.forceGrabNow. */
    public void forceGrabNow(ServerPlayer target) {
        this.planRunner.forceGrabNow(target);
    }

    /** See PlanRunner.consumeFreshEscape. */
    public boolean consumeFreshEscape() {
        return this.planRunner.consumeFreshEscape();
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
        this.planRunner.start(plan, null, null, 100, true, CaveScale.NORMAL, List.of());
    }

    public boolean isCrawling() {
        return this.getPose() == Pose.SWIMMING;
    }

    /** Real movement check, independent of pose - see WendigoVisual's three-way animation pick
     * (crawl while moving, a held crawl-idle pose while crawling but stopped, or the plain rest
     * pose while standing and stopped). Same threshold updatePose() itself uses to decide whether
     * to force the crawl hitbox. Full 3D, not horizontal-only - a wendigo climbing straight up a
     * shaft has near-zero horizontal velocity but real vertical velocity, and horizontal-only would
     * misread that as "not moving," freezing the animation mid-climb. */
    public boolean isMoving() {
        return this.getDeltaMovement().lengthSqr() > CRAWL_SPEED_THRESHOLD_SQR;
    }

    /** Real speed in blocks/tick - see WendigoVisual's movement-speed-scaled crawl playback, which
     * buckets this against the entity's own base MOVEMENT_SPEED attribute rather than tracking
     * which PlanRunner call site (or semantic speed band) requested the current move, so it stays
     * correct regardless of which movement primitive is driving it. Full 3D - see isMoving(). */
    public double currentSpeed() {
        return Math.sqrt(this.getDeltaMovement().lengthSqr());
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

    /** See lockedTarget's own field comment. Set by WendigoManager, read by Targeting. */
    public void setLockedTarget(ServerPlayer target) {
        this.lockedTarget = target;
    }

    public ServerPlayer getLockedTarget() {
        return this.lockedTarget;
    }

    // Set only by /wendigo headtest - forces isChasing() true without a real plan/chase action, so a
    // stationary lineup can exercise WendigoVisual's chase-tracking codepath (which only cares about
    // isChasing()'s boolean value, not how it got set) without the wendigo actually running at anyone.
    private boolean debugForceChasing;

    public void setDebugForceChasing(boolean forced) {
        this.debugForceChasing = forced;
    }

    /** True while the currently-executing plan action is combat.chase or internal.chase_until_light -
     * see PlanRunner.isChasing. WendigoVisual reads this to keep the face glowing and the head
     * tracking the target during an active chase, the same visual treatment a held stare gets. */
    public boolean isChasing() {
        return this.debugForceChasing || this.planRunner.isChasing();
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
