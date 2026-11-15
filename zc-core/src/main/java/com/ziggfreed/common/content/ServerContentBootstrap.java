package com.ziggfreed.common.content;

import java.util.List;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.event.events.BootEvent;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.ziggfreed.common.content.command.ZigPackCommand;

/**
 * Wires the server content pack ({@link ServerContent}) at the library's setup, start and shutdown: the
 * pack found or made, its text registered for the start's catalogue load, the {@code /zigpack} family,
 * and the load-order refresh once every pack has loaded. A server running this library alone gets all
 * of it; a consumer registers nothing for it.
 */
public final class ServerContentBootstrap {

    private ServerContentBootstrap() {
    }

    /** At the library's {@code setup()}. */
    public static void install(@Nonnull PluginBase plugin) {
        ServerContentPack.ensure();
        ServerContentText.load();
        plugin.getCommandRegistry().registerCommand(new ZigPackCommand());
        plugin.getEventRegistry().register(BootEvent.class,
                event -> ServerContentPack.refreshLoadOrder(List.of(ServerContentText.GROUP + ":" + ServerContentText.NAME)));
    }

    /** At the library's {@code start()}: a pack made this session registers now, which tells the Asset Editor. */
    public static void start() {
        ServerContentPack.registerIfStarting();
    }

    /** At the library's {@code shutdown()}. */
    public static void shutdown() {
        ServerContentText.release();
    }
}
