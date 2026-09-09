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
import com.ziggfreed.common.ui.hud.KeyedCustomHud;
import com.ziggfreed.common.ui.hud.RepaintCoalescer;
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
 * move brought; overrides, from {@link HudBarConfig}; and a panel, from {@link HudBarPanelConfig}.
 * It asks nothing of anyone at paint time and knows nothing about what any value measures. A
 * consumer never touches this class: it calls {@link HudBars#moved} or {@link HudBars#itemMoved}.
 *
 * <p><b>How it paints.</b> A move records the gain and starts the row's linger clock, then asks for
 * a paint. Paints are folded per tick ({@link RepaintCoalescer}) and held to one per the panel's
 * authored {@code RepaintMs}. That window is a leading edge with a trailing flush, not a plain
 * throttle: a move arriving when the window is already open redraws at once, and one arriving
 * inside it is drawn at the window's end rather than dropped, so the number on screen is never
 * waiting on a LATER move to bring it up to date. One sweep is armed at a time, at the earliest
 * linger expiry, and it re-arms itself while anything is still live, so a row goes away on time with
 * no tick anywhere. Everything that touches the player runs on their world thread.
 *
 * <p><b>The document.</b> Slots are declared up front in columns ({@code #ZigBarCol<c>} holding
 * {@code #ZigBar<c>_<r>}), addressed by index and hidden when surplus, because a partial update can
 * restyle an element that exists but never add one. Each slot is a {@code #Line} (icon pair,
 * {@code #Label}, {@code #Gain}) over a {@code #Bar} holding the two end captions and a
 * {@code #Track} of {@code #Fill} and {@code #Pulse}. Paths and ids are prefixed {@code Zig}
 * because the client's UI namespace is flat across mods. Text lands on {@code .TextSpans}, the fill
 * and the pulse are {@code Anchor} width pushes and their colour a {@code .Background.Color}
 * retint, so no per-colour texture is shipped for a bar.
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

    /** Which panel this is: its document, its element names, its declared slots and its default corner. */
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

    /** Unused: content-sized, so the anchor omits Height. */
    @Override
    protected int panelHeight() {
        return 0;
    }

    @Override
    protected boolean usesContentHeight() {
        return true;
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
        return panel().position(layout.defaultPosition());
    }

    /** The first paint, inside the native {@code addCustomHud}: the document, its position, and no rows. */
    @Override
    protected final void build(@Nonnull UICommandBuilder cmd) {
        cmd.append(layout.template());
        applyConfiguredPosition(cmd);
        paint(cmd, layout, List.of(), panel(), configuredPosition(), false);
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

    /** World thread: drop what has expired, read what is left, draw, and keep a sweep armed while anything lives. */
    private void paintNow() {
        try {
            markPushed();
            long now = System.currentTimeMillis();
            List<Row> rows = collectLive(now);
            HudBarPanelAsset panel = panel();
            UICommandBuilder cmd = new UICommandBuilder();
            paint(cmd, layout, choose(rows, panel.maxVisible(layout.totalSlots()), layout.totalSlots()),
                    panel, configuredPosition(), true);
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
     * How many columns {@code rows} spread across on {@code panel}: a new column opens only once
     * the authored {@code RowsPerColumn} is exceeded, and never more than the document declares.
     * Pure, so the layout can be reasoned about without a client.
     */
    static int columnsFor(int rows, @Nonnull HudBarLayout layout, @Nonnull HudBarPanelAsset panel) {
        if (rows <= 0) {
            return 1;
        }
        int wanted = ceilDiv(rows, panel.rowsPerColumn());
        return Math.max(1, Math.min(wanted, panel.columns(layout.columns())));
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
     * <p>Rows fill COLUMN BY COLUMN, so a stack that has grown wide still reads top-to-bottom down
     * its first column before continuing at the top of the next: the order rows are chosen in is
     * meaningful, and reading it across the rows instead would scramble it. A column with nothing in
     * it is hidden outright rather than left as an empty gutter, which is what lets one document
     * draw a single narrow column and a wide several-column block without knowing which it is.
     *
     * <p>{@code animate} is false for the build push, where every row would arrive at once and a
     * leading-edge pulse on all of them reads as noise rather than as movement.
     */
    static void paint(@Nonnull UICommandBuilder cmd, @Nonnull HudBarLayout layout, @Nonnull List<Row> rows,
            @Nonnull HudBarPanelAsset panel, @Nullable HudPosition position, boolean animate) {
        cmd.set(layout.root() + ".Visible", panel.enabled() && !rows.isEmpty());
        int allowed = columnsFor(rows.size(), layout, panel);
        int perColumn = rowsPerColumnFor(rows.size(), allowed, layout);
        int used = usedColumnsFor(rows.size(), perColumn);
        if (position != null) {
            // The panel is only as wide as the columns actually in use, so a single-column stack
            // never draws its background across the gutter a second column would have filled.
            cmd.setObject(layout.root() + ".Anchor",
                    position.toAnchorContentHeight(layout.panelWidthFor(Math.max(1, used))));
        }
        // A panel pinned to the RIGHT edge grows leftward as columns open, so its first column has
        // to be the RIGHTMOST one: filling left-to-right there would shift every column already on
        // screen sideways the moment a new one opened, and drag the rows out from under the eye
        // reading them. A left-pinned panel grows the other way and fills the ordinary way.
        boolean rightToLeft = position != null
                && position.getHorizontalEdge() == HudPosition.HorizontalEdge.RIGHT;
        long now = System.currentTimeMillis();
        for (int slotColumn = 0; slotColumn < layout.columns(); slotColumn++) {
            boolean columnUsed = slotColumn < used;
            cmd.set(layout.columnSelector(slotColumn) + ".Visible", columnUsed);
            int ordinal = rightToLeft ? used - 1 - slotColumn : slotColumn;
            int firstInColumn = ordinal * perColumn;
            for (int row = 0; row < layout.slotsPerColumn(); row++) {
                String slot = layout.slotSelector(slotColumn, row);
                int index = firstInColumn + row;
                if (!columnUsed || row >= perColumn || index >= rows.size()) {
                    cmd.set(slot + ".Visible", false);
                    continue;
                }
                paintRow(cmd, layout, slot, rows.get(index), animate, now);
            }
        }
    }

    /** One slot: its line, its two bar-end captions and its fill. */
    private static void paintRow(@Nonnull UICommandBuilder cmd, @Nonnull HudBarLayout layout,
            @Nonnull String slot, @Nonnull Row row, boolean animate, long now) {
        HudBarLook look = row.look();
        cmd.set(slot + ".Visible", true);
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
