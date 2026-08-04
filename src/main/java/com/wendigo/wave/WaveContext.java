package com.wendigo.wave;

import java.util.Map;

import net.minecraft.server.level.ServerPlayer;

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
	// Null if this is the wendigo's first real encounter with this player this session (see
	// EncounterHistory) - never populated for debug-forced waves.
	private final EncounterHistory.Entry previousEncounter;
	private final int nowTick;
	// Rough classification of the open space around the player right now (see CaveScaleScanner) -
	// meant as general-purpose context, not tied to any one use.
	private final CaveScale caveScale;

	/** The wendigo's own live distance from the player and whether it's currently perched on a
	 * ceiling roughly above them (see DarkSpotScanner.findCeilingVantagePoint) - only meaningful for
	 * an already-alive entity being engaged, see WaveContext's own currentPosition field. */
	public record CurrentPosition(double distanceFromPlayer, boolean isOnTopPlayer) {
	}

	public WaveContext(ServerPlayer player, int severity, int severityCap, Map<String, Integer> torchCountsByBand,
			CurrentPosition currentPosition, EncounterHistory.Entry previousEncounter, int nowTick, CaveScale caveScale) {
		this.player = player;
		this.severity = severity;
		this.severityCap = severityCap;
		this.torchCountsByBand = torchCountsByBand;
		this.currentPosition = currentPosition;
		this.previousEncounter = previousEncounter;
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

	/** Renders this context as the user-prompt text the LLM sees alongside the action schema. */
	public String toPromptText() {
		StringBuilder sb = new StringBuilder();
		sb.append("Target player: ").append(this.player.getGameProfile().name()).append(". ");
		sb.append("Dweller severity for this player: ").append(this.severity).append("/").append(this.severityCap)
			.append(" (a slow-burning escalation across many separate encounters with this player over "
				+ "time - higher means this relationship with the dark is more established, not something "
				+ "to try to advance within a single wave). ");
		sb.append("Current caving scenario: ").append(describeCaveScale())
			.append(" - how tight or open the space around the player is right now. ");
		sb.append("Positioning distance bands (spawn_at, movement.approach_band, combat.break_torch's optional "
			+ "band), nearest to furthest - resolved LIVE against wherever the player actually is at the moment "
			+ "each one is used, never a frozen position from right now: close_as_possible (0-4 blocks), close "
			+ "(5-9), medium (10-16), far (17-24), farther (25-35), farthest (36+, also the natural despawn/"
			+ "retreat distance). These are a DIFFERENT ladder from predicate.player_distance's own bands below - "
			+ "same-sounding names (medium/far) mean different ranges in each one, so don't mix them up: this "
			+ "ladder is only for spawn_at/movement.approach_band/combat.break_torch's band field. ");
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
			sb.append("The wendigo is already active right now, ").append(String.format("%.0f", this.currentPosition.distanceFromPlayer()))
				.append(" blocks from the player")
				.append(this.currentPosition.isOnTopPlayer() ? ", currently perched on a ceiling roughly above them" : "")
				.append(" - if that's already a good distance for what this plan is about to do, spawn_at can "
					+ "resolve to right here with no travel at all; there's no obligation to move first just "
					+ "because a band exists. ");
		}
		sb.append(describePreviousEncounter());
		return sb.toString();
	}

	private String describeCaveScale() {
		return switch (this.caveScale) {
			case TIGHT -> "tight/narrow (mineshaft-like corridor)";
			case MASSIVE -> "massive open cavern";
			default -> "a normal-sized cave";
		};
	}

	private String describePreviousEncounter() {
		if (this.previousEncounter == null) {
			return "This is the wendigo's first real encounter with this player (this session) - no history to react to yet.\n";
		}
		double secondsAgo = (this.nowTick - this.previousEncounter.endTick()) / 20.0;
		StringBuilder sb = new StringBuilder();
		sb.append("Previous encounter (#").append(this.previousEncounter.waveCount()).append(" with this player) ended ")
			.append(String.format("%.0f", secondsAgo)).append("s ago: ")
			.append(this.previousEncounter.vanishedCleanly() ? "vanished unnoticed" : "had to withdraw/flee")
			.append(this.previousEncounter.reachedDeadStare() ? ", was spotted dead-on at some point" : ", was never directly stared at")
			.append(this.previousEncounter.hitLanded() ? ", caught the player" : ", never caught the player")
			.append(". Its plan that time, in order, was: ")
			.append(String.join(" -> ", this.previousEncounter.planShape()))
			.append(". React to that outcome rather than ignoring it, and don't just repeat the same sequence again - "
				+ "vary the approach.\n");
		return sb.toString();
	}
}
