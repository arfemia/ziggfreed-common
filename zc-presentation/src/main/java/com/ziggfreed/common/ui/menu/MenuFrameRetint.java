package com.ziggfreed.common.ui.menu;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.ziggfreed.common.ui.UiRetint;
import com.ziggfreed.common.ui.ZigFrame;
import com.ziggfreed.common.ui.theme.Palette;

/**
 * The library's own paint of the menu frame from the palette in force, used when no consumer paints the
 * frame itself ({@link MenuDeps#theme()} is null). The frame is the band ({@link ZigFrame}): a palette naming its
 * own band art ({@code bandTexture}) swaps every band piece to it, else {@code frame} tints the band. A palette
 * naming a texture set swaps the named panels and the close button to that set's art (the frame's own file names
 * under {@code textureDir}, the convention a consumer's theme art already follows), else {@code primary} tints the
 * panels; the band's art never comes from {@code textureDir}, so an older set with no band file draws no missing
 * texture. An unset slot leaves the authored look, so the default palette paints nothing. Every push goes through
 * {@link UiRetint}, which never sends a bare String background, onto an id the frame declares with a Background.
 */
final class MenuFrameRetint {

    static final String TEX_PANEL = "ContainerPanelPatch.png";
    static final String TEX_CLOSE = "ContainerCloseButton.png";
    static final String TEX_CLOSE_HOVER = "ContainerCloseButtonHovered.png";
    static final String TEX_CLOSE_PRESS = "ContainerCloseButtonPressed.png";

    private MenuFrameRetint() {
    }

    /** Does {@code palette} change the frame at all? */
    static boolean paints(@Nonnull Palette palette) {
        return palette.hasTextures() || palette.hasBandTexture() || UiRetint.isSixDigitHex(palette.frame)
                || UiRetint.isSixDigitHex(palette.primary);
    }

    /** Paint the frame contract (the band, the close button) and {@code panels} from {@code palette}. */
    static void apply(@Nonnull UICommandBuilder cmd, @Nonnull Palette palette, @Nonnull String... panels) {
        if (palette.hasBandTexture()) {
            ZigFrame.swapBand(cmd, palette.bandTexture, null);
        } else {
            ZigFrame.tintBand(cmd, palette.frame);
        }
        if (palette.hasTextures()) {
            for (String panel : panels) {
                UiRetint.swapPatch(cmd, panel, palette.textureDir + TEX_PANEL, palette.panelBorder, null);
            }
            UiRetint.swapPatch(cmd, ZigFrame.CLOSE + ".Style.Default", palette.textureDir + TEX_CLOSE, 0, null);
            UiRetint.swapPatch(cmd, ZigFrame.CLOSE + ".Style.Hovered", palette.textureDir + TEX_CLOSE_HOVER, 0, null);
            UiRetint.swapPatch(cmd, ZigFrame.CLOSE + ".Style.Pressed", palette.textureDir + TEX_CLOSE_PRESS, 0, null);
            return;
        }
        for (String panel : panels) {
            UiRetint.retintColor(cmd, panel, palette.primary);
        }
    }
}
