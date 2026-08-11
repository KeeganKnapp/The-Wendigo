package com.wendigo.wave;

import java.util.List;
import java.util.Map;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import com.wendigo.plan.PositionBands;
import com.wendigo.spatial.CaveScaleScanner.CaveScale;

/** Engine-built context for one wave: the target player, their severity, and a live snapshot of
 * torch counts per distance band - NOT pre-scanned positions. Every actual position a plan resolves
 * against (spawn_at, movement.approach_band, despawn/retreat) is looked up fresh, live, at the
 * moment it's needed (see DarkSpotScanner.findLiveBandPosition) - this class only carries what the
 * PROMPT needs to know at request time (necessarily a snapshot, since there's exactly one LLM call
 * per plan), never a stored position a later step would resolve against. */
public final class WaveContext {
	/** The 6 live distance bands, nearest to furthest - see SemanticBands.bandDistanceMin/Max. Public
	 * so WendigoManager's torch-count scan and this class's own prompt text share one label list. */
	public static final String[] BAND_LABELS =
		{"close_as_possible", "close", "medium", "far", "farther", "farthest"};

	private final ServerPlayer player;
	private final int severity;
	private final int severityCap;
	// Live torch count per band (see BAND_LABELS), captured once at buildContext time - purely
	// informational for the prompt ("torches_at_medium_distance: 10"), not something later resolution
	// reads from; combat.break_torch's own band-constrained lookup and spawn_on_torch's own
	// eligibility/position resolution both re-scan live at the moment they're actually needed instead
	// of trusting this snapshot, which could already be stale by then.
	private final Map<String, Integer> torchCountsByBand;
	// Only set when engaging an already-alive, already-orbiting entity (not a fresh spawn, where
	// there's no current wendigo position to report yet) - lets the prompt tell the model "you're
	// already at X, decide for yourself whether that's good enough" instead of always assuming a
	// fresh appearance.
	private final CurrentPosition currentPosition;
	// Every real encounter with this player so far this session, oldest first - empty if this is the
	// wendigo's first real encounter with this player (see EncounterHistory) - never populated for
	// debug-forced waves.
	private final List<EncounterHistory.Entry> history;
	private final int nowTick;
	// Rough classification of the open space around the player right now (see CaveScaleScanner) -
	// meant as general-purpose context, not tied to any one use.
	private final CaveScale caveScale;

	/** The wendigo's own live distance from the player, whether it's currently perched on a ceiling
	 * roughly above them (see DarkSpotScanner.findCeilingVantagePoint), whether it's currently
	 * attached to any wall/ceiling at all (see WendigoEntity.getGroundSide()) rather than resting on a
	 * plain floor, and whether the player is already looking straight at it right now - only
	 * meaningful for an already-alive entity being engaged, see WaveContext's own currentPosition
	 * field (a genuinely fresh spawn is always unwatched by construction - see resolveUnwatchedSpot -
	 * so there's nothing to report there). Three separate facts, not one: onWallOrCeiling answers
	 * "would movement.drop actually do anything right now" (a fresh spawn never needs this), isOnTopPlayer
	 * specifically answers "is it positioned for an overhead ambush drop" (a wendigo on a side wall
	 * could satisfy the first without the second at all), and isPlayerLookingAtSelf (checked at the
	 * "in_view" band - see SemanticBands.lookAngleDegrees - the same threshold a stare's own weakest
	 * noticed-band uses) answers "does the plan need to open already-spotted rather than assuming a
	 * clean, unseen start" - the user's own explicit request, since re-engaging an already-orbiting
	 * wendigo can land it somewhere the player happens to already be facing, unlike a fresh spawn.
	 * playerDirection is the user's own explicit "general change in distance... over the last 5
	 * seconds" request (see WendigoEntity.playerDirection's own doc comment) - "movingCloser"/
	 * "movingAway"/"steady", or null if the entity hasn't been tracking long enough yet to say (same
	 * "nothing to report yet" null convention this whole record already follows for a fresh spawn). */
	public record CurrentPosition(double distanceFromPlayer, boolean isOnTopPlayer, boolean onWallOrCeiling,
			boolean isPlayerLookingAtSelf, String playerDirection) {
	}

	public WaveContext(ServerPlayer player, int severity, int severityCap, Map<String, Integer> torchCountsByBand,
			CurrentPosition currentPosition, List<EncounterHistory.Entry> history, int nowTick, CaveScale caveScale) {
		this.player = player;
		this.severity = severity;
		this.severityCap = severityCap;
		this.torchCountsByBand = torchCountsByBand;
		this.currentPosition = currentPosition;
		this.history = history;
		this.nowTick = nowTick;
		this.caveScale = caveScale;
	}

	public CaveScale caveScale() {
		return this.caveScale;
	}

	public ServerPlayer player() {
		return this.player;
	}

	public int severity() {
		return this.severity;
	}

	public int severityCap() {
		return this.severityCap;
	}

	/** Live torch count for one band, captured at buildContext time - see the field's own comment
	 * on why nothing downstream should treat this as still-accurate by the time it's read. */
	public int torchCountForBand(String band) {
		return this.torchCountsByBand.getOrDefault(band, 0);
	}

	// Same threshold PlanPredicates.targetMoving/targetIsStopped already use (that class's own
	// horizontalSpeedSqr is private, different package, so this is a deliberate duplicate, not a
	// shared constant - kept in sync by hand, same tradeoff DarknessMalus's own doc comment already
	// accepts for its own light-threshold duplication against DarkSpotScanner.MAX_DARK_LIGHT).
	private static final double WALKING_SPEED_THRESHOLD_SQR = 0.0009; // (~0.03 blocks/tick)^2

	/** The user's own explicit request: previously only a boolean (predicate.target_moving/
	 * target_is_stopped, engine-evaluated facts a plan can branch on, not prompt context at all) - a
	 * real tri-state read directly off the live player, the same "live snapshot, read at
	 * toPromptText() time" pattern torchCountsByBand's own doc comment already established for this
	 * class. isSprinting() checked first since a sprinting player can momentarily read a low raw
	 * delta on the exact tick this renders (right at a stride's low point) - the vanilla flag itself
	 * is the more reliable signal for "sprinting" specifically, the speed threshold only needed to
	 * tell a walk from standing still. */
	private String describePlayerMovement() {
		if (this.player.isSprinting()) {
			return "sprinting";
		}
		Vec3 delta = this.player.getDeltaMovement();
		double horizontalSpeedSqr = delta.x * delta.x + delta.z * delta.z;
		return horizontalSpeedSqr > WALKING_SPEED_THRESHOLD_SQR ? "walking" : "standing still";
	}

	/** Renders this context as the user-prompt text the LLM sees alongside the action schema. */
	public String toPromptText() {
		StringBuilder sb = new StringBuilder();
		sb.append("Target player: ").append(this.player.getGameProfile().name()).append(". ");
		sb.append("They are currently ").append(describePlayerMovement()).append(". ");
		sb.append("Dweller severity for this player: ").append(this.severity).append("/").append(this.severityCap)
			.append(" (a slow-burning escalation across many separate encounters with this player over "
				+ "time - higher means this relationship with the dark is more established, not something "
				+ "to try to advance within a single wave). ");
		sb.append("Current caving scenario: ").append(describeCaveScale())
			.append(" - how tight or open the space around the player is right now. ");
		sb.append("Positioning distance bands (movement.approach_band, combat.break_torch's optional "
			+ "band), nearest to furthest - resolved LIVE against wherever the player actually is at the moment "
			+ "each one is used, never a frozen position from right now: close_as_possible (0-4 blocks), close "
			+ "(5-9), medium (10-16), far (17-24), farther (25-35), farthest (36+, also the natural despawn/"
			+ "retreat distance). These are a DIFFERENT ladder from predicate.player_distance's own bands below - "
			+ "same-sounding names (medium/far) mean different ranges in each one, so don't mix them up: this "
			+ "ladder is only for movement.approach_band/combat.break_torch's band field. ");
		sb.append("Combat/predicate distance bands (predicate.player_distance only), nearest to furthest: "
			+ "grab_distance (0-3 blocks), lunge_distance (4-9), close_quarters (10-14), medium (15-24), far "
			+ "(25+). ");
		sb.append("Live torch counts by positioning band right now: ");
		for (int i = 0; i < BAND_LABELS.length; i++) {
			if (i > 0) {
				sb.append(", ");
			}
			sb.append("torches_at_").append(BAND_LABELS[i]).append("_distance: ").append(torchCountForBand(BAND_LABELS[i]));
		}
		sb.append(". Worth planning combat.break_torch(band=...) around whichever band actually has some - a "
			+ "band with a zero count has nothing for it to find, and it'll just skip cleanly if you try anyway. ");
		if (this.currentPosition != null) {
			String currentBand = PositionBands.classify(this.currentPosition.distanceFromPlayer());
			sb.append("The wendigo is already active right now, ").append(String.format("%.0f", this.currentPosition.distanceFromPlayer()))
				.append(" blocks from the player (").append(currentBand).append(" band)")
				.append(this.currentPosition.isOnTopPlayer() ? ", currently perched on a ceiling roughly above them" : "")
				.append(" - if that's already a good position for what this plan is about to do, the plan can just "
					+ "start straight into it with no travel at all; there's no obligation to move first just "
					+ "because a band exists. Also relevant for combat.teleport_to_band's own source-band "
					+ "precondition (see its own schema description) - it currently being in the ").append(currentBand)
				.append(" band is exactly what that precondition checks against. ");
			sb.append(this.currentPosition.isPlayerLookingAtSelf()
				? "The player is ALREADY looking straight at it right now, this instant, before the plan has even "
					+ "started - this is not a fresh, unseen appearance, so don't open with a stare-hold gated on "
					+ "being noticed (it already has been); react to already being seen instead, e.g. commit "
					+ "immediately if the stage allows it, or reposition out of view first if the moment calls for "
					+ "staying hidden a while longer. "
				: "The player is NOT currently looking at it - it's free to open with an ordinary unseen "
					+ "reveal/stare-hold the normal way. ");
			sb.append(this.currentPosition.onWallOrCeiling()
				? "It's currently attached to a wall/ceiling somewhere (not resting on the floor) - a plain "
					+ "movement.drop right at the start of this plan would let go and fall for real, no spot_above "
					+ "positioning needed first since it's already off the floor. "
				: "It's currently on the floor, not attached to any wall/ceiling - movement.drop would do nothing "
					+ "if used right now; only reach for it once something earlier in THIS plan (spawn_at/"
					+ "movement.approach_band with band=spot_above, or ordinary climbing along the way) has "
					+ "actually put it back on a surface first. ");
			if (this.currentPosition.playerDirection() != null) {
				sb.append(switch (this.currentPosition.playerDirection()) {
					case "movingCloser" -> "Over roughly the last 5 seconds the player has been closing the "
						+ "distance to it (movingCloser). ";
					case "movingAway" -> "Over roughly the last 5 seconds the player has been moving away from "
						+ "it (movingAway). ";
					default -> "Over roughly the last 5 seconds the player's distance to it has stayed "
						+ "roughly steady (steady). ";
				});
			}
		}
		sb.append(describeEncounterHistory());
		return sb.toString();
	}

	private String describeCaveScale() {
		return switch (this.caveScale) {
			case TIGHT -> "tight/narrow (mineshaft-like corridor)";
			case MASSIVE -> "massive open cavern";
			default -> "a normal-sized cave";
		};
	}

	/** The Entry this request's own previous_encounter_recap should be attributed to, if the model
	 * writes one - null for a genuinely fresh first encounter this session, in which case there's
	 * nothing for the model to recap at all (its own schema description says as much). Always the
	 * NEWEST entry in the full history (see the field's own comment) - every older entry should
	 * already have a real recap filled in by the time a later request reads it, so the newest one is
	 * the only one that can still need writing. Package-visible so WendigoManager can pass this same
	 * Entry's waveCount back into EncounterHistory.updateRecap once the response comes back, without
	 * needing to re-look-up EncounterHistory itself and risk a race against a newer encounter having
	 * been recorded in the meantime. */
	EncounterHistory.Entry previousEncounter() {
		return this.history.isEmpty() ? null : this.history.get(this.history.size() - 1);
	}

	/** "The whole story for that run" (the user's own explicit request) - every real encounter this
	 * session with this player, oldest first, not just the single most recent one. Each entry prefers
	 * the model's own prior recap (see EncounterHistory.Entry's own doc comment for why it's a
	 * request-cycle behind) over re-deriving the same mechanical description every time - it's a
	 * genuinely richer signal (captures the wendigo's own INTENT, e.g. "I set a stare jumpscare," not
	 * just the raw mechanical fact of which steps ran). Falls back to structured facts only for
	 * whichever entry doesn't have one yet (in practice, at most the newest one - see previousEncounter's
	 * own doc comment), so this never goes silent even for the very first request that has anything to
	 * describe at all. */
	private String describeEncounterHistory() {
		if (this.history.isEmpty()) {
			return "This is the wendigo's first real encounter with this player (this session) - no history to react to yet.\n";
		}
		StringBuilder sb = new StringBuilder();
		sb.append("Story so far this session with this player (").append(this.history.size())
			.append(this.history.size() == 1 ? " encounter" : " encounters").append("):\n");
		for (EncounterHistory.Entry entry : this.history) {
			sb.append(describeEntry(entry)).append("\n");
		}
		sb.append("React to this whole history rather than just the most recent entry - notice patterns across "
			+ "multiple encounters, and don't just repeat the same sequence again - vary the approach.\n");
		return sb.toString();
	}

	private String describeEntry(EncounterHistory.Entry entry) {
		double secondsAgo = (this.nowTick - entry.endTick()) / 20.0;
		StringBuilder sb = new StringBuilder();
		sb.append("- Encounter #").append(entry.waveCount()).append(" (ended ")
			.append(String.format("%.0f", secondsAgo)).append("s ago): ");
		if (entry.recap() != null && !entry.recap().isBlank()) {
			sb.append(entry.recap());
		} else {
			sb.append(entry.withdrewInstantly() ? "vanished unnoticed" : "had to withdraw/flee")
				.append(entry.reachedDeadStare() ? ", was spotted dead-on at some point" : ", was never directly stared at");
			// grabbedSuccessfully and dealtDamage are deliberately separate facts, not one - a real
			// catch that ends in an escape before any damage lands is a genuinely different outcome
			// from never touching the player at all, and both are different again from a catch that
			// actually finished with damage dealt (see EncounterHistory.Entry's own doc comment).
			if (entry.grabbedSuccessfully() && entry.dealtDamage()) {
				sb.append(", caught the player and dealt damage before the encounter ended");
			} else if (entry.grabbedSuccessfully()) {
				sb.append(", caught the player but they got away before any damage landed");
			} else {
				sb.append(", never caught the player");
			}
			sb.append(", broke ").append(entry.torchBreakCount())
				.append(entry.torchBreakCount() == 1 ? " torch" : " torches");
			if (entry.interruptReason() != null) {
				// Only mentioned when non-null - a genuinely negative signal (the engine had to
				// force-end it: the player went unreachable, wandered too far, left the valid Y range,
				// or the wendigo got stuck), distinct from an ordinary global_rule preemption or a
				// grab-forced carry-flee, neither of which counts as this - see EncounterHistory.Entry's
				// own doc comment on interruptReason for the exact distinction. Now names the actual
				// reason (the engine's own text) rather than just flagging that one happened, plus WHERE
				// it happened - whatever action was still in flight the instant the engine stepped in,
				// when known.
				sb.append(", and had to be cut short by the engine (").append(entry.interruptReason()).append(")");
				if (entry.interruptedDuringAction() != null) {
					sb.append(", right in the middle of ").append(entry.interruptedDuringAction());
				}
				sb.append(" - not a clean ending");
			}
			sb.append(". Its plan that time, in order, was: ")
				.append(String.join(" -> ", entry.planShape()));
		}
		sb.append(".");
		return sb.toString();
	}
}
