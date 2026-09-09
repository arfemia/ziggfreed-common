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
 * One player's progress-bar panel: a minimal, semi-transparent stack of up to
 * {@value HudBarPanelAsset#MAX_SLOTS} rows, drawn only while the values behind them are moving.
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
 * <p><b>How it paints.</b> A move records the gain and starts the row's linger clock, then asks
 * for a paint. Paints are folded per tick ({@link RepaintCoalescer}) and held to one every
 * {@value #REPAINT_INTERVAL_MS} ms, with the last change always painted (a paint that arrives inside
 * the window is deferred to the window's end rather than dropped). One sweep is armed at a time, at
 * the earliest linger expiry, and it re-arms itself while anything is still live, so a row goes
 * away on time with no tick anywhere. Everything that touches the player runs on their world thread.
 *
 * <p>The document is {@code Hud/ZigHudBars.ui}: four slots {@code #ZigBar0..3}, each a
 * {@code #Line} (icon pair, {@code #Label}, {@code #Gain}) over a {@code #Track} holding the
 * {@code #Fill}. Slots are addressed by index and surplus ones hidden, so a repaint never
 * re-appends. The path and the ids are prefixed {@code Zig} because the client's UI namespace is flat
 * across mods. Text lands on {@code .TextSpans}, the fill is an {@code Anchor} width push and the
 * colour a {@code .Background.Color} retint, so no texture is shipped for it.
 */
public final class HudBarHud extends KeyedCustomHud {

    /** The HUD's key on the native per-player {@code HudManager}, under this library's own id. */
    public static final String HUD_KEY = "ziggfreedcommon:hud_bars";

    static final String TEMPLATE = "Hud/ZigHudBars.ui";
    static final String ROOT = "#ZigHudBarsPanel";

    /** Panel WIDTH in pixels; must match {@code #ZigHudBarsPanel}'s anchor in the document. Content-sized vertically. */
    static final int PANEL_WIDTH_PX = 320;

    /**
     * The fill's full width: the row width (the panel less its horizontal padding) less the track's
     * own one-pixel padding each side. Must match {@code #Track} in the document.
     */
    static final int TRACK_INNER_WIDTH_PX = 294;

    /** At most one paint per this many milliseconds per player; the last change is never dropped. */
    static final long REPAINT_INTERVAL_MS = 100L;

    /** A sweep fires this much after the earliest expiry, so a clock read a hair early still finds it past. */
    private static final long SWEEP_SLACK_MS = 20L;

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

    public HudBarHud(@Nonnull PlayerRef playerRef) {
        super(playerRef, HUD_KEY);
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
    protected String rootSelector() {
        return ROOT;
    }

    @Override
    protected int panelWidth() {
        return PANEL_WIDTH_PX;
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

    @Override
    protected long updateIntervalMs() {
        return REPAINT_INTERVAL_MS;
    }

    @Nonnull
    @Override
    protected HudPosition configuredPosition() {
        return HudBarPanelConfig.getInstance().current().position();
    }

    /** The first paint, inside the native {@code addCustomHud}: the document, its position, and no rows. */
    @Override
    protected void build(@Nonnull UICommandBuilder cmd) {
        cmd.append(TEMPLATE);
        applyConfiguredPosition(cmd);
        paint(cmd, List.of(), HudBarPanelConfig.getInstance().current().enabled());
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
            @Nonnull HudBarDisplay display) {
        long now = System.currentTimeMillis();
        long linger = HudBarLook.resolve(rowId, HudBarConfig.getInstance().bySource(rowId), display).lingerMs();
        LiveBar state = live.computeIfAbsent(rowId, id -> new LiveBar());
        long expiresAt;
        synchronized (state) {
            state.reading = reading;
            state.itemId = itemId;
            state.display = display;
            state.gain += delta;
            state.lastMovedMs = now;
            state.expiresAtMs = now + linger;
            expiresAt = state.expiresAtMs;
        }
        requestPaint(now);
        armSweep(expiresAt, now);
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
            HudBarPanelAsset panel = HudBarPanelConfig.getInstance().current();
            UICommandBuilder cmd = new UICommandBuilder();
            paint(cmd, choose(rows, panel.maxVisible()), panel.enabled());
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
    static List<Row> choose(@Nonnull List<Row> rows, int maxVisible) {
        int slots = Math.max(0, Math.min(maxVisible, HudBarPanelAsset.MAX_SLOTS));
        List<Row> newest = new ArrayList<>(rows);
        newest.sort(Comparator.comparingLong(Row::lastMovedMs).reversed().thenComparing(STACK_ORDER));
        List<Row> shown = new ArrayList<>(newest.subList(0, Math.min(slots, newest.size())));
        shown.sort(STACK_ORDER);
        return shown;
    }

    /**
     * Map rows onto the document's fixed slots. Every slot is addressed on every paint - shown ones
     * filled, surplus ones hidden - because a partial update can restyle an element that exists but
     * never add one. A row with no fill hides its track and is its first line alone. The whole
     * panel goes when nothing is showing or the owner switched it off.
     */
    static void paint(@Nonnull UICommandBuilder cmd, @Nonnull List<Row> rows, boolean panelEnabled) {
        cmd.set(ROOT + ".Visible", panelEnabled && !rows.isEmpty());
        for (int i = 0; i < HudBarPanelAsset.MAX_SLOTS; i++) {
            String slot = "#ZigBar" + i;
            if (i >= rows.size()) {
                cmd.set(slot + ".Visible", false);
                continue;
            }
            Row row = rows.get(i);
            HudBarLook look = row.look();
            cmd.set(slot + ".Visible", true);
            // .TextSpans, never .Text: a Message on a Label's String sink crashes the client.
            cmd.set(slot + " #Line #Label.TextSpans", look.label());
            boolean hasGain = row.gain() > 0;
            cmd.set(slot + " #Line #Gain.Visible", hasGain);
            if (hasGain) {
                cmd.set(slot + " #Line #Gain.TextSpans", gain(row.gain()));
            }
            boolean iconShown = IconRenderer.applyIcon(cmd, slot + " #Line", look.icon());
            cmd.set(slot + " #Line #IcoGap.Visible", iconShown);
            boolean fill = row.drawsFill();
            cmd.set(slot + " #Track.Visible", fill);
            if (fill) {
                UiRetint.retintColor(cmd, slot + " #Track #Fill", look.color());
                int fillWidth = (int) Math.round(row.reading().fraction() * TRACK_INNER_WIDTH_PX);
                cmd.setObject(slot + " #Track #Fill.Anchor", fillAnchor(fillWidth));
            }
        }
    }

    /**
     * The gain as a typed numeric param on the shared key, so each client groups the digits itself. A
     * whole number binds as a long, so a value that is only ever integral never grows a decimal point.
     */
    @Nonnull
    static Message gain(double gain) {
        if (gain == Math.rint(gain) && Math.abs(gain) < Long.MAX_VALUE) {
            return Msg.key(GAIN_KEY, (long) gain);
        }
        return Msg.key(GAIN_KEY, gain);
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
