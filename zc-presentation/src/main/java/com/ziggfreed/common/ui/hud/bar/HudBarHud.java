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
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.ui.UiRetint;
import com.ziggfreed.common.ui.hud.HudPosition;
import com.ziggfreed.common.ui.hud.KeyedCustomHud;
import com.ziggfreed.common.ui.hud.RepaintCoalescer;
import com.ziggfreed.common.ui.icon.IconRenderer;
import com.ziggfreed.common.util.SafeLog;

/**
 * One player's progress-bar panel: a minimal, semi-transparent stack of up to
 * {@value HudBarPanelAsset#MAX_SLOTS} bars, each two lines - the bar's name with the gain that has
 * landed since it came up, and the fill underneath - drawn only while the value behind it is moving.
 *
 * <p><b>What it knows.</b> Bars, from {@link HudBarConfig}; a panel, from {@link HudBarPanelConfig};
 * and readings, from whichever {@link HudBarSource} a bar's namespace registered. It knows nothing
 * about what any value measures. A consumer never touches this class: it ships bar files and calls
 * {@link HudBars#moved} when a value changes.
 *
 * <p><b>How it paints.</b> A change records the gain and starts the bar's linger clock, then asks
 * for a paint. Paints are folded per tick ({@link RepaintCoalescer}) and held to one every
 * {@value #REPAINT_INTERVAL_MS} ms, with the last change always painted (a paint that arrives inside
 * the window is deferred to the window's end rather than dropped). One sweep is armed at a time, at
 * the earliest linger expiry, and it re-arms itself while anything is still live, so a bar goes
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

    private final Map<String, LiveBar> live = new ConcurrentHashMap<>();
    private final RepaintCoalescer coalescer = new RepaintCoalescer(this::paintNow);
    private final AtomicBoolean paintDeferred = new AtomicBoolean();
    private final AtomicBoolean sweepArmed = new AtomicBoolean();

    public HudBarHud(@Nonnull PlayerRef playerRef) {
        super(playerRef, HUD_KEY);
    }

    /** One bar's moving state: the gain since it came up and when it goes away. Mutated under its own lock. */
    private static final class LiveBar {
        double gain;
        long lastMovedMs;
        long expiresAtMs;
    }

    /** What one drawn slot needs: the bar, its live state, and the reading behind it. */
    record Row(@Nonnull HudBarAsset bar, double gain, long lastMovedMs, @Nonnull HudBarSource.Reading reading) {
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

    /** The first paint, inside the native {@code addCustomHud}: the document, its position, and no bars. */
    @Override
    protected void build(@Nonnull UICommandBuilder cmd) {
        cmd.append(TEMPLATE);
        applyConfiguredPosition(cmd);
        paint(cmd, List.of(), HudBarPanelConfig.getInstance().current().enabled());
    }

    // ==================== a value moved ====================

    /**
     * {@code bar}'s value changed by {@code delta}: add it to the gain shown since the bar came up,
     * restart the bar's linger, and paint. Any thread; the paint runs on the player's world thread.
     */
    public void moved(@Nonnull HudBarAsset bar, double delta) {
        long now = System.currentTimeMillis();
        LiveBar state = live.computeIfAbsent(bar.getId(), id -> new LiveBar());
        long expiresAt;
        synchronized (state) {
            state.gain += delta;
            state.lastMovedMs = now;
            state.expiresAtMs = now + bar.lingerMs();
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
     * World thread: every live bar with a reading. A bar whose linger ran out, or whose file is gone
     * or switched off, is forgotten here; one whose source declines this time is kept live but not
     * drawn, so it still comes back if the source answers on the next move.
     */
    @Nonnull
    private List<Row> collectLive(long now) {
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<String, LiveBar> entry : live.entrySet()) {
            LiveBar state = entry.getValue();
            double gain;
            long lastMoved;
            long expiresAt;
            synchronized (state) {
                gain = state.gain;
                lastMoved = state.lastMovedMs;
                expiresAt = state.expiresAtMs;
            }
            HudBarAsset bar = HudBarConfig.getInstance().resolve(entry.getKey());
            if (expiresAt <= now || bar == null || !bar.enabled()) {
                live.remove(entry.getKey(), state);
                continue;
            }
            HudBarSource.Reading reading = HudBarSources.read(getPlayerRef(), bar);
            if (reading != null) {
                rows.add(new Row(bar, gain, lastMoved, reading));
            }
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
     * Which rows get a slot, and in what order: the {@code maxVisible} most recently moved bars, so a
     * value that just started moving is never kept off the panel by one that has been idling toward
     * its expiry, drawn in their authored {@link HudBarAsset#order()} (then id) so the stack reads
     * the same whichever of them moved last.
     */
    @Nonnull
    static List<Row> choose(@Nonnull List<Row> rows, int maxVisible) {
        int slots = Math.max(0, Math.min(maxVisible, HudBarPanelAsset.MAX_SLOTS));
        List<Row> newest = new ArrayList<>(rows);
        newest.sort(Comparator.comparingLong(Row::lastMovedMs).reversed()
                .thenComparing(Row::bar, HudBarConfig::byOrderThenId));
        List<Row> shown = new ArrayList<>(newest.subList(0, Math.min(slots, newest.size())));
        shown.sort(Comparator.comparing(Row::bar, HudBarConfig::byOrderThenId));
        return shown;
    }

    /**
     * Map rows onto the document's fixed slots. Every slot is addressed on every paint - shown ones
     * filled, surplus ones hidden - because a partial update can restyle an element that exists but
     * never add one. The whole panel goes when nothing is showing or the owner switched it off.
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
            HudBarAsset bar = row.bar();
            cmd.set(slot + ".Visible", true);
            // .TextSpans, never .Text: a Message on a Label's String sink crashes the client.
            cmd.set(slot + " #Line #Label.TextSpans", label(bar));
            boolean hasGain = row.gain() > 0;
            cmd.set(slot + " #Line #Gain.Visible", hasGain);
            if (hasGain) {
                cmd.set(slot + " #Line #Gain.TextSpans", gain(row.gain()));
            }
            boolean iconShown = IconRenderer.applyIcon(cmd, slot + " #Line", bar.icon());
            cmd.set(slot + " #Line #IcoGap.Visible", iconShown);
            UiRetint.retintColor(cmd, slot + " #Track #Fill", bar.color());
            int fillWidth = (int) Math.round(row.reading().fraction() * TRACK_INNER_WIDTH_PX);
            cmd.setObject(slot + " #Track #Fill.Anchor", fillAnchor(fillWidth));
        }
    }

    /** The bar's name, resolved on the client; its own id when the file names no key. */
    @Nonnull
    private static Message label(@Nonnull HudBarAsset bar) {
        String key = bar.labelKey();
        return key != null ? ContentKeys.tr(key) : Msg.raw(bar.getId());
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

    /** Whether {@code barId} is live on this panel right now; for a test reading the state. */
    boolean isLive(@Nonnull String barId) {
        return live.containsKey(barId);
    }

    /** Forget a bar the panel should no longer draw; the next paint hides its slot. */
    void forget(@Nullable String barId) {
        if (barId != null) {
            live.remove(barId);
        }
    }
}
