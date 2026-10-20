package com.ziggfreed.common.ui.kit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;

import com.ziggfreed.common.ui.UiRetint;
import com.ziggfreed.common.ui.icon.ItemQualityLook;
import com.ziggfreed.common.ui.menu.MenuPalette;

/**
 * Paints tile grids: category tiles ({@code Pages/ZigCollectionTile.ui}), keepsake shelves
 * ({@code Pages/ZigKeepsakeTile.ui}), statistic tiles ({@code Pages/ZigStatTile.ui}), item slots
 * ({@code Pages/ZigItemSlotTile.ui}), wares ({@code Pages/ZigWareTile.ui}) and reward cards
 * ({@code Pages/ZigRewardCard.ui}, with {@code Pages/ZigRewardDivider.ui} between a run that is not items and the
 * items), the last two under titled runs ({@code Pages/ZigTileSection.ui}). Each tile call clears its grid and appends
 * one template per tile, so a grid is repainted whole (in a build, or a partial update that binds only the tiles it
 * appended).
 *
 * <p>Selectors: a category tile's button {@code grid[i] #Tile}, a keepsake {@code shelf[i] #Keep} (its three
 * background layers {@code #KeepEarned}, {@code #KeepToEarn}, {@code #KeepMissed}, exactly one shown), a statistic
 * {@code grid[i] #StatTile}, an item slot {@code grid[i] #Slot} (its three background layers {@code #SlotOwned},
 * {@code #SlotSeen}, {@code #SlotHidden}, exactly one shown), a ware's button {@code grid[i] #Ware}, a reward card's
 * two faces {@code grid[i] #ItemCard} and {@code grid[i] #PlainCard} (exactly one shown), a section's grid
 * {@code list[i] #SecGrid}.
 */
public final class TilePainter {

    public static final String COLLECTION_TEMPLATE = "Pages/ZigCollectionTile.ui";
    public static final String KEEPSAKE_TEMPLATE = "Pages/ZigKeepsakeTile.ui";
    public static final String STAT_TEMPLATE = "Pages/ZigStatTile.ui";
    public static final String ITEM_SLOT_TEMPLATE = "Pages/ZigItemSlotTile.ui";
    public static final String WARE_TEMPLATE = "Pages/ZigWareTile.ui";
    public static final String SECTION_TEMPLATE = "Pages/ZigTileSection.ui";
    public static final String REWARD_CARD_TEMPLATE = "Pages/ZigRewardCard.ui";
    public static final String REWARD_DIVIDER_TEMPLATE = "Pages/ZigRewardDivider.ui";

    /**
     * Whether a category tile underway wears its completion ring ({@code #Ring}, vanilla's
     * {@code CircularProgressBar}) beside its bar. The one switch: false leaves the bar alone.
     */
    static final boolean RINGS = true;

    private TilePainter() {
    }

    /**
     * Category tiles into {@code grid}: picture, name, count, bar, the check and complete style when complete (the
     * completion ring while underway), the pill, the accent strip (clamped to the row contrast floor), the "new"
     * mark; each tile bound to {@code binding}'s answer (null leaves it unbound).
     */
    public static void collection(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events, @Nonnull String grid,
            @Nonnull List<CollectionTile> tiles, @Nonnull Function<CollectionTile, EventData> binding) {
        cmd.clear(grid);
        for (int i = 0; i < tiles.size(); i++) {
            CollectionTile tile = tiles.get(i);
            cmd.append(grid, COLLECTION_TEMPLATE);
            String t = KitPaint.child(grid, i) + " #Tile";
            if (tile.complete()) {
                ZigStyles.apply(cmd, t + ".Style", ZigStyles.Name.TILE_COMPLETE, null);
            }
            KitPaint.picture(cmd, t + " #Pic", tile.picture());
            KitPaint.text(cmd, t + " #Name", tile.name());
            KitPaint.optional(cmd, t + " #Count", tile.count());
            Progress progress = tile.progress();
            cmd.set(t + " #BarTrack.Visible", progress != null);
            if (progress != null) {
                cmd.set(t + " #Bar.Value", progress.fraction());
            }
            cmd.set(t + " #Check.Visible", tile.complete());
            boolean ring = RINGS && progress != null && !tile.complete();
            cmd.set(t + " #Ring.Visible", ring);
            if (ring) {
                cmd.set(t + " #Ring.Value", progress.fraction());
            }
            Pill badge = tile.badge();
            cmd.set(t + " #Badge.Visible", badge != null);
            if (badge != null) {
                PillPainter.paint(cmd, t + " #Badge", badge);
            }
            String accent = tile.accentHex();
            cmd.set(t + " #AccentStrip.Visible", accent != null);
            if (accent != null) {
                UiRetint.fill(cmd, t + " #AccentStrip", ZigTokens.clampAccent(accent));
            }
            cmd.set(t + " #New.Visible", tile.unseen());
            EventData data = binding.apply(tile);
            if (data != null) {
                events.addEventBinding(CustomUIEventBindingType.Activating, t, data);
            }
        }
    }

    /**
     * Keepsake tiles onto {@code shelf}: the layer for its state, the picture (dimmed by the scrim unless earned),
     * the year (gold when earned), the state line (green for the highlighted, live year), the tooltip.
     */
    public static void keepsakes(@Nonnull UICommandBuilder cmd, @Nonnull String shelf,
            @Nonnull List<KeepsakeTile> tiles) {
        cmd.clear(shelf);
        for (int i = 0; i < tiles.size(); i++) {
            KeepsakeTile tile = tiles.get(i);
            cmd.append(shelf, KEEPSAKE_TEMPLATE);
            String k = KitPaint.child(shelf, i) + " #Keep";
            KeepsakeState state = tile.state();
            cmd.set(k + " #KeepEarned.Visible", state == KeepsakeState.EARNED);
            cmd.set(k + " #KeepToEarn.Visible", state == KeepsakeState.TO_EARN);
            cmd.set(k + " #KeepMissed.Visible", state == KeepsakeState.MISSED);
            KitPaint.picture(cmd, k + " #Pic", tile.picture());
            cmd.set(k + " #Scrim.Visible", state != KeepsakeState.EARNED);
            KitPaint.text(cmd, k + " #Label", tile.label());
            if (state == KeepsakeState.EARNED) {
                cmd.set(k + " #Label.Style.TextColor", ZigTokens.ACCENT);
            }
            KitPaint.text(cmd, k + " #StateLine", tile.stateLine());
            if (tile.highlighted()) {
                cmd.set(k + " #StateLine.Style.TextColor", ZigTokens.TONE_LIVE_TEXT);
            }
            if (tile.tooltip() != null) {
                KitPaint.tooltip(cmd, k, tile.tooltip());
            }
        }
    }

    /**
     * Statistic tiles into {@code grid}: picture, figure (faint when it is zero), name, caption and server line.
     */
    public static void stats(@Nonnull UICommandBuilder cmd, @Nonnull String grid, @Nonnull List<StatTile> tiles) {
        cmd.clear(grid);
        for (int i = 0; i < tiles.size(); i++) {
            StatTile tile = tiles.get(i);
            cmd.append(grid, STAT_TEMPLATE);
            String s = KitPaint.child(grid, i) + " #StatTile";
            KitPaint.picture(cmd, s + " #Pic", tile.picture());
            KitPaint.text(cmd, s + " #Figure", tile.figure());
            if (tile.zero()) {
                cmd.set(s + " #Figure.Style.TextColor", ZigTokens.INK_FAINT);
            }
            KitPaint.text(cmd, s + " #Name", tile.name());
            KitPaint.optional(cmd, s + " #Caption", tile.caption());
            KitPaint.optional(cmd, s + " #Server", tile.serverLine());
        }
    }

    /**
     * Item slots into {@code grid}, each showing exactly one of its three layers: owned (the complete tile, its check),
     * shown but not yet had (the default tile, the picture under the scrim), or a mystery (the empty tile, its glyph,
     * no picture). The tooltip sits on {@code #Slot}.
     */
    public static void itemSlots(@Nonnull UICommandBuilder cmd, @Nonnull String grid, @Nonnull List<ItemSlotTile> tiles) {
        cmd.clear(grid);
        for (int i = 0; i < tiles.size(); i++) {
            ItemSlotTile tile = tiles.get(i);
            cmd.append(grid, ITEM_SLOT_TEMPLATE);
            String s = KitPaint.child(grid, i) + " #Slot";
            boolean seen = !tile.owned() && !tile.mystery();
            cmd.set(s + " #SlotOwned.Visible", tile.owned());
            cmd.set(s + " #SlotSeen.Visible", seen);
            cmd.set(s + " #SlotHidden.Visible", tile.mystery());
            KitPaint.picture(cmd, s + " #Pic", tile.mystery() ? Picture.NONE : tile.picture());
            cmd.set(s + " #Scrim.Visible", seen);
            cmd.set(s + " #Check.Visible", tile.owned());
            cmd.set(s + " #Mystery.Visible", tile.mystery() && tile.glyph() != null);
            if (tile.mystery() && tile.glyph() != null) {
                KitPaint.text(cmd, s + " #Mystery", tile.glyph());
            }
            if (tile.tooltip() != null) {
                KitPaint.tooltip(cmd, s, tile.tooltip());
            }
        }
    }

    /**
     * One titled run of tiles appended into {@code list} at {@code index} (how many sections were appended there since
     * it was last cleared): its heading, and its meta at the end ({@code null} hides it), such as a shelf's countdown.
     *
     * @return the selector of the grid the run's tiles go into ({@link #wares})
     */
    @Nonnull
    public static String section(@Nonnull UICommandBuilder cmd, @Nonnull String list, int index,
            @Nonnull Message heading, @Nullable Message meta) {
        cmd.append(list, SECTION_TEMPLATE);
        String s = KitPaint.child(list, index);
        KitPaint.text(cmd, s + " #SecHead #HeadLabel", heading);
        KitPaint.optional(cmd, s + " #SecHead #Meta", meta);
        return s + " #SecGrid";
    }

    /**
     * Ware tiles into {@code grid}: the selected look on the ware being read, picture, name, the price line (its
     * picture hidden when there is none, the amount in the danger ink when short), the pill, the scrim over a ware out
     * of reach, the lock on one behind a requirement, the tooltip; each tile bound to {@code binding}'s answer (null
     * leaves it unbound) without locking the interface, since a ware's press answers with a partial update.
     *
     * @return each tile's button selector, in order: what {@link #selectWare} takes in a later partial update
     */
    @Nonnull
    public static List<String> wares(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull String grid, @Nonnull List<WareTile> tiles, @Nonnull Function<WareTile, EventData> binding) {
        cmd.clear(grid);
        List<String> selectors = new ArrayList<>(tiles.size());
        for (int i = 0; i < tiles.size(); i++) {
            WareTile tile = tiles.get(i);
            cmd.append(grid, WARE_TEMPLATE);
            String w = KitPaint.child(grid, i) + " #Ware";
            if (tile.selected()) {
                ZigStyles.apply(cmd, w + ".Style", ZigStyles.Name.TILE_SELECTED, null);
            }
            KitPaint.picture(cmd, w + " #Pic", tile.picture());
            KitPaint.text(cmd, w + " #Name", tile.name());
            Message cost = tile.cost();
            cmd.set(w + " #Foot.Visible", cost != null);
            if (cost != null) {
                KitPaint.text(cmd, w + " #Cost", cost);
                if (tile.costShort()) {
                    cmd.set(w + " #Cost.Style.TextColor", ZigTokens.TONE_DANGER_TEXT);
                }
                cmd.set(w + " #CostPic.Visible", KitPaint.picture(cmd, w + " #CostPic", tile.costPicture()));
            }
            Pill badge = tile.badge();
            cmd.set(w + " #Badge.Visible", badge != null);
            if (badge != null) {
                PillPainter.paint(cmd, w + " #Badge", badge);
            }
            cmd.set(w + " #Scrim.Visible", tile.dimmed());
            cmd.set(w + " #Lock.Visible", tile.locked());
            if (tile.tooltip() != null) {
                KitPaint.tooltip(cmd, w, tile.tooltip());
            }
            EventData data = binding.apply(tile);
            if (data != null) {
                events.addEventBinding(CustomUIEventBindingType.Activating, w, data, false);
            }
            selectors.add(w);
        }
        return selectors;
    }

    /** Swap a painted ware (a selector {@link #wares} answered) between its resting and its selected look. */
    public static void selectWare(@Nonnull UICommandBuilder cmd, @Nonnull String wareSelector, boolean selected) {
        ZigStyles.apply(cmd, wareSelector + ".Style",
                selected ? ZigStyles.Name.TILE_SELECTED : ZigStyles.Name.TILE, null);
    }

    /**
     * Reward cards into {@code grid} (a titled run's wrapping grid, {@link #section}), one per line in the order given,
     * in the style of vanilla's Memories page; the whole card is one button. A line whose picture keeps its item's
     * tooltip ({@link Picture#tooltipItem}) is an item: when this server ships it, the card shows its item face, the
     * item ({@code #Slot}, an {@code ItemSlot}, its {@code .ItemId} set as vanilla's barter page sets it, whose hover
     * shows the item's own tooltip) on its quality's slot art ({@code #SlotBack}, the quality's {@code SlotTexture}),
     * its words, and the engine's word for that quality in its colour ({@link ItemQualityLook}; hidden where the quality
     * shows none). The quality is the one the line names ({@link DetailLine#quality}), else the item's own. Every other
     * line, and an item this server does not ship, shows the plain face on the kit's warm plate: its picture, its words
     * under it (a reward's own line carries its amount), the word for the quality the line names in its colour (none
     * when it names none), its tooltip on the card; a line's count is not shown. One divider row sits before the first
     * item that follows a line that is not one. A line whose select id is set is bound to {@code binding}'s answer
     * (null leaves it unbound) on the face it shows.
     */
    public static void rewardCards(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull String grid, @Nonnull List<DetailLine> lines, @Nonnull Function<DetailLine, EventData> binding) {
        rewardCards(cmd, events, grid, lines, binding, ItemQualityLook::of);
    }

    /** {@link #rewardCards} over an injected item quality reading (null for an item the server does not ship). */
    static void rewardCards(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events, @Nonnull String grid,
            @Nonnull List<DetailLine> lines, @Nonnull Function<DetailLine, EventData> binding,
            @Nonnull Function<String, ItemQualityLook> looks) {
        rewardCards(cmd, events, grid, lines, binding, looks, ItemQualityLook::ofQuality);
    }

    /**
     * {@link #rewardCards} over injected readings: an item's quality ({@code looks}, null for an item the server does
     * not ship) and a quality's by its id ({@code qualities}, null for one it does not ship).
     */
    static void rewardCards(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events, @Nonnull String grid,
            @Nonnull List<DetailLine> lines, @Nonnull Function<DetailLine, EventData> binding,
            @Nonnull Function<String, ItemQualityLook> looks, @Nonnull Function<String, ItemQualityLook> qualities) {
        cmd.clear(grid);
        int child = 0;
        boolean plainBefore = false;
        boolean divided = false;
        for (DetailLine line : lines) {
            Picture picture = line.picture();
            boolean item = picture.tooltip() && picture.itemId() != null;
            if (item && plainBefore && !divided) {
                cmd.append(grid, REWARD_DIVIDER_TEMPLATE);
                child++;
                divided = true;
            }
            plainBefore |= !item;
            cmd.append(grid, REWARD_CARD_TEMPLATE);
            String card = KitPaint.child(grid, child++);
            ItemQualityLook named = line.quality() == null ? null : qualities.apply(line.quality());
            ItemQualityLook look = item ? looks.apply(picture.itemId()) : null;
            String face = look != null
                    ? itemCard(cmd, card, picture.itemId(), line, named != null ? named : look)
                    : plainCard(cmd, card, line, named);
            EventData data = line.selectId() == null ? null : binding.apply(line);
            if (data != null) {
                events.addEventBinding(CustomUIEventBindingType.Activating, face, data);
            }
        }
    }

    /** A card's item face: the item on its quality's slot art, the words, the quality's word. Its selector. */
    @Nonnull
    private static String itemCard(@Nonnull UICommandBuilder cmd, @Nonnull String card, @Nonnull String itemId,
            @Nonnull DetailLine line, @Nonnull ItemQualityLook look) {
        String face = card + " #ItemCard";
        cmd.set(card + " #PlainCard.Visible", false);
        cmd.set(face + ".Visible", true);
        String slot = look.slotTexture();
        cmd.set(face + " #SlotBack.Visible", slot != null);
        if (slot != null) {
            cmd.set(face + " #SlotBack.AssetPath", slot);
        }
        cmd.set(face + " #Slot.ItemId", itemId);
        KitPaint.text(cmd, face + " #ItemName", line.text());
        qualityWord(cmd, face + " #Rarity", look);
        return face;
    }

    /** A card's plain face: the picture, the words, the named quality's word, the tooltip. Its selector. */
    @Nonnull
    private static String plainCard(@Nonnull UICommandBuilder cmd, @Nonnull String card, @Nonnull DetailLine line,
            @Nullable ItemQualityLook named) {
        String face = card + " #PlainCard";
        cmd.set(card + " #ItemCard.Visible", false);
        cmd.set(face + ".Visible", true);
        KitPaint.picture(cmd, face + " #Pic", line.picture());
        KitPaint.text(cmd, face + " #PlainName", line.text());
        qualityWord(cmd, face + " #PlainRarity", named);
        KitPaint.tooltip(cmd, face, line.tooltip());
        return face;
    }

    /** {@code look}'s word on the label at {@code label} in its colour ({@link #qualityInk}); hidden with none. */
    private static void qualityWord(@Nonnull UICommandBuilder cmd, @Nonnull String label,
            @Nullable ItemQualityLook look) {
        Message word = look == null ? null : look.word();
        cmd.set(label + ".Visible", word != null);
        if (word != null) {
            KitPaint.text(cmd, label, word);
            cmd.set(label + ".Style.TextColor", qualityInk(look.colour()));
        }
    }

    /**
     * A quality's word colour as painted: its own {@code TextColor} where it keeps the mark floor over the row (the
     * game colours the word the same way in its tooltip), else the body ink, never the kit's gold, which would read as
     * a better tier than it is.
     */
    @Nonnull
    static String qualityInk(@Nullable String colour) {
        if (colour == null || !UiRetint.isSixDigitHex(colour)) {
            return ZigTokens.INK_BODY;
        }
        String hex = colour.toLowerCase(Locale.ROOT);
        return MenuPalette.contrast(hex, ZigTokens.rowSeen()) >= ZigTokens.MARK_FLOOR ? hex : ZigTokens.INK_BODY;
    }
}
