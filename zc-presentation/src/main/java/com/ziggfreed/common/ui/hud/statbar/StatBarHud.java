package com.ziggfreed.common.ui.hud.statbar;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.progress.gate.GateQuery;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.progress.gate.StoreGates;
import com.ziggfreed.common.resource.ResourceCosts;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.UiRetint;
import com.ziggfreed.common.ui.hud.HudPosition;
import com.ziggfreed.common.ui.hud.HudPreferences;
import com.ziggfreed.common.ui.hud.KeyedCustomHud;
import com.ziggfreed.common.ui.hud.panel.HudSpotPosition;
import com.ziggfreed.common.util.SafeLog;

/**
 * One player's stat bars: every bar file in force ({@link StatBarConfig}) whose place is on their screen and whose
 * {@code ShowWhen} holds for them, painted into the positional slots of {@code Hud/ZigStatBars.ui} in order, at most
 * {@link StatBarLayout#SLOTS}. Repainted a few times a second by {@link StatBarTickSystem} (a pool refills between
 * any two moments a mod could report), and at once through {@link StatBars#refresh} after a spend; each paint pushes
 * only what changed since the last one, so a full bar standing still costs nothing on the wire.
 *
 * <p>A bar beside another HUD reads that HUD at every paint ({@link KeyedCustomHud#currentPosition},
 * {@link KeyedCustomHud#showing}, its box and the width of what it shows), so an owner moving it, a player hiding it
 * or a slot coming and going moves or hides the bar on the next paint. A standing bar follows the player's own switch
 * that hides every HUD panel ({@link HudPreferences#isHideAll}).
 */
public final class StatBarHud extends KeyedCustomHud {

    /** The HUD's key on each player's {@code HudManager}. */
    public static final String HUD_KEY = "ziggfreedcommon.stat_bars";

    /** The document, under {@code Common/UI/Custom/}. */
    static final String DOCUMENT = "Hud/ZigStatBars.ui";

    /** The full-viewport wrapper the slots sit in. */
    static final String ROOT = "#ZigStatBarsRoot";

    /** A slot's id is this and its index. */
    static final String SLOT_PREFIX = "#ZigStatBar";

    /** The figure's key: the whole value, a typed number grouped by each player's own client. */
    static final String FIGURE_KEY = "ziggfreedcommon.ui.hud.statbar.figure";

    /** How often a bar is read and repainted at most: five times a second, the cadence the ability HUD keeps. */
    private static final long PAINT_INTERVAL_MS = 200L;

    /** Bars that met a colour nobody could read, named once each. */
    private static final Set<String> WARNED_COLOURS = ConcurrentHashMap.newKeySet();

    /** One slot as last pushed. */
    private record Slot(boolean visible, @Nullable StatBarLayout.Box box, @Nullable String labelOf, long figure,
            int fill, @Nullable String colour) {

        static final Slot HIDDEN = new Slot(false, null, null, -1L, -1, null);
    }

    /** One slot as the next paint wants it, with the label to push when its source moved. */
    private record Want(@Nonnull Slot slot, @Nullable StatBarAsset bar) {
    }

    private final Slot[] pushed = new Slot[StatBarLayout.SLOTS];
    private volatile boolean forceNext = true;

    public StatBarHud(@Nonnull PlayerRef playerRef) {
        super(playerRef, HUD_KEY);
    }

    @Nonnull
    @Override
    protected String rootSelector() {
        return ROOT;
    }

    /** The wrapper fills the screen; each slot carries its own place, so this HUD has no box of its own. */
    @Override
    protected int panelWidth() {
        return 0;
    }

    @Override
    protected int panelHeight() {
        return 0;
    }

    @Override
    protected long updateIntervalMs() {
        return PAINT_INTERVAL_MS;
    }

    /** Unused: no slot follows this HUD's own position, and its wrapper is never re-anchored. */
    @Nonnull
    @Override
    protected HudPosition configuredPosition() {
        return StatBarLayout.STANDING_FALLBACK;
    }

    @Override
    protected void build(@Nonnull UICommandBuilder commandBuilder) {
        commandBuilder.append(DOCUMENT);
        forceNext = true;
    }

    /** The next paint pushes every slot whole (an owner reload, a fresh document). Any thread. */
    void forceRepaint() {
        forceNext = true;
    }

    /**
     * Paint this player's bars if the interval has run ({@code now} past it) or {@code force}. World thread: reads the
     * player's stats, their HUDs and their gates.
     */
    void paint(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref,
            @Nonnull Player player, boolean force) {
        PlayerRef playerRef = getPlayerRef();
        if (!acquirePush(force || forceNext)) {
            return;
        }
        if (coveredByPage(playerRef) && !forceNext) {
            return; // the client draws no HUD under a page; the first paint after it closes catches up
        }
        boolean whole = forceNext;
        forceNext = false;
        Want[] wants = plan(accessor, ref, player, playerRef);
        UICommandBuilder cmd = new UICommandBuilder();
        boolean dirty = false;
        for (int i = 0; i < StatBarLayout.SLOTS; i++) {
            dirty |= paintSlot(cmd, i, wants[i], whole);
        }
        if (dirty) {
            update(false, cmd);
        }
    }

    /** What each slot should show now, in order: the bars that are on this player's screen, then empty slots. */
    @Nonnull
    private Want[] plan(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref,
            @Nonnull Player player, @Nonnull PlayerRef playerRef) {
        Want[] wants = new Want[StatBarLayout.SLOTS];
        Map<String, Integer> stacked = new HashMap<>();
        int used = 0;
        List<StatBarAsset> bars = StatBarConfig.getInstance().enabledInOrder();
        for (StatBarAsset bar : bars) {
            if (used == StatBarLayout.SLOTS) {
                break;
            }
            StatBarPlacement placement = bar.placement();
            String stat = bar.stat();
            if (placement == null || stat == null) {
                continue;
            }
            ResourceCosts.Level level = ResourceCosts.level(accessor, ref, stat);
            if (level == null) {
                continue;
            }
            String hostKey = placement.beside();
            KeyedCustomHud host = hostKey == null || HUD_KEY.equalsIgnoreCase(hostKey)
                    ? null : KeyedCustomHud.get(player, hostKey, KeyedCustomHud.class);
            HudPosition hostAt = host == null ? null : host.currentPosition();
            StatBarLayout.Box box;
            if (host != null) {
                if (!host.showing() || hostAt == null || host.boxHeight() <= 0 || !shows(bar, playerRef, player)) {
                    continue;
                }
                String side = hostKey.toLowerCase(Locale.ROOT) + (placement.below() ? "|below" : "|above");
                int before = stacked.merge(side, 1, Integer::sum) - 1;
                box = StatBarLayout.beside(hostAt, host.boxWidth(), host.boxHeight(), host.contentWidth(),
                        placement.below(), placement.gap(), before);
            } else {
                HudSpotPosition position = placement.position();
                if (position == null || HudPreferences.isHideAll(playerRef) || !shows(bar, playerRef, player)) {
                    continue;
                }
                box = StatBarLayout.standing(position.over(StatBarLayout.STANDING_FALLBACK), placement.width());
            }
            String colour = colourOf(bar);
            int fill = StatBarLayout.fillWidth(box.barWidth(), level.fraction());
            Slot slot = new Slot(true, box, labelSource(bar), StatBarLayout.figure(level.value()), fill, colour);
            wants[used++] = new Want(slot, bar);
        }
        for (int i = used; i < StatBarLayout.SLOTS; i++) {
            wants[i] = new Want(Slot.HIDDEN, null);
        }
        return wants;
    }

    /** Push what changed in slot {@code index}; whether anything was pushed. */
    private boolean paintSlot(@Nonnull UICommandBuilder cmd, int index, @Nonnull Want want, boolean whole) {
        Slot last = whole || pushed[index] == null ? null : pushed[index];
        Slot next = want.slot();
        String sel = SLOT_PREFIX + index;
        boolean dirty = false;
        if (!next.visible()) {
            if (last == null || last.visible()) {
                cmd.set(sel + ".Visible", false);
                dirty = true;
            }
            pushed[index] = Slot.HIDDEN;
            return dirty;
        }
        StatBarLayout.Box box = Objects.requireNonNull(next.box());
        if (last == null || !box.equals(last.box())) {
            cmd.setObject(sel + ".Anchor", box.anchor());
            cmd.setObject(sel + " #Lead.Anchor", box.leadAnchor());
            cmd.setObject(sel + " #Bar.Anchor", box.barAnchor());
            dirty = true;
        }
        if ((last == null || !Objects.equals(next.labelOf(), last.labelOf())) && want.bar() != null) {
            cmd.set(sel + " #Bar #Label.TextSpans", want.bar().label());
            dirty = true;
        }
        if (last == null || next.figure() != last.figure()) {
            cmd.set(sel + " #Bar #Figure.TextSpans", Msg.key(FIGURE_KEY, next.figure()));
            dirty = true;
        }
        if (last == null || next.fill() != last.fill()) {
            cmd.setObject(sel + " #Bar #Track #Fill.Anchor", StatBarLayout.fillAnchor(next.fill()));
            dirty = true;
        }
        if (last == null || !Objects.equals(next.colour(), last.colour())) {
            UiRetint.retintColor(cmd, sel + " #Bar #Track #Fill", next.colour());
            dirty = true;
        }
        if (last == null || !last.visible()) {
            cmd.set(sel + ".Visible", true);
            dirty = true;
        }
        pushed[index] = next;
        return dirty;
    }

    /** What a slot's label is read from: the bar and its key, so a slot taken by another bar relabels. */
    @Nonnull
    private static String labelSource(@Nonnull StatBarAsset bar) {
        return bar.getId() + "|" + bar.labelKey();
    }

    /** {@code bar}'s fill colour, else the kit's active fill, its unreadable value named once. */
    @Nonnull
    private static String colourOf(@Nonnull StatBarAsset bar) {
        String colour = StatBarLayout.colour(bar.color());
        if (colour != null) {
            return colour;
        }
        if (bar.color() != null && WARNED_COLOURS.add(bar.getId() + "|" + bar.color())) {
            SafeLog.warn("[hud] the stat bar '" + bar.getId() + "' names the colour '" + bar.color() + "', which is"
                    + " neither six hex digits nor a kit colour, so it is drawn in the kit's active blue");
        }
        return StatBarLayout.fallbackColour();
    }

    /** Does {@code bar}'s {@code ShowWhen} hold for this player? Nothing written holds; no player, or a throw, fails. */
    private static boolean shows(@Nonnull StatBarAsset bar, @Nonnull PlayerRef playerRef, @Nonnull Player player) {
        GateSpec when = bar.showWhen();
        if (when == null) {
            return true;
        }
        Subject subject = Subject.of(playerRef, player);
        if (subject == null) {
            return false;
        }
        try {
            return StoreGates.passes(when, GateQuery.of(subject));
        } catch (Throwable t) {
            SafeLog.fine("[hud] the stat bar '" + bar.getId() + "' could not ask its ShowWhen: " + t.getMessage());
            return false;
        }
    }
}
