package com.wendigo.plan;

/**
 * The 6 live distance-from-player bands used for reporting/classifying a position (see classify) -
 * movement.approach_spot/combat.teleport's own destination search no longer uses these at all (see
 * SemanticBands.actionSearchMinDistance - cave scale drives that now, not a fixed band ladder), but
 * the vocabulary itself stays alive for prompt-facing description (WaveContext's own current-distance
 * report) and predicate.player_distance's own band checks. Public (unlike the rest of SemanticBands,
 * which is package-private) specifically so com.wendigo.wave.WendigoManager can share the exact same
 * numbers - same precedent as ProximityBands' own public split. Not scientifically tuned - first
 * pass, adjust by feel like everything else in SemanticBands.
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
	 * Backs WaveContext's own prompt report of the wendigo's current live distance/band when it's
	 * already active. */
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
