package com.ziggfreed.common.objectives.title;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;

import com.hypixel.hytale.event.EventPriority;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.event.events.player.PlayerChatEvent;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.ziggfreed.common.ui.name.PlayerDisplayNames;
import com.ziggfreed.common.util.SafeLog;

/**
 * A title in chat, behind the owner switch {@code $Chat} in {@code titles.json} (R55; off by default,
 * never the nameplate): while it is on, a chat line names its sender through the display-name seam
 * ({@link PlayerDisplayNames#displayName}), so "Ziggfreed, Hallowed: hello", every other contribution
 * to the seam included.
 *
 * <p>It listens LATE on the engine's async chat event and sets its formatter only while the switch is on
 * AND the event still carries the engine's {@link PlayerChatEvent#DEFAULT_FORMATTER} (an identity
 * check): a formatter another mod installed is left alone, and such a mod names a player with a title
 * through {@link ZigTitles#display(java.util.UUID, String)} itself. The line is the engine's own
 * {@value #PLAYER_MESSAGE_KEY} with the name nested as a {@link Message}.
 *
 * <p>Nothing here may throw into the engine: a listener that throws completes the dispatch
 * exceptionally and a formatter that throws runs inside the dispatch's completion, and either drops
 * the line for everyone. So the listener is guarded whole, and the formatter falls back to the
 * engine's own line on any throw. The switch is a volatile read per message
 * ({@link TitleOwnerLayers#chat()}), never a file read.
 */
public final class TitleChat {

    /** The engine's chat line: {@code {username}} and {@code {message}}. */
    static final String PLAYER_MESSAGE_KEY = "server.chat.playerMessage";

    private static final String USERNAME_PARAM = "username";

    private static final String MESSAGE_PARAM = "message";

    private static final PlayerChatEvent.Formatter FORMATTER = TitleChat::format;

    private static final AtomicBoolean LISTENER_WARNED = new AtomicBoolean();

    private static final AtomicBoolean FORMATTER_WARNED = new AtomicBoolean();

    private TitleChat() {
    }

    /** Listen on the chat event, LATE, so a chat mod's own formatter is already in place. Setup only. */
    public static void register(@Nonnull PluginBase plugin) {
        plugin.getEventRegistry().registerAsyncGlobal(EventPriority.LATE, PlayerChatEvent.class,
                TitleChat::onDispatch);
    }

    @Nonnull
    private static CompletableFuture<PlayerChatEvent> onDispatch(@Nonnull CompletableFuture<PlayerChatEvent> future) {
        return future.thenApply(TitleChat::onChat);
    }

    /** Set the title formatter when the switch is on and the engine's own still stands. Never throws. */
    @Nonnull
    static PlayerChatEvent onChat(@Nonnull PlayerChatEvent event) {
        try {
            if (TitleOwnerLayers.chat() && event.getFormatter() == PlayerChatEvent.DEFAULT_FORMATTER) {
                event.setFormatter(FORMATTER);
            }
        } catch (Throwable t) {
            if (LISTENER_WARNED.compareAndSet(false, true)) {
                SafeLog.warn("[title] the chat listener failed, so a chat line keeps the game's own name: "
                        + t.getMessage());
            }
        }
        return event;
    }

    /** The engine's line with the sender named through the seam; the engine's own line on any throw. */
    @Nonnull
    static Message format(@Nonnull PlayerRef sender, @Nonnull String message) {
        try {
            return Message.translation(PLAYER_MESSAGE_KEY)
                    .param(USERNAME_PARAM, PlayerDisplayNames.displayName(sender.getUuid(), sender.getUsername()))
                    .param(MESSAGE_PARAM, message);
        } catch (Throwable t) {
            if (FORMATTER_WARNED.compareAndSet(false, true)) {
                SafeLog.warn("[title] a chat line could not carry a title, so it names the player plainly: "
                        + t.getMessage());
            }
            return PlayerChatEvent.DEFAULT_FORMATTER.format(sender, message);
        }
    }
}
