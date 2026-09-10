package com.ziggfreed.common.ui.hud.panel;

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
 *       exists, is enabled and fits the panel. It swaps the WHOLE group, corner, offsets, spread,
 *       band, cut, floor and colour together, because an owner's inline nudge belongs to the
 *       owner's corner and would put the player's spot somewhere nobody chose if it folded over
 *       it.</li>
 *   <li>Else <b>the spot the panel names</b> ({@code Placement} on {@link HudPanelAsset}),
 *       leaf by leaf over the document's own fallback, with <b>the panel's inline leaves</b>
 *       ({@code Position}, {@code Columns}, {@code RowsPerColumn}, {@code Gap}, {@code Cutout},
 *       {@code MinHeight}, {@code Color}) folded over that: how an owner states custom values
 *       without authoring a spot of their own.</li>
 *   <li>Else <b>the document's fallback</b>: the corner its layout declares, one column, one row
 *       per column, no band, no cut, no floor and no colour of its own (the shared card look, which
 *       is the layer under every colour here, is folded in by the paint through
 *       {@link com.ziggfreed.common.ui.hud.card.HudCardLook#resolve}).</li>
 * </ol>
 *
 * <p>A panel naming a spot nothing authored sits at the document's fallback and says so once
 * in the log rather than silently: the name is a typo or a pack that is no longer installed, and
 * either is worth a line.
 *
 * @param position      the corner and offsets the panel hangs from
 * @param columns       the most columns the rows spread across, before the document's own ceiling
 * @param rowsPerColumn how many rows one column takes before the next opens
 * @param gap           the band left clear across the rows, folded leaf by leaf, or null for none;
 *                      whether a folded band APPLIES ({@link HudSpotGap#applies}) is the paint's
 *                      guard, so a layer switching it off with a zero still folds
 * @param cutout        the cells left empty at the pinned end of one column, folded leaf by leaf,
 *                      or null for none; whether it APPLIES ({@link HudSpotCutout#applies}) is the
 *                      paint's guard too
 * @param minHeight     the least height the panel draws at, 0 for none
 * @param color         the frame colour the spot or the panel states, already validated, or null
 *                      to take the shared card look
 * @param id            the spot this was resolved from, or null for a document fallback or an
 *                      inline-only panel
 */
public record HudSpot(@Nonnull HudPosition position, int columns, int rowsPerColumn,
        @Nullable HudSpotGap gap, @Nullable HudSpotCutout cutout, int minHeight, @Nullable String color,
        @Nullable String id) {

    /** How many columns a panel spreads across when nothing says otherwise: one. */
    public static final int DEFAULT_COLUMNS = 1;

    /** How many rows a column takes before the next opens, when nothing says otherwise. */
    public static final int DEFAULT_ROWS_PER_COLUMN = 1;

    /** Panels already warned about a spot they name that nothing authored, so a boot logs it once. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    public HudSpot {
        columns = Math.max(1, columns);
        rowsPerColumn = Math.max(1, rowsPerColumn);
        minHeight = Math.max(0, minHeight);
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

    /** The document's own fallback for {@code layout}: its declared corner, one column, one row per column, no band, no cut, no floor, no colour. */
    @Nonnull
    public static HudSpot fallback(@Nonnull HudPanelLayout layout) {
        return new HudSpot(layout.defaultPosition(), DEFAULT_COLUMNS, DEFAULT_ROWS_PER_COLUMN,
                null, null, 0, null, null);
    }

    /**
     * Resolve where {@code layout}'s panel sits for a player whose own pick is {@code playerPick}
     * (null for none), against the folded spots.
     */
    @Nonnull
    public static HudSpot resolve(@Nonnull HudPanelAsset panel, @Nonnull HudPanelLayout layout,
            @Nullable String playerPick) {
        return resolve(panel, layout, playerPick, HudSpotConfig.getInstance());
    }

    /** As {@link #resolve(HudPanelAsset, HudPanelLayout, String)}, over an explicit fold, for a test. */
    @Nonnull
    static HudSpot resolve(@Nonnull HudPanelAsset panel, @Nonnull HudPanelLayout layout,
            @Nullable String playerPick, @Nonnull HudSpotConfig spots) {
        HudSpotAsset picked = spots.spot(playerPick);
        if (picked != null && picked.enabled() && picked.fits(layout.panelId())) {
            return fallback(layout).under(picked);
        }
        HudSpot resolved = fallback(layout);
        String named = panel.placement();
        if (named != null) {
            HudSpotAsset base = spots.spot(named);
            if (base != null) {
                resolved = resolved.under(base);
            } else {
                warnMissing(layout.panelId(), named);
            }
        }
        HudPosition position = resolved.position();
        HudSpotPosition inline = panel.authoredPosition();
        if (inline != null) {
            position = inline.over(position);
        }
        Integer columns = panel.authoredColumns();
        Integer rowsPerColumn = panel.authoredRowsPerColumn();
        HudSpotGap inlineGap = panel.authoredGap();
        HudSpotCutout inlineCutout = panel.authoredCutout();
        Integer minHeight = panel.authoredMinHeight();
        String inlineColor = panel.authoredColor();
        return new HudSpot(position,
                columns != null ? columns : resolved.columns(),
                rowsPerColumn != null ? rowsPerColumn : resolved.rowsPerColumn(),
                inlineGap != null ? inlineGap.over(resolved.gap()) : resolved.gap(),
                inlineCutout != null ? inlineCutout.over(resolved.cutout()) : resolved.cutout(),
                minHeight != null ? minHeight : resolved.minHeight(),
                inlineColor != null ? inlineColor : resolved.color(),
                resolved.id());
    }

    /** This answer with {@code spot}'s authored leaves folded over it, carrying {@code spot}'s id. */
    @Nonnull
    private HudSpot under(@Nonnull HudSpotAsset spot) {
        HudSpotPosition authored = spot.position();
        Integer spotColumns = spot.columns();
        Integer spotRows = spot.rowsPerColumn();
        HudSpotGap spotGap = spot.gap();
        HudSpotCutout spotCutout = spot.cutout();
        Integer spotMinHeight = spot.minHeight();
        String spotColor = spot.color();
        return new HudSpot(authored != null ? authored.over(position) : position,
                spotColumns != null ? spotColumns : columns,
                spotRows != null ? spotRows : rowsPerColumn,
                spotGap != null ? spotGap.over(gap) : gap,
                spotCutout != null ? spotCutout.over(cutout) : cutout,
                spotMinHeight != null ? spotMinHeight : minHeight,
                spotColor != null ? spotColor : color,
                spot.getId());
    }

    private static void warnMissing(@Nonnull String panelId, @Nonnull String named) {
        if (WARNED.add(panelId.toLowerCase(Locale.ROOT) + ":" + named.toLowerCase(Locale.ROOT))) {
            SafeLog.warn("[hud] the panel '" + panelId + "' names the spot '" + named
                    + "', which nothing authored (Server/ZiggfreedCommon/HudSpots/" + named
                    + ".json); it sits where its document says until one exists");
        }
    }

    /** Forget which missing spots have been warned about; for a test. */
    static void resetWarnings() {
        WARNED.clear();
    }
}
