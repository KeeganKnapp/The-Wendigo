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
}
