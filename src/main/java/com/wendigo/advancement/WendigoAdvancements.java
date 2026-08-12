package com.wendigo.advancement;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.ServerAdvancementManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;

import com.wendigo.WendigoMod;

/**
 * The user's own explicit advancement chain, rooted at "Ye Who Mine Here" (a real vanilla
 * minecraft:location trigger on player Y &lt;= 0 - see lurks/root.json), whose actual descendants'
 * conditions mostly can't be expressed as plain vanilla predicates (they depend on this mod's own
 * internal state - a successful grab, a landed spectral hit, which stage a killed wendigo was at) so
 * each one's own criteria is the standard "minecraft:impossible" trigger (vanilla itself never fires
 * it) and is instead granted directly from code, right at the moment this mod's own logic already
 * detects the real event - see each PATH constant's own call site for where.
 * <p>
 * SPECTRAL_ARROW_CRAFTED is the one exception: "crafted a specific recipe" IS fully expressible as a
 * real vanilla predicate (minecraft:recipe_crafted, scoped to this mod's own two alt spectral-arrow
 * recipes - see lurks/spectral_arrow_crafted.json), so unlike every sibling here it's never granted
 * from code at all, and grant()'s own generic chain-walk below will silently no-op on it when walking
 * THROUGH it toward a deeper descendant (it has no "impossible" criterion for that walk to award) -
 * accepted deliberately: it still always completes on its own via its real trigger whenever a player
 * actually crafts either recipe, independent of this class entirely.
 * <p>
 * Current tree shape: root -&gt; grabbed ("The Wendigo", hidden until earned) -&gt;
 * spectral_arrow_crafted -&gt; spectral_hit -&gt; stage5_kill, plus root -&gt; soul_light -&gt;
 * soul_light_relit ("Learn to live with it" - granted the instant a player relights a soul torch or
 * soul campfire the wendigo snuffed - see SOUL_LIGHT_RELIT's own doc comment for the two separate
 * grant sites), plus a SECOND branch off grabbed itself: grabbed -&gt; stage1_complete -&gt;
 * stage2_complete -&gt; stage3_complete -&gt; stage4_complete - the user's own explicit "advancements for
 * each stage completed... consecutive with stage 1 completion being a child of 'The Wendigo'"
 * request, granted from WendigoManager's own tickLevel right where resolveRunOutcome resolves
 * favorably for that stage (see STAGE_COMPLETE's own doc comment). None of these show at all until
 * grabbed itself is earned - ordinary vanilla parent-chain display behavior, nothing this class needs
 * to enforce itself. Stage 5 has no completion advancement of its own in this branch (its only "goal"
 * is stage5_kill, already covered by the spectral-arrow branch) - kept here only as documentation, not
 * enforced by this class, which walks whatever the real parent chain in the JSON says (see grant's
 * own comment).
 */
public final class WendigoAdvancements {
	private WendigoAdvancements() {
	}

	// Every non-root, non-real-trigger advancement's criteria is this one same key - see the class
	// doc comment for why (a "minecraft:impossible" trigger, only ever satisfied by an explicit
	// award() call from code).
	private static final String IMPOSSIBLE_CRITERION = "impossible";
	// The true root's own real vanilla trigger's own criterion name - see lurks/root.json.
	private static final String ROOT_CRITERION = "below_y_zero";

	public static final String ROOT = "lurks/root";
	public static final String GRABBED = "lurks/grabbed";
	public static final String SPECTRAL_ARROW_CRAFTED = "lurks/spectral_arrow_crafted";
	public static final String SPECTRAL_HIT = "lurks/spectral_hit";
	public static final String SOUL_LIGHT = "lurks/soul_light";
	public static final String STAGE5_KILL = "lurks/stage5_kill";
	// Granted from two separate places, both a player relighting something the wendigo snuffed: the
	// torch half is granted directly from SnuffedTorchBlock/SnuffedWallTorchBlock's own useItemOn
	// (soul-family relightBlock only - regular-family relights don't count, nothing was "learned"),
	// the campfire half (plain vanilla CampfireBlock, no custom subclass to hook into) is granted from
	// this class's own init() below, via a UseBlockCallback that only observes the interaction.
	public static final String SOUL_LIGHT_RELIT = "lurks/soul_light_relit";
	// Index 0 unused (stages are 1-4 here - there is no stage 5 entry, see the class doc comment),
	// same 0-padded-array-as-direct-index convention WendigoProgressionTracker.STAGE_PERCENTS
	// already establishes, so a caller can index this directly with a stage number.
	private static final String[] STAGE_COMPLETE = {
		null,
		"lurks/stage1_complete",
		"lurks/stage2_complete",
		"lurks/stage3_complete",
		"lurks/stage4_complete",
	};

	/** The stage-completion advancement path for stage (1-4) - see the class doc comment for the
	 * chain shape. Granted from WendigoManager's own tickLevel the instant resolveRunOutcome
	 * resolves FAVORABLY for that stage (a real advance, not a soul-light regression) - stage 5 has
	 * no entry here (isGoalMet's own stage-5 goal is unreachable by design, only a genuine kill ends
	 * it, already covered by STAGE5_KILL). */
	public static String stageComplete(int stage) {
		return STAGE_COMPLETE[stage];
	}

	/** Grants the given advancement (see the PATH constants above) to this player, first walking UP
	 * its own real "parent" chain (as declared in the advancement JSON itself, via
	 * Advancement.parent()) and awarding every ancestor root-first - not just the top-level ROOT.
	 * Needed once the tree grew a middle tier (root -> grabbed -> ... -> stage5_kill): a hardcoded
	 * "award ROOT then the target" would leave "grabbed" itself ungranted when awarding a deeper
	 * advancement directly, which would show it as earned while its own immediate parent still reads
	 * locked/unseen - award() itself is idempotent (a no-op once already granted, and also a no-op if
	 * the given criterion name doesn't exist on that advancement at all - see SPECTRAL_ARROW_CRAFTED's
	 * own doc comment above), so walking the whole chain every time costs nothing once the ancestors
	 * are already unlocked. */
	public static void grant(ServerPlayer player, String path) {
		ServerAdvancementManager manager = player.level().getServer().getAdvancements();
		List<AdvancementHolder> chain = new ArrayList<>();
		AdvancementHolder current = manager.get(WendigoMod.id(path));
		while (current != null) {
			chain.add(current);
			Optional<Identifier> parentId = current.value().parent();
			current = parentId.isPresent() ? manager.get(parentId.get()) : null;
		}
		PlayerAdvancements advancements = player.getAdvancements();
		for (int i = chain.size() - 1; i >= 0; i--) {
			AdvancementHolder holder = chain.get(i);
			// Only the true root (no parent of its own) uses its real vanilla criterion name - every
			// other ancestor in the chain is one of this class's own "impossible" advancements.
			String criterion = holder.value().parent().isEmpty() ? ROOT_CRITERION : IMPOSSIBLE_CRITERION;
			advancements.award(holder, criterion);
		}
	}

	/** Registers the one hook this class needs an event for: detecting a player about to relight a
	 * soul campfire with flint and steel. Torch relights are granted directly from
	 * SnuffedTorchBlock/SnuffedWallTorchBlock's own useItemOn (a custom block this mod already owns),
	 * but soul campfires are plain vanilla CampfireBlock instances with no custom subclass to hook
	 * into - this only OBSERVES the interaction (never blocks or consumes it) and always returns PASS
	 * so vanilla's own CampfireBlock.useItemOn relight logic runs completely untouched. Checked before
	 * vanilla actually relights it (LIT still false here), but that's fine - a player right-clicking an
	 * unlit soul campfire with flint and steel is going to relight it regardless, same as the torch
	 * blocks' own useItemOn granting before returning SUCCESS. */
	public static void init() {
		UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
			if (!(level instanceof ServerLevel) || !(player instanceof ServerPlayer serverPlayer)) {
				return InteractionResult.PASS;
			}
			if (!player.getItemInHand(hand).is(Items.FLINT_AND_STEEL)) {
				return InteractionResult.PASS;
			}
			BlockPos pos = hitResult.getBlockPos();
			BlockState state = level.getBlockState(pos);
			if (state.is(Blocks.SOUL_CAMPFIRE) && !state.getValue(CampfireBlock.LIT)) {
				grant(serverPlayer, SOUL_LIGHT_RELIT);
			}
			return InteractionResult.PASS;
		});
	}
}
