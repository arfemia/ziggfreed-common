package com.ziggfreed.common.ui.icon;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;

import com.ziggfreed.common.icon.IconSpec;

/**
 * The ONE seam that paints an {@link IconSpec} into a row or a chip, so every icon-bearing surface
 * routes through one decision instead of re-deciding item-versus-texture per site.
 *
 * <p>A row carrying an icon ships two sibling widgets and this toggles the right one by
 * {@code .Visible}:
 * <ul>
 *   <li>an {@code ItemIcon #IcoItem} whose {@code .ItemId} is the item whose generated icon to draw
 *       (item ids give the widest coverage, exactly the id {@code /give} takes), and</li>
 *   <li>an {@code AssetImage #IcoTex} whose {@code .AssetPath} is a Common-rooted texture path.</li>
 * </ul>
 *
 * <p>Item id WINS over texture path; with nothing to draw BOTH widgets hide and the row renders its
 * text alone. Both values are pushed as plain Strings, the always-safe form. The child ids
 * ({@code #IcoItem} / {@code #IcoTex}) are fixed by convention across every icon-bearing row.
 */
public final class IconRenderer {

    /** Relative child ids every icon-bearing row must declare (an {@code ItemIcon} and an {@code AssetImage}). */
    public static final String ITEM_ICON_ID = "#IcoItem";

    public static final String TEXTURE_ICON_ID = "#IcoTex";

    private IconRenderer() {
    }

    /**
     * Paint {@code spec} into the row at {@code rowSelector}, whose two icon widgets are its
     * {@code #IcoItem} / {@code #IcoTex} children. A null or empty spec hides both.
     *
     * @return whether anything was drawn, for a caller that also collapses a surrounding slot
     */
    public static boolean applyIcon(@Nonnull UICommandBuilder cmd, @Nonnull String rowSelector,
            @Nullable IconSpec spec) {
        return applyIcon(cmd, rowSelector, spec == null ? null : spec.itemId(),
                spec == null ? null : spec.texturePath());
    }

    /**
     * Paint an icon from its two leaves, for a caller holding them apart rather than as a spec.
     * Item id wins; both blank hides both widgets.
     *
     * @return whether anything was drawn
     */
    public static boolean applyIcon(@Nonnull UICommandBuilder cmd, @Nonnull String rowSelector,
            @Nullable String itemId, @Nullable String texturePath) {
        String itemSel = rowSelector + " " + ITEM_ICON_ID;
        String texSel = rowSelector + " " + TEXTURE_ICON_ID;
        boolean hasItem = itemId != null && !itemId.isBlank();
        boolean hasTexture = !hasItem && texturePath != null && !texturePath.isBlank();

        cmd.set(itemSel + ".Visible", hasItem);
        if (hasItem) {
            cmd.set(itemSel + ".ItemId", itemId);
        }
        cmd.set(texSel + ".Visible", hasTexture);
        if (hasTexture) {
            cmd.set(texSel + ".AssetPath", texturePath);
        }
        return hasItem || hasTexture;
    }
}
