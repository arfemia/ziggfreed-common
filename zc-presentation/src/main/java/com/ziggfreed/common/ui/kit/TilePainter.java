package com.ziggfreed.common.ui.kit;

import java.util.List;
import java.util.function.Function;

import javax.annotation.Nonnull;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;

import com.ziggfreed.common.ui.UiRetint;

/**
 * Paints tile grids: category tiles ({@code Pages/ZigCollectionTile.ui}), keepsake shelves
 * ({@code Pages/ZigKeepsakeTile.ui}) and statistic tiles ({@code Pages/ZigStatTile.ui}). Each call clears its grid
 * and appends one template per tile, so a grid is repainted whole (in a build, or a partial update that binds only
 * the tiles it appended).
 *
 * <p>Selectors: a category tile's button {@code grid[i] #Tile}, a keepsake {@code shelf[i] #Keep} (its three
 * background layers {@code #KeepEarned}, {@code #KeepToEarn}, {@code #KeepMissed}, exactly one shown), a statistic
 * {@code grid[i] #StatTile}.
 */
public final class TilePainter {

    public static final String COLLECTION_TEMPLATE = "Pages/ZigCollectionTile.ui";
    public static final String KEEPSAKE_TEMPLATE = "Pages/ZigKeepsakeTile.ui";
    public static final String STAT_TEMPLATE = "Pages/ZigStatTile.ui";

    private TilePainter() {
    }

    /**
     * Category tiles into {@code grid}: picture, name, count, bar, the check and complete style when complete, the
     * pill, the accent strip (clamped to the row contrast floor), the "new" mark; each tile bound to
     * {@code binding}'s answer (null leaves it unbound).
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
}
