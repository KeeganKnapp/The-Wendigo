package com.wendigo.plan;

/**
 * Shared proximity-to-player vocabulary - used both by predicate.player_distance (see
 * PlanPredicates) and to label scanned spot distances in the wave-context prompt (see
 * com.wendigo.wave.WaveContext). Public (unlike the rest of SemanticBands, which is
 * package-private) specifically so both sides use the exact same bands: the model can then read
 * "spot_a: 6 blocks away (lunge_distance)" and directly know that's already inside the range a
 * predicate.player_distance check would call lunge_distance, without reconciling a raw block
 * count against band names it only knows the relative ordering of, not the numbers - e.g. it
 * should be less likely to spawn/hide at a spot that's already within flee-triggering range of
 * the player. Not scientifically tuned - first-pass numbers, adjust by feel like everything else
 * in SemanticBands.
 */
public final class ProximityBands {
	private ProximityBands() {
	}

	/** Upper bound of each band, in blocks - used as the threshold for predicate.player_distance. */
	public static double blocks(String band) {
		return switch (band) {
			case "grab_distance" -> 4.0;
			case "lunge_distance" -> 9.0;
			case "close_quarters" -> 14.0;
			case "far" -> 35.0;
			default -> 24.0; // "medium"
		};
	}

	/** Which band a raw distance falls into - used to label spot distances in the wave prompt. */
	public static String labelFor(double distanceBlocks) {
		if (distanceBlocks <= 4.0) {
			return "grab_distance";
		}
		if (distanceBlocks <= 9.0) {
			return "lunge_distance";
		}
		if (distanceBlocks <= 14.0) {
			return "close_quarters";
		}
		if (distanceBlocks <= 24.0) {
			return "medium";
		}
		return "far"; // 35+ is still meaningfully "far" - the band stays open-ended past its own threshold
	}
}
