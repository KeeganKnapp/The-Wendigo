package com.wendigo.block;

import java.util.Map;

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
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.BlockBehaviour.Properties;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import eu.pb4.polymer.core.api.block.PolymerBlock;

import com.wendigo.advancement.WendigoAdvancements;

/**
 * SnuffedTorchBlock's wall-mounted sibling - see that class's own doc comment for why this extends
 * Block directly rather than WallTorchBlock (same flame-particle-constructor/animateTick mismatch).
 * FACING/SHAPES/canSurvive below are copied from WallTorchBlock's own real implementation (its SHAPES
 * field is private, not reusable directly) rather than inherited.
 */
public class SnuffedWallTorchBlock extends Block implements PolymerBlock {
	public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;
	private static final Map<Direction, VoxelShape> SHAPES =
		Shapes.rotateHorizontal(Block.boxZ(5.0, 3.0, 13.0, 11.0, 16.0));

	private final Block relightBlock;

	public SnuffedWallTorchBlock(Properties properties, Block relightBlock) {
		super(properties);
		this.relightBlock = relightBlock;
		this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
	}

	public Block relightBlock() {
		return this.relightBlock;
	}

	/** Always the plain unlit redstone wall torch look (preserving FACING), regardless of which real
	 * family this instance stands in for - see SnuffedTorchBlock's own doc comment for the standing
	 * case this mirrors. */
	@Override
	public BlockState getPolymerBlockState(BlockState state, @Nullable PacketContext context) {
		return Blocks.REDSTONE_WALL_TORCH.defaultBlockState()
			.setValue(BlockStateProperties.LIT, false)
			.setValue(FACING, state.getValue(FACING));
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPES.get(state.getValue(FACING));
	}

	@Override
	protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
		Direction facing = state.getValue(FACING);
		BlockPos relativePos = pos.relative(facing.getOpposite());
		return level.getBlockState(relativePos).isFaceSturdy(level, relativePos, facing);
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	/** Same as SnuffedTorchBlock.useItemOn - see its own doc comment - just preserving FACING on the
	 * way back to the real wall-torch block instead of always facing one fixed direction. */
	@Override
	protected InteractionResult useItemOn(ItemStack itemStack, BlockState state, Level level, BlockPos pos,
			Player player, InteractionHand hand, BlockHitResult hitResult) {
		if (!itemStack.is(Items.FLINT_AND_STEEL)) {
			return super.useItemOn(itemStack, state, level, pos, player, hand, hitResult);
		}
		if (!level.isClientSide()) {
			BlockState relitState = this.relightBlock.defaultBlockState().setValue(FACING, state.getValue(FACING));
			level.setBlock(pos, relitState, Block.UPDATE_ALL);
			level.playSound(player, pos, SoundEvents.FLINTANDSTEEL_USE, SoundSource.BLOCKS,
				1.0F, level.getRandom().nextFloat() * 0.4F + 0.8F);
			level.gameEvent(player, GameEvent.BLOCK_CHANGE, pos);
			itemStack.hurtAndBreak(1, player, hand.asEquipmentSlot());
			// The new "Learn to live with it" advancement, soul torch only - see
			// WendigoAdvancements.SOUL_LIGHT_RELIT's own doc comment.
			if (this.relightBlock == Blocks.SOUL_WALL_TORCH && player instanceof ServerPlayer serverPlayer) {
				WendigoAdvancements.grant(serverPlayer, WendigoAdvancements.SOUL_LIGHT_RELIT);
			}
		}
		return InteractionResult.SUCCESS;
	}
}
