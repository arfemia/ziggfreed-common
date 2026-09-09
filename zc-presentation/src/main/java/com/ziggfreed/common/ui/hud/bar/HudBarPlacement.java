package com.ziggfreed.common.ui.hud.bar;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.ui.hud.HudPosition;
import com.ziggfreed.common.util.SafeLog;

/**
 * Where one player's panel sits and how its rows spread there, RESOLVED: the one answer every paint
 * reads, worked out from the layers that may have a say, in the order they have it.
 *
 * <ol>
 *   <li><b>The player's own pick</b>, when they made one in the HUD settings and the spot still
 *       exists, is enabled and fits the panel. It swaps the WHOLE group, corner, offsets and
 *       spread together, because an owner's inline nudge belongs to the owner's corner and would
 *       put the player's spot somewhere nobody chose if it folded over it.</li>
 *   <li>Else <b>the placement the panel names</b> ({@code Placement} on {@link HudBarPanelAsset}),
 *       leaf by leaf over the document's own fallback, with <b>the panel's inline leaves</b>
 *       ({@code Position}, {@code Columns}, {@code RowsPerColumn}) folded over that: how an owner
 *       states custom values without authoring a spot of their own.</li>
 *   <li>Else <b>the document's fallback</b>: the corner its layout declares, one column, one row
 *       per column.</li>
 * </ol>
 *
 * <p>A panel naming a placement nothing authored sits at the document's fallback and says so once
 * in the log rather than silently: the name is a typo or a pack that is no longer installed, and
 * either is worth a line.
 *
 * @param position      the corner and offsets the panel hangs from
 * @param columns       the most columns the rows spread across, before the document's own ceiling
 * @param rowsPerColumn how many rows one column takes before the next opens
 * @param id            the placement this was resolved from, or null for a document fallback or an
 *                      inline-only panel
 */
public record HudBarPlacement(@Nonnull HudPosition position, int columns, int rowsPerColumn,
        @Nullable String id) {

    /** How many columns a panel spreads across when nothing says otherwise: one. */
    public static final int DEFAULT_COLUMNS = 1;

    /** How many rows a column takes before the next opens, when nothing says otherwise. */
    public static final int DEFAULT_ROWS_PER_COLUMN = 1;

    /** Panels already warned about a placement they name that nothing authored, so a boot logs it once. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    public HudBarPlacement {
        columns = Math.max(1, columns);
        rowsPerColumn = Math.max(1, rowsPerColumn);
    }

    /** The column cap held to what the document declares: a spread can never exceed the slots that exist. */
    public int columns(int documentColumns) {
        return Math.max(1, Math.min(columns, Math.max(1, documentColumns)));
    }

    /** True when the panel is pinned to its bottom edge, so it grows upward and fills each column bottom-up. */
    public boolean bottomUp() {
        return position.getAnchorEdge() == HudPosition.AnchorEdge.BOTTOM;
    }

    /** True when the panel is pinned to its right edge, so it grows leftward and its first column is the rightmost. */
    public boolean rightToLeft() {
        return position.getHorizontalEdge() == HudPosition.HorizontalEdge.RIGHT;
    }

    /** The document's own fallback for {@code layout}: its declared corner, one column, one row per column. */
    @Nonnull
    public static HudBarPlacement fallback(@Nonnull HudBarLayout layout) {
        return new HudBarPlacement(layout.defaultPosition(), DEFAULT_COLUMNS, DEFAULT_ROWS_PER_COLUMN, null);
    }

    /**
     * Resolve where {@code layout}'s panel sits for a player whose own pick is {@code playerPick}
     * (null for none), against the folded placements.
     */
    @Nonnull
    public static HudBarPlacement resolve(@Nonnull HudBarPanelAsset panel, @Nonnull HudBarLayout layout,
            @Nullable String playerPick) {
        return resolve(panel, layout, playerPick, HudBarPlacementConfig.getInstance());
    }

    /** As {@link #resolve(HudBarPanelAsset, HudBarLayout, String)}, over an explicit fold, for a test. */
    @Nonnull
    static HudBarPlacement resolve(@Nonnull HudBarPanelAsset panel, @Nonnull HudBarLayout layout,
            @Nullable String playerPick, @Nonnull HudBarPlacementConfig placements) {
        HudBarPlacementAsset picked = placements.placement(playerPick);
        if (picked != null && picked.enabled() && picked.fits(layout.panelId())) {
            return fallback(layout).under(picked);
        }
        HudBarPlacement resolved = fallback(layout);
        String named = panel.placement();
        if (named != null) {
            HudBarPlacementAsset base = placements.placement(named);
            if (base != null) {
                resolved = resolved.under(base);
            } else {
                warnMissing(layout.panelId(), named);
            }
        }
        HudPosition position = resolved.position();
        HudBarPosition inline = panel.authoredPosition();
        if (inline != null) {
            position = inline.over(position);
        }
        Integer columns = panel.authoredColumns();
        Integer rowsPerColumn = panel.authoredRowsPerColumn();
        return new HudBarPlacement(position,
                columns != null ? columns : resolved.columns(),
                rowsPerColumn != null ? rowsPerColumn : resolved.rowsPerColumn(),
                resolved.id());
    }

    /** This placement with {@code spot}'s authored leaves folded over it, carrying {@code spot}'s id. */
    @Nonnull
    private HudBarPlacement under(@Nonnull HudBarPlacementAsset spot) {
        HudBarPosition authored = spot.position();
        Integer spotColumns = spot.columns();
        Integer spotRows = spot.rowsPerColumn();
        return new HudBarPlacement(authored != null ? authored.over(position) : position,
                spotColumns != null ? spotColumns : columns,
                spotRows != null ? spotRows : rowsPerColumn,
                spot.getId());
    }

    private static void warnMissing(@Nonnull String panelId, @Nonnull String named) {
        if (WARNED.add(panelId.toLowerCase(Locale.ROOT) + ":" + named.toLowerCase(Locale.ROOT))) {
            SafeLog.warn("[hud] the bar panel '" + panelId + "' names the placement '" + named
                    + "', which nothing authored (Server/ZiggfreedCommon/HudBarPlacements/" + named
                    + ".json); it sits where its document says until one exists");
        }
    }

    /** Forget which missing placements have been warned about; for a test. */
    static void resetWarnings() {
        WARNED.clear();
    }
}
