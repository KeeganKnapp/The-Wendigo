package com.wendigo.wave;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import com.wendigo.plan.ProximityBands;
import com.wendigo.spatial.CaveScaleScanner.CaveScale;

/** Engine-scanned context for one wave: the target player, their severity, and labeled dark-spot candidates. */
public final class WaveContext {
	private static final String[] SPOT_LABELS = {"spot_a", "spot_b", "spot_c", "spot_d", "spot_e", "spot_f"};

	private final ServerPlayer player;
	private final int severity;
	private final int severityCap;
	private final List<BlockPos> spots; // nearest -> furthest, index-aligned with SPOT_LABELS
	// Both index-aligned with spots - positions (not just counts) so /wendigo debug can draw them,
	// not just report a number in the prompt.
	private final List<List<BlockPos>> dimSpotsPerSpot;
	private final List<List<BlockPos>> torchesPerSpot;
	// Index-aligned with spots - which other spot indices are actually walkable-to from this one
	// (see SpotConnectivity). Lets the model chain movement.approach_spot into a farther spot before
	// movement.approach_dim_spot, instead of only ever having access to whichever spot it spawned at.
	private final List<List<Integer>> reachableSpotsPerSpot;
	// Null if this is the wendigo's first real encounter with this player this session (see
	// EncounterHistory) - never populated for debug-forced waves.
	private final EncounterHistory.Entry previousEncounter;
	private final int nowTick;
	// Rough classification of the open space around the player right now (see CaveScaleScanner) -
	// currently only gates spawn_at's on_torch option, but meant as general-purpose context beyond
	// that single use.
	private final CaveScale caveScale;

	public WaveContext(ServerPlayer player, int severity, int severityCap, List<BlockPos> spots,
			List<List<BlockPos>> dimSpotsPerSpot, List<List<BlockPos>> torchesPerSpot,
			List<List<Integer>> reachableSpotsPerSpot, EncounterHistory.Entry previousEncounter, int nowTick,
			CaveScale caveScale) {
		this.player = player;
		this.severity = severity;
		this.severityCap = severityCap;
		this.spots = spots;
		this.dimSpotsPerSpot = dimSpotsPerSpot;
		this.torchesPerSpot = torchesPerSpot;
		this.reachableSpotsPerSpot = reachableSpotsPerSpot;
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

	/** All scanned dark spots, nearest to furthest - used to build a despawn fallback chain. */
	public List<BlockPos> spots() {
		return this.spots;
	}

	/** Each dark spot's nearby dim spots, index-aligned with spots() - used by /wendigo debug's particle view. */
	public List<List<BlockPos>> dimSpotsPerSpot() {
		return this.dimSpotsPerSpot;
	}

	/** Resolves a schema spot label (e.g. "spot_b") to its scanned position, or null if not found. */
	public BlockPos resolve(String label) {
		for (int i = 0; i < SPOT_LABELS.length; i++) {
			if (SPOT_LABELS[i].equals(label) && i < this.spots.size()) {
				return this.spots.get(i);
			}
		}
		return null;
	}

	/** Renders this context as the user-prompt text the LLM sees alongside the action schema. */
	public String toPromptText() {
		ServerLevel level = this.player.level();
		BlockPos playerPos = this.player.blockPosition();

		StringBuilder sb = new StringBuilder();
		sb.append("Target player: ").append(this.player.getGameProfile().name()).append(". ");
		sb.append("Dweller severity for this player: ").append(this.severity).append("/").append(this.severityCap)
			.append(" (cumulative time spent below y=0 - higher means this has been going on longer). ");
		sb.append("Current caving scenario: ").append(describeCaveScale())
			.append(" - how tight or open the space around the player is right now. ");
		sb.append("Distance bands, nearest to furthest: grab_distance (0-4 blocks), lunge_distance (5-9), "
			+ "close_quarters (10-14), medium (15-24), far (25+) - the same bands predicate.player_distance "
			+ "compares against, so you can tell before picking spawn_at whether a spot already sits inside "
			+ "combat/flee range of the player. ");
		sb.append("Scanned dark spots near the player, nearest to furthest:\n");
		for (int i = 0; i < this.spots.size(); i++) {
			BlockPos spot = this.spots.get(i);
			double distance = Math.sqrt(spot.distSqr(playerPos));
			int light = level.getMaxLocalRawBrightness(spot);
			int dimSpots = i < this.dimSpotsPerSpot.size() ? this.dimSpotsPerSpot.get(i).size() : 0;
			int torches = i < this.torchesPerSpot.size() ? this.torchesPerSpot.get(i).size() : 0;
			String reachable = describeReachable(i);
			sb.append(String.format(
				"- %s: %.0f blocks away (%s), light level %d, %d nearby dim spots (edge-of-light positions reachable from here), %d nearby torches, pathfindable to: %s%n",
				SPOT_LABELS[i], distance, ProximityBands.labelFor(distance), light, dimSpots, torches, reachable));
		}
		sb.append("movement.approach_spot moves the wendigo to another scanned spot by label - only use one "
			+ "listed in that spot's \"pathfindable to\" above, since that's a real, engine-verified route, not a "
			+ "guess. This is how to reach a spot's dim spots when it isn't the one you spawned at: "
			+ "movement.approach_spot to get there, then movement.approach_dim_spot (which always searches from "
			+ "wherever the wendigo currently is) to actually reach one of its dim spots - e.g. spawn at a "
			+ "distant spot with no dim spots of its own, approach_spot to a closer one that has some, then "
			+ "approach_dim_spot from there for a real creeping-toward-the-light approach instead of just "
			+ "appearing already at the edge of the light.\n");
		sb.append("combat.break_torch resolves the nearest known light source live from wherever the wendigo "
			+ "currently is (no label needed) - the per-spot torch counts above tell you whether that's worth "
			+ "planning around before picking spawn_at, e.g. spawning at a distant spot that still has its own "
			+ "nearby torch to snuff out.\n");
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
			.append(this.previousEncounter.hitLanded() ? ", landed a hit" : ", never landed a hit")
			.append(". Its plan that time, in order, was: ")
			.append(String.join(" -> ", this.previousEncounter.planShape()))
			.append(". React to that outcome rather than ignoring it, and don't just repeat the same sequence again - "
				+ "vary the approach.\n");
		return sb.toString();
	}

	private String describeReachable(int index) {
		if (index >= this.reachableSpotsPerSpot.size() || this.reachableSpotsPerSpot.get(index).isEmpty()) {
			return "none";
		}
		StringBuilder sb = new StringBuilder();
		for (int other : this.reachableSpotsPerSpot.get(index)) {
			if (!sb.isEmpty()) {
				sb.append(", ");
			}
			sb.append(SPOT_LABELS[other]);
		}
		return sb.toString();
	}
}
