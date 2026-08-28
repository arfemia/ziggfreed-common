package com.ziggfreed.common.loot.reward;

import java.util.List;

import javax.annotation.Nonnull;

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
 * at - which sends the reward to the payout layer's park instead.
 */
public final class FeetDropOverflow implements LootRewardKinds.Overflow {

    @Override
    public boolean handle(@Nonnull Subject subject, @Nonnull ItemStack stack) {
        Player player = subject.handleAs(Player.class);
        Ref<EntityStore> ref = player == null ? null : player.getReference();
        if (ref == null || !ref.isValid()) {
            return false;
        }
        return NativeLootService.spawnAtFeet(ref, List.of(stack));
    }
}
