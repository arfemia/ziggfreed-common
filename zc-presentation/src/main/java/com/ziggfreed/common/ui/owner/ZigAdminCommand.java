package com.ziggfreed.common.ui.owner;

import java.awt.Color;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * {@code /zigadmin}: opens the owner menu ({@link OwnerMenu}), every owner screen on the server in one place.
 *
 * <p>No permission check is written here: the engine derives the command's node
 * ({@code ziggfreed.ziggfreedcommon.command.zigadmin}, {@code OwnerAudience.NODE}) and refuses the call before the body
 * runs, so an operator and whoever a server grants that node may run it. The menu then asks the owner audience as it
 * opens (a consumer's own admin rule, where one filled it), and refuses in so many words. It needs a player, since a
 * page needs a screen.
 */
public final class ZigAdminCommand extends AbstractPlayerCommand {

    /** The command. */
    public static final String NAME = "zigadmin";

    private static final Color BAD = new Color(0xFF5555);

    public ZigAdminCommand() {
        super(NAME, OwnerText.key("menu.command.desc"));
    }

    @Override
    protected void execute(@Nonnull CommandContext ctx, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null || !OwnerMenu.open(store, ref, player, null)) {
            ctx.sendMessage(OwnerText.line("menu.refused").color(BAD));
        }
    }
}
