package com.ziggfreed.common.ui.hud.bar;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.ui.UiRetint;
import com.ziggfreed.common.ui.hud.HudPosition;
import com.ziggfreed.common.ui.hud.HudPreferences;
import com.ziggfreed.common.ui.hud.KeyedCustomHud;
import com.ziggfreed.common.ui.hud.RepaintCoalescer;
import com.ziggfreed.common.ui.hud.card.HudCardConfig;
import com.ziggfreed.common.ui.hud.card.HudCardLook;
import com.ziggfreed.common.ui.icon.IconRenderer;
import com.ziggfreed.common.util.SafeLog;

/**
 * One player's view of one bar panel: rows drawn only while the values behind them are moving. The
 * drawing is the same whichever panel this is, so it lives here once and reads a {@link HudBarLayout}
 * for everything that differs; {@link HudBarStackHud} and {@link HudBarGridHud} are that layout and
 * nothing else.
 *
 * <p><b>Two parts to a row, freely combined.</b> A row draws a FILL when it was moved with a
 * reading ({@link HudBars#moved} hands a {@link HudBarReading} over with every move, and the row
 * keeps the latest), and it carries an ITEM when it was moved as one ({@link HudBars#itemMoved}),
 * in which case its picture and name are the item's own and the number beside the name is the
 * running count since the row came up. A row with a fill is two lines, the name with the gain
 * beside it over the fill; a row without one is the first line alone. Rows that draw a fill sit
 * above rows that do not.
 *
 * <p><b>What it knows.</b> Rows, created on demand by the first move reported under an id and
 * dressed by the {@link HudBarDisplay} that came with the move, each holding the reading its last
 * move brought; overrides, from {@link HudBarConfig}; a panel, from {@link HudBarPanelConfig}; and
 * where THIS player's panel sits, resolved per paint by {@link HudBarPlacement#resolve} from the
 * spot the panel names, the owner's inline leaves and the player's own pick
 * ({@link HudPreferences}). It asks nothing of anyone at paint time and knows nothing about what
 * any value measures. A consumer never touches this class: it calls {@link HudBars#moved} or
 * {@link HudBars#itemMoved}.
 *
 * <p><b>How it paints.</b> A move records the gain and starts the row's linger clock, then asks for
 * a paint. Paints are folded per tick ({@link RepaintCoalescer}) and held to one per the panel's
 * authored {@code RepaintMs}. That window is a leading edge with a trailing flush, not a plain
 * throttle: a move arriving when the window is already open redraws at once, and one arriving
 * inside it is drawn at the window's end rather than dropped, so the number on screen is never
 * waiting on a LATER move to bring it up to date. One sweep is armed at a time, at the earliest
 * linger expiry, and it re-arms itself while anything is still live, so a row goes away on time with
 * no tick anywhere. A panel the player hid paints only its own {@code Visible} false and keeps its
 * rows, so showing it again mid-run shows the ledger as it stands. Everything that touches the
 * player runs on their world thread.
 *
 * <p><b>The document.</b> Slots are declared up front in columns ({@code #ZigBarCol<c>} holding
 * {@code #ZigBarC<c>R<r>}), addressed by index and hidden when surplus, because a partial update can
 * restyle an element that exists but never add one. Each slot is a {@code #Line} (icon pair,
 * {@code #Label}, {@code #Gain}) over a {@code #Bar} holding the two end captions and a
 * {@code #Track} of {@code #Fill} and {@code #Pulse}. Paths and ids are prefixed {@code Zig}
 * because the client's UI namespace is flat across mods. Text lands on {@code .TextSpans}, the fill
 * and the pulse are {@code Anchor} width pushes and their colour a {@code .Background.Color}
 * retint, so no per-colour texture is shipped for a bar.
 *
 * <p><b>The panel's size is pushed, never sized to content.</b> The panel is an absolutely
 * anchored box inside a full-viewport wrapper, which is what lets a Bottom pin hold (a top-down
 * flow has no bottom to pin to), and an absolute box has to be told how tall it is. So every paint
 * pushes an explicit {@code Anchor} at the width of the columns in use and the height the rows in
 * the tallest column add up to ({@link #panelHeightFor}), a row about an item being shorter than a
 * row with a fill, plus the spot's band where it applies, floored by the spot's {@code MinHeight}.
 * The band itself costs no element: it is the row margin of ONE slot per column, pushed taller
 * ({@link #gapSlotFor}), and reset on every other slot with every paint.
 *
 * <p><b>Every column is aligned by its first visible slot.</b> A column is a top-down stack inside
 * the panel and lays its rows from its own top, so a column shorter than the panel's inner height
 * has to be pushed to where it belongs, and that push is the same mechanism as the band: the row
 * margin of the column's FIRST VISIBLE slot, pushed by the column's leading space
 * ({@link ColumnShape#leadingPx}). Each in-use column is measured from the pinned edge
 * ({@link #shapeOf}): the cells its spot's {@code Cutout} keeps empty (plus the band, when the
 * split falls inside the cut), then the band where the split falls between two of its own rows,
 * then its own rows; the tallest of those extents is the panel's inner height. A bottom-pinned
 * column is pushed down by whatever it falls short of that height, so it bottom-aligns against the
 * pinned edge; a top-pinned column is pushed down by its cut alone, so its rows start past the cut.
 * A cut column therefore stands taller than the rest, with empty frame beside it above the shorter
 * columns, which is the shape a cut asks for.
 *
 * <p><b>The card's colour is one hex, folded like everything else and pushed only when it says
 * something.</b> The panel's frame is a shipped 9-slice, and its colour is a multiply over it
 * ({@link HudCardLook}): the spot's or the panel's own {@code Color}, folded into the
 * {@link HudBarPlacement} with the other leaves, over the record every HUD card shares
 * ({@link HudCardConfig}). The identity pushes nothing, so the common case costs no command. The
 * dressing inside every bar (the well, the gloss, the shade) follows the card's OPACITY on its own
 * ({@link HudBarDressing}): nothing at full opacity, else each overlay at its own hue and a
 * fraction of its shipped strength, pushed per painted fill row.
 */
public abstract class HudBarHud extends KeyedCustomHud {

    /** A sweep fires this much after the earliest expiry, so a clock read a hair early still finds it past. */
    private static final long SWEEP_SLACK_MS = 20L;

    /** How long after a row moves its fill still shows the leading-edge pulse. */
    private static final long PULSE_MS = 700L;

    /** How wide that pulse is; it is held narrower on a bar too short to hold it. */
    private static final int PULSE_WIDTH_PX = 18;

    /** The shared word for a gain, {@code +{0, number}}, grouped by each player's own client. */
    private static final String GAIN_KEY = "ziggfreedcommon.ui.hud.bar.gain";

    /**
     * How the stack reads: rows that draw a fill first, then the settled order (lower higher), then
     * the id, so the stack never reshuffles as different rows take the latest move.
     */
    static final Comparator<Row> STACK_ORDER = Comparator.comparing((Row r) -> !r.drawsFill())
            .thenComparingInt(r -> r.look().order())
            .thenComparing(Row::id);

    private final Map<String, LiveBar> live = new ConcurrentHashMap<>();
    private final RepaintCoalescer coalescer = new RepaintCoalescer(this::paintNow);
    private final AtomicBoolean paintDeferred = new AtomicBoolean();
    private final AtomicBoolean sweepArmed = new AtomicBoolean();

    /** Which panel this is: its document, its element names, its declared slots and its fallback corner. */
    private final HudBarLayout layout;

    protected HudBarHud(@Nonnull PlayerRef playerRef, @Nonnull HudBarLayout layout) {
        super(playerRef, layout.hudKey());
        this.layout = layout;
    }

    /** Which panel this is. */
    @Nonnull
    public final HudBarLayout layout() {
        return layout;
    }

    /** The authored leaves for this panel, folded; always answers. */
    @Nonnull
    private HudBarPanelAsset panel() {
        return HudBarPanelConfig.getInstance().panel(layout.panelId());
    }

    /**
     * Where THIS player's panel sits and how its rows spread: the player's own pick when they made
     * one, else the spot the panel names with the owner's inline leaves over it, else the document's
     * fallback. Read per paint, so a pick, a reload or an owner edit lands on the next paint. World
     * thread, because the pick is read off the player's entity.
     */
    @Nonnull
    private HudBarPlacement placement() {
        return HudBarPlacement.resolve(panel(), layout,
                HudPreferences.placementPick(getPlayerRef(), layout.panelId()));
    }

    /**
     * One row's moving state: the two parts it was moved with (the reading its fill is drawn from,
     * an item it counts), the display its last move came with, the gain since it came up and when
     * it goes away. Mutated under its own lock.
     */
    private static final class LiveBar {
        @Nullable HudBarReading reading;
        @Nullable String itemId;
        @Nonnull HudBarDisplay display = HudBarDisplay.NONE;
        double gain;
        long lastMovedMs;
        long expiresAtMs;
    }

    /**
     * What one drawn slot needs: the row's id, its settled look, the item it carries (null for
     * none), its live state and the reading its fill is drawn from (null for a row with none).
     */
    record Row(@Nonnull String id, @Nonnull HudBarLook look, @Nullable String itemId, double gain,
            long lastMovedMs, @Nullable HudBarReading reading) {

        /** Whether the row has a fill to draw. */
        boolean drawsFill() {
            return reading != null;
        }

        /** Whether the row is about an item. */
        boolean carriesItem() {
            return itemId != null;
        }
    }

    /**
     * One in-use column's vertical shape, measured from the pinned edge: how many rows it draws,
     * the space kept clear before its first row (the cells its spot's cut leaves empty, plus the
     * band when the split falls inside the cut), which slot carries the band and how tall it is
     * where the split falls between two of its own rows, and what its drawn rows add up to. A
     * column drawing nothing has no shape at all ({@link #NONE}): it neither pushes the frame nor
     * needs a push itself.
     *
     * @param drawn      how many rows the column draws: the rows it was given, held to the cells
     *                   left past its cut
     * @param reservedPx the space before its first row, from the pinned edge
     * @param bandSlot   the slot whose margin carries the band, or -1 for none
     * @param bandPx     the band's height where the column carries one, else 0
     * @param rowsPx     the drawn rows' heights summed, each as tall as its own kind
     */
    record ColumnShape(int drawn, int reservedPx, int bandSlot, int bandPx, int rowsPx) {

        /** A column drawing nothing. */
        static final ColumnShape NONE = new ColumnShape(0, 0, -1, 0, 0);

        /** How far the column reaches from the pinned edge: its reserved space, its band and its rows. */
        int extentPx() {
            return reservedPx + bandPx + rowsPx;
        }

        /**
         * The push on the column's first visible slot's margin, so the column sits where it belongs
         * inside a panel {@code innerHeightPx} tall. Bottom-up, the column stacks from the panel's
         * top and has to be pushed down by whatever it falls short of the inner height, so a column
         * shorter than the tallest (the last column of an uneven split, say) bottom-aligns against
         * the pinned edge by construction, and a cut column with the full height needs no push at
         * all: its rows already start above its cut. Top-down, the column is pushed down by its
         * reserved space alone, so its cut sits at the top and the column runs past it.
         */
        int leadingPx(int innerHeightPx, boolean bottomUp) {
            if (drawn == 0) {
                return 0;
            }
            return bottomUp ? Math.max(0, innerHeightPx - extentPx()) : reservedPx;
        }

        /**
         * What the slot at {@code slotRow}, drawing the column's row {@code ordinal}, is pushed by
         * over the document's own row margin: the column's leading space on its first visible slot
         * (the one drawing ordinal 0 top-down, the last drawn ordinal bottom-up, since a bottom-up
         * column's higher ordinals sit higher), the band on the slot below the split, and the two
         * ADDED where they are one slot, never one over the other. Pure, so every slot's margin can
         * be checked without a client.
         */
        int pushAt(int slotRow, int ordinal, int innerHeightPx, boolean bottomUp) {
            int leadOrdinal = bottomUp ? drawn - 1 : 0;
            return (ordinal == leadOrdinal ? leadingPx(innerHeightPx, bottomUp) : 0)
                    + (slotRow == bandSlot ? bandPx : 0);
        }
    }

    // ==================== KeyedCustomHud contract ====================

    @Nonnull
    @Override
    protected final String rootSelector() {
        return layout.root();
    }

    @Override
    protected final int panelWidth() {
        return layout.panelWidthPx();
    }

    /**
     * The empty panel's height, for the build push alone: every paint that follows pushes the
     * height its rows add up to, so this only has to be a box the first paint can replace.
     */
    @Override
    protected int panelHeight() {
        return layout.panelHeightFor(0);
    }

    /**
     * The authored repaint window, read per call so a settings reload lands on the next movement
     * rather than the next restart.
     */
    @Override
    protected final long updateIntervalMs() {
        return panel().repaintMs();
    }

    @Nonnull
    @Override
    protected final HudPosition configuredPosition() {
        return placement().position();
    }

    /** The first paint, inside the native {@code addCustomHud}: the document, its position, and no rows. */
    @Override
    protected final void build(@Nonnull UICommandBuilder cmd) {
        cmd.append(layout.template());
        applyConfiguredPosition(cmd);
        HudBarPlacement placement = placement();
        paint(cmd, layout, List.of(), panel(), placement, cardLook(placement), false);
    }

    /**
     * The look this player's panel wears: the colour its spot or its panel states, folded into
     * {@code placement} with the other leaves, over the record every HUD card shares. Read per
     * paint, so a reload of either lands on the next paint.
     */
    @Nonnull
    private static HudCardLook cardLook(@Nonnull HudBarPlacement placement) {
        return HudCardLook.resolve(placement.color(), HudCardConfig.getInstance().sharedColor());
    }

    // ==================== a value moved ====================

    /**
     * The row {@code rowId} moved by {@code delta}: create it if this is its first move, remember
     * the two parts it was moved with and the display that came along, add the delta to the gain
     * shown since it came up, restart its linger, and paint. Any thread; the paint runs on the
     * player's world thread.
     *
     * @param reading where the value now stands, drawn as the row's fill, or null for a row with no fill
     * @param itemId  the item the row counts, or null for a row about no item
     */
    void moved(@Nonnull String rowId, @Nullable HudBarReading reading, @Nullable String itemId, double delta,
            @Nonnull HudBarDisplay display, boolean absolute) {
        long now = System.currentTimeMillis();
        long linger = HudBarLook.resolve(rowId, HudBarConfig.getInstance().bySource(rowId), display).lingerMs();
        LiveBar state = live.computeIfAbsent(rowId, id -> new LiveBar());
        long expiresAt;
        synchronized (state) {
            state.reading = reading;
            state.itemId = itemId;
            state.display = display;
            // A mod that keeps its own running total for the stretch of activity a row belongs to
            // states the number outright; one that only knows what just happened adds it on.
            state.gain = absolute ? delta : state.gain + delta;
            state.lastMovedMs = now;
            // A HELD row has no expiry at all rather than one a very long way off, so the arithmetic
            // cannot overflow into a time already past and quietly drop the row on its first sweep.
            state.expiresAtMs = linger == HudBarLook.LINGER_HELD ? Long.MAX_VALUE : now + linger;
            expiresAt = state.expiresAtMs;
        }
        requestPaint(now);
        if (expiresAt != Long.MAX_VALUE) {
            armSweep(expiresAt, now);
        }
    }

    /**
     * Bring every live row's expiry forward to at most {@code withinMs} from now, so a set of rows
     * held for a stretch of activity goes away together when that activity ends instead of hanging
     * on its own clock. A row already fading sooner keeps its own time. World thread not required.
     */
    void fadeAll(long withinMs) {
        long now = System.currentTimeMillis();
        long deadline = now + Math.max(0L, withinMs);
        boolean any = false;
        for (LiveBar state : live.values()) {
            synchronized (state) {
                if (state.expiresAtMs > deadline) {
                    state.expiresAtMs = deadline;
                    any = true;
                }
            }
        }
        if (any) {
            armSweep(deadline, now);
        }
    }

    /** Queue a paint on this player's world thread, folded with any other request this tick. Any thread. */
    public void repaint() {
        requestPaint(System.currentTimeMillis());
    }

    /**
     * Paint now when the window since the last paint has passed, else once, at the window's end. A
     * request inside an already-deferred window folds into that one.
     */
    private void requestPaint(long now) {
        if (dueForPush(now)) {
            World world = aliveWorldOf(getPlayerRef());
            if (world != null) {
                coalescer.request(world);
            }
            return;
        }
        if (!paintDeferred.compareAndSet(false, true)) {
            return;
        }
        long delay = Math.max(1L, remainingInterval(now));
        HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
            paintDeferred.set(false);
            World world = aliveWorldOf(getPlayerRef());
            if (world != null) {
                coalescer.request(world);
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    /** Arm the one sweep, at {@code expiresAt}, unless one is already waiting. */
    private void armSweep(long expiresAt, long now) {
        if (!sweepArmed.compareAndSet(false, true)) {
            return;
        }
        long delay = Math.max(1L, expiresAt - now + SWEEP_SLACK_MS);
        HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
            sweepArmed.set(false);
            World world = aliveWorldOf(getPlayerRef());
            if (world != null) {
                coalescer.request(world);
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    // ==================== the paint ====================

    /**
     * World thread: drop what has expired, read what is left, draw, and keep a sweep armed while
     * anything lives. A panel the player hid paints only its own {@code Visible} false: its rows are
     * kept and swept exactly as if it showed, so showing it again shows the ledger as it stands.
     */
    private void paintNow() {
        try {
            markPushed();
            long now = System.currentTimeMillis();
            List<Row> rows = collectLive(now);
            HudBarPanelAsset panel = panel();
            HudBarPlacement placement = placement();
            UICommandBuilder cmd = new UICommandBuilder();
            if (HudPreferences.isHidden(getPlayerRef(), layout.panelId())) {
                cmd.set(layout.root() + ".Visible", false);
            } else {
                paint(cmd, layout, choose(rows, slotCap(panel, placement, layout), layout.totalSlots()),
                        panel, placement, cardLook(placement), true);
            }
            update(false, cmd);
            rearmSweep(now);
        } catch (Throwable t) {
            SafeLog.warn("[hud] the progress-bar panel failed to repaint for " + getPlayerRef().getUsername()
                    + ": " + t.getMessage());
        }
    }

    /**
     * World thread: every live row, dressed by its override over its display and carrying the
     * reading its last move brought. A row whose linger ran out, or whose override switched it off,
     * is forgotten here.
     */
    @Nonnull
    private List<Row> collectLive(long now) {
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<String, LiveBar> entry : live.entrySet()) {
            String id = entry.getKey();
            LiveBar state = entry.getValue();
            HudBarReading reading;
            String itemId;
            HudBarDisplay display;
            double gain;
            long lastMoved;
            long expiresAt;
            synchronized (state) {
                reading = state.reading;
                itemId = state.itemId;
                display = state.display;
                gain = state.gain;
                lastMoved = state.lastMovedMs;
                expiresAt = state.expiresAtMs;
            }
            HudBarAsset override = HudBarConfig.getInstance().bySource(id);
            if (expiresAt <= now || (override != null && !override.enabled())) {
                live.remove(id, state);
                continue;
            }
            rows.add(new Row(id, HudBarLook.resolve(id, override, display), itemId, gain, lastMoved, reading));
        }
        return rows;
    }

    /** After a paint, keep one sweep waiting for the earliest expiry still live. */
    private void rearmSweep(long now) {
        long earliest = Long.MAX_VALUE;
        for (LiveBar state : live.values()) {
            synchronized (state) {
                earliest = Math.min(earliest, state.expiresAtMs);
            }
        }
        // Long.MAX_VALUE is both "nothing is live" and "everything live is HELD"; neither wants a
        // sweep, and a held row is sent away by fadeAll rather than by a clock.
        if (earliest != Long.MAX_VALUE) {
            armSweep(earliest, now);
        }
    }

    /**
     * How many rows may be drawn at once: the panel's authored {@code MaxVisible}, and never more
     * than the columns the spot opens can hold, or the rows past that would open a column the spot
     * never allowed. Pure, tested.
     */
    static int slotCap(@Nonnull HudBarPanelAsset panel, @Nonnull HudBarPlacement placement,
            @Nonnull HudBarLayout layout) {
        int reachable = placement.columns(layout.columns()) * layout.slotsPerColumn();
        return Math.max(0, Math.min(panel.maxVisible(layout.totalSlots()), reachable));
    }

    /**
     * Which rows get a slot, and in what order: the {@code maxVisible} most recently moved rows of
     * either kind, so a value that just started moving is never kept off the panel by one that has
     * been idling toward its expiry, drawn in {@link #STACK_ORDER} (fill rows above item rows, then
     * the settled order, then id) so the stack reads the same whichever of them moved last.
     */
    @Nonnull
    static List<Row> choose(@Nonnull List<Row> rows, int maxVisible, int declaredSlots) {
        int slots = Math.max(0, Math.min(maxVisible, declaredSlots));
        List<Row> newest = new ArrayList<>(rows);
        newest.sort(Comparator.comparingLong(Row::lastMovedMs).reversed().thenComparing(STACK_ORDER));
        List<Row> shown = new ArrayList<>(newest.subList(0, Math.min(slots, newest.size())));
        shown.sort(STACK_ORDER);
        return shown;
    }

    /**
     * How many columns {@code rows} spread across at {@code placement}: a new column opens only once
     * the spot's {@code RowsPerColumn} is exceeded, and never more than the spot allows or the
     * document declares. A spot asking for a deeper column than the document has (a spot measured
     * for the tall ledger, picked for the three-deep block) is read at the document's depth, or the
     * rows past that depth would be drawn nowhere. Pure, so the layout can be reasoned about without
     * a client.
     */
    static int columnsFor(int rows, @Nonnull HudBarLayout layout, @Nonnull HudBarPlacement placement) {
        if (rows <= 0) {
            return 1;
        }
        int depth = Math.min(placement.rowsPerColumn(), Math.max(1, layout.slotsPerColumn()));
        int wanted = ceilDiv(rows, depth);
        return Math.max(1, Math.min(wanted, placement.columns(layout.columns())));
    }

    /**
     * How many rows one column takes once {@code rows} are spread across {@code columns}: an even
     * split, rounded up so the last column is the short one, and never past what the document
     * declares per column.
     */
    static int rowsPerColumnFor(int rows, int columns, @Nonnull HudBarLayout layout) {
        if (rows <= 0) {
            return 0;
        }
        return Math.min(ceilDiv(rows, Math.max(1, columns)), layout.slotsPerColumn());
    }

    /**
     * How many columns actually END UP with a row in them once {@code rows} are split
     * {@code perColumn} deep. This is not always what {@link #columnsFor} allowed: four rows across
     * a three-column allowance split two deep, which fills two columns and leaves the third empty,
     * and an empty column must neither be drawn nor counted in the panel's width.
     */
    static int usedColumnsFor(int rows, int perColumn) {
        if (rows <= 0 || perColumn <= 0) {
            return 0;
        }
        return ceilDiv(rows, perColumn);
    }

    /**
     * Which of a column's {@code perColumn} rows the slot at {@code slotRow} draws. Top-down, the
     * slot draws its own ordinal. Bottom-up, for a panel pinned to the screen's bottom edge, the
     * FIRST row goes in the LAST used slot: hidden slots collapse out of the column, so the lowest
     * visible slot sits against the pinned edge, and a row already on screen stays put when the next
     * one opens above it instead of being pushed up by a row appearing underneath.
     */
    static int ordinalInColumn(int slotRow, int perColumn, boolean bottomUp) {
        return bottomUp ? perColumn - 1 - slotRow : slotRow;
    }

    /**
     * Which slot of a column carries the band {@code gap} leaves clear, or -1 for none. The band is
     * the taller row margin of the ONE slot sitting visually just below the split, so it costs no
     * element: {@code AfterRow} counts rows from the pinned edge, and the row just past it is what
     * sits below the split. A column whose first {@code cut} cells are empty draws its rows from row
     * {@code cut + 1}, so it carries the band only when the split falls BETWEEN two of its own rows
     * ({@code cut < AfterRow < cut + rowsDrawn}); a split at or below its first row is space the
     * cut already reserves, and one at or past its last row has nothing to leave a band under (a
     * band above a column's topmost row would be dead space in the frame). Top-down the row just
     * past the split is ordinal {@code AfterRow - cut}, in the slot of the same index; bottom-up,
     * where a column's higher ordinals sit higher on screen, the row just below the split is
     * ordinal {@code AfterRow - cut - 1}, and {@link #ordinalInColumn} puts that in slot
     * {@code perColumn - AfterRow + cut}. No band at all when the gap is absent or either of its
     * numbers is not positive.
     */
    static int gapSlotFor(@Nullable HudBarGap gap, int cut, int rowsDrawn, int perColumn, boolean bottomUp) {
        if (gap == null || !gap.applies() || perColumn <= 0) {
            return -1;
        }
        int afterRow = gap.afterRow();
        if (afterRow <= cut || afterRow >= cut + rowsDrawn) {
            return -1;
        }
        return bottomUp ? perColumn - afterRow + cut : afterRow - cut;
    }

    /**
     * The shape of the column at {@code ordinal} once {@code rows} are split {@code perColumn} deep,
     * measured from the pinned edge. Its cut is what the spot's {@code Cutout} leaves at this
     * ordinal (counted from the panel's own first column, the same index the paint maps onto a
     * slot column for a right-pinned spot), and it draws as many of its rows as the cells past the
     * cut can hold: a column asked to hold more than that draws the ones that fit, exactly as rows
     * past the document's slot ceiling are not drawn. Its reserved space is the cut cells, each as
     * tall as a row with a fill, plus the band when the split falls at or below its first row (the
     * cut swallows the split, so the column's rows all sit above the band); its band is the band
     * where the split falls between two of its own rows ({@link #gapSlotFor}). Pure, so a column's
     * geometry can be checked without a client.
     */
    @Nonnull
    static ColumnShape shapeOf(@Nonnull List<Row> rows, int ordinal, int perColumn, @Nonnull HudBarLayout layout,
            @Nonnull HudBarPlacement placement) {
        int first = ordinal * perColumn;
        int inColumn = Math.max(0, Math.min(perColumn, rows.size() - first));
        HudBarCutout cutout = placement.cutout();
        int cut = cutout == null ? 0 : cutout.rowsAt(ordinal);
        int drawn = Math.max(0, Math.min(inColumn, layout.slotsPerColumn() - cut));
        if (drawn == 0) {
            return ColumnShape.NONE;
        }
        HudBarGap gap = placement.gap();
        int bandPx = gap != null && gap.applies() ? gap.pixels() : 0;
        int reserved = cut * layout.rowHeightPx(true) + (bandPx > 0 && gap.afterRow() <= cut ? bandPx : 0);
        int bandSlot = gapSlotFor(gap, cut, drawn, perColumn, placement.bottomUp());
        int rowsPx = 0;
        for (int i = 0; i < drawn; i++) {
            rowsPx += layout.rowHeightPx(rows.get(first + i).drawsFill());
        }
        return new ColumnShape(drawn, reserved, bandSlot, bandSlot >= 0 ? bandPx : 0, rowsPx);
    }

    /**
     * How far the tallest of {@code used} columns reaches from the pinned edge once {@code rows}
     * are split {@code perColumn} deep: the panel's inner height, which every shorter column is
     * aligned inside ({@link ColumnShape#leadingPx}). Pure.
     */
    static int innerHeightFor(@Nonnull List<Row> rows, int used, int perColumn, @Nonnull HudBarLayout layout,
            @Nonnull HudBarPlacement placement) {
        int tallest = 0;
        for (int column = 0; column < used; column++) {
            tallest = Math.max(tallest, shapeOf(rows, column, perColumn, layout, placement).extentPx());
        }
        return tallest;
    }

    /**
     * How tall the panel is around {@code rows} split {@code perColumn} deep across {@code used}
     * columns: the tallest column's extent ({@link #innerHeightFor}: its cut's reserved space, the
     * band where it carries one, and its rows summed one by one, a row with a fill carrying the
     * bar block and a row about an item not), inside the panel's vertical padding, and never
     * shorter than the spot's floor. Pure, so the height can be checked without a client.
     */
    static int panelHeightFor(@Nonnull List<Row> rows, int used, int perColumn, @Nonnull HudBarLayout layout,
            @Nonnull HudBarPlacement placement) {
        return frameHeightFor(innerHeightFor(rows, used, perColumn, layout, placement), layout, placement);
    }

    /** The panel around an inner height: the padding at both ends, and never below the spot's floor. */
    private static int frameHeightFor(int innerHeightPx, @Nonnull HudBarLayout layout,
            @Nonnull HudBarPlacement placement) {
        return Math.max(layout.panelHeightFor(innerHeightPx), placement.minHeight());
    }

    private static int ceilDiv(int value, int by) {
        int divisor = Math.max(1, by);
        return (value + divisor - 1) / divisor;
    }

    /**
     * Map rows onto the document's fixed slots. Every slot is addressed on every paint - shown ones
     * filled, surplus ones hidden - because a partial update can restyle an element that exists but
     * never add one. A row with no fill hides its bar and is its first line alone. The whole panel
     * goes when nothing is showing or the owner switched it off.
     *
     * <p>Rows fill COLUMN BY COLUMN, so a stack that has grown wide still reads down its first
     * column before continuing at the top of the next: the order rows are chosen in is meaningful,
     * and reading it across the rows instead would scramble it. A column with nothing in it is
     * hidden outright rather than left as an empty gutter, which is what lets one document draw a
     * single narrow column and a wide several-column block without knowing which it is.
     *
     * <p>A panel pinned to the RIGHT edge grows leftward as columns open, so its first column is the
     * RIGHTMOST one, and one pinned to the BOTTOM edge grows upward as rows come up, so each
     * column's first row is its LOWEST: either way, what is already on screen stays where the eye
     * left it when the next thing opens beside or above it.
     *
     * <p>Every column is placed by the margin of its FIRST VISIBLE slot ({@link ColumnShape#leadingPx}:
     * bottom-up the column's shortfall against the panel's inner height, top-down its cut), and the
     * band by the margin of the ONE slot below the split ({@link #gapSlotFor}); what each slot is
     * pushed by is {@link ColumnShape#pushAt}, and a slot that is both carries the two pushes ADDED,
     * never one over the other. A column that draws fewer rows than it was given (its cut left it
     * fewer cells than the split) hides the surplus slots exactly as a column past the document's
     * depth would.
     *
     * <p>{@code look} is the card's colour, resolved by the caller ({@link #cardLook}): the frame is
     * retinted only when it says something, and the dressing in every painted bar follows its
     * opacity ({@link HudBarDressing#at}). {@code animate} is false for the build push, where every
     * row would arrive at once and a leading-edge pulse on all of them reads as noise rather than
     * as movement.
     */
    static void paint(@Nonnull UICommandBuilder cmd, @Nonnull HudBarLayout layout, @Nonnull List<Row> rows,
            @Nonnull HudBarPanelAsset panel, @Nonnull HudBarPlacement placement, @Nonnull HudCardLook look,
            boolean animate) {
        cmd.set(layout.root() + ".Visible", panel.enabled() && !rows.isEmpty());
        int allowed = columnsFor(rows.size(), layout, placement);
        int perColumn = rowsPerColumnFor(rows.size(), allowed, layout);
        int used = Math.min(allowed, usedColumnsFor(rows.size(), perColumn));
        int innerHeight = innerHeightFor(rows, used, perColumn, layout, placement);
        // The panel is only as wide as the columns actually in use, so a single-column stack never
        // draws its background across the gutter a second column would have filled, and only as
        // tall as the rows in them, because an absolutely anchored box has no content to size to.
        cmd.setObject(layout.root() + ".Anchor", placement.position().toAnchor(
                layout.panelWidthFor(Math.max(1, used)),
                frameHeightFor(innerHeight, layout, placement)));
        // The frame's colour is a multiply over the shipped patch, pushed only when something
        // authored one: the identity IS the frame as the document draws it and costs no command.
        UiRetint.retintColor(cmd, layout.root(), look.cardColor());
        HudBarDressing dressing = HudBarDressing.at(look.opacity());
        boolean rightToLeft = placement.rightToLeft();
        boolean bottomUp = placement.bottomUp();
        long now = System.currentTimeMillis();
        for (int slotColumn = 0; slotColumn < layout.columns(); slotColumn++) {
            boolean columnUsed = slotColumn < used;
            cmd.set(layout.columnSelector(slotColumn) + ".Visible", columnUsed);
            int ordinal = rightToLeft ? used - 1 - slotColumn : slotColumn;
            ColumnShape shape = columnUsed
                    ? shapeOf(rows, ordinal, perColumn, layout, placement)
                    : ColumnShape.NONE;
            int firstInColumn = ordinal * perColumn;
            for (int row = 0; row < layout.slotsPerColumn(); row++) {
                String slot = layout.slotSelector(slotColumn, row);
                int inColumn = columnUsed && row < perColumn ? ordinalInColumn(row, perColumn, bottomUp) : -1;
                if (inColumn < 0 || inColumn >= shape.drawn()) {
                    cmd.set(slot + ".Visible", false);
                    continue;
                }
                paintRow(cmd, layout, slot, rows.get(firstInColumn + inColumn),
                        shape.pushAt(row, inColumn, innerHeight, bottomUp), dressing, animate, now);
            }
        }
    }

    /**
     * One slot: its margin (the document's, plus {@code pushPx}: the column's leading space on its
     * first visible slot and the band on the one slot that carries it, added where they are the
     * same slot), its line, its two bar-end captions and its fill, and, when the card is dimmed
     * ({@code dressing} non-null), the well, gloss and shade around the fill at the card's opacity.
     * The margin is pushed on EVERY visible slot, every paint, or a push left on a slot by an
     * earlier spread would survive the rows moving out from under it.
     */
    private static void paintRow(@Nonnull UICommandBuilder cmd, @Nonnull HudBarLayout layout,
            @Nonnull String slot, @Nonnull Row row, int pushPx, @Nullable HudBarDressing dressing,
            boolean animate, long now) {
        HudBarLook look = row.look();
        cmd.set(slot + ".Visible", true);
        cmd.setObject(slot + ".Anchor", rowAnchor(layout, layout.rowMarginPx() + pushPx));
        // .TextSpans, never .Text: a Message on a Label's String sink crashes the client.
        cmd.set(slot + " #Line #Label.TextSpans", look.label());
        boolean hasGain = row.gain() > 0;
        cmd.set(slot + " #Line #Gain.Visible", hasGain);
        if (hasGain) {
            cmd.set(slot + " #Line #Gain.TextSpans", gain(row.gain(), look.countKey()));
        }
        boolean iconShown = IconRenderer.applyIcon(cmd, slot + " #Line", look.icon());
        cmd.set(slot + " #Line #IcoGap.Visible", iconShown);

        boolean fill = row.drawsFill();
        cmd.set(slot + " #Bar.Visible", fill);
        if (!fill) {
            return;
        }
        // The two captions are whatever the reporting mod handed over with the movement: where the
        // reading counts from, and where it counts to. An end with nothing to say is painted BLANK
        // rather than hidden, because hiding it would collapse its width out of the row and shift
        // the track the fill's pixel width is measured against.
        Message lead = look.leadCaption();
        Message trail = look.trailCaption();
        cmd.set(slot + " #Bar #Lead.TextSpans", lead != null ? lead : Msg.raw(""));
        cmd.set(slot + " #Bar #Trail.TextSpans", trail != null ? trail : Msg.raw(""));

        // The fill is a plain retinted Group, not a native ProgressBar: that element carries no
        // colour of its own, and a row's colour is chosen at runtime by whoever moved it. Everything
        // that makes this read as a bar rather than a rectangle - the sunk track, the gloss across
        // its top, the bright leading edge - is colour-agnostic dressing in the document, so any
        // colour at all still comes out looking like a bar.
        String track = slot + " #Bar #Track";
        UiRetint.retintColor(cmd, track + " #Fill", look.color());
        int fillWidth = (int) Math.round(row.reading().fraction() * layout.trackInnerWidthPx());
        cmd.setObject(track + " #Fill.Anchor", fillAnchor(fillWidth));
        // The dressing follows the card: a dimmed card gets a well, a gloss and a shade at the same
        // fraction of their shipped strength, each keeping its own hue, so the bar reads as a bar
        // at any opacity. A card at full opacity pushes nothing and the document's constants stand.
        if (dressing != null) {
            UiRetint.retintColor(cmd, track, dressing.well());
            UiRetint.retintColor(cmd, track + " #Fill #Gloss", dressing.gloss());
            UiRetint.retintColor(cmd, track + " #Fill #Base", dressing.shade());
        }

        // The pulse rides the fill's leading edge for a moment after a movement, so the bar that
        // just moved is the one the eye goes to. A bar sitting still shows nothing.
        boolean pulsing = animate && fillWidth > 0 && now - row.lastMovedMs() <= PULSE_MS;
        cmd.set(track + " #Pulse.Visible", pulsing);
        if (pulsing) {
            UiRetint.retintColor(cmd, track + " #Pulse", look.color());
            cmd.setObject(track + " #Pulse.Anchor", pulseAnchor(fillWidth, layout.trackInnerWidthPx()));
        }
    }

    /**
     * The row's number as a typed numeric param, so each client groups the digits itself, on the row's
     * own key when it named one and the panel's plain "+N" otherwise. A whole number binds as a long,
     * so a value that is only ever integral never grows a decimal point.
     */
    @Nonnull
    static Message gain(double gain, @Nullable String countKey) {
        String key = countKey != null && !countKey.isBlank() ? countKey : GAIN_KEY;
        if (gain == Math.rint(gain) && Math.abs(gain) < Long.MAX_VALUE) {
            return Msg.key(key, (long) gain);
        }
        return Msg.key(key, gain);
    }

    /**
     * The pulse's anchor: a short bright band sitting ON the fill's leading edge, held back at both
     * ends so it never hangs off the track at a nearly empty or a full bar.
     */
    @Nonnull
    private static Anchor pulseAnchor(int fillWidth, int trackWidth) {
        int width = Math.max(1, Math.min(PULSE_WIDTH_PX, fillWidth));
        int left = Math.max(0, Math.min(fillWidth - width, trackWidth - width));
        Anchor a = new Anchor();
        a.setLeft(Value.of(left));
        a.setTop(Value.of(0));
        a.setBottom(Value.of(0));
        a.setWidth(Value.of(width));
        return a;
    }

    /**
     * A slot's anchor: the document's row shape ({@code @RowAnchor}, a top margin and the column's
     * width) with the margin at {@code topPx}, which is the plain margin on every slot but a
     * column's first visible one and the one carrying the band.
     */
    @Nonnull
    private static Anchor rowAnchor(@Nonnull HudBarLayout layout, int topPx) {
        Anchor a = new Anchor();
        a.setTop(Value.of(Math.max(0, topPx)));
        a.setWidth(Value.of(layout.columnWidthPx()));
        return a;
    }

    /** The fill's anchor: pinned to the track's left and both vertical edges, {@code width} wide. */
    @Nonnull
    private static Anchor fillAnchor(int width) {
        Anchor a = new Anchor();
        a.setLeft(Value.of(0));
        a.setTop(Value.of(0));
        a.setBottom(Value.of(0));
        a.setWidth(Value.of(Math.max(0, width)));
        return a;
    }

    /** Whether {@code rowId} is live on this panel right now; for a test reading the state. */
    boolean isLive(@Nonnull String rowId) {
        return live.containsKey(rowId);
    }

    /** Forget a row the panel should no longer draw; the next paint hides its slot. */
    void forget(@Nullable String rowId) {
        if (rowId != null) {
            live.remove(rowId);
        }
    }
}
