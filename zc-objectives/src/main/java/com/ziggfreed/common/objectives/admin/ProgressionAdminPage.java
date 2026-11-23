package com.ziggfreed.common.objectives.admin;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.authoring.AuthoringWizards;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.objectives.authoring.AchievementWizard;
import com.ziggfreed.common.objectives.authoring.QuestWizard;
import com.ziggfreed.common.objectives.command.ProgressAdminMessages;
import com.ziggfreed.common.reputation.authoring.ReputationWizard;
import com.ziggfreed.common.ui.SettingsUiUtil;
import com.ziggfreed.common.ui.UiRetint;
import com.ziggfreed.common.ui.ZigRichButton;
import com.ziggfreed.common.ui.kit.Hero;
import com.ziggfreed.common.ui.kit.HeroPainter;
import com.ziggfreed.common.ui.kit.ZigTokens;
import com.ziggfreed.common.ui.owner.OwnerPageGuard;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.ui.toast.ToastablePage;
import com.ziggfreed.common.util.SafeLog;

/**
 * The progression admin page: one row per registered {@link SystemSwitch}, each showing its label,
 * its live SERVER-WIDE state and, where the switch is writable, an on/off toggle. Opened only
 * through {@link ProgressionAdminPages#open}, whose audience seam defaults to the library's owner
 * audience - every event but Close asks that same audience again (zc-presentation's
 * {@code OwnerPageGuard}, which closes the page on a refusal), since a client can send any event.
 *
 * <p>Three toggle paints, one per honest answer: ON / OFF for a value that read cleanly, a locked
 * tint (no binding) for a read-only switch, and an UNKNOWN "?" for a read that threw - never OFF,
 * because the page must not claim a state nobody could read. A write goes through
 * {@link SystemSwitches#writeGuarded}; refused or applied, the page says so in a toast and reopens
 * so every row repaints from a live read. A read-only row carries a "governed elsewhere" hint line.
 * A row above the footer opens the library's in-game editors (the quest editor, {@link QuestWizard}, the reputation
 * editor and the achievement editor, {@link AchievementWizard}), each button shown only while its wizard is
 * registered; the editor's own Save asks its author node, whoever opened it.
 *
 * <p>Stateless across events: the page holds nothing but the player, so every binding round-trips
 * the full state (an action and, for a toggle, the switch id). EVERY exit path sends a response;
 * one that does not leaves the client spinning forever.
 */
public final class ProgressionAdminPage extends ToastablePage<ProgressionAdminEventData> {

    static final String PAGE_TEMPLATE = "Pages/ZigProgressionAdminPage.ui";

    /** The shared settings-form toggle row (zc-presentation), appended once per switch. */
    static final String ROW_TEMPLATE = "Pages/ZigFormToggleRow.ui";

    /** The editor row's button that opens the quest editor. */
    private static final String QUEST_EDITOR_BUTTON = "#QuestEditorButton";

    /** The editor row's button that opens the reputation editor. */
    private static final String EDITOR_BUTTON = "#ReputationEditorButton";

    /** The editor row's button that opens the achievement editor. */
    private static final String ACHIEVEMENT_EDITOR_BUTTON = "#AchievementEditorButton";

    // The two paints SettingsUiUtil.setToggle does not cover, both the kit's tones (ZigTokens, a tint over the
    // game's patch, so opaque): a read nobody could make (the available tone's amber "?") and a switch this page may
    // show but never flip (the blocked tone's steel, no binding).
    private static final String UNKNOWN_BG = ZigTokens.TONE_AVAILABLE_FILL;
    private static final String UNKNOWN_LABEL = ZigTokens.TONE_AVAILABLE_TEXT;
    private static final String LOCKED_BG = ZigTokens.TONE_BLOCKED_FILL;
    private static final String LOCKED_LABEL = ZigTokens.TONE_BLOCKED_TEXT;

    /** The deps resolved for THIS open, so build and its event handlers read one consistent set. */
    @Nonnull private ProgressionAdminDeps deps = ProgressionAdminDeps.DEFAULTS;

    public ProgressionAdminPage(@Nonnull PlayerRef playerRef) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction,
                ProgressionAdminEventData.CODEC);
    }

    /** A line of this page, resolved from the admin lang family the command surface already ships. */
    @Nonnull
    private static Message msg(@Nonnull String key, @Nonnull Object... args) {
        return Msg.key(ProgressAdminMessages.PREFIX + key, args);
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd,
                      @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        deps = ProgressionAdminPages.resolvedDeps();
        appendTemplate(cmd);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton",
                EventData.of("Action", "close"));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#BackButton",
                EventData.of("Action", "back"));

        // The title and subtitle are the band frame's hero: the gold title, the line under the game's rule.
        HeroPainter.paint(cmd, new Hero(null, msg("page.title"), null, msg("page.subtitle")));
        ZigRichButton.text(cmd, "#BackButton", msg("page.back"));
        paintEditor(cmd, events, QUEST_EDITOR_BUTTON, QuestWizard.ID, "page.quest_editor");
        paintEditor(cmd, events, EDITOR_BUTTON, ReputationWizard.ID, "page.editor.reputation");
        paintEditor(cmd, events, ACHIEVEMENT_EDITOR_BUTTON, AchievementWizard.ID, "page.editor.achievement");

        List<SystemSwitch> switches = SystemSwitches.all();
        if (switches.isEmpty()) {
            cmd.set("#Empty.TextSpans", msg("page.empty"));
            cmd.set("#Empty.Visible", true);
            renderToastInto(cmd);
            return;
        }
        for (int i = 0; i < switches.size(); i++) {
            appendRow(cmd, events, "#Rows[" + i + "]", switches.get(i));
        }
        renderToastInto(cmd);
    }

    /**
     * An in-game editor's button: shown, worded and bound while its wizard is registered (the editor's own
     * Save asks its author node), hidden otherwise.
     */
    private static void paintEditor(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
                                    @Nonnull String sel, @Nonnull String wizardId, @Nonnull String labelKey) {
        boolean shown = AuthoringWizards.known(wizardId);
        cmd.set(sel + ".Visible", shown);
        if (shown) {
            ZigRichButton.text(cmd, sel, msg(labelKey));
            events.addEventBinding(CustomUIEventBindingType.Activating, sel,
                    EventData.of("Action", "editor").append("Id", wizardId), false);
        }
    }

    /** One switch, one shared toggle row: label, hint line, state paint, and (writable only) the binding. */
    private void appendRow(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
                           @Nonnull String rowSel, @Nonnull SystemSwitch sw) {
        cmd.append("#Rows", ROW_TEMPLATE);
        cmd.set(rowSel + " #Title.TextSpans", sw.label());
        paintToggle(cmd, rowSel + " #Toggle", SystemSwitches.readGuarded(sw), sw.readOnly());
        Message hint = hintFor(sw);
        if (hint != null) {
            cmd.set(rowSel + " #Hint.TextSpans", hint);
            cmd.set(rowSel + " #Hint.Visible", true);
        }
        if (!sw.readOnly()) {
            events.addEventBinding(CustomUIEventBindingType.Activating, rowSel + " #Toggle",
                    EventData.of("Action", "toggle").append("Id", sw.id()), false);
        }
    }

    /**
     * The row's sub-label: the switch's own hint, plus the "governed elsewhere" line on a
     * read-only row (both when it carries both); null hides the line.
     */
    @Nullable
    private static Message hintFor(@Nonnull SystemSwitch sw) {
        Message hint = sw.hint();
        if (!sw.readOnly()) {
            return hint;
        }
        Message governed = msg("page.readonly");
        return hint == null ? governed : Msg.join(hint, Msg.raw(" "), governed);
    }

    /**
     * Paint one toggle in whichever of the three states is the honest one. UNKNOWN wins over
     * everything: a read that threw must never paint as OFF, and a locked row nobody could read
     * shows the same "?" (the missing binding already says it cannot be pressed).
     */
    private static void paintToggle(@Nonnull UICommandBuilder cmd, @Nonnull String sel,
                                    @Nullable Boolean state, boolean readOnly) {
        if (state == null) {
            ZigRichButton.text(cmd, sel, msg("page.unknown"));
            UiRetint.retintButtonStates(cmd, sel, UNKNOWN_BG, UNKNOWN_BG, UNKNOWN_BG);
            ZigRichButton.color(cmd, sel, UNKNOWN_LABEL);
            return;
        }
        if (readOnly) {
            ZigRichButton.text(cmd, sel, msg(state ? "page.on" : "page.off"));
            UiRetint.retintButtonStates(cmd, sel, LOCKED_BG, LOCKED_BG, LOCKED_BG);
            ZigRichButton.color(cmd, sel, LOCKED_LABEL);
            return;
        }
        SettingsUiUtil.setToggle(cmd, sel, state, msg("page.on"), msg("page.off"));
    }

    /**
     * Get the page's markup onto the screen, through the consumer's theme where there is one.
     * Guarded with the plain append as the fallback, the objective book's rule: a theme is
     * decoration, and one that throws must not cost the admin the screen.
     */
    private void appendTemplate(@Nonnull UICommandBuilder cmd) {
        try {
            deps.theme().appendThemed(cmd, PAGE_TEMPLATE, "#SwitchPanel");
            return;
        } catch (Throwable t) {
            SafeLog.warn("[progression-admin] a page theme failed, so the admin page renders plain: "
                    + t.getMessage());
        }
        cmd.append(PAGE_TEMPLATE);
    }

    // ==================== events ====================

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                @Nonnull ProgressionAdminEventData data) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            this.sendUpdate(new UICommandBuilder(), new UIEventBuilder(), false);
            return;
        }
        String action = data.action == null ? "" : data.action;
        if ("close".equals(action) || action.isEmpty()) {
            player.getPageManager().setPage(ref, store, Page.None);
            return;
        }
        // Who may act is asked again on every event (a refusal is said and closes the page, answering the client).
        if (!OwnerPageGuard.stillAllowed(store, ref, player, playerRef, deps::mayOpenGuarded)) {
            return;
        }
        switch (action) {
            case "back" -> {
                if (!deps.backGuarded(store, ref, player)) {
                    player.getPageManager().setPage(ref, store, Page.None);
                }
            }
            case "toggle" -> handleToggle(ref, store, player, data.id);
            case "editor" -> {
                if (data.id == null || !AuthoringWizards.open(data.id, ref, store)) {
                    showToast(ToastKind.ERROR, msg("page.editor.failed"));
                    reopen(player, ref, store);
                }
            }
            default -> this.sendUpdate(new UICommandBuilder(), new UIEventBuilder(), false);
        }
    }

    /**
     * Flip one switch. UNKNOWN reads as "not on", so the press that follows a broken read asks for
     * ON - deterministic, and the reopened page shows whatever the write really left behind.
     */
    private void handleToggle(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                              @Nonnull Player player, @Nullable String id) {
        SystemSwitch sw = SystemSwitches.get(id);
        if (sw == null || sw.readOnly()) {
            // A stale row (the switch is gone) or a row that never had a binding: refuse, honestly.
            showToast(ToastKind.ERROR, msg("page.refused",
                    sw != null ? sw.label() : Msg.raw(id == null ? "" : id)));
            reopen(player, ref, store);
            return;
        }
        boolean target = !Boolean.TRUE.equals(SystemSwitches.readGuarded(sw));
        if (SystemSwitches.writeGuarded(sw, target)) {
            showToast(ToastKind.SUCCESS,
                    msg("page.changed", sw.label(), msg(target ? "page.on" : "page.off")));
        } else {
            showToast(ToastKind.ERROR, msg("page.refused", sw.label()));
        }
        reopen(player, ref, store);
    }

    /** Reopen fresh, so every row repaints from a live read; the toast rides the rebuild. */
    private void reopen(@Nonnull Player player, @Nonnull Ref<EntityStore> ref,
                        @Nonnull Store<EntityStore> store) {
        player.getPageManager().openCustomPage(ref, store, new ProgressionAdminPage(playerRef));
    }
}
