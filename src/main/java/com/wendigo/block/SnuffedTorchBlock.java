package com.wendigo.block;

import org.jspecify.annotations.Nullable;

import net.fabricmc.fabric.api.networking.v1.context.PacketContext;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.BlockBehaviour.Properties;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import eu.pb4.polymer.core.api.block.PolymerBlock;

import com.wendigo.advancement.WendigoAdvancements;

/**
 * A standing torch the wendigo has snuffed out - light level 0, same standing-column hitbox a real
 * torch has (copied from BaseTorchBlock's own SHAPE, not inherited from it - extending BaseTorchBlock/
 * TorchBlock directly drags in TorchBlock's own flame-particle constructor param and its animateTick
 * flame/smoke particles, both wrong for a block that's specifically supposed to read as put out).
 * Client-visually this always maps to a plain unlit redstone torch regardless of which real family was
 * snuffed (see WendigoBlocks.getPolymerBlockState) - relightBlock/dropItem below are what remembers the
 * real family server-side, invisible to the player until they relight or break it.
 */
public class SnuffedTorchBlock extends Block implements PolymerBlock {
	private static final VoxelShape SHAPE = Block.column(4.0, 0.0, 10.0);

	// The real block this instance stands in for - what a flint-and-steel relight places back, and
	// (via WendigoBlocks' own real-to-snuffed lookup) how snuffByWendigo picks which of the 8
	// registered instances matches a given real torch in the first place.
	private final Block relightBlock;

	public SnuffedTorchBlock(Properties properties, Block relightBlock) {
		super(properties);
		this.relightBlock = relightBlock;
	}

	public Block relightBlock() {
		return this.relightBlock;
	}

	/** Always the plain unlit redstone torch look, regardless of which real family this instance
	 * stands in for - see this class's own doc comment. Light level 0 there matches this block's own
	 * real light level (both independently 0), so no PolymerBlock.forceLightUpdates override is
	 * needed. */
	@Override
	public BlockState getPolymerBlockState(BlockState state, @Nullable PacketContext context) {
		return Blocks.REDSTONE_TORCH.defaultBlockState().setValue(BlockStateProperties.LIT, false);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPE;
	}

	// Same as BaseTorchBlock's own canSurvive - a torch (snuffed or not) needs solid ground below it,
	// so breaking that support still correctly pops this off instead of leaving it floating.
	@Override
	protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
		return canSupportCenter(level, pos.below(), Direction.UP);
	}

	/** Right-clicking with flint and steel relights it back into its own original real block - the
	 * user's own explicit request, mirroring the exact idiom FlintAndSteelItem.useOn's own candle-
	 * lighting branch already uses (that branch doesn't reach custom blocks like this one - it only
	 * special-cases CampfireBlock/CandleBlock/CandleCakeBlock by exact type check - so this has to be
	 * handled here instead, intercepted before the item's own useOn dispatch ever runs). */
	@Override
	protected InteractionResult useItemOn(ItemStack itemStack, BlockState state, Level level, BlockPos pos,
			Player player, InteractionHand hand, BlockHitResult hitResult) {
		if (!itemStack.is(Items.FLINT_AND_STEEL)) {
			return super.useItemOn(itemStack, state, level, pos, player, hand, hitResult);
		}
		if (!level.isClientSide()) {
			level.setBlock(pos, this.relightBlock.defaultBlockState(), Block.UPDATE_ALL);
			level.playSound(player, pos, SoundEvents.FLINTANDSTEEL_USE, SoundSource.BLOCKS,
				1.0F, level.getRandom().nextFloat() * 0.4F + 0.8F);
			level.gameEvent(player, GameEvent.BLOCK_CHANGE, pos);
			itemStack.hurtAndBreak(1, player, hand.asEquipmentSlot());
			// The new "Learn to live with it" advancement, soul torch only - see
			// WendigoAdvancements.SOUL_LIGHT_RELIT's own doc comment.
			if (this.relightBlock == Blocks.SOUL_TORCH && player instanceof ServerPlayer serverPlayer) {
				WendigoAdvancements.grant(serverPlayer, WendigoAdvancements.SOUL_LIGHT_RELIT);
			}
		}
		return InteractionResult.SUCCESS;
	}
}
