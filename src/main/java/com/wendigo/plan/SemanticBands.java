package com.wendigo.plan;

/**
 * Maps the schema's semantic enums (speed, distance, search_radius, duration, max_iterations)
 * to real engine values, plus a few fixed tuning constants. Nothing here is scientifically
 * tuned - first-pass numbers to make the executor behave reasonably, adjust by feel.
 */
final class SemanticBands {
	private SemanticBands() {
	}

	static double speedMultiplier(String speed) {
		return switch (speed) {
			case "slow" -> 0.6;
			case "fast" -> 1.5;
			default -> 1.0; // "normal"
		};
	}

	// Representative block distance for a semantic band - used both as a movement/orbit target
	// radius and as the threshold for predicate.player_distance.
	static double distanceBlocks(String distance) {
		return switch (distance) {
			case "close" -> 6.0;
			case "far" -> 24.0;
			default -> 14.0; // "medium"
		};
	}

	static double searchRadiusBlocks(String radius) {
		return "far".equals(radius) ? 20.0 : 10.0; // "near" default
	}

	/** [minTicks, maxTicks] - the engine rolls a random point in range so waits don't feel identical. */
	static int[] waitTicks(String duration) {
		return switch (duration) {
			case "short" -> new int[] {20, 40};
			case "long" -> new int[] {140, 220};
			default -> new int[] {60, 100}; // "medium"
		};
	}

	static int maxIterations(String band) {
		return switch (band) {
			case "few" -> 3;
			case "many" -> 20;
			default -> 8; // "some"
		};
	}

	// Raw light level at/below which a position counts as "dark" - matches vanilla's hostile
	// mob spawn threshold, not independently tuned.
	static final int DARKNESS_LIGHT_THRESHOLD = 7;

	// Angle within which a player's look vector counts as "facing" the wendigo.
	static final double LOOK_AT_ANGLE_DEGREES = 20.0;

	static final double ARRIVAL_DISTANCE = 2.5;
	// Safety net so a movement/timing action can never hang the plan forever (e.g. an
	// unreachable path target) - overridden per-action where a real duration is known.
	static final int ACTION_TIMEOUT_TICKS = 200;
	static final double NEAREST_PLAYER_RADIUS = 64.0;
}
