package com.wendigo;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

/** A focused, editable subset of the wendigo's own gameplay-tuning constants - stored as
 * config/wendigo-tuning.json, same load/default-write shape as WendigoWaveConfig (this project's own
 * established config pattern, not a new mechanism). Deliberately NOT every tunable constant in the
 * codebase (there are dozens, scattered across PlanRunner/WendigoManager/SemanticBands/DarknessMalus) -
 * the user's own explicit "some common configurations" ask, not "expose everything." Picked the ones
 * this whole session's own back-and-forth tuning touched most: grab/carry timing, orbit behavior, and
 * chase repathing. Read directly via WendigoMod.tuningConfig (a shared static instance, the same
 * "loaded once at mod init" shape WendigoMod.progressionTracker/wendigoManager already use) rather than
 * threaded through PlanRunner's own constructor - PlanRunner has no existing config wiring at all, and
 * adding one just for this would be a much larger, riskier change than this focused set warrants. */
public class WendigoTuningConfig {
	// See PlanRunner.rideFairChanceTicks - how long (in seconds) a grabbed player must be carried
	// before the despawn damage is allowed to land, linearly interpolated between these two stage
	// endpoints (stage 2-4 fall in between). Also directly drives carryFleeReleaseTick (see
	// PlanRunner.startCarryFlee) - the one flat timer that actually ends the carry.
	public int rideFairChanceSecondsAtStage1 = 7;
	public int rideFairChanceSecondsAtStage5 = 2;
	// See PlanRunner.beginForcedRide - how many dismount (spammed-shift) attempts a grabbed player
	// needs before a forced ride lets go on its own, scaled by severity between these two bounds.
	public int rideEscapeAttemptsMin = 5;
	public int rideEscapeAttemptsMax = 20;
	// See PlanRunner.tickOrbit - the flat performance cap (in blocks) on how far an orbiting/mid-plan
	// wendigo is allowed to be from its target before force-relocating/ending instead of continuing to
	// chase the gap. Mirrors WendigoManager.ORBIT_DESPAWN_DISTANCE (same constant, same meaning - kept
	// as one shared config value rather than two separately-tunable ones that could drift apart).
	public double orbitDespawnDistance = 32.0;
	// See PlanRunner.tickOrbit - the chance (0.0-1.0) that a wander deadline just re-rolls and holds
	// position instead of always relocating to a fresh in-band spot.
	public double orbitWanderSkipChance = 0.4;
	// See SemanticBands.isAcceptableOrbitPath / WendigoManager.tryEnterOrbit,relocateOrDiscard /
	// PlanRunner.tickOrbit - how many fresh candidates a bounded orbit-position retry loop tries
	// (each one a real AWCAPI pathfind) before giving up and falling back to the older, unverified
	// nearest-darkness search. First pass - adjust by feel; bounded specifically because these
	// searches already run on an already-throttled ~1/sec cadence (ORBIT_RECHECK_INTERVAL_TICKS/
	// ORBIT_SPAWN_SEARCH_INTERVAL_TICKS) - unlike chase (repaths reactively, once per genuinely
	// completed/stuck path), this can issue up to this many pathfinds in one cycle, so keep it modest.
	public int orbitReachRetryAttempts = 4;
	// See SemanticBands.actionSearchMinDistance - the minimum search distance (blocks) movement.
	// approach_spot/combat.teleport's own destination resolvers start from, per cave scale. The user's
	// own explicit call: start all three equal (effectively "as close as darkness allows") rather than
	// assuming a real cave-scale split up front, but keep 3 distinct fields (not one shared constant)
	// specifically so they can be differentiated later without a code change once actually playtested.
	public double tightCaveActionMinDistance = 0.0;
	public double normalCaveActionMinDistance = 0.0;
	public double massiveCaveActionMinDistance = 0.0;
	// Hard ceiling on how far out movement.approach_spot/combat.teleport's own destination resolvers
	// will search - previously these reused orbitDespawnDistance (64.0) as their own max, letting a
	// destination land anywhere out to the same distance the wendigo would eventually give up and
	// despawn at, even in a totally disconnected pocket of the cave. The user's own explicit report:
	// an eyeline teleport could resolve "super far out in an unreachable cave" - a real, live-reported
	// problem, not a hypothetical - since a farther candidate is statistically less likely to be
	// genuinely connected to the player's own position than a closer one. Deliberately its own field,
	// not reused from orbitDespawnDistance - that one still governs the separate "how far before the
	// whole engagement gives up" concept, unaffected by this.
	public double actionMaxSearchDistance = 32.0;

	public static WendigoTuningConfig load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve("wendigo-tuning.json");
		Gson gson = new GsonBuilder().setPrettyPrinting().create();

		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path)) {
				WendigoTuningConfig loaded = gson.fromJson(reader, WendigoTuningConfig.class);
				return loaded != null ? loaded : new WendigoTuningConfig();
			} catch (IOException e) {
				throw new RuntimeException("Failed to read " + path, e);
			}
		}

		WendigoTuningConfig defaults = new WendigoTuningConfig();
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, gson.toJson(defaults));
		} catch (IOException e) {
			throw new RuntimeException("Failed to write default config to " + path, e);
		}
		return defaults;
	}
}
