package com.ziggfreed.common.objectives.hud;

import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.objectives.hud.TrackedQuestSnapshot.Block;
import com.ziggfreed.common.objectives.hud.TrackedQuestSnapshot.Row;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.UiText;
import com.ziggfreed.common.ui.hud.HudPosition;
import com.ziggfreed.common.ui.hud.KeyedCustomHud;
import com.ziggfreed.common.util.SafeLog;

/**
 * The in-world tracker of a player's pinned quests, drawn to match the native objective HUD: a
 * content-sized top-right panel that hugs only the tracked quests, one uppercase heading per quest,
 * and per-objective rows of a default/complete glyph, the objective's line and a right-aligned
 * count. Every word comes from the shared runtime - titles and lines through the registered text
 * sources - so this panel can never drift from the quest log that pinned the quest.
 *
 * <p><b>It repaints on the quest engine's own native events and on nothing else.</b> There is no
 * tick: a pin, an accept, an objective moving, a completion, a claim and an abandon each fire an
 * {@code IEvent} the engine already announces (the pin one included), {@link TrackedQuestHuds}
 * listens, and this repaints. A burst of events in one tick paints once
 * ({@link RepaintCoalescer}). Two things no quest event covers - a player hiding the HUD for
 * themselves, a rule of the world they walked into - are the consumer's to know about, and it
 * pushes {@link TrackedQuestHuds#repaint(PlayerRef)} from those two sites.
 *
 * <p><b>World thread.</b> A repaint may be ASKED for from any thread; the paint itself always runs
 * on the player's own world thread, because reading the tracked state resolves the player's
 * entity. {@link #repaint()} queues the paint on that world and returns.
 *
 * <p>The document is {@code Hud/ZigQuestTracker.ui}, five quest blocks ({@code #ZigQuest0..4}) of
 * four rows each ({@code #ObjRow0..3}, each an {@code #IcoDefault}{i}/{@code #IcoComplete}{i}
 * glyph pair, an {@code #Obj}{i} label and a {@code #Count}{i} label). Slots are addressed by
 * index and surplus ones hidden, so a repaint never re-appends. The document path and every
 * top-level id are prefixed {@code Zig} because the client's UI namespace is flat across mods; a
 * generic name can be clobbered by a co-installed mod's document, after which the anchor set in
 * {@code build()} fails and disconnects the player.
 */
public final class TrackedQuestHud extends KeyedCustomHud implements TrackedQuestHuds.Tracker {

    /** The HUD's key on the native per-player {@code HudManager}, under this library's own id. */
    public static final String HUD_KEY = "ziggfreedcommon:quest_tracker";

    static final String TEMPLATE = "Hud/ZigQuestTracker.ui";
    static final String ROOT = "#ZigQuestHudPanel";

    /**
     * Panel WIDTH in pixels; must match {@code #ZigQuestHudPanel}'s anchor in the document (the
     * native ObjectivePanel is 350 wide). Content-sized vertically ({@link #usesContentHeight()}).
     */
    private static final int PANEL_WIDTH_PX = 350;

    private final RepaintCoalescer coalescer = new RepaintCoalescer(this::paintNow);

    /** What the last paint showed, read off-thread by the objective-event pre-filter. */
    private volatile Set<String> shownQuestIds = null;

    public TrackedQuestHud(@Nonnull PlayerRef playerRef) {
        super(playerRef, HUD_KEY);
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

    /** No throttle window: repaints are event-driven and folded per tick by the coalescer. */
    @Override
    protected long updateIntervalMs() {
        return 0L;
    }

    @Nonnull
    @Override
    protected HudPosition configuredPosition() {
        return TrackedQuestHuds.resolvedDeps().position();
    }

    /**
     * The first paint, on the world thread inside the native {@code addCustomHud}: the document,
     * its position, the consumer's theme, and the live state - so a player who connects while the
     * owner has the tracker off, or with nothing pinned, never sees the document's default panel.
     */
    @Override
    protected void build(@Nonnull UICommandBuilder cmd) {
        cmd.append(TEMPLATE);
        applyConfiguredPosition(cmd);
        TrackedQuestHudDeps deps = TrackedQuestHuds.resolvedDeps();
        deps.paintTheme(cmd, ROOT);
        paint(cmd, snapshot(deps), deps);
    }

    // ==================== repaint ====================

    /** Queue a paint on this player's world thread, folded with any other request this tick. Any thread. */
    @Override
    public void repaint() {
        World world = worldOf(getPlayerRef());
        if (world != null) {
            coalescer.request(world);
        }
    }

    /**
     * Is {@code questId} on the tracker as last painted? True before any paint, so an event for a
     * quest whose pin has not been drawn yet still repaints rather than being filtered away.
     */
    @Override
    public boolean shows(@Nonnull String questId) {
        Set<String> shown = shownQuestIds;
        return shown == null || shown.contains(questId);
    }

    /** World thread: one partial update carrying the current state. */
    private void paintNow() {
        try {
            TrackedQuestHudDeps deps = TrackedQuestHuds.resolvedDeps();
            UICommandBuilder cmd = new UICommandBuilder();
            paint(cmd, snapshot(deps), deps);
            update(false, cmd);
        } catch (Throwable t) {
            SafeLog.warn("[progression] the tracked-quest HUD failed to repaint for "
                    + getPlayerRef().getUsername() + ": " + t.getMessage());
        }
    }

    /** World thread: the state to draw, off the runtime's own subject for this player. */
    @Nonnull
    private TrackedQuestSnapshot snapshot(@Nonnull TrackedQuestHudDeps deps) {
        TrackedQuestSnapshot snapshot = TrackedQuestSnapshot.of(ProgressionRuntime.quests(), subject(), deps);
        shownQuestIds = snapshot.questIds();
        return snapshot;
    }

    /**
     * The runtime's subject for this player, resolved per paint rather than cached: the entity
     * reference changes when the player crosses into another world, and the subject carries the
     * handles a consumer's audience answer reads.
     */
    @Nullable
    private Subject subject() {
        Ref<EntityStore> ref = getPlayerRef().getReference();
        if (ref == null || !ref.isValid()) {
            return null;
        }
        Store<EntityStore> store = ref.getStore();
        return store == null ? null : ProgressionRuntime.subjects().questSubject(store, ref);
    }

    /**
     * The alive world holding this player's entity right now, or null when they are gone. Read off
     * the entity's own store rather than the reference's last-ticked world uuid, because that uuid
     * lags a hop by up to a tick and a paint queued on the world the player just LEFT would read
     * the new store off the wrong thread. Plain field reads, safe from any thread.
     */
    @Nullable
    static World worldOf(@Nonnull PlayerRef playerRef) {
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null) {
            return null;
        }
        World world = ref.getStore().getExternalData().getWorld();
        return world != null && world.isAlive() ? world : null;
    }

    // ==================== drawing ====================

    /**
     * Map a snapshot onto the document's fixed slots. Every slot is addressed on every paint -
     * shown ones filled, surplus ones hidden - because a partial update can restyle an element that
     * exists but never add one.
     */
    static void paint(@Nonnull UICommandBuilder cmd, @Nonnull TrackedQuestSnapshot snapshot,
            @Nonnull TrackedQuestHudDeps deps) {
        // The whole panel goes when nothing is pinned (or the tracker is off for this player), so
        // the screen stays clean; there is no header, each quest's title reads as its own heading.
        cmd.set(ROOT + ".Visible", snapshot.panelVisible());
        for (int qi = 0; qi < TrackedQuestSnapshot.MAX_QUESTS; qi++) {
            String qSel = "#ZigQuest" + qi;
            if (qi >= snapshot.blocks().size()) {
                cmd.set(qSel + ".Visible", false);
                continue;
            }
            Block block = snapshot.blocks().get(qi);
            cmd.set(qSel + ".Visible", true);
            // .TextSpans, never .Text: a Message on a Label's String sink crashes the client.
            cmd.set(qSel + " #Title.TextSpans", block.title());
            for (int oi = 0; oi < TrackedQuestSnapshot.MAX_ROWS; oi++) {
                String rowSel = qSel + " #ObjRow" + oi;
                if (oi >= block.rows().size()) {
                    cmd.set(rowSel + ".Visible", false);
                    continue;
                }
                Row row = block.rows().get(oi);
                cmd.set(rowSel + ".Visible", true);
                cmd.set(rowSel + " #Obj" + oi + ".TextSpans", row.text());
                cmd.set(rowSel + " #Obj" + oi + ".Style.TextColor", deps.taskColor(row.complete()));
                // The count is two numbers, pure data, so it goes on the plain String sink.
                UiText.setText(cmd, rowSel + " #Count" + oi + ".Text", row.count());
                cmd.set(rowSel + " #Count" + oi + ".Style.TextColor", deps.countColor(row.complete()));
                cmd.set(rowSel + " #IcoDefault" + oi + ".Visible", !row.complete());
                cmd.set(rowSel + " #IcoComplete" + oi + ".Visible", row.complete());
            }
        }
    }
}
