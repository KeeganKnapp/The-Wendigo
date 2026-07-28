package com.wendigo.wave;

import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import com.wendigo.plan.ProximityBands;
import com.wendigo.spatial.CaveScaleScanner.CaveScale;
import com.wendigo.spatial.DarkSpotScanner;

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
	// Torch-adjacent positions DarkSpotScanner.findWaveSpots' flood found along the way, separate from
	// spots() - the spawn_on_torch fallback candidate pool (see WendigoManager). All reachable from
	// the player by the same construction as spots() itself, no separate verification needed - moot
	// once nothing is offered in the schema anyway (SchemaBuilder gates spawn_on_torch on at least one
	// of these clearing the current severity's distance floor - see minTorchSpawnDistance - not simply
	// on this list being non-empty).
	private final List<DarkSpotScanner.WaveSpot> torchSpawnCandidates;
	// Null if this is the wendigo's first real encounter with this player this session (see
	// EncounterHistory) - never populated for debug-forced waves.
	private final EncounterHistory.Entry previousEncounter;
	private final int nowTick;
	// Rough classification of the open space around the player right now (see CaveScaleScanner) -
	// meant as general-purpose context, not tied to any one use.
	private final CaveScale caveScale;
	// Spot position -> the torch it's linked to, for spots DarkSpotScanner.findWaveSpots accepted as
	// too-lit-but-torch-adjacent rather than genuinely dark (see DarkSpotScanner.WaveSpot) - only
	// entries for spots that ARE torch-linked, so absence means "genuinely dark, nothing to break".
	// Invisible to the model entirely (spawn_at just offers the label like any other spot); only
	// WendigoManager.spawnWave reads this, to know whether to destroy a torch on arrival there.
	private final Map<BlockPos, BlockPos> torchLinkedSpots;

	public WaveContext(ServerPlayer player, int severity, int severityCap, List<BlockPos> spots,
			List<List<BlockPos>> dimSpotsPerSpot, List<List<BlockPos>> torchesPerSpot,
			List<DarkSpotScanner.WaveSpot> torchSpawnCandidates, EncounterHistory.Entry previousEncounter, int nowTick,
			CaveScale caveScale, Map<BlockPos, BlockPos> torchLinkedSpots) {
		this.player = player;
		this.severity = severity;
		this.severityCap = severityCap;
		this.spots = spots;
		this.dimSpotsPerSpot = dimSpotsPerSpot;
		this.torchesPerSpot = torchesPerSpot;
		this.torchSpawnCandidates = torchSpawnCandidates;
		this.previousEncounter = previousEncounter;
		this.nowTick = nowTick;
		this.caveScale = caveScale;
		this.torchLinkedSpots = torchLinkedSpots;
	}

	/** Torch-adjacent spawn_on_torch candidates - see the field's own comment. */
	public List<DarkSpotScanner.WaveSpot> torchSpawnCandidates() {
		return this.torchSpawnCandidates;
	}

	/** The torch this spot is linked to, or null if it's a genuinely dark spot with nothing to break. */
	public BlockPos linkedTorchFor(BlockPos spot) {
		return this.torchLinkedSpots.get(spot);
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
		if (this.spots.isEmpty()) {
			sb.append("No scanned dark spots found near the player at all right now - nowhere hidden enough nearby "
				+ "to offer as spawn_at.\n");
		} else {
			sb.append("Scanned dark spots near the player, nearest to furthest - all of them reachable from the "
				+ "player and from each other (engine-verified, walked via a real connected search, not a guess), "
				+ "so movement.approach_spot can freely move between any two of these labels:\n");
			for (int i = 0; i < this.spots.size(); i++) {
				BlockPos spot = this.spots.get(i);
				double distance = Math.sqrt(spot.distSqr(playerPos));
				int light = level.getMaxLocalRawBrightness(spot);
				int dimSpots = i < this.dimSpotsPerSpot.size() ? this.dimSpotsPerSpot.get(i).size() : 0;
				int torches = i < this.torchesPerSpot.size() ? this.torchesPerSpot.get(i).size() : 0;
				sb.append(String.format(
					"- %s: %.0f blocks away (%s), light level %d, %d nearby dim spots (edge-of-light positions reachable from here), %d nearby torches%n",
					SPOT_LABELS[i], distance, ProximityBands.labelFor(distance), light, dimSpots, torches));
			}
			sb.append("This is how to reach a spot's dim spots when it isn't the one you spawned at: "
				+ "movement.approach_spot to get there, then movement.approach_dim_spot (which always searches from "
				+ "wherever the wendigo currently is) to actually reach one of its dim spots - e.g. spawn at a "
				+ "distant spot with no dim spots of its own, approach_spot to a closer one that has some, then "
				+ "approach_dim_spot from there for a real creeping-toward-the-light approach instead of just "
				+ "appearing already at the edge of the light.\n");
			sb.append("combat.break_torch resolves the nearest known light source live from wherever the wendigo "
				+ "currently is (no label needed) - the per-spot torch counts above tell you whether that's worth "
				+ "planning around before picking spawn_at, e.g. spawning at a distant spot that still has its own "
				+ "nearby torch to snuff out.\n");
		}
		if (!this.torchSpawnCandidates.isEmpty()) {
			sb.append(this.torchSpawnCandidates.size()).append(" reachable, already-lit torch-adjacent position(s) "
				+ "also found nearby - see spawn_at's own description for spawn_on_torch, the option to spawn "
				+ "already-exposed on one of these instead of waiting on real darkness.\n");
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
