package com.ziggfreed.common.ui.kit;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;

import com.ziggfreed.common.ui.UiText;
import com.ziggfreed.common.ui.icon.IconRenderer;

/** The painters' shared leaf pushes: text on {@code .TextSpans}, an optional line, a picture, a tooltip. */
final class KitPaint {

    private KitPaint() {
    }

    /** A message on a label's {@code .TextSpans}, the sink that substitutes params and renders markup. */
    static void text(@Nonnull UICommandBuilder cmd, @Nonnull String label, @Nonnull Message text) {
        cmd.set(label + ".TextSpans", text);
    }

    /** A label shown with {@code text}, or hidden when there is none. Both ways, so a repaint clears the last one. */
    static void optional(@Nonnull UICommandBuilder cmd, @Nonnull String label, @Nullable Message text) {
        if (text != null) {
            cmd.set(label + ".TextSpans", text);
        }
        cmd.set(label + ".Visible", text != null);
    }

    /**
     * A kit picture slot ({@code @ZigPicture}: a {@code Group} holding {@code AssetImage #IcoTex}) painted plain: the
     * item's own icon texture, else the texture, else the picture hides.
     *
     * @return whether anything was drawn
     */
    static boolean picture(@Nonnull UICommandBuilder cmd, @Nonnull String slot, @Nonnull Picture picture) {
        return IconRenderer.applyPlainIcon(cmd, slot, picture.itemId(), picture.texturePath());
    }

    /**
     * An element's tooltip: a translation goes for the client to resolve, anything else as plain characters
     * ({@link UiText}); none clears a tooltip an earlier paint left.
     */
    static void tooltip(@Nonnull UICommandBuilder cmd, @Nonnull String element, @Nullable Message tooltip) {
        if (tooltip == null) {
            UiText.setText(cmd, element + ".TooltipText", "");
        } else {
            UiText.setText(cmd, element + ".TooltipText", tooltip);
        }
    }

    /** {@code list[index]}. */
    @Nonnull
    static String child(@Nonnull String list, int index) {
        return list + "[" + index + "]";
    }
}
