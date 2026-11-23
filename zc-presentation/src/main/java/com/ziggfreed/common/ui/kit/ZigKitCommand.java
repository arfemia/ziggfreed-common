package com.ziggfreed.common.ui.kit;

import java.awt.Color;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.i18n.Msg;

/**
 * {@code /zigkit}: the page kit's owner verbs.
 *
 * <pre>
 * /zigkit gallery     open the kit gallery: every piece of the page kit in every state, on the band and on the panel
 * </pre>
 *
 * <p>No permission check is written here: the engine derives one node per verb from the plugin and the command name
 * ({@code ziggfreed.ziggfreedcommon.command.zigkit.gallery}) and refuses the call before a body runs, so the gallery
 * opens for an operator and for whoever a server grants that node, and for no other player. The verb needs a player,
 * since a page needs a screen. Its words are {@code ziggfreedcommon.ui.lang}'s {@code gallery.*}.
 */
public final class ZigKitCommand extends AbstractCommandCollection {

    /** The command family. */
    public static final String FAMILY = "zigkit";

    /** Open the kit gallery. */
    public static final String GALLERY = "gallery";

    private static final Color BAD = new Color(0xFF5555);

    public ZigKitCommand() {
        super(FAMILY, KitGallery.PREFIX + "family.desc");
        addSubCommand(new GalleryVerb());
    }

    /** {@code gallery}: the kit gallery on the owner running it, on their world thread. */
    private static final class GalleryVerb extends AbstractPlayerCommand {

        GalleryVerb() {
            super(GALLERY, KitGallery.PREFIX + "desc");
        }

        @Override
        protected void execute(@Nonnull CommandContext ctx, @Nonnull Store<EntityStore> store,
                @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef player, @Nonnull World world) {
            if (!KitGalleryPage.open(ref, store, player, KitGalleryPage.View.BAND)) {
                ctx.sendMessage(Msg.key(KitGallery.PREFIX + "failed").color(BAD));
            }
        }
    }
}
