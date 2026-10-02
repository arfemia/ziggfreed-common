package com.ziggfreed.common.loot.reward;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.instance.reward.NativeLootService;
import com.ziggfreed.common.subject.Subject;

/**
 * The default {@link LootRewardKinds.Overflow} policy: an item reward that does not fit the bag lands
 * on the GROUND at the receiving player's feet, so a full inventory means a pickup rather than a lost
 * or parked reward. It drops through {@link NativeLootService#spawnAtFeet} - the ONE guarded,
 * tick-safe ground-drop seam every ground spawn uses - so an overflow drop behaves exactly like a
 * mob's death drops and is safe even when the grant fired from inside a system tick (a quest reward
 * paid off a block-break moment).
 *
 * <p>The wiring root installs one of these at boot, which makes it a DEFAULT, not a hard-wire: a
 * consumer that wants its own policy calls {@link LootRewardKinds#overflow} with its own sink (a
 * consumer's setup runs after the library's), and passing null instead restores fail-and-park - a
 * grant that cannot land fails loudly and the payout layer parks a replayable reward for the
 * player's next connect.
 *
 * <p>True means the stack landed, or was handed to the owning world's thread to land right after the
 * current tick. A subject with no live player behind it answers false - there are no feet to drop
 * at - which sends the reward to the payout layer's park instead (a rolled table's pile has no
 * replayable form, so its loss is warned rather than parked).
 */
public final class FeetDropOverflow implements LootRewardKinds.Overflow {

    @Override
    public boolean handle(@Nonnull Subject subject, @Nonnull ItemStack stack) {
        Ref<EntityStore> ref = feetOf(subject);
        return ref != null && NativeLootService.spawnAtFeet(ref, List.of(stack));
    }

    /**
     * The whole pile in ONE spawn, so the engine spreads its stacks around the player's feet
     * together instead of one landing per stack. The spawn answers for the pile as a whole, which is
     * the all-or-nothing answer the contract asks for. The stacks are copied first, because a spawn the
     * world defers to after the current tick still holds the list.
     */
    @Override
    public boolean handleAll(@Nonnull Subject subject, @Nonnull List<ItemStack> stacks) {
        if (stacks.isEmpty()) {
            return true;
        }
        Ref<EntityStore> ref = feetOf(subject);
        return ref != null && NativeLootService.spawnAtFeet(ref, new ArrayList<>(stacks));
    }

    /** The live player's ref to drop at, or null when there are no feet to drop at. */
    @Nullable
    private static Ref<EntityStore> feetOf(@Nonnull Subject subject) {
        Player player = subject.handleAs(Player.class);
        Ref<EntityStore> ref = player == null ? null : player.getReference();
        return ref == null || !ref.isValid() ? null : ref;
    }
}
