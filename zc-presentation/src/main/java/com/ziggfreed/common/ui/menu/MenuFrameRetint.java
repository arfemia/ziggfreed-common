package com.ziggfreed.common.ui.menu;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.ziggfreed.common.ui.UiRetint;
import com.ziggfreed.common.ui.theme.Palette;

/**
 * The library's own paint of the menu frame from the palette in force, used when no consumer paints the
 * frame itself ({@link MenuDeps#theme()} is null). A palette naming a texture set swaps the frame body, the
 * named panels, both ornaments and the close button to that set's art (the frame's own five file names under
 * {@code textureDir}, the convention a consumer's theme art already follows); otherwise {@code frame} tints
 * the frame body and the ornaments and {@code primary} the panels. An unset slot leaves the authored look,
 * so the default palette paints nothing. Every push goes through {@link UiRetint}, which never sends a bare
 * String background.
 */
final class MenuFrameRetint {

    static final String TEX_FRAME = "ContainerFullPatch.png";
    static final String TEX_PANEL = "ContainerPanelPatch.png";
    static final String TEX_DECOR_TOP = "ContainerDecorationTop.png";
    static final String TEX_DECOR_BOTTOM = "ContainerDecorationBottom.png";
    static final String TEX_CLOSE = "ContainerCloseButton.png";
    static final String TEX_CLOSE_HOVER = "ContainerCloseButtonHovered.png";
    static final String TEX_CLOSE_PRESS = "ContainerCloseButtonPressed.png";

    private MenuFrameRetint() {
    }

    /** Does {@code palette} change the frame at all? */
    static boolean paints(@Nonnull Palette palette) {
        return palette.hasTextures() || UiRetint.isSixDigitHex(palette.frame) || UiRetint.isSixDigitHex(palette.primary);
    }

    /** Paint the frame contract ({@code #Content}, ornaments, close) and {@code panels} from {@code palette}. */
    static void apply(@Nonnull UICommandBuilder cmd, @Nonnull Palette palette, @Nonnull String... panels) {
        if (palette.hasTextures()) {
            UiRetint.swapPatch(cmd, "#Content", palette.textureDir + TEX_FRAME, palette.frameBorder, null);
            for (String panel : panels) {
                UiRetint.swapPatch(cmd, panel, palette.textureDir + TEX_PANEL, palette.panelBorder, null);
            }
            UiRetint.swapPatch(cmd, "#DecorTop", palette.textureDir + TEX_DECOR_TOP, 0, null);
            UiRetint.swapPatch(cmd, "#DecorBottom", palette.textureDir + TEX_DECOR_BOTTOM, 0, null);
            UiRetint.swapPatch(cmd, "#CloseButton.Style.Default", palette.textureDir + TEX_CLOSE, 0, null);
            UiRetint.swapPatch(cmd, "#CloseButton.Style.Hovered", palette.textureDir + TEX_CLOSE_HOVER, 0, null);
            UiRetint.swapPatch(cmd, "#CloseButton.Style.Pressed", palette.textureDir + TEX_CLOSE_PRESS, 0, null);
            return;
        }
        UiRetint.retintColor(cmd, "#Content", palette.frame);
        for (String panel : panels) {
            UiRetint.retintColor(cmd, panel, palette.primary);
        }
        UiRetint.retintColor(cmd, "#DecorTop", palette.frame);
        UiRetint.retintColor(cmd, "#DecorBottom", palette.frame);
    }
}
