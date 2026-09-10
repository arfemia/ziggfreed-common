package com.ziggfreed.common.ui.hud.panel;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.i18n.NativeNames;
import com.ziggfreed.common.icon.IconSpec;

/**
 * How a row on the shared progress-bar panel wants to look, as said by whoever reports its
 * movement: a name, a picture, a fill colour, a place in the stack and how long it lingers after
 * its last move. Every part is optional, and a part left null reads from the next layer down: a
 * {@link HudRowAsset} authored for the row puts its own parts OVER these, and whatever neither says
 * is the panel's default ({@link HudRowLook}). Nothing has to be authored for a row to appear and
 * read right, because the mod reporting a move already knows what the value is called, what it is
 * drawn with elsewhere and which family it belongs to.
 *
 * <p>A consumer builds one beside every {@link HudPanels#moved} call from its own vocabulary, so the
 * panel never learns what the value measures. {@link #forItem} is the display an item derives for
 * itself: its own name, resolved the way every item name in this library is, and its own generated
 * icon.
 *
 * <p><b>{@link #countKey}</b> is how a row words its own number: a localization key taking that
 * number as its one parameter, for a row whose figure is not a gain and reads wrong as "+N". A row
 * that names none uses the panel's own plain wording. The key is the reporting mod's, and the panel
 * neither reads it nor learns what is being counted.
 *
 * <p><b>The two captions</b> ({@link #leadCaption} / {@link #trailCaption}) are the short words a
 * fill row shows at its bar's left and right ends: where the reading is counting FROM and where it
 * is counting TO, in whatever terms the reporting mod measures in. The library never composes them
 * and never reads them - it draws whatever came with the move, and a row that supplies neither
 * simply draws a bare bar. They are deliberately NOT authorable on {@link HudRowAsset}: a caption
 * is per-move data that changes as the value moves, so a file has nothing useful to say about it,
 * while a label or a colour is a settled fact about the row that a file rightly overrides.
 */
public record HudRowDisplay(@Nullable Message label, @Nullable IconSpec icon, @Nullable String color,
        @Nullable Integer order, @Nullable Long lingerMs, @Nullable Message leadCaption,
        @Nullable Message trailCaption, @Nullable String countKey) {

    /** No preference on any part: the row reads its override, then the defaults. */
    public static final HudRowDisplay NONE =
            new HudRowDisplay(null, null, null, null, null, null, null, null);

    /** A name, a picture, a fill colour and a place in the stack; the linger stays the default. */
    @Nonnull
    public static HudRowDisplay of(@Nullable Message label, @Nullable IconSpec icon, @Nullable String color,
            int order) {
        return new HudRowDisplay(label, icon, color, order, null, null, null, null);
    }

    /**
     * These parts, with the two bar-end captions added: what the reading counts from at the bar's
     * left, and what it counts toward at its right. Either may be null for an end that shows nothing.
     */
    @Nonnull
    public HudRowDisplay between(@Nullable Message lead, @Nullable Message trail) {
        return new HudRowDisplay(label, icon, color, order, lingerMs, lead, trail, countKey);
    }

    /**
     * These parts, with the row's number worded by {@code key} instead of the panel's own plain
     * "+N". The key takes the number as its one parameter, so each client still writes the digits
     * itself; what the key SAYS around them is the reporting mod's business, and the panel neither
     * reads it nor knows what is being counted. The panel's own "+N" is written compact from ten
     * thousand up ("+12.3k"), its gain column having no room for the grouped form; a row wording
     * its own number is bound the whole figure at every magnitude, so a key that may be asked to
     * word a large one leaves the room for it.
     */
    @Nonnull
    public HudRowDisplay counting(@Nullable String key) {
        return new HudRowDisplay(label, icon, color, order, lingerMs, leadCaption, trailCaption, key);
    }

    /** These parts, held on their panel until something sends them away rather than fading on a clock. */
    @Nonnull
    public HudRowDisplay held() {
        return new HudRowDisplay(label, icon, color, order, HudRowLook.LINGER_HELD, leadCaption,
                trailCaption, countKey);
    }

    /** These parts with {@code color} instead: the one part a consumer re-states per move. */
    @Nonnull
    public HudRowDisplay colored(@Nullable String replacement) {
        return new HudRowDisplay(label, icon, replacement, order, lingerMs, leadCaption, trailCaption,
                countKey);
    }

    /**
     * The display an item supplies for itself: the item's own name ({@link NativeNames#itemNameMsg},
     * so a vanilla item, a pack item and an id nothing has authored all read properly) and the
     * item's own generated icon. No colour, since an item row draws no fill, and no order, so it
     * sorts after every row that named one.
     */
    @Nonnull
    public static HudRowDisplay forItem(@Nonnull String itemId) {
        return new HudRowDisplay(NativeNames.itemNameMsg(itemId), IconSpec.ofItem(itemId), null, null, null,
                null, null, null);
    }

    /** These parts over {@code under}'s: a part this display leaves null reads from {@code under}. */
    @Nonnull
    public HudRowDisplay over(@Nonnull HudRowDisplay under) {
        return new HudRowDisplay(
                label != null ? label : under.label,
                icon != null ? icon : under.icon,
                color != null ? color : under.color,
                order != null ? order : under.order,
                lingerMs != null ? lingerMs : under.lingerMs,
                leadCaption != null ? leadCaption : under.leadCaption,
                trailCaption != null ? trailCaption : under.trailCaption,
                countKey != null ? countKey : under.countKey);
    }
}
