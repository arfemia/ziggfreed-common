package com.ziggfreed.common.ui.owner;

import java.util.List;
import java.util.Objects;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.owner.OwnerAudience;
import com.ziggfreed.common.util.SafeLog;

/**
 * One help topic a tile of the owner menu carries ({@link OwnerTile#help}, or {@link OwnerMenu#help} for a tile another
 * mod added): the words of a mod's own lang file under one key prefix, read as the topic's {@code title},
 * {@code subtitle} and its four parts ({@link #PARTS}: {@code overview}, {@code how}, {@code examples}, {@code tips}),
 * each a full registered key ({@code yourmod.help.boosts.overview}). The menu's Help shows the tiles that carry a topic,
 * and pressing one shows its topics ({@link OwnerHelpPage}), with a button that opens the tile's screen; a consumer's
 * own screen opens a tile's help with {@link #open}. The words are the topic's mod's, never the library's.
 */
public record OwnerHelp(@Nonnull String prefix) {

    /** The four parts every topic shows, in order, each under its heading. */
    public static final List<String> PARTS = List.of("overview", "how", "examples", "tips");

    public OwnerHelp {
        Objects.requireNonNull(prefix, "prefix");
        String trimmed = prefix.trim();
        while (trimmed.endsWith(".")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("a help topic needs a key prefix");
        }
        prefix = trimmed;
    }

    /** The topic whose words sit under {@code prefix} ({@code yourmod.help.boosts}). */
    @Nonnull
    public static OwnerHelp topic(@Nonnull String prefix) {
        return new OwnerHelp(prefix);
    }

    /** The topic's title. */
    @Nonnull
    public Message title() {
        return ContentKeys.tr(prefix + ".title");
    }

    /** The line under its title. */
    @Nonnull
    public Message subtitle() {
        return ContentKeys.tr(prefix + ".subtitle");
    }

    /** One of its {@link #PARTS}. */
    @Nonnull
    public Message part(@Nonnull String part) {
        return ContentKeys.tr(prefix + "." + part);
    }

    /**
     * Open the help of the tile {@code tileId} for {@code player}, its Back returning to the owner menu's Help with
     * {@code menuFirst}'s section at the front (a consumer's own screen's help button). False when the entity is no
     * player, the owner audience refuses, no tile of that id shows them or it carries no topic, or the open threw; the
     * caller still owes the client an answer then.
     */
    public static boolean open(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull Player player, @Nonnull String tileId, @Nullable String menuFirst) {
        PlayerRef playerRef = PlayerAccess.playerRef(player);
        if (playerRef == null || !OwnerAudience.mayAdminister(store, ref, player)) {
            return false;
        }
        OwnerTile tile = OwnerMenu.find(tileId, menuFirst);
        if (tile == null || !tile.shownTo(new OwnerViewer(store, ref, player, playerRef))
                || OwnerMenu.topics(tile).isEmpty()) {
            return false;
        }
        try {
            player.getPageManager().openCustomPage(ref, store, new OwnerHelpPage(playerRef, tile.id(), menuFirst));
            return true;
        } catch (Throwable t) {
            SafeLog.warn("[owner] the help of '" + tileId + "' failed to open", t);
            return false;
        }
    }
}
