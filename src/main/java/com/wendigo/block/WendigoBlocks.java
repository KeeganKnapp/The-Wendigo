package com.wendigo.block;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour.Properties;
import net.minecraft.world.level.material.PushReaction;

import com.wendigo.WendigoMod;

/**
 * Registers the wendigo's own snuffed-torch blocks - see LightSourceScanner.snuffByWendigo, the only
 * thing that ever places these. Never obtainable/placeable by a player directly (no BlockItem
 * registered for any of them - they only ever appear where the wendigo put one, and only ever leave
 * via a flint-and-steel relight or being mined), so unlike ModEntities there's no separate attribute-
 * registration step needed here, just the block registrations themselves.
 * <p>
 * REAL_TO_SNUFFED backs snuffByWendigo's own "which of these 8 matches the real torch actually at this
 * position" lookup - built once here, at class-init time, rather than a switch/if-chain repeated at
 * every call site.
 */
public final class WendigoBlocks {
	private static final ResourceKey<Block> SNUFFED_TORCH_KEY = key("snuffed_torch");
	private static final ResourceKey<Block> SNUFFED_SOUL_TORCH_KEY = key("snuffed_soul_torch");
	private static final ResourceKey<Block> SNUFFED_COPPER_TORCH_KEY = key("snuffed_copper_torch");
	private static final ResourceKey<Block> SNUFFED_REDSTONE_TORCH_KEY = key("snuffed_redstone_torch");
	private static final ResourceKey<Block> SNUFFED_WALL_TORCH_KEY = key("snuffed_wall_torch");
	private static final ResourceKey<Block> SNUFFED_SOUL_WALL_TORCH_KEY = key("snuffed_soul_wall_torch");
	private static final ResourceKey<Block> SNUFFED_COPPER_WALL_TORCH_KEY = key("snuffed_copper_wall_torch");
	private static final ResourceKey<Block> SNUFFED_REDSTONE_WALL_TORCH_KEY = key("snuffed_redstone_wall_torch");

	// Matches vanilla torches' own Properties (see Blocks.TORCH's own registration) minus the
	// lit-based light function - these are permanently off until relit back into the real block, not
	// conditionally lit like redstone_torch itself. setId is required, not cosmetic - Properties.
	// effectiveDrops() (the default loot-table-path inference every registration goes through, even
	// ours) NPEs on a missing id otherwise; vanilla's own Blocks.register does the exact same
	// properties.setId(id) before construction, confirmed by decompiling it.
	private static Properties standingProperties(ResourceKey<Block> key) {
		return Properties.of().setId(key).noCollision().instabreak().lightLevel(state -> 0)
			.sound(SoundType.WOOD).pushReaction(PushReaction.DESTROY);
	}

	public static final SnuffedTorchBlock SNUFFED_TORCH = Registry.register(BuiltInRegistries.BLOCK,
		SNUFFED_TORCH_KEY, new SnuffedTorchBlock(standingProperties(SNUFFED_TORCH_KEY), Blocks.TORCH));
	public static final SnuffedTorchBlock SNUFFED_SOUL_TORCH = Registry.register(BuiltInRegistries.BLOCK,
		SNUFFED_SOUL_TORCH_KEY, new SnuffedTorchBlock(standingProperties(SNUFFED_SOUL_TORCH_KEY), Blocks.SOUL_TORCH));
	public static final SnuffedTorchBlock SNUFFED_COPPER_TORCH = Registry.register(BuiltInRegistries.BLOCK,
		SNUFFED_COPPER_TORCH_KEY, new SnuffedTorchBlock(standingProperties(SNUFFED_COPPER_TORCH_KEY), Blocks.COPPER_TORCH));
	public static final SnuffedTorchBlock SNUFFED_REDSTONE_TORCH = Registry.register(BuiltInRegistries.BLOCK,
		SNUFFED_REDSTONE_TORCH_KEY, new SnuffedTorchBlock(standingProperties(SNUFFED_REDSTONE_TORCH_KEY), Blocks.REDSTONE_TORCH));

	public static final SnuffedWallTorchBlock SNUFFED_WALL_TORCH = Registry.register(BuiltInRegistries.BLOCK,
		SNUFFED_WALL_TORCH_KEY, new SnuffedWallTorchBlock(standingProperties(SNUFFED_WALL_TORCH_KEY), Blocks.WALL_TORCH));
	public static final SnuffedWallTorchBlock SNUFFED_SOUL_WALL_TORCH = Registry.register(BuiltInRegistries.BLOCK,
		SNUFFED_SOUL_WALL_TORCH_KEY, new SnuffedWallTorchBlock(standingProperties(SNUFFED_SOUL_WALL_TORCH_KEY), Blocks.SOUL_WALL_TORCH));
	public static final SnuffedWallTorchBlock SNUFFED_COPPER_WALL_TORCH = Registry.register(BuiltInRegistries.BLOCK,
		SNUFFED_COPPER_WALL_TORCH_KEY, new SnuffedWallTorchBlock(standingProperties(SNUFFED_COPPER_WALL_TORCH_KEY), Blocks.COPPER_WALL_TORCH));
	public static final SnuffedWallTorchBlock SNUFFED_REDSTONE_WALL_TORCH = Registry.register(BuiltInRegistries.BLOCK,
		SNUFFED_REDSTONE_WALL_TORCH_KEY, new SnuffedWallTorchBlock(standingProperties(SNUFFED_REDSTONE_WALL_TORCH_KEY), Blocks.REDSTONE_WALL_TORCH));

	private static final Map<Block, Block> REAL_TO_SNUFFED = buildRealToSnuffed();

	private static Map<Block, Block> buildRealToSnuffed() {
		Map<Block, Block> map = new HashMap<>();
		map.put(Blocks.TORCH, SNUFFED_TORCH);
		map.put(Blocks.SOUL_TORCH, SNUFFED_SOUL_TORCH);
		map.put(Blocks.COPPER_TORCH, SNUFFED_COPPER_TORCH);
		map.put(Blocks.REDSTONE_TORCH, SNUFFED_REDSTONE_TORCH);
		map.put(Blocks.WALL_TORCH, SNUFFED_WALL_TORCH);
		map.put(Blocks.SOUL_WALL_TORCH, SNUFFED_SOUL_WALL_TORCH);
		map.put(Blocks.COPPER_WALL_TORCH, SNUFFED_COPPER_WALL_TORCH);
		map.put(Blocks.REDSTONE_WALL_TORCH, SNUFFED_REDSTONE_WALL_TORCH);
		return map;
	}

	/** The snuffed-block instance standing in for realBlock, or null if realBlock isn't one of the 8
	 * real torch families this covers - LightSourceScanner.snuffByWendigo's own lookup, kept here
	 * rather than duplicated since this class already owns both sides of the mapping. */
	public static Block snuffedFor(Block realBlock) {
		return REAL_TO_SNUFFED.get(realBlock);
	}

	private WendigoBlocks() {
	}

	/** No-op body - just forces this class's own static initializers (the Registry.register calls
	 * above) to run at a predictable point during mod init, same convention ModEntities/WendigoSounds
	 * already use for their own init() methods. */
	public static void init() {
	}

	private static ResourceKey<Block> key(String path) {
		return ResourceKey.create(Registries.BLOCK, WendigoMod.id(path));
	}
}
