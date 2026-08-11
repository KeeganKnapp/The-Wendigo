package com.wendigo.advancement;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.ServerAdvancementManager;
import net.minecraft.server.level.ServerPlayer;

import com.wendigo.WendigoMod;

/**
 * The user's own explicit advancement chain, rooted at "Ye Who Mine Here" (a real vanilla
 * minecraft:location trigger on player Y &lt;= 0 - see lurks/root.json), whose actual descendants'
 * conditions can't be expressed as plain vanilla predicates (they depend on this mod's own internal
 * state - a successful grab, which spear tier repelled one, which stage a killed wendigo was at) so
 * each one's own criteria is the standard "minecraft:impossible" trigger (vanilla itself never fires
 * it) and is instead granted directly from code, right at the moment this mod's own logic already
 * detects the real event - see each PATH constant's own call site for where. Current tree shape:
 * root -&gt; grabbed ("It lurks...", hidden until earned) -&gt; spear_repel -&gt; stage5_kill, plus
 * root -&gt; soul_light as a separate branch - kept here only as documentation, not enforced by this
 * class, which walks whatever the real parent chain in the JSON says (see grant's own comment).
 */
public final class WendigoAdvancements {
	private WendigoAdvancements() {
	}

	// Every non-root advancement's criteria is this one same key - see the class doc comment for why
	// (a "minecraft:impossible" trigger, only ever satisfied by an explicit award() call from code).
	private static final String IMPOSSIBLE_CRITERION = "impossible";
	// The true root's own real vanilla trigger's own criterion name - see lurks/root.json.
	private static final String ROOT_CRITERION = "below_y_zero";

	public static final String ROOT = "lurks/root";
	public static final String GRABBED = "lurks/grabbed";
	public static final String SPEAR_REPEL = "lurks/spear_repel";
	public static final String SOUL_LIGHT = "lurks/soul_light";
	public static final String STAGE5_KILL = "lurks/stage5_kill";

	/** Grants the given advancement (see the PATH constants above) to this player, first walking UP
	 * its own real "parent" chain (as declared in the advancement JSON itself, via
	 * Advancement.parent()) and awarding every ancestor root-first - not just the top-level ROOT.
	 * Needed once the tree grew a middle tier (root -> grabbed -> spear_repel -> stage5_kill): a
	 * hardcoded "award ROOT then the target" would leave "grabbed" itself ungranted when awarding
	 * spear_repel directly, which would show spear_repel as earned while its own immediate parent
	 * still reads locked/unseen - award() itself is idempotent (a no-op once already granted), so
	 * walking the whole chain every time costs nothing once the ancestors are already unlocked. */
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
}
