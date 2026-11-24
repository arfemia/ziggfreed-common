package com.ziggfreed.common.pvp;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.util.SafeLog;

/**
 * The lines everyone in a world reads about a bounty, from {@code ziggfreedcommon.pvp.lang}, sent only while the
 * world's rules write {@code Bounty.Announce} (the default): a bounty going up, growing, ended by a hunter, lost to
 * another death, or lost to leaving mid-fight. A player's name is data (a raw param); the streak a typed number.
 */
final class PvpText {

    private static final String PREFIX = "ziggfreedcommon.pvp.";

    /** The colour of a bounty line. */
    private static final String COLOR = "#e0a040";

    private PvpText() {
    }

    static void bountyRaised(@Nullable World world, @Nonnull PvpRules.Bounty rules, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> player, int streak) {
        tell(world, rules, Msg.key(PREFIX + "bounty.raised", name(store, player), (long) streak));
    }

    static void bountyGrew(@Nullable World world, @Nonnull PvpRules.Bounty rules, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> player, int streak) {
        tell(world, rules, Msg.key(PREFIX + "bounty.grew", name(store, player), (long) streak));
    }

    static void bountyEnded(@Nullable World world, @Nonnull PvpRules.Bounty rules, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> hunter, @Nonnull Ref<EntityStore> victim, int streak) {
        tell(world, rules, Msg.key(PREFIX + "bounty.ended", name(store, hunter), name(store, victim), (long) streak));
    }

    static void bountyLost(@Nullable World world, @Nonnull PvpRules.Bounty rules, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> victim, int streak) {
        tell(world, rules, Msg.key(PREFIX + "bounty.lost", name(store, victim), (long) streak));
    }

    static void bountyFled(@Nullable World world, @Nonnull PvpRules.Bounty rules, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> player, int streak) {
        tell(world, rules, Msg.key(PREFIX + "bounty.fled", name(store, player), (long) streak));
    }

    /** The player's name as data, or an empty one when it cannot be read. */
    @Nonnull
    private static String name(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        try {
            PlayerRef player = ref.isValid() ? PlayerAccess.playerRef(store, ref) : null;
            String name = player == null ? null : player.getUsername();
            return name == null ? "" : name;
        } catch (Throwable t) {
            return "";
        }
    }

    /** Send {@code line} to everyone in {@code world}, while the rules announce. */
    private static void tell(@Nullable World world, @Nonnull PvpRules.Bounty rules, @Nonnull Message line) {
        if (world == null || !rules.announce()) {
            return;
        }
        try {
            Message coloured = line.color(COLOR);
            for (PlayerRef player : world.getPlayerRefs()) {
                if (player != null) {
                    player.sendMessage(coloured);
                }
            }
        } catch (Throwable t) {
            SafeLog.fine("[pvp] a bounty line could not be sent: " + t.getMessage());
        }
    }
}
