package com.wendigo.wave;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.mojang.serialization.Codec;

import net.minecraft.core.UUIDUtil;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import com.wendigo.WendigoMod;

/**
 * The one piece of progression state that survives a server restart - how many times each player
 * has ever fully completed a run (see WendigoProgressionTracker.completedRuns), the user's own
 * explicit request. Everything else the tracker owns (in-progress run state, the eligibility timer,
 * stage-5's persisted health) deliberately stays in-memory only, same as before a restart already
 * discards it: a run/hunt in progress has no live entity left to resume once the server comes back
 * up anyway, so there's nothing correct to persist there. Attached to the overworld only - see
 * WendigoProgressionTracker's own SERVER_STARTED hookup.
 */
public final class WendigoProgressionData extends SavedData {
	// STRING_CODEC, not CODEC - an unbounded map's keys have to actually serialize as strings (NBT
	// compound tag keys always are), where UUIDUtil.CODEC's own int-array encoding doesn't qualify.
	// The xmap's own decode side additionally copies into a fresh HashMap rather than trusting
	// whatever map instance unboundedMap's decode handed back (DFU's own map-codec decode commonly
	// returns an immutable map, confirmed live: completedRuns.put() on it threw
	// UnsupportedOperationException the moment a real mutation - completeRun/setRunsForTesting -
	// actually ran against it).
	public static final Codec<WendigoProgressionData> CODEC = Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.INT)
		.xmap(map -> new WendigoProgressionData(new HashMap<>(map)), data -> data.completedRuns);

	public static final SavedDataType<WendigoProgressionData> TYPE = new SavedDataType<>(
		WendigoMod.id("progression"), WendigoProgressionData::new, CODEC, DataFixTypes.LEVEL);

	final Map<UUID, Integer> completedRuns;

	public WendigoProgressionData() {
		this(new HashMap<>());
	}

	private WendigoProgressionData(Map<UUID, Integer> completedRuns) {
		this.completedRuns = completedRuns;
	}

	/** Exposed read-only for the persistence GameTests (com.wendigo.gametest, a different package) -
	 * WendigoProgressionTracker itself is same-package and never needs this, it reads/writes
	 * completedRuns directly. */
	public Map<UUID, Integer> completedRuns() {
		return this.completedRuns;
	}
}
