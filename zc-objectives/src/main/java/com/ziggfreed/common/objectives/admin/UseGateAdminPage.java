package com.ziggfreed.common.objectives.admin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

import com.ziggfreed.common.factor.FactorNames;
import com.ziggfreed.common.factor.FeatureFlags;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.match.NamePattern;
import com.ziggfreed.common.objectives.command.ProgressAdminMessages;
import com.ziggfreed.common.progress.gate.FeatureLift;
import com.ziggfreed.common.progress.gate.GateEvaluator;
import com.ziggfreed.common.progress.gate.RefusalLines;
import com.ziggfreed.common.ui.SettingsUiUtil;
import com.ziggfreed.common.ui.ZigRichButton;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.ui.toast.ToastablePage;
import com.ziggfreed.common.usegate.ItemLevelTags;
import com.ziggfreed.common.usegate.UseGateAsset;
import com.ziggfreed.common.usegate.UseGateAxis;
import com.ziggfreed.common.usegate.UseGateConfig;
import com.ziggfreed.common.usegate.UseGateOwnerLayers;
import com.ziggfreed.common.util.SafeLog;

/**
 * The Use Gates admin page: every feature the installed use gates hide behind (a switch row, flippable where the
 * declaring mod registered a writer, {@link FeatureFlags#registerWriter}), then every folded use gate (a gate row:
 * its id, what it is about, what it asks, and an on/off switch written into the owner file through
 * {@link UseGateOwnerLayers#setEnabled}). Opened only through {@link UseGateAdminPages#open}; it never checks
 * permissions itself.
 *
 * <p>Each row is the shared settings-form toggle row (zc-presentation's {@code Pages/ZigFormToggleRow.ui}), a page
 * of {@value #PER_PAGE} at a time. Stateless across events: every binding round-trips the action, the row's id and
 * the page shown, and EVERY exit path sends a response or replaces the page.
 */
public final class UseGateAdminPage extends ToastablePage<UseGateAdminEventData> {

    static final String PAGE_TEMPLATE = "Pages/ZigUseGateAdminPage.ui";

    /** The shared settings-form toggle row (zc-presentation), appended once per row. */
    static final String ROW_TEMPLATE = "Pages/ZigFormToggleRow.ui";

    /** Rows on one page of the list. */
    static final int PER_PAGE = 25;

    private static final String SWITCH_SEPARATOR = "|";

    /** One feature the gates hide behind, with how many gates and how many tagged items it hides. */
    private record Switch(@Nonnull FeatureLift.Lifted lifted, int gates, int items) {

        /** What a toggle binding round-trips: the factor and the feature as authored. */
        @Nonnull
        String key() {
            return lifted.factorId() + SWITCH_SEPARATOR + lifted.param();
        }

        /** The identity two gates lifting the same feature share, matched without regard to case. */
        @Nonnull
        String identity() {
            return key().toLowerCase(Locale.ROOT);
        }
    }

    /** One row of the list: a switch or a gate, never both. */
    private record Row(@Nullable Switch feature, @Nullable UseGateAsset gate) {
    }

    @Nonnull private ProgressionAdminDeps deps = ProgressionAdminDeps.DEFAULTS;
    private final int page;

    public UseGateAdminPage(@Nonnull PlayerRef playerRef, int page) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, UseGateAdminEventData.CODEC);
        this.page = Math.max(0, page);
    }

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

        cmd.set("#Title.TextSpans", msg("usegates.title"));
        cmd.set("#Subtitle.TextSpans", msg("usegates.subtitle"));
        ZigRichButton.text(cmd, "#BackButton", msg("page.back"));
        ZigRichButton.text(cmd, "#PrevButton", msg("usegates.prev"));
        ZigRichButton.text(cmd, "#NextButton", msg("usegates.next"));

        List<Row> rows;
        try {
            rows = rows();
        } catch (Throwable t) {
            SafeLog.warn("[usegate-admin] the gate list could not be read: " + t.getMessage());
            rows = List.of();
        }
        if (rows.isEmpty()) {
            cmd.set("#Empty.TextSpans", msg("usegates.empty"));
            cmd.set("#Empty.Visible", true);
            cmd.set("#Pager.Visible", false);
            renderToastInto(cmd);
            return;
        }
        int pages = Math.max(1, (rows.size() + PER_PAGE - 1) / PER_PAGE);
        int shown = Math.min(page, pages - 1);
        cmd.set("#PageLabel.TextSpans", msg("usegates.page", shown + 1, pages));
        if (shown > 0) {
            events.addEventBinding(CustomUIEventBindingType.Activating, "#PrevButton",
                    EventData.of("Action", "page").append("Page", String.valueOf(shown - 1)), false);
        }
        if (shown < pages - 1) {
            events.addEventBinding(CustomUIEventBindingType.Activating, "#NextButton",
                    EventData.of("Action", "page").append("Page", String.valueOf(shown + 1)), false);
        }
        cmd.set("#PrevButton.Visible", shown > 0);
        cmd.set("#NextButton.Visible", shown < pages - 1);

        int from = shown * PER_PAGE;
        int to = Math.min(rows.size(), from + PER_PAGE);
        for (int i = from; i < to; i++) {
            String sel = "#Rows[" + (i - from) + "]";
            Row row = rows.get(i);
            cmd.append("#Rows", ROW_TEMPLATE);
            if (row.feature() != null) {
                paintSwitch(cmd, events, sel, row.feature(), shown);
            } else if (row.gate() != null) {
                paintGate(cmd, events, sel, row.gate(), shown);
            }
        }
        renderToastInto(cmd);
    }

    /** Every switch, then every gate in id order. */
    @Nonnull
    private static List<Row> rows() {
        List<UseGateAsset> gates = new ArrayList<>(UseGateConfig.getInstance().all().values());
        gates.removeIf(g -> g == null || g.getId() == null);
        gates.sort(Comparator.comparing(UseGateAsset::getId, String.CASE_INSENSITIVE_ORDER));
        List<Row> rows = new ArrayList<>();
        for (Switch feature : switches(gates)) {
            rows.add(new Row(feature, null));
        }
        for (UseGateAsset gate : gates) {
            rows.add(new Row(null, gate));
        }
        return rows;
    }

    /**
     * The switches (never mod presence) the gates sit behind (each gate's {@code Feature} and any feature its
     * {@code Requires} lifts), then those the tagged items' {@code ZigLevelFeature} names, each with its counts.
     */
    @Nonnull
    private static List<Switch> switches(@Nonnull List<UseGateAsset> gates) {
        Map<String, Switch> found = new LinkedHashMap<>();
        for (UseGateAsset gate : gates) {
            for (FeatureLift.Lifted lifted : gate.switches()) {
                count(found, lifted, 1, 0);
            }
        }
        for (ItemLevelTags.Tagged tagged : ItemLevelTags.all()) {
            for (FeatureLift.Lifted lifted : tagged.switches()) {
                count(found, lifted, 0, 1);
            }
        }
        return new ArrayList<>(found.values());
    }

    private static void count(@Nonnull Map<String, Switch> found, @Nonnull FeatureLift.Lifted lifted, int gates,
            int items) {
        Switch next = new Switch(lifted, gates, items);
        Switch held = found.get(next.identity());
        found.put(next.identity(), held == null ? next
                : new Switch(held.lifted(), held.gates() + gates, held.items() + items));
    }

    private void paintSwitch(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events, @Nonnull String sel,
            @Nonnull Switch feature, int shown) {
        FeatureLift.Lifted lifted = feature.lifted();
        Message name = FactorNames.name(lifted.factorId(), lifted.param());
        cmd.set(sel + " #Title.TextSpans", name != null ? name : Msg.raw(lifted.param()));
        Message gatesHint = feature.gates() > 0 ? msg("usegates.switch.hint", feature.gates()) : null;
        Message itemsHint = feature.items() > 0 ? msg("usegates.switch.items_hint", feature.items()) : null;
        cmd.set(sel + " #Hint.TextSpans", gatesHint != null && itemsHint != null
                ? msg("usegates.gate.hint", gatesHint, itemsHint)
                : gatesHint != null ? gatesHint : itemsHint != null ? itemsHint : msg("usegates.switch.hint", 0));
        cmd.set(sel + " #Hint.Visible", true);
        SettingsUiUtil.setToggle(cmd, sel + " #Toggle", lifted.isOn(), msg("page.on"), msg("page.off"));
        if (FeatureFlags.isWritable(FeatureFlags.namespaceOf(lifted.factorId()), lifted.param())) {
            events.addEventBinding(CustomUIEventBindingType.Activating, sel + " #Toggle",
                    EventData.of("Action", "switch").append("Id", feature.key())
                            .append("Page", String.valueOf(shown)), false);
        }
    }

    private void paintGate(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events, @Nonnull String sel,
            @Nonnull UseGateAsset gate, int shown) {
        cmd.set(sel + " #Title.TextSpans", Msg.raw(gate.getId()));
        cmd.set(sel + " #Hint.TextSpans", msg("usegates.gate.hint", onLine(gate), asksLine(gate)));
        cmd.set(sel + " #Hint.Visible", true);
        SettingsUiUtil.setToggle(cmd, sel + " #Toggle", gate.isEnabled(), msg("page.on"), msg("page.off"));
        events.addEventBinding(CustomUIEventBindingType.Activating, sel + " #Toggle",
                EventData.of("Action", "gate").append("Id", gate.getId()).append("Page", String.valueOf(shown)), false);
    }

    /** What the gate is about: each axis with its patterns, as authored. */
    @Nonnull
    private static Message onLine(@Nonnull UseGateAsset gate) {
        List<Message> parts = new ArrayList<>();
        for (UseGateAxis axis : UseGateAxis.values()) {
            List<NamePattern> patterns = gate.on().patterns(axis);
            if (patterns.isEmpty()) {
                continue;
            }
            StringBuilder raw = new StringBuilder();
            for (NamePattern pattern : patterns) {
                if (raw.length() > 0) {
                    raw.append(", ");
                }
                raw.append(pattern.raw.trim());
            }
            if (!parts.isEmpty()) {
                parts.add(Msg.raw("; "));
            }
            parts.add(msg("usegates.gate.on", msg("usegates.axis." + axis.name().toLowerCase(Locale.ROOT)),
                    Msg.raw(raw.toString())));
        }
        return parts.isEmpty() ? msg("usegates.gate.nothing") : Msg.cat(parts.toArray(new Message[0]));
    }

    /** What the gate asks, each requirement worded by the shared refusal mapping. */
    @Nonnull
    private static Message asksLine(@Nonnull UseGateAsset gate) {
        List<Message> lines = RefusalLines.linesOf(GateEvaluator.asks(gate.liveRequires()));
        if (lines.isEmpty()) {
            return msg("usegates.gate.nothing");
        }
        List<Message> parts = new ArrayList<>(lines.size() * 2);
        for (Message line : lines) {
            if (!parts.isEmpty()) {
                parts.add(Msg.raw(", "));
            }
            parts.add(line);
        }
        return msg("usegates.gate.requires", Msg.cat(parts.toArray(new Message[0])));
    }

    private void appendTemplate(@Nonnull UICommandBuilder cmd) {
        try {
            deps.theme().appendThemed(cmd, PAGE_TEMPLATE, "#GatePanel");
            return;
        } catch (Throwable t) {
            SafeLog.warn("[usegate-admin] a page theme failed, so the Use Gates page renders plain: "
                    + t.getMessage());
        }
        cmd.append(PAGE_TEMPLATE);
    }

    // ==================== events ====================

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                @Nonnull UseGateAdminEventData data) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            this.sendUpdate(new UICommandBuilder(), new UIEventBuilder(), false);
            return;
        }
        String action = data.action == null ? "" : data.action;
        int shown = pageOf(data.page);
        switch (action) {
            case "back" -> {
                if (!deps.backGuarded(store, ref, player)) {
                    player.getPageManager().setPage(ref, store, Page.None);
                }
            }
            case "page" -> reopen(player, ref, store, shown);
            case "gate" -> {
                switchGate(data.id);
                reopen(player, ref, store, shown);
            }
            case "switch" -> {
                switchFeature(data.id);
                reopen(player, ref, store, shown);
            }
            case "close", "" -> player.getPageManager().setPage(ref, store, Page.None);
            default -> this.sendUpdate(new UICommandBuilder(), new UIEventBuilder(), false);
        }
    }

    /** Flip one gate's Enabled in the owner file, and say what happened in a toast. */
    private void switchGate(@Nullable String id) {
        UseGateAsset gate = id == null || id.isBlank() ? null : UseGateConfig.getInstance().resolve(id);
        if (gate == null) {
            showToast(ToastKind.ERROR, msg("page.refused", Msg.raw(id == null ? "" : id)));
            return;
        }
        boolean target = !gate.isEnabled();
        if (UseGateOwnerLayers.setEnabled(gate.getId(), target)) {
            showToast(ToastKind.SUCCESS, msg("page.changed", Msg.raw(gate.getId()), msg(target ? "page.on" : "page.off")));
        } else {
            showToast(ToastKind.ERROR, msg("page.refused", Msg.raw(gate.getId())));
        }
    }

    /** Flip one feature through its declaring mod's writer, and say what happened in a toast. */
    private void switchFeature(@Nullable String key) {
        int split = key == null ? -1 : key.indexOf(SWITCH_SEPARATOR);
        if (split <= 0 || split >= key.length() - 1) {
            showToast(ToastKind.ERROR, msg("page.refused", Msg.raw(key == null ? "" : key)));
            return;
        }
        String factorId = key.substring(0, split);
        String param = key.substring(split + 1);
        String namespace = FeatureFlags.namespaceOf(factorId);
        Double reading = FeatureFlags.read(namespace, param);
        boolean target = reading == null || reading < 1.0;
        Message name = FactorNames.name(factorId, param);
        Message label = name != null ? name : Msg.raw(param);
        if (FeatureFlags.write(namespace, param, target)) {
            showToast(ToastKind.SUCCESS, msg("page.changed", label, msg(target ? "page.on" : "page.off")));
        } else {
            showToast(ToastKind.ERROR, msg("page.refused", label));
        }
    }

    private static int pageOf(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Reopen fresh on {@code shown}, so every row repaints from a live read; a toast rides the rebuild. */
    private void reopen(@Nonnull Player player, @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            int shown) {
        player.getPageManager().openCustomPage(ref, store, new UseGateAdminPage(playerRef, shown));
    }
}
