package com.wendigo.plan;

/**
 * The 6 live-resolved distance-from-player bands that replaced the old pre-scanned spot_a..spot_f
 * system - every position a plan needs (spawn_at, movement.approach_band, combat.break_torch's
 * optional band, despawn/retreat's own farthest-band target) is resolved fresh against these ranges
 * at the moment it's actually needed, never cached. Public (unlike the rest of SemanticBands, which
 * is package-private) specifically so com.wendigo.wave.WendigoManager can share the exact same
 * numbers PlanRunner resolves movement.approach_band/despawn against - same precedent as
 * ProximityBands' own public split. Not scientifically tuned - first pass, adjust by feel like
 * everything else in SemanticBands. Severity/cave-scale gating for these lives in
 * SchemaBuilder.isBandAllowed, not here - this is purely the distance-range vocabulary.
 */
public final class PositionBands {
	private PositionBands() {
	}

	public static double distanceMin(String band) {
		return switch (band) {
			case "close_as_possible" -> 0.0;
			case "close" -> 5.0;
			case "medium" -> 10.0;
			case "far" -> 17.0;
			case "farther" -> 25.0;
			default -> 36.0; // "farthest"
		};
	}

	public static double distanceMax(String band) {
		return switch (band) {
			case "close_as_possible" -> 4.0;
			case "close" -> 9.0;
			case "medium" -> 16.0;
			case "far" -> 24.0;
			case "farther" -> 35.0;
			default -> 64.0; // "farthest" - open-ended in spirit, capped at the live-band search's own outer radius
		};
	}

	/** Classifies a live distance into whichever of the 5 ordinary bands (close/medium/far/farther/
	 * farthest - NOT close_as_possible, a special situational value with no distance range of its
	 * own worth classifying into separately) it falls into, using each band's own distanceMin as the
	 * threshold rather than distanceMax - the two aren't quite contiguous (close's own 9.0 max and
	 * medium's own 10.0 min leave a 1-block gap, same for every other adjacent pair), so anchoring on
	 * distanceMin instead is what actually tiles the whole number line with no gap or overlap.
	 * Backs combat.teleport_to_band's own source-band precondition (TierGates.teleportSourceBands)
	 * and its matching WaveContext prompt context - both need to classify the wendigo's own current
	 * live distance the exact same way. */
	public static String classify(double distance) {
		if (distance < distanceMin("medium")) {
			return "close";
		}
		if (distance < distanceMin("far")) {
			return "medium";
		}
		if (distance < distanceMin("farther")) {
			return "far";
		}
		if (distance < distanceMin("farthest")) {
			return "farther";
		}
		return "farthest";
	}
}
