package com.wendigo.entity;

import java.util.List;

import com.google.gson.JsonObject;

import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.Vec3;

import com.nyfaria.awcapi.ClimberHelper;
import com.nyfaria.awcapi.entity.ClimberComponent;
import com.nyfaria.awcapi.entity.IAdvancedClimber;

import com.wendigo.WendigoMod;
import com.wendigo.debug.WendigoDebug;
import com.wendigo.plan.PlanRunner;
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
    // pathfinder's own node graph (which reasons in whole blocks) never saw.
    //
    // Briefly bumped to 0.8, then to 1.4 (vanilla Spider's own real width, EntityType.SPIDER.sized
    // (1.4F, 0.9F), confirmed via decompiling EntityType's registration) as an experiment testing
    // whether making the entity physically unable to enter a single-block-wide gap at all would
    // sidestep a reported "gets in, can't get back out" stall bug. Reverted back to 0.8 (the user's
    // own explicit follow-up) - still meaningfully wider than the original 0.6 and clear of the
    // ~0.9 straddling-risk line above, while keeping single-block-crevice crawling as a real
    // crawl-pose capability again.
    // Height is 0.9F, not 1.0F - deliberately just under a hard cutoff in vanilla's own pathfinding
    // math (NodeEvaluator.prepare derives its required vertical clearance as
    // Mth.floor(getBbHeight() + 1), verified via bytecode), where 1.0F would round up to needing a
    // genuine 2-tall gap instead of 1. Crawl pose exists specifically to fit through single-block
    // crevices - crossing that line would silently break that while still reading as "about 1 block
    // tall", so it's pinned just below it on purpose, not an oversight.
    // Deliberately plain scalable() - NOT withEyeHeight() (tried once, reverted: see git history/
    // getVisualEyePosition's own doc comment for why raising the real getEyePosition() above its
    // vanilla default caused the wendigo to suffocate against ordinary cave ceilings - isInWall()
    // builds its check box centered on getEyePosition(), and vanilla's own 0.85-of-height default is
    // exactly what keeps that box safely inside the entity's own collision box everywhere else in the
    // engine that relies on it. The rig's real (taller) visual head position is handled entirely
    // separately - see getVisualEyePosition, used only by stare-detection, never touching this.
    private static final EntityDimensions CRAWLING_DIMENSIONS = EntityDimensions.scalable(0.8F, 0.9F);
    // Deliberately shorter than the model's real proportions (which stand about 3 blocks tall) - this
    // is the physical collision box only, sized for easy pathfinding through ordinary 2-tall corridors
    // (a mineshaft's own typical clearance) while standing, not for visually matching the model.
    // DarkSpotScanner.hasStandingClearance intentionally still requires a genuine 3-tall gap to enter/
    // stay in this pose at all (unrelated to this constant's own value, not derived from it) - so
    // whenever this hitbox actually is standing, there's always at least a block of headroom above it
    // for the taller model, and a merely-2-tall space (not enough for the model, even though this
    // hitbox alone would fit) still forces crawl pose instead of letting the model clip the ceiling.
    // Deliberately plain scalable() - see CRAWLING_DIMENSIONS' own comment just above.
    private static final EntityDimensions STANDING_DIMENSIONS = EntityDimensions.scalable(0.6F, 2.0F);
    private static final double CRAWL_SPEED_THRESHOLD_SQR = 1.0E-4;
    // Flipping pose (and its bounding-box resize via refreshDimensions()) on every single tick's
    // raw velocity reading flickers rapidly while walking down slopes/stairs. Requiring the desired
    // pose to be stable for a few consecutive ticks before actually committing to it damps that
    // flicker. Bumped from 5 to 10 - live-reported bug, still reproducing after the first debounce
    // pass: standing on the angled geometry right at a plain 1-block-high ledge/step (a DIFFERENT
    // case from RESTING_ON_FLOOR_DEBOUNCE_TICKS' own outside-corner case just below - this one is
    // flat ground, no wall/ceiling involved at all) visibly cycled through every pose/model this
    // entity has, meaning the underlying raw signal was oscillating for LONGER than 5 ticks could
    // absorb at that specific edge, not that debouncing itself was broken. See STEP_HEIGHT's own
    // comment in the constructor for a complementary, more direct attempt at the same underlying
    // geometry (a taller allowed step might let vanilla just walk the ledge cleanly instead of ever
    // entering this jittery transition state at all) - this wider window is the fallback for
    // whatever jitter still gets through regardless.
    private static final int POSE_SWITCH_DEBOUNCE_TICKS = 10;
    // A fast, jittery corner transition (e.g. an outside/convex ceiling-to-wall corner, or a flat-
    // ground 1-block step/ledge - see POSE_SWITCH_DEBOUNCE_TICKS' own comment for that second,
    // separately live-reported case) can read as briefly resting on a horizontal floor for a tick or
    // two before AWCAPI's own attachment settles onto the new surface - see isRestingOnFloor's own
    // doc comment for why that's worth debouncing here, not just smoothing downstream. Bumped from 5
    // to 10 alongside POSE_SWITCH_DEBOUNCE_TICKS for the same reason - kept in step with that one
    // rather than independently retuned, since WendigoVisual reads both this and the pose switch to
    // pick which model set renders, and two different absorption windows on inputs to the same
    // downstream decision was never a deliberate design choice.
    private static final int RESTING_ON_FLOOR_DEBOUNCE_TICKS = 10;
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
    // See updateClimbingSpeedPenalty's own doc comment for the confirmed (decompiled, not guessed)
    // mechanism this exists to mitigate: AWCAPI's ClimberComponent.updateOffsetsAndOrientation only
    // ever searches for/blends toward a wall or ceiling attachment point while mob.onGround() reads
    // true THAT SAME TICK - real per-tick movement (travelOnGround) resolves collision at whatever
    // speed the wendigo is currently moving, and the faster it's moving, the more likely a single
    // tick's displacement carries it past a surface edge (a corner, a narrow ledge) without registering
    // contact, reading onGround() false and skipping reattachment entirely that tick; five consecutive
    // misses (attachedTicks reaching 0) fully reverts to gravity mode - the actual fall.
    //
    // Ramped by Orientation.pitch (AWCAPI's own, already-smoothed value - 0=floor, 90=dead-on wall,
    // 180=dead-on ceiling; see calculateOrientation, decompiled to confirm this exact conversion, not
    // guessed) rather than a flat on/off - the user's own explicit request: walls keep full speed
    // (the actual fall risk is worse looking straight up at a ceiling, per further live testing), and
    // linearly interpolating the penalty in over the 90-180 range means the smoothed pitch value
    // already sweeping through that range while crossing a bumpy, irregular ceiling (climbing up the
    // side of one lump, over its flat top, down the far side, AWCAPI's own attachedTicks*0.2f blend
    // already makes this a continuous sweep, not a discrete jump) produces a continuously-varying
    // penalty instead of visibly snapping speed up and down at each block-to-block transition.
    private static final Identifier CLIMBING_SPEED_PENALTY_ID = WendigoMod.id("climbing_speed_penalty");
    // Orientation.pitch at which the penalty starts ramping in - a dead-on wall (90) itself still
    // gets zero penalty; only pitches beyond this, toward a ceiling, ramp any cut in at all.
    private static final float CLIMBING_PENALTY_RAMP_START_PITCH = 90.0f;
    // Orientation.pitch at which the penalty reaches its full amount - a dead-on ceiling (180).
    private static final float CLIMBING_PENALTY_RAMP_END_PITCH = 180.0f;
    // Full cut to the base MOVEMENT_SPEED attribute, reached only at CLIMBING_PENALTY_RAMP_END_PITCH -
    // first pass, not derived from anything more rigorous than "clearly slower reduces how far a
    // single tick can overshoot a surface edge" - adjust by feel once live-tested.
    private static final double CLIMBING_SPEED_PENALTY_MAX_FRACTION = -0.4;
    // TEMPORARY diagnostic threshold only - see updateClimbingSpeedPenalty's own debug logging.
    private static final float PITCH_LOG_JUMP_THRESHOLD_DEGREES = 20.0f;
    // How many consecutive ticks forceDetach forces onGround() to read false for - matches
    // ClimberComponent's own attachedTicks max (5, confirmed via decompiling ClimberComponent.class:
    // updateOffsetsAndOrientation only searches for/blends toward a new attachment point while
    // onGround() reads true, and 5 consecutive misses fully depletes attachedTicks to 0, at which
    // point the attachment offset/normal blend has fully reverted to plain gravity). This alone only
    // reverts the COSMETIC orientation/offset blend, though - confirmed via decompiling
    // ClimberComponent.updateWalkingSide that the actual sticking-force PHYSICS (travelOnGround) reads
    // an entirely independent field, groundDirection, set purely from a real geometric collision probe
    // every tick regardless of onGround()/attachedTicks. Depleting attachedTicks without ALSO breaking
    // that geometric contact (see forceDetach's own FORCE_DETACH_PUSH_DISTANCE) just re-levels the
    // model's rotation while the sticking force keeps holding it against the surface - a real bug this
    // class shipped and caught via its own GameTest (movement.drop measurably not falling) before this
    // comment was updated to describe the fix.
    private static final int FORCE_DETACH_TICKS = 5;
    // How far forceDetach physically pushes the entity away from its current attachment surface, in
    // blocks - has to clear updateWalkingSide's own probe reach in the direction being pushed, or the
    // very next tick's probe just finds the same surface again and re-attaches instantly. That reach is
    // AABB.inflate(0.2) plus stickingDistance (confirmed via decompile: 0.1 while mob.zza reads 0,
    // which aiStep forces during a forceDetach window specifically to keep this reach - and therefore
    // this push distance - small) = 0.3 total; 0.4 clears it with a small margin without a visually
    // large pop away from the surface.
    private static final double FORCE_DETACH_PUSH_DISTANCE = 0.4;
    // See updateGroundDirectionStability's own doc comment. How many CONSECUTIVE ticks
    // getGroundDirection() has to keep changing from the tick right before it before this is treated
    // as genuine thrashing rather than one ordinary real transition (a legitimate floor-to-wall
    // corner is exactly one change, then stable) - short enough to react quickly (0.3s), long enough
    // that a single real transition can never trip it on its own.
    private static final int GROUND_DIRECTION_INSTABILITY_TICKS = 6;
    // How far updateGroundDirectionStability physically pushes the entity toward whichever surface it
    // most recently favored once thrashing is detected - same "a plain velocity nudge doesn't
    // reliably move the needle here,
    // needs real geometric displacement" reasoning as FORCE_DETACH_PUSH_DISTANCE's own comment, not
    // independently derived. Smaller than that constant on purpose - this only has to turn a
    // near-tied margin into a clearly-not-tied one, not clear a whole probe reach.
    private static final double GROUND_DIRECTION_STABILITY_PUSH_DISTANCE = 0.3;
    // See updateOrientationConvergence's own doc comment - same magnitude/reasoning as
    // GROUND_DIRECTION_STABILITY_PUSH_DISTANCE, not independently derived.
    private static final double ORIENTATION_CONVERGENCE_PUSH_DISTANCE = 0.3;
    // See recoverFromSpuriousAttachmentLoss's own doc comment - same magnitude/reasoning as
    // GROUND_DIRECTION_STABILITY_PUSH_DISTANCE, not independently derived.
    private static final double SPURIOUS_LOSS_RECOVERY_PUSH_DISTANCE = 0.3;
    // How much longer, on top of FORCE_DETACH_TICKS, recoverFromSpuriousAttachmentLoss stays
    // suppressed after a deliberate forceDetach - see that method's own updated doc comment. Its own
    // "was climbing last tick, now floor-reading" transition only fires once the orientation blend
    // has actually caught up to reading floor-like, which happens around when FORCE_DETACH_TICKS
    // itself ends, not immediately - without this extra grace window, the very mitigation built to
    // catch an ACCIDENTAL attachment loss and nudge back onto the last surface would immediately
    // undo a deliberate one instead, right as it's supposed to be falling with no real floor under it
    // yet.
    private static final int FORCE_DETACH_RECOVERY_GRACE_TICKS = 15;

    private int forceDetachTicksRemaining;
    private int suppressRecoveryTicksRemaining;

    private WendigoVisual visual;
    private boolean staring;
    private Pose desiredPose = Pose.STANDING;
    private int desiredPoseStableTicks;
    private boolean restingOnFloorDebounced = true;
    private int restingOnFloorStableTicks;
    // See recoverFromSpuriousAttachmentLoss's own doc comment - tracks the last real wall/ceiling
    // normal seen while genuinely climbing, and the raw (undebounced) resting-on-floor reading from
    // the previous tick, purely to detect the specific one-tick transition that mitigation reacts to.
    private Vec3 lastClimbingNormal = new Vec3(0.0, 1.0, 0.0);
    private boolean wasRestingOnFloorRawLastTick = true;
    // See updateGroundDirectionStability's own doc comment - tracks getGroundDirection() across ticks
    // purely to detect that specific thrashing pattern.
    private Direction lastGroundDirection;
    private int groundDirectionChangeTicks;
    // See getGroundSide's own override/doc comment - the last reading that WASN'T flagged as a
    // spurious AWCAPI fallback, used to paper over the exact ticks that fallback fires on. Defaults
    // to UP (floor) - harmless before any real probe has ever run (matches ordinary floor spawn).
    private Direction lastGoodGroundSide = Direction.UP;
    // TEMPORARY diagnostic only (see updateOrientationConvergence's own debug logging) - whether that
    // method was actively correcting a mismatch as of the last tick, used purely to log once per real
    // transition instead of every single tick the mismatch persists.
    private boolean wasConvergingOrientationLastTick;
    // TEMPORARY diagnostic only - see updateClimbingSpeedPenalty's own debug logging. NaN means "no
    // reading logged yet" (harmless first-tick skip, not a real jump).
    private float lastLoggedPitch = Float.NaN;
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
        // Live-reported bug, root-caused via decompiling ClimberComponent.onTravel: AWCAPI defaults
        // canClimbInWater to false, and while it's false, onTravel bails out of its own climbing
        // physics entirely the instant the mob is in water it can't stand on (isTravelingInFluid=true,
        // returns false to fall back to vanilla's ordinary swim travel) - meaning the wendigo was never
        // actually climbing at all while submerged, just swimming like any other mob. That explains the
        // reported step-up failure exactly: vanilla's own auto-step assist only ever fires while
        // onGround() reads true, which a genuinely buoyant/swimming entity doesn't get, the same
        // "can't step up out of the water" limitation ordinary mobs have. IAdvancedClimber's own
        // setCanClimbInWater (default method, delegates straight to ClimberComponent) keeps the real
        // climbing/stepping physics active in water too, the same as on dry land.
        this.setCanClimbInWater(true);
        this.getAttribute(Attributes.FOLLOW_RANGE).addPermanentModifier(FOLLOW_RANGE_CLIMB_BONUS);
        this.getAttribute(Attributes.WATER_MOVEMENT_EFFICIENCY).addPermanentModifier(FAST_SWIM_BONUS);
        // Immune to fall damage entirely - user's own explicit request, and also just practical given
        // how much of this entity's own movement is wall/ceiling climbing and falling off one
        // unexpectedly (see the known ceiling-attachment-loss issue elsewhere in this file's history)
        // - a creature that's supposed to read as unnervingly capable in the dark shouldn't visibly
        // hurt itself from an ordinary fall. Set the base value directly rather than a permanent
        // modifier (the pattern the other two attributes above use) since this needs to land on an
        // exact 0 regardless of whatever the real base default happens to be, not a relative
        // adjustment from it.
        this.getAttribute(Attributes.FALL_DAMAGE_MULTIPLIER).setBaseValue(0.0);
        // Previously tried raising this (initClimber leaves it at AWCAPI's own 0.1 default) to speed up
        // ordinary floor ledge-crossing, then reverted - it was the one thing that changed between a
        // live test where wall/ceiling attachment tilt worked and a later one where the rig stayed flat
        // everywhere. Retried now with a real reason to believe it's safe, not just a blind guess: per
        // ClimberComponent.onTravel (decompiled, not guessed), the mob's OWN maxUpStep() reading (which
        // reads straight from this same attribute - confirmed via decompiling LivingEntity.maxUpStep -
        // is captured into a local once per call) is used for AWCAPI's own step math, and this exact
        // attribute's BASE VALUE is then explicitly reset to 0 every time any attachedSides axis just
        // changed (a real transition between surfaces) specifically so vanilla's own separate step-up
        // code doesn't also fire and double up on the same movement that tick - a value set here only
        // actually reads through to vanilla's ordinary flat-ground stepping outside of a transition tick,
        // it never overrides AWCAPI's transition-specific behavior. If the flat-rig symptom reappears
        // anyway, this line (not the search-range widening below, which is unrelated) is the first thing
        // to revert - the user's own explicit request to test 1.0 (a full block) rather than something
        // smaller.
        this.getAttribute(Attributes.STEP_HEIGHT).setBaseValue(0.1);
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
        // PathType.FENCE carries a baked-in -1.0F (BLOCKED-equivalent) default malus (confirmed via
        // javap against PathType's own static initializer - not guessed), and Mob.getPathfindingMalus
        // falls back to exactly that default for any PathType this entity hasn't explicitly
        // overridden via setPathfindingMalus (also confirmed via javap). Neither EnderMan nor this
        // class overrode it before now, so the wendigo treated every fence-top node as fully
        // impassable by default - a real problem in a mineshaft, whose scaffolding is built almost
        // entirely out of fences: reported live as getting stuck mid-path for a long stretch before
        // eventually completing it anyway, which matches this exactly (vanilla's own coarse ~100-tick
        // isStuck() re-evaluation eventually recomputes a route avoiding the fence lattice instead of
        // resolving the block right away). AdvancedWalkNodeProcessor (this entity's own node
        // evaluator, via DarknessAwareClimberNavigation) genuinely still assigns PathType.FENCE the
        // same way vanilla's own WalkNodeEvaluator does (confirmed via javap against AWCAPI's own
        // class) - not something AWCAPI already reinterprets for a climbing mob. 0.0F matches
        // WALKABLE's own malus exactly: a fence-top node reads as perfectly ordinary ground to path
        // across instead of a wall to route around, which this creature can physically stand on
        // without issue regardless (see canClimbOnBlock's own fence exclusion - that's specifically
        // about not climbing UP a fence like a wall, unrelated to walking across its top).
        this.setPathfindingMalus(PathType.FENCE, 0.0F);
        // Same fix, same class of problem: PathType.WATER/WATER_BORDER both carry a baked-in 8.0F
        // default malus (confirmed via javap against PathType's own static initializer) - not
        // blocked outright, but a heavy cost penalty the pathfinder strongly prefers to route around,
        // which reads as hesitant/slow pathing through anything from a single shin-deep puddle to a
        // real flooded passage. The node evaluator doesn't distinguish depth - a position simply
        // reads as PathType.WATER whenever it's a water block, shallow or deep alike - so one fix
        // covers both. 0.0F matches WALKABLE's own malus, same reasoning as the fence fix above: this
        // creature already swims at full speed once actually in water (see FAST_SWIM_BONUS), so there's
        // no physical reason for the pathfinder to keep treating a water route as costlier than a dry
        // one.
        this.setPathfindingMalus(PathType.WATER, 0.0F);
        this.setPathfindingMalus(PathType.WATER_BORDER, 0.0F);
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

    /** Removes EnderMan's own random teleport-on-hurt reaction entirely - the user's own explicit
     * follow-up request after finding the suffocation bug (see getVisualEyePosition's own doc
     * comment) also happened to trigger it: EnderMan.hurtServer's 1-in-10-chance-on-non-LivingEntity-
     * damage branch calls this exact no-arg teleport() (confirmed via javap), and every other vanilla
     * Enderman teleport pathway (chasing/fleeing/water-avoidance AI goals) never runs at all for this
     * entity - see the class doc comment's own "Deliberately NOT calling super.registerGoals()" - so
     * this one override removes every live way the wendigo could ever randomly blink away, not just
     * the damage-triggered case that happened to surface it. Its own actual movement is entirely
     * PlanRunner/pathfinding-driven regardless; a random teleport was never a mechanic this entity
     * was meant to have in the first place, just an unremoved vanilla side effect. */
    @Override
    protected boolean teleport() {
        return false;
    }

    // Set true the instant a fire-type hit actually lands - see hurtServer below. Read and cleared
    // once per tick by WendigoManager (consumeTookFireDamage) to trigger a cosmetic despawn - the
    // user's own explicit "delegate to the respawn search" request, same shape as every other
    // cosmetic discard (too far/too close/trapped/out of air).
    private boolean tookFireDamage;

    /** DamageTypeTags.IS_FIRE (confirmed via javap against the real tag, not guessed - covers
     * IN_FIRE/ON_FIRE/LAVA/HOT_FLOOR/FIREBALL and friends in one check rather than enumerating each
     * DamageTypes constant by hand) landing here means real damage actually applied (super's own
     * return value - invulnerability/damage-immunity ticks already filtered out anything that didn't
     * really land), not just an attempted hit. */
    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        boolean result = super.hurtServer(level, source, amount);
        if (result && source.is(DamageTypeTags.IS_FIRE)) {
            this.tookFireDamage = true;
        }
        return result;
    }

    /** Reads and clears tookFireDamage in one step - see its own field comment. */
    public boolean consumeTookFireDamage() {
        boolean result = this.tookFireDamage;
        this.tookFireDamage = false;
        return result;
    }

    /** setSilent(true) (see the constructor) blanket-suppresses every automatic vanilla sound path -
     * ambient/idle noise is meant to stay fully off (see WendigoSounds), but the user separately
     * asked for a real hurt sound and real footsteps back, so those two get their own explicit
     * playSound calls that bypass isSilent() the same way WendigoSounds.play() already does (a
     * direct ServerLevel.playSound call, not the isSilent()-gated Entity.playSound(SoundEvent,F,F)
     * vanilla's own default playHurtSound/playStepSound would otherwise use - confirmed via javap).
     * Pitch pinned to 0 per explicit user request (allay's own natural pitch reads too high/cheerful
     * for a hit landing on this creature). */
    @Override
    protected void playHurtSound(DamageSource source) {
        if (this.level() instanceof ServerLevel serverLevel) {
            serverLevel.playSound(null, this.blockPosition(), SoundEvents.ALLAY_HURT, SoundSource.HOSTILE, 1.0F, 0.0F);
        }
    }

    /** See playHurtSound's own comment - same isSilent()-bypass reasoning. Uses a fixed sound
     * (SoundEvents.STONE_STEP, user's own explicit choice) rather than the generic per-block
     * SoundType footstep every other mob gets (confirmed via javap: Entity.playStepSound's default
     * body always uses the walked-on block's own SoundType, never a per-entity override). Volume/
     * pitch match vanilla's own typical footstep loudness (SoundType's ~0.15 volume scale), not a
     * per-step random pitch vanilla doesn't apply here either. */
    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        if (this.level() instanceof ServerLevel serverLevel) {
            serverLevel.playSound(null, pos, SoundEvents.STONE_STEP, SoundSource.HOSTILE, 0.15F, 1.0F);
        }
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

    /** The real insertion point for both the zza-widening mitigation (spurious detach while idle) and
     * forceDetach's own onGround() override - NOT tick()'s own ClimberHelper.tickClimber call (that
     * one, confirmed via CFR-decompiling ClimberComponent.tick(), only does path-tracking bookkeeping
     * and never reads zza or onGround() at all). ClimberHelper.livingTickClimber -> updateWalkingSide/
     * updateOffsetsAndOrientation, the methods that actually read those two fields, run here, at the
     * very start of aiStep - matching SpiderMixin's own injection point - which is BEFORE super.aiStep()
     * (Mob's goal-selector-driven MoveControl.tick() and the real travel()/collision resolution) sets a
     * fresh zza and resolves this tick's real onGround() from actual collision. So both overrides below
     * only ever touch whatever zza/onGround() were left at by the END of LAST tick - never this tick's
     * real movement, which still runs untouched in super.aiStep() right after, using its own freshly
     * computed values regardless of what's restored here first. */
    @Override
    public void aiStep() {
        float realZza = this.zza;
        boolean realOnGround = this.onGround();
        boolean forceDetaching = this.forceDetachTicksRemaining > 0;
        if (forceDetaching) {
            this.forceDetachTicksRemaining--;
            this.setOnGround(false);
        }
        // Narrow (not widened) for the whole grace window a forceDetach is still settling (not just
        // its own shorter forceDetachTicksRemaining span) - a real bug an earlier version of this
        // method had: the general idle-widening branch below resumed as soon as forceDetachTicksRemaining
        // hit 0, and gravity hadn't yet pulled the entity more than 1.5 blocks away from the surface it
        // just pushed off of by then, so the newly-widened probe just rediscovered that same ceiling and
        // snapped straight back onto it - a GameTest (movement.drop measurably not falling) caught this
        // exact case. suppressRecoveryTicksRemaining's own window (FORCE_DETACH_RECOVERY_GRACE_TICKS
        // longer) is already sized for "long enough for the fall to become unambiguous", so reusing it
        // here too keeps both mitigations settling on the same timeline instead of two independently
        // guessed durations.
        if (forceDetaching || this.suppressRecoveryTicksRemaining > 0) {
            this.zza = 0.0f;
        } else {
            // See CLIMBING_SPEED_PENALTY_ID's own comment - widens updateWalkingSide's probe from a
            // razor-thin 0.1 blocks (mob.zza reads 0 while genuinely idle/holding position) to 1.5,
            // mitigating the spurious-detach-while-stationary case this was originally added for.
            this.zza = 1.0f;
        }
        ClimberHelper.livingTickClimber(this);
        this.zza = realZza;
        this.setOnGround(realOnGround);
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

    /** AWCAPI's own pathfinder reads THIS EXACT METHOD to decide which attachment side a fresh path
     * is even allowed to start from (confirmed via decompiling AdvancedWalkNodeProcessor.
     * isValidStartingSide, which calls this.advancedPathFindingEntity.getGroundSide()), and
     * IAdvancedClimber's own default implementation is a raw pass-through to ClimberComponent.
     * getGroundDirection().getKey() - the same per-tick "closest colliding face" probe
     * getGroundDirection() reads, which falls back to a plain Direction.DOWN default whenever
     * nothing registers within its own reach, not because it actually found a floor. Live debug
     * logging (since removed - see WendigoEntity's git history for the exact transcript) confirmed
     * this value stays correct and stable through an entire reproduction of the "flips to the wrong
     * normal" bug on a wide-open ceiling, ruling this specific mechanism out as the cause on its
     * own - kept anyway as a real, independently-justified correction (a spurious empty-probe
     * fallback is still possible on small/edge geometry, just not what this particular bug turned
     * out to be), and because updateOrientationConvergence below now also depends on this value
     * being trustworthy. A raw reading of DOWN is only trusted if hasRealFloorNearby() (the same
     * real, independent block-collision check recoverFromSpuriousAttachmentLoss already uses for
     * exactly this "is this floor reading actually real" question) backs it up; otherwise the last
     * reading that WAS trusted (lastGoodGroundSide) is returned instead. */
    @Override
    public Direction getGroundSide() {
        Direction raw = IAdvancedClimber.super.getGroundSide();
        if (raw == Direction.DOWN && !hasRealFloorNearby()) {
            return this.lastGoodGroundSide;
        }
        this.lastGoodGroundSide = raw;
        return raw;
    }

    // --- end IAdvancedClimber physics wiring --------------------------------------------------

    /** See DarknessMalus.lightTolerant - PlanRunner toggles this around the two primitives
     * (combat.lunge_attack, combat.break_torch) that are meant to cross into light on purpose. */
    public void setLightTolerantPathing(boolean tolerant) {
        if (this.getNavigation() instanceof DarknessAwareClimberNavigation navigation) {
            navigation.setLightTolerant(tolerant);
        }
    }

    // Mirrors DarknessMalus.severityPercent locally (see setSeverityPercent below) so callers outside
    // pathfinding - currently just remove()'s stage-1 despawn-effect scaling - can read "what stage is
    // this encounter" without reaching into DarknessAwareClimberNavigation. Set at every point this
    // entity's stage becomes known: a real wave's start (PlanRunner.start) and, since the stage-1
    // exposure reaction needs it even before any plan has started, WendigoManager.tryEnterOrbit's own
    // fresh-spawn-into-orbit path too.
    private int severityPercent;

    /** See DarknessMalus.severityPercent - PlanRunner.start calls this with the current
     * wave's severity so early-stage pathfinding can hard-refuse routes through real light. */
    public void setSeverityPercent(int severityPercent) {
        this.severityPercent = severityPercent;
        if (this.getNavigation() instanceof DarknessAwareClimberNavigation navigation) {
            navigation.setSeverityPercent(severityPercent);
        }
    }

    public int getSeverityPercent() {
        return this.severityPercent;
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
        // Checked BEFORE super.tick() specifically, not after - super.tick() is where this tick's
        // real movement (AWCAPI's travelOnGround, via its own onTravel mixin hook around
        // Entity.travel()) actually consumes the current MOVEMENT_SPEED attribute value, so the
        // penalty has to already be in place before that call to affect THIS tick's displacement, not
        // next tick's. Reads getOrientation() as of wherever the last tick's movement left it - a
        // one-tick-stale read, same as every other per-tick AWCAPI state query in this class, and fine
        // for a coarse speed cap that doesn't need perfect same-tick precision.
        updateClimbingSpeedPenalty();
        super.tick();
        // Matches SpiderMixin's own injection point (verified via javap -v: @Inject(method="tick",
        // at=@At("RETURN"))) - ClimberHelper.tickClimber updates path-tracking bookkeeping once per
        // tick, after the rest of this tick's physics have already run. See aiStep's own doc comment
        // for where the zza/onGround() overrides that actually matter for attachment live now - this
        // call doesn't read either field.
        ClimberHelper.tickClimber(this);
        recoverFromSpuriousAttachmentLoss();
        updateOrientationConvergence();
        updateGroundDirectionStability();
        updateRestingOnFloorDebounce();
        updatePose();

        if (!this.level().isClientSide()) {
            this.planRunner.tick();
        }
    }

    /** See CLIMBING_SPEED_PENALTY_ID's own field comment for the confirmed root cause this mitigates
     * and why it's ramped by pitch rather than flat on/off. Reads Orientation.pitch directly (AWCAPI's
     * own already-smoothed value - not the debounced isRestingOnFloor(), which only distinguishes
     * floor from everything else and isn't smoothed the same way) - constructs a fresh
     * AttributeModifier with the same id every call (AttributeModifier is an immutable record, so a
     * continuously-varying amount means a new instance each time, not a mutation) and
     * addOrUpdateTransientModifier's own replace-if-present semantics handle the rest, including the
     * "back on a genuine floor or a dead-on wall, zero penalty" case - no separate removal path
     * needed, a zero-amount ADD_MULTIPLIED_BASE modifier is already a no-op. Safe to call
     * unconditionally every tick. */
    private void updateClimbingSpeedPenalty() {
        float pitch = this.getOrientation().pitch;
        double rampFraction = Mth.clamp(
            (pitch - CLIMBING_PENALTY_RAMP_START_PITCH) / (CLIMBING_PENALTY_RAMP_END_PITCH - CLIMBING_PENALTY_RAMP_START_PITCH),
            0.0, 1.0);
        AttributeModifier modifier = new AttributeModifier(CLIMBING_SPEED_PENALTY_ID,
            CLIMBING_SPEED_PENALTY_MAX_FRACTION * rampFraction, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        this.getAttribute(Attributes.MOVEMENT_SPEED).addOrUpdateTransientModifier(modifier);
        // TEMPORARY - live-debugging the "gains speed on the ceiling" bug. The user's own diagnosis:
        // this ramp's own pitch reading drops, killing the penalty - while getGroundSide() and
        // orientation.normal.y both stayed provably correct throughout an earlier repro (see
        // getGroundSide's own updated comment). A normal.y > 0.9 threshold (updateOrientationConvergence's
        // old trigger) was too strict to ever catch this: pitch only has to dip below
        // CLIMBING_PENALTY_RAMP_START_PITCH (90) to zero the ramp out completely, nowhere near what
        // normal.y actually crossing into floor-reading range would take. Logs pitch/rampFraction/
        // groundSide/normal.y together the instant pitch jumps by more than PITCH_LOG_JUMP_THRESHOLD_DEGREES
        // in one tick, to catch the exact moment and real numbers live instead of guessing again.
        // Remove once the real mechanism is confirmed.
        if (WendigoDebug.anyEnabled() && !Float.isNaN(this.lastLoggedPitch)
                && Math.abs(pitch - this.lastLoggedPitch) > PITCH_LOG_JUMP_THRESHOLD_DEGREES
                && this.level() instanceof ServerLevel serverLevel) {
            WendigoDebug.say(serverLevel, "pitch jumped " + String.format("%.1f", this.lastLoggedPitch)
                + " -> " + String.format("%.1f", pitch) + " (rampFraction=" + String.format("%.2f", rampFraction)
                + ", groundSide=" + this.getGroundSide() + ", normal.y=" + String.format("%.3f", this.getOrientation().normal.y)
                + ", zza=" + this.zza + ", onGround=" + this.onGround() + ")");
        }
        this.lastLoggedPitch = pitch;
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

    /** Live-reported bug: the wendigo visibly flips to a plain right-side-up standing render while
     * STILL genuinely attached to a ceiling (especially a small one) - not falling, not corrected by
     * recoverFromSpuriousAttachmentLoss's own "1-tick fall preventer" (that one only reacts to a real
     * physical detach, and this entity was never physically detached at all) - it just sits there
     * upright, mid-air by every visual read, with nothing obviously holding it up. Same root cause
     * this session already live-debugged once before, for a completely different symptom
     * (forceDetach's own push-direction bug): getOrientation().normal only updates once
     * updateOffsetsAndOrientation's own attachment SEARCH has actually succeeded at least once, and
     * that search's own direction bias is seeded from whatever orientation.normal ALREADY is - a
     * chicken-and-egg gap a small/awkward ceiling patch can apparently still get stuck in even during
     * ordinary climbing, not just a fresh teleport-spawn (confirmed via debug logging back then: a
     * genuinely ceiling-attached entity can read orientation.normal as the plain (0,1,0) floor
     * default). Every downstream reader of THIS method (isRestingOnFloor's pose switch, and
     * WendigoVisual's own tilted-vs-flat rendering choice) trusted that single, sometimes-stuck value
     * alone, so a stuck floor-default normal read as "standing on a floor" even while the real
     * sticking-force physics (independent of orientation.normal entirely - see getGroundDirection's
     * own comment) was still genuinely holding it to the ceiling.
     * <p>
     * Fixed the same way forceDetach's push direction was: cross-check getGroundSide() (this class's
     * own override, not the raw AWCAPI pass-through - see its own doc comment for a second, deeper
     * bug found in that same signal since this method was first fixed) instead of trusting
     * orientation.normal alone - it points TOWARD the surface (opposite convention from
     * orientation.normal), so DOWN means "the surface is below me", the same thing normal.y > 0.9 is
     * trying to detect. Requiring BOTH signals to agree means a stuck floor-default normal can no
     * longer overrule what the more reliable geometric probe says is actually there. */
    private boolean restingOnFloorRaw() {
        return this.getOrientation().normal.y > 0.9 && this.getGroundSide() == Direction.DOWN;
    }

    /** Backstop for the same "falls off the ceiling, model flips upright first" bug tick()'s own zza
     * override above now fixes proactively (see that comment for the confirmed root cause -
     * ClimberComponent.updateWalkingSide's probe collapsing to 0.1 blocks whenever Mob.zza reads 0).
     * The proactive fix keeps the probe wide every tick, but this reactive detector stays in place as
     * a second line of defense for whatever it doesn't cover (a genuinely legitimate detach the wide
     * probe still can't find anything from, or any other path into the same AWCAPI gravity-mode
     * fallback this class hasn't identified) - AWCAPI is a third-party dependency this mod can't
     * modify or embed (see build.gradle). Detects the specific one-tick transition (was genuinely
     * climbing last tick, suddenly reads as floor-resting this tick, with no real floor block anywhere
     * nearby to legitimately explain it) and immediately pushes back into the last known climbing
     * surface, giving AWCAPI's own attachment search something concrete to re-latch onto next tick
     * instead of continuing to free-fall. Best-effort, same as the zza override above: mitigates a
     * black-box third-party physics edge case, not a guaranteed fix.
     * <p>
     * Live-debugged real bug: this used to call nudgeTowardAttachedSurface (a velocity-only
     * setDeltaMovement), the SAME mistake found and fixed twice elsewhere for this exact class of
     * problem (updateGroundDirectionStability, updateOrientationConvergence) but never carried back
     * to this method - the very first one built, and the one a live-reported "flips on the ceiling,
     * every time a path ends, no matter how far the real floor actually is" bug traced straight back
     * to: this entity is normally still actively moving (real velocity from mid-path/mid-chase
     * momentum) the whole time a spurious flip like this can happen, and setDeltaMovement REPLACES
     * the entire velocity vector - zeroing out whatever real movement was in progress instead of
     * adding a real, additional shove to break the spurious reading. A plain velocity nudge also just
     * isn't strong enough to reliably force a fresh collision on its own (the same lesson forceDetach's
     * own comment already learned the hard way for the opposite problem). A real positional
     * displacement, same technique this class already uses everywhere else this exact lesson applies,
     * doesn't touch velocity at all - just adds a real, one-tick shove toward the last known good
     * surface on top of whatever movement is already happening.
     * <p>Suppressed for a while after forceDetach (see suppressRecoveryTicksRemaining/
     * FORCE_DETACH_RECOVERY_GRACE_TICKS) - this method exists specifically to catch and undo an
     * ACCIDENTAL loss of attachment, which from this method's own point of view is indistinguishable
     * from a deliberate one (movement.drop) still genuinely falling with no real floor under it yet.
     * Still tracks wasRestingOnFloorRawLastTick/lastClimbingNormal every tick regardless of
     * suppression, only the corrective push itself is skipped, so nothing goes stale once suppression
     * ends. */
    private void recoverFromSpuriousAttachmentLoss() {
        boolean restingNow = restingOnFloorRaw();
        boolean suppressed = this.suppressRecoveryTicksRemaining > 0;
        if (suppressed) {
            this.suppressRecoveryTicksRemaining--;
        }
        if (!restingNow) {
            this.lastClimbingNormal = this.getOrientation().normal;
        } else if (!suppressed && !this.wasRestingOnFloorRawLastTick && !hasRealFloorNearby()) {
            Vec3 towardSurface = this.lastClimbingNormal.normalize().scale(-SPURIOUS_LOSS_RECOVERY_PUSH_DISTANCE);
            this.move(MoverType.SELF, towardSurface);
        }
        this.wasRestingOnFloorRawLastTick = restingNow;
    }

    /** Direct fix for the "flips to the wrong normal" bug, now root-caused via live debug logging
     * (getGroundSide() stayed correctly UP the entire time a visual upright-flip was observed on a
     * wide-open ceiling - see that method's own updated comment): the mismatch isn't in which SIDE
     * this entity thinks it's attached to at all, it's that getOrientation()'s own COSMETIC
     * normal/attachmentNormal blend can get and STAY stuck near the plain floor default
     * independently of that, for reasons already established earlier this session (forceDetach's own
     * push-direction bug, and restingOnFloorRaw's own ceiling-flip fix): updateOffsetsAndOrientation's
     * attachment SEARCH is seeded from whatever orientation.normal already is, a chicken-and-egg gap
     * that can apparently persist for more than the single transitional tick
     * recoverFromSpuriousAttachmentLoss above already catches - it can just sit there stuck, tick
     * after tick, with nothing forcing it to re-converge, however long the entity stays genuinely
     * wall/ceiling-attached by getGroundSide()'s own always-reliable measure.
     * <p>
     * Detects that exact disagreement every tick - getGroundSide() says wall/ceiling (not DOWN), but
     * orientation.normal still reads floor-like (y > 0.9) - and pushes toward whichever surface
     * getGroundSide() says is real, giving AWCAPI's own search a concrete, current collision to
     * re-anchor on every tick the mismatch persists, instead of continuing to feed itself its own
     * stuck seed indefinitely.
     * <p>
     * Deliberately a real move(), NOT nudgeTowardAttachedSurface (a velocity-only setDeltaMovement) -
     * a live-reported real bug in the first version of this fix: this entity is normally still
     * actively MOVING (mid-path) the whole time this mismatch can persist, unlike
     * nudgeTowardAttachedSurface's own only-ever-used case (a motionless fresh spawn) - and
     * setDeltaMovement REPLACES the entire velocity vector, silently zeroing out whatever real
     * horizontal movement was already happening every single tick this ran, on top of being too weak
     * to reliably force a fresh collision anyway (the same "a plain velocity nudge doesn't move the
     * needle here" lesson forceDetach's own comment already learned the hard way). A real positional
     * displacement, same technique and magnitude as updateGroundDirectionStability's own fix,
     * doesn't touch velocity at all - just adds a real, one-tick shove on top of whatever movement is
     * already in progress. */
    private void updateOrientationConvergence() {
        Direction groundSide = this.getGroundSide();
        boolean converging = groundSide != Direction.DOWN && this.getOrientation().normal.y > 0.9;
        if (converging) {
            Vec3 towardSurface = new Vec3(groundSide.getStepX(), groundSide.getStepY(), groundSide.getStepZ())
                .scale(ORIENTATION_CONVERGENCE_PUSH_DISTANCE);
            this.move(MoverType.SELF, towardSurface);
        }
        // TEMPORARY - live-debugging this fix itself, same as getGroundSide's own removed logging -
        // logs once per real start/stop transition, not every tick the mismatch persists, so a real
        // repro doesn't get buried in spam. Remove once this is confirmed fixed.
        if (converging != this.wasConvergingOrientationLastTick && WendigoDebug.anyEnabled()
                && this.level() instanceof ServerLevel serverLevel) {
            WendigoDebug.say(serverLevel, "orientation convergence " + (converging ? "STARTED" : "stopped")
                + " (groundSide=" + groundSide + ", normal.y=" + String.format("%.3f", this.getOrientation().normal.y)
                + ", onGround=" + this.onGround() + ", navInProgress=" + this.getNavigation().isInProgress() + ")");
        }
        this.wasConvergingOrientationLastTick = converging;
    }

    /** Live-reported bug: the wendigo visibly stuck/thrashing inside a straight, single-block (1x1)
     * crevice - a DIFFERENT failure mode from the ordinary stuck/no-progress detectors (PlanRunner's
     * own isMakingNoProgress/isOrbitTrapped), which only trip after several real seconds of literally
     * zero net displacement; this one can look like constant (if useless) motion the whole time, never
     * fully still, so those never catch it. Root-caused via decompiling ClimberComponent.
     * updateWalkingSide: its own "which way is the ground" probe computes the geometrically CLOSEST
     * colliding face among all 6 directions fresh every single tick, with no preference at all for
     * whichever direction it already picked last tick. In a corridor whose cross-section is only
     * slightly larger than this entity's own crawling hitbox (CRAWLING_DIMENSIONS is 0.8 wide/0.9
     * tall - a plain 1x1 tunnel leaves roughly 0.1-0.2 blocks of margin on every side, floor/ceiling/
     * left-wall/right-wall alike), those four directions all read as nearly EQUALLY close - a near-tie
     * the tiniest sub-block jitter from ordinary movement can flip every single tick. Each flip means
     * a completely different sticking-force direction fighting whatever the previous tick's force was
     * already doing, so the entity can churn indefinitely without ever building real forward progress.
     * <p>
     * Mitigated by detecting GROUND_DIRECTION_INSTABILITY_TICKS consecutive ticks of the probe's
     * result actually changing, then forcing a decisive tie-break: a real (not merely cosmetic) push
     * TOWARD whichever direction the probe most recently reported (current, captured right before the
     * threshold check below - getLeft() already points toward the surface, so pushing further that
     * way moves the entity closer to it) - the same "a plain velocity nudge doesn't reliably move the
     * needle here, this needs real geometric displacement" reasoning forceDetach's own comment already
     * established for the opposite problem (breaking contact, not gaining it) - just far enough that
     * whichever face won the last read becomes unambiguously the closest one again.
     * <p>
     * Deliberately NOT a hardcoded downward push, unlike an earlier version of this fix - a real,
     * live-reported regression that caused: pushing down unconditionally is right for a ground-level
     * tunnel tie (this method's own original motivating case), but actively wrong for a wall/ceiling
     * tie - the exact same ambiguity this method exists to break can just as easily happen climbing
     * near a small ceiling patch's own edge (reported: "cleanly climbs as long as the path stays
     * uninterrupted, flips upright the instant it repaths/goes off course near one"), where a forced
     * downward shove is precisely what yanks the entity off the ceiling it was trying to stay attached
     * to. Reinforcing whichever surface the geometry itself most recently favored, instead of assuming
     * it should always be the floor, fixes the tunnel case exactly the same way (current reads DOWN
     * there) while no longer fighting a genuine ceiling/wall attachment. Best-effort, same caveat
     * every other AWCAPI-black-box mitigation in this class carries - this works around behavior
     * inside a dependency this mod can't modify (see build.gradle), not a guaranteed fix. */
    private void updateGroundDirectionStability() {
        Direction current = this.getGroundDirection().getLeft();
        if (current != this.lastGroundDirection) {
            this.groundDirectionChangeTicks++;
        } else {
            this.groundDirectionChangeTicks = 0;
        }
        this.lastGroundDirection = current;
        if (this.groundDirectionChangeTicks < GROUND_DIRECTION_INSTABILITY_TICKS) {
            return;
        }
        this.groundDirectionChangeTicks = 0;
        Vec3 towardCurrentSurface = new Vec3(current.getStepX(), current.getStepY(), current.getStepZ())
            .scale(GROUND_DIRECTION_STABILITY_PUSH_DISTANCE);
        this.move(MoverType.SELF, towardCurrentSurface);
    }

    /**
     * The user's own explicit "literally just removes their attachment" request (see movement.drop's
     * own schema description). Two parts, both necessary (confirmed the hard way - an earlier version
     * of this method only did the second part, and a GameTest asserting a real Y-position fall caught
     * that it wasn't actually falling): first, an immediate FORCE_DETACH_PUSH_DISTANCE shove away from
     * the current attachment surface (getOrientation().normal already points away from it, same
     * convention nudgeTowardAttachedSurface's own doc comment establishes, just un-negated here since
     * that method moves toward the surface and this one needs the opposite) - this is what actually
     * breaks contact, since ClimberComponent.updateWalkingSide's real per-tick collision probe (which
     * drives the actual sticking-force physics, not just cosmetic orientation) only cares about real
     * geometric distance, completely independent of onGround()/attachedTicks. Second, forces
     * FORCE_DETACH_TICKS consecutive onGround()==false reads (see aiStep's own insertion point) so the
     * cosmetic attachment offset/orientation blend reverts to plain gravity-mode in step with the real
     * physical detachment the push just caused, instead of visibly lagging a few ticks behind it.
     * Suppresses recoverFromSpuriousAttachmentLoss for the duration plus a grace window (see
     * FORCE_DETACH_RECOVERY_GRACE_TICKS) so that reactive mitigation doesn't immediately undo this
     * deliberate one. What happens after is ordinary physics from here - ordinary gravity, and AWCAPI's
     * own reattachment search picking back up wherever it next finds solid ground (or the player, if
     * this is used as an ambush).
     */
    public void forceDetach() {
        // Deliberately getGroundDirection() (ClimberComponent.updateWalkingSide's own real, per-tick
        // geometric collision probe) here, NOT getOrientation().normal - live-debugged the difference:
        // orientation.normal only updates once updateOffsetsAndOrientation's own attachment SEARCH has
        // actually succeeded at least once, and that search's own direction bias is seeded from
        // whatever orientation.normal already was (a chicken-and-egg gap a fresh ceiling/wall spawn
        // that hasn't done any real climbing movement yet can get stuck in - confirmed via a debug log
        // showing a genuinely ceiling-attached entity still reading orientation.normal as the plain
        // (0,1,0) floor default). getGroundDirection() has no such dependency - it's recomputed from
        // scratch every tick purely from real nearby collision geometry, so it's already correct here
        // regardless of how this attachment was established. getLeft() points TOWARD the surface
        // (opposite convention from orientation.normal - confirmed via updateWalkingSide's own facing
        // iteration), so the push direction is negated relative to how nudgeTowardAttachedSurface uses
        // orientation.normal.
        Direction towardSurface = this.getGroundDirection().getLeft();
        Vec3 awayFromSurface = new Vec3(-towardSurface.getStepX(), -towardSurface.getStepY(), -towardSurface.getStepZ());
        this.move(MoverType.SELF, awayFromSurface.scale(FORCE_DETACH_PUSH_DISTANCE));
        this.forceDetachTicksRemaining = FORCE_DETACH_TICKS;
        this.suppressRecoveryTicksRemaining = FORCE_DETACH_TICKS + FORCE_DETACH_RECOVERY_GRACE_TICKS;
    }

    /** Best-effort "is this floor-resting reading actually explained by real ground" check backing
     * recoverFromSpuriousAttachmentLoss - a solid block directly beneath the current position counts
     * as a legitimate floor; anything else (mid-air, still against a wall/ceiling with open space
     * below) doesn't, and is treated as attachment loss instead of a real landing.
     * <p>
     * Live-debugged root cause of a real bug: this used to ALSO accept a solid block two full blocks
     * below (feet.below(2)), meant to tolerate sub-block fractional Y at the moment of a transition -
     * but for an entity hanging just under a ceiling, checking two whole blocks straight down easily
     * reaches the room's OWN real floor in anything but a genuinely tall room (most cave/mine
     * geometry isn't), which made this return true - "yes, a real floor is right here" - for a
     * completely spurious ceiling-to-floor flip, exactly blocking the one thing
     * recoverFromSpuriousAttachmentLoss exists to catch. Live debug logging confirmed the exact
     * scenario: pitch collapsed from ~180 to ~0, onGround genuinely false, in a single tick - and the
     * fall preventer never fired, because this check kept reporting a "real" floor that was actually
     * just the room's distant floor, several blocks away from where the entity actually was. Tightened
     * to the single, immediately-adjacent block only - a genuine floor landing has solid ground
     * directly beneath, not two blocks down. */
    private boolean hasRealFloorNearby() {
        return !isPassableForFloorCheck(this.blockPosition().below());
    }

    private boolean isPassableForFloorCheck(BlockPos pos) {
        return this.level().getBlockState(pos).getCollisionShape(this.level(), pos).isEmpty();
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

    /** Crawl pose is forced by four independent triggers - moving (the original mechanic), simply
     * not having room to stand at all right now (the pathfinder itself computes entityHeight from
     * whatever hitbox is current when a path is requested, and the spawn scan can now place it in a
     * 1-block gap - so physically fitting there has to be automatic, not just a byproduct of already
     * moving), actively navigating toward a destination, or not resting on a horizontal floor at all
     * (climbing a wall/ceiling - see isRestingOnFloor).
     *
     * <p>The navigating trigger exists to fix a real reported bug: a path computed while crawling can
     * legitimately route through a tight crevice (entityHeight was small at prepare() time), but a
     * multi-tick stall right at the crevice's mouth - jittering against real collision, exactly what
     * isMakingNoProgress's own comment describes - reads as near-zero velocity for long enough to
     * clear POSE_SWITCH_DEBOUNCE_TICKS and grow back to the 2.0-tall standing hitbox before it's
     * actually through the gap, physically wedging it there even though the nav still considers the
     * path valid (see checkStuck's own "valid path but wedged" case). Tying crawl to "do I still have
     * an active nav goal" as well as raw instantaneous velocity keeps the smaller hitbox committed for
     * the whole approach, not just whichever ticks happen to read a nonzero velocity. */
    private void updatePose() {
        boolean moving = isMoving();
        boolean navigating = this.getNavigation().isInProgress();
        boolean noRoomToStand = !hasStandingClearanceHere();
        boolean climbing = !isRestingOnFloor();
        Pose desired = (moving || navigating || noRoomToStand || climbing) ? Pose.SWIMMING : Pose.STANDING;
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
     * (teleported, not walked/climbed into place) directly onto a ceiling/wall position (see
     * DarkSpotScanner.findLiveBandPosition) falls straight off it instead of sticking - live testing
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
     * Starts a wave's plan body, running to completion and then attempting a live-resolved despawn
     * move (see PlanRunner) - every position a step needs, including despawn's own farthest-band
     * target, is resolved fresh against the player's current position when that step actually runs,
     * not pre-scanned here.
     */
    public void startWave(JsonObject plan, int severityPercent, boolean tierGatingBypassed) {
        this.planRunner.start(plan, severityPercent, tierGatingBypassed);
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

    /** See PlanRunner.isRepeatedlyStuck. */
    public boolean isRepeatedlyStuck() {
        return this.planRunner.isRepeatedlyStuck();
    }

    /** Post-grab second phase: walk to destination, then enter orbit around target - see
     * PlanRunner.startReturnToOrbit. */
    public void startReturnToOrbit(BlockPos destination, ServerPlayer target) {
        this.planRunner.startReturnToOrbit(destination, target);
    }

    public boolean isReturningToOrbit() {
        return this.planRunner.isReturningToOrbit();
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

    /** See PlanRunner.consumeRideJustEnded. */
    public boolean consumeRideJustEnded() {
        return this.planRunner.consumeRideJustEnded();
    }

    /** See PlanRunner.consumeSpearRepelJustHappened. */
    public boolean consumeSpearRepelJustHappened() {
        return this.planRunner.consumeSpearRepelJustHappened();
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
        this.planRunner.startRaw(plan);
    }

    public boolean isCrawling() {
        return this.getPose() == Pose.SWIMMING;
    }

    // Matches WendigoAnimationData's own baked "head" bone Y translation per pose (REST_POSE=2.5,
    // CRAWL_IDLE=0.9375) - real, live-tested numbers pulled directly from the rig data, not derived
    // from the (deliberately much shorter) collision box these deliberately do NOT match.
    private static final double VISUAL_EYE_HEIGHT_STANDING = 2.5;
    private static final double VISUAL_EYE_HEIGHT_CRAWLING = 0.9375;

    /** Where the visible rig's head/eyes actually render right now - deliberately separate from
     * getEyePosition() (left at vanilla's own default), which every stare-detection check (see
     * PlanPredicates.isLookingAtSelf) should use INSTEAD OF getEyePosition() for exactly that reason.
     * A real bug already happened the other way around: raising getEyePosition() itself to match the
     * rig (via EntityDimensions.withEyeHeight) put it above the entity's own much shorter collision
     * box, and Entity.isInWall() builds its suffocation-check box centered on getEyePosition() - the
     * wendigo started taking real suffocation damage climbing ordinary 2-tall cave ceilings,
     * triggering EnderMan's own random teleport-on-hurt reaction repeatedly. This method exists so
     * stare-detection can still target the real visual head position without ever touching the
     * actual getEyePosition() vanilla combat/physics code relies on.
     * <p>Delegates to WendigoVisual.getBoneWorldPosition("head") - the exact same bone-transform
     * composition the rig actually renders with (baked animation matrix + floor yaw or full
     * wall/ceiling attachment orientation, whichever applies right now), not an independently
     * hand-derived approximation. A first version tried projecting a fixed eye-height scalar along
     * getOrientation().normal alone - closer than a plain vertical offset, but still provably wrong
     * for anything but a perfectly flat attachment: the real bone matrix also has its own local
     * rotation and X/Z translation (see WendigoAnimationData's own crawl-pose head entries), which a
     * single-normal projection can't represent. Real-world symptom this caused: a genuine dead-on
     * stare at a crawling (any non-flat-standing pose forces crawl - see updatePose) wendigo was
     * still never detected, since the checked point didn't land where the rig's head actually was. */
    public Vec3 getVisualEyePosition() {
        if (this.visual != null) {
            return this.visual.getBoneWorldPosition("head");
        }
        // Fallback for the brief window before WendigoMod's own ENTITY_LOAD hook attaches the visual
        // rig (see setVisual) - a straight vertical offset, exactly right only while standing flat on
        // a floor; never relied on once the rig actually exists, which is effectively always in
        // practice (the hook fires the same tick the entity loads).
        double eyeHeight = isCrawling() ? VISUAL_EYE_HEIGHT_CRAWLING : VISUAL_EYE_HEIGHT_STANDING;
        return this.position().add(this.getOrientation().normal.scale(eyeHeight));
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

    // Same particle/sound beat as spawnWave's own spawn-side call (see WendigoManager -
    // playSpawnDespawnEffect) - duplicated here rather than shared across packages, since entity
    // depending on wave would invert this project's existing layering (wave orchestrates entity/
    // plan, never the reverse). User's own explicit choice of particle/sound/numbers throughout.
    private static final int DESPAWN_PARTICLE_COUNT = 1000;
    private static final double DESPAWN_PARTICLE_DX = 0.5;
    private static final double DESPAWN_PARTICLE_DY = 1.0;
    private static final double DESPAWN_PARTICLE_DZ = 0.5;
    // Stage 1 (see getSeverityPercent's own comment - matches WendigoManager's STAGE_UNDER_20 bucket)
    // reads as "barely a presence" everywhere else in this mod, so its despawn shouldn't announce
    // itself with the same full-strength smoke/sound every later stage gets - the user's own explicit
    // request, scaling BOTH particle count and sound volume down together rather than dropping either
    // one entirely (a silent or particle-less vanish read as a bug, not a deliberately faint one).
    private static final float STAGE1_DESPAWN_EFFECT_SCALE = 0.2F;
    private static final int STAGE1_MAX_PERCENT = 20; // exclusive - mirrors WendigoManager.buildSystemPrompt's own STAGE_UNDER_20 cutoff

    /** Base Entity.remove doesn't eject passengers on its own (confirmed via bytecode - neither it
     * nor Mob does this). Without this override, a player still forcibly mounted (see
     * PlanRunner.beginForcedRide) when the wave gets discarded via a backstop path that doesn't go
     * through PlanRunner.completeWave (see WendigoManager.checkForcedWaveEnd) would be left riding an
     * entity that no longer exists. Runs for every removal path, not just that one, as a blanket
     * guarantee - also exactly why this is the one shared place the despawn particle/sound effect
     * lives, rather than needing to be duplicated at every individual discard() call site across
     * WendigoManager. */
    @Override
    public void remove(Entity.RemovalReason reason) {
        this.ejectPassengers();
        if (this.level() instanceof ServerLevel serverLevel) {
            boolean stage1 = this.severityPercent < STAGE1_MAX_PERCENT;
            float scale = stage1 ? STAGE1_DESPAWN_EFFECT_SCALE : 1.0F;
            int particleCount = stage1 ? Math.round(DESPAWN_PARTICLE_COUNT * STAGE1_DESPAWN_EFFECT_SCALE) : DESPAWN_PARTICLE_COUNT;
            serverLevel.sendParticles(ParticleTypes.SMOKE, this.getX(), this.getY(), this.getZ(),
                particleCount, DESPAWN_PARTICLE_DX, DESPAWN_PARTICLE_DY, DESPAWN_PARTICLE_DZ, 0.0);
            serverLevel.playSound(null, this.getX(), this.getY(), this.getZ(),
                SoundEvents.WARDEN_ATTACK_IMPACT, SoundSource.HOSTILE, scale, 0.0F);
        }
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

    /** Which rig layer(s) {@link WendigoVisual} should actually give real items to at construction
     * time - see /wendigo summon's own comment. ALL is the normal in-game appearance (base +
     * growth overlay + eyes all stacked); the other three isolate a single texture so it can be
     * inspected without the other two layers drawn on top of it. Must be set before the entity is
     * added to the level (WendigoVisual reads it once, in its constructor, off WendigoMod's
     * ENTITY_LOAD hook - changing it after spawn has no effect). */
    public enum TexturePreviewMode { ALL, BASE_ONLY, GROWTH_ONLY, EYES_ONLY }

    private TexturePreviewMode texturePreviewMode = TexturePreviewMode.ALL;

    public void setTexturePreviewMode(TexturePreviewMode mode) {
        this.texturePreviewMode = mode;
    }

    public TexturePreviewMode getTexturePreviewMode() {
        return this.texturePreviewMode;
    }
}
