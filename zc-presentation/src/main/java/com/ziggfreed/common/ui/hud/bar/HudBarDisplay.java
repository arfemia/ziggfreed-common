package com.ziggfreed.common.ui.hud.bar;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.i18n.NativeNames;
import com.ziggfreed.common.icon.IconSpec;

/**
 * How a row on the shared progress-bar panel wants to look, as said by whoever reports its
 * movement: a name, a picture, a fill colour, a place in the stack and how long it lingers after
 * its last move. Every part is optional, and a part left null reads from the next layer down: a
 * {@link HudBarAsset} authored for the row puts its own parts OVER these, and whatever neither says
 * is the panel's default ({@link HudBarLook}). Nothing has to be authored for a row to appear and
 * read right, because the mod reporting a move already knows what the value is called, what it is
 * drawn with elsewhere and which family it belongs to.
 *
 * <p>A consumer builds one beside every {@link HudBars#moved} call from its own vocabulary, so the
 * panel never learns what the value measures. {@link #forItem} is the display an item derives for
 * itself: its own name, resolved the way every item name in this library is, and its own generated
 * icon.
 */
public record HudBarDisplay(@Nullable Message label, @Nullable IconSpec icon, @Nullable String color,
        @Nullable Integer order, @Nullable Long lingerMs) {

    /** No preference on any part: the row reads its override, then the defaults. */
    public static final HudBarDisplay NONE = new HudBarDisplay(null, null, null, null, null);

    /** A name, a picture, a fill colour and a place in the stack; the linger stays the default. */
    @Nonnull
    public static HudBarDisplay of(@Nullable Message label, @Nullable IconSpec icon, @Nullable String color,
            int order) {
        return new HudBarDisplay(label, icon, color, order, null);
    }

    /**
     * The display an item supplies for itself: the item's own name ({@link NativeNames#itemNameMsg},
     * so a vanilla item, a pack item and an id nothing has authored all read properly) and the
     * item's own generated icon. No colour, since an item row draws no fill, and no order, so it
     * sorts after every row that named one.
     */
    @Nonnull
    public static HudBarDisplay forItem(@Nonnull String itemId) {
        return new HudBarDisplay(NativeNames.itemNameMsg(itemId), IconSpec.ofItem(itemId), null, null, null);
    }

    /** These parts over {@code under}'s: a part this display leaves null reads from {@code under}. */
    @Nonnull
    public HudBarDisplay over(@Nonnull HudBarDisplay under) {
        return new HudBarDisplay(
                label != null ? label : under.label,
                icon != null ? icon : under.icon,
                color != null ? color : under.color,
                order != null ? order : under.order,
                lingerMs != null ? lingerMs : under.lingerMs);
    }
}
