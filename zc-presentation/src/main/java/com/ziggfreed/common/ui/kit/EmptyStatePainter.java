package com.ziggfreed.common.ui.kit;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;

import com.ziggfreed.common.ui.ZigRichButton;

/**
 * Paints an {@link EmptyState} into an {@code @ZigEmptyState} instance: {@code #EPic}, {@code #ETitle},
 * {@code #ELine}, and the {@code #EAction} button when the state has an action.
 */
public final class EmptyStatePainter {

    private EmptyStatePainter() {
    }

    /**
     * @param host   the {@code @ZigEmptyState} instance's id
     * @param action the button's event data; pass it only in the update that builds the host (a full build, or a
     *               partial that appended the host), never on a repaint of a live one, which would bind it twice
     */
    public static void paint(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events, @Nonnull String host,
            @Nonnull EmptyState state, @Nullable EventData action) {
        KitPaint.picture(cmd, host + " #EPic", state.picture());
        KitPaint.text(cmd, host + " #ETitle", state.title());
        KitPaint.optional(cmd, host + " #ELine", state.line());
        String button = host + " #EAction";
        DetailAction act = state.action();
        cmd.set(button + ".Visible", act != null);
        if (act != null) {
            ZigRichButton.text(cmd, button, act.label());
            if (action != null) {
                events.addEventBinding(CustomUIEventBindingType.Activating, button, action);
            }
        }
    }
}
