package com.ziggfreed.common.objectives.admin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.LocalizableString;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.loot.trigger.BonusRowAsset;
import com.ziggfreed.common.owner.OwnerFileBinding;
import com.ziggfreed.common.ui.SettingsUiUtil;
import com.ziggfreed.common.ui.ZigFrame;
import com.ziggfreed.common.ui.ZigRichButton;
import com.ziggfreed.common.ui.form.OwnerFormField;
import com.ziggfreed.common.ui.form.OwnerFormKind;
import com.ziggfreed.common.ui.form.OwnerFormPlan;
import com.ziggfreed.common.ui.form.OwnerFormWrite;
import com.ziggfreed.common.ui.kit.FormLeaf;
import com.ziggfreed.common.ui.kit.FormLeafPainter;
import com.ziggfreed.common.ui.kit.Hero;
import com.ziggfreed.common.ui.kit.HeroPainter;
import com.ziggfreed.common.ui.owner.OwnerPageGuard;
import com.ziggfreed.common.ui.owner.OwnerText;
import com.ziggfreed.common.util.SafeLog;

/**
 * The Kill Drops admin page: every kill bonus row on the server, any mod's and the owner's own, what each covers and
 * pays, and four leaves of each an owner may change here (switched on, the mobs it covers, its chance and whether the
 * killer's kill yield adds to it), each the kit's owner leaf row ({@link FormLeafPainter}) with the value in force,
 * the value under the owner's word and a Reset. Save writes only the changed leaves into
 * {@code mods/ziggfreedcommon/bonus-rows.json} through the file's binding ({@link OwnerFormWrite}, the owner form's
 * own writer), which reads the file again; a Reset takes one leaf out at once. Opened only through
 * {@link KillDropsAdminPages#open}, with the Use Gates page's deps.
 *
 * <p>A row's commands are never edited here: the page says how many a row runs and that they change in the owner file
 * only, so a settings permission never becomes a way to run a console command. A row of the owner's own (nothing under
 * the owner's word) may not lose its mobs, which would widen it to every mob ({@link KillDropsPlan#blankedAlone}).
 *
 * <p>A page of rows at a time ({@link KillDropsPlan#ROWS_PER_PAGE}); turning the page drops what was typed and not
 * saved. Every event but Close asks the deps' audience again ({@link OwnerPageGuard}), and every exit answers the
 * client: Close closes, Back and a page turn open a page, everything else gets an update.
 */
public final class KillDropsAdminPage extends InteractiveCustomUIPage<KillDropsAdminEventData> {

    static final String PAGE_TEMPLATE = "Pages/ZigKillDropsAdminPage.ui";
    static final String HEADING_TEMPLATE = "Pages/ZigFormHeaderRow.ui";
    static final String NOTE_TEMPLATE = "Pages/ZigFormNoteRow.ui";

    /** What an event round-trips. */
    static final String ACTION = "Action";
    static final String FIELD = "Field";
    static final String PAGE = "Page";
    static final String CLOSE = "close";
    static final String BACK = "back";
    static final String SAVE = "save";
    static final String CHANGE = "field";
    static final String RESET = "reset";
    static final String TURN = "page";

    static final String FIELDS = "#Fields";
    static final String STATUS = "#Status";
    static final String EMPTY = "#Empty";
    static final String PANEL = "#KillDropsPanel";
    static final String BACK_BUTTON = "#BackButton";
    static final String SAVE_BUTTON = "#SaveButton";
    static final String PREV_BUTTON = "#PrevButton";
    static final String NEXT_BUTTON = "#NextButton";
    static final String PAGE_LABEL = "#PageLabel";
    static final String PAGER = "#Pager";

    /** The prefix of this page's own words, in {@code ziggfreedcommon.bonus.lang}. */
    static final String PREFIX = "ziggfreedcommon.bonus.admin.";

    @Nonnull private ProgressionAdminDeps deps = ProgressionAdminDeps.DEFAULTS;
    private final int page;
    @Nullable private KillDropsPlan plan;
    @Nonnull private final List<String> rows = new ArrayList<>();
    @Nonnull private final List<FormLeaf> leaves = new ArrayList<>();
    @Nonnull private final Map<Integer, String> typed = new LinkedHashMap<>();

    public KillDropsAdminPage(@Nonnull PlayerRef playerRef, int page) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, KillDropsAdminEventData.CODEC);
        this.page = Math.max(0, page);
    }

    @Nonnull
    static Message msg(@Nonnull String key, @Nonnull Object... args) {
        return Msg.key(PREFIX + key, args);
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull Store<EntityStore> store) {
        deps = ProgressionAdminPages.resolvedDeps();
        appendTemplate(cmd);
        plan = null;
        rows.clear();
        leaves.clear();
        typed.clear();
        events.addEventBinding(CustomUIEventBindingType.Activating, ZigFrame.CLOSE, EventData.of(ACTION, CLOSE));
        events.addEventBinding(CustomUIEventBindingType.Activating, BACK_BUTTON, EventData.of(ACTION, BACK));
        events.addEventBinding(CustomUIEventBindingType.Activating, SAVE_BUTTON, EventData.of(ACTION, SAVE));
        HeroPainter.paint(cmd, new Hero(null, msg("title"), null, msg("subtitle")));
        ZigRichButton.text(cmd, BACK_BUTTON, msg("back"));
        ZigRichButton.text(cmd, SAVE_BUTTON, msg("save"));
        ZigRichButton.text(cmd, PREV_BUTTON, msg("prev"));
        ZigRichButton.text(cmd, NEXT_BUTTON, msg("next"));
        try {
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player == null || !deps.mayOpenGuarded(store, ref, player)) {
                refused(cmd);
                return;
            }
            KillDropsPlan read = KillDropsPlan.read(page);
            paint(cmd, events, read);
            plan = read;
        } catch (Throwable t) {
            SafeLog.warn("[loot] the kill drops screen could not paint everything", t);
        }
    }

    /** The page for a player who may not see it: the refusal on the status line, nothing to save, no pager. */
    private static void refused(@Nonnull UICommandBuilder cmd) {
        status(cmd, OwnerText.line("menu.refused"), true);
        cmd.set(SAVE_BUTTON + ".Disabled", true);
        cmd.set(PAGER + ".Visible", false);
    }

    /** Paint {@code read}, appended already: the pager, the status line, then each row's heading, note and leaves. */
    private void paint(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events, @Nonnull KillDropsPlan read) {
        OwnerFileBinding binding = read.binding();
        if (binding == null) {
            status(cmd, OwnerText.line("form.unbound"), true);
        } else if (read.malformed()) {
            status(cmd, OwnerText.line("form.malformed", binding.id()), true);
        }
        cmd.set(SAVE_BUTTON + ".Disabled", !read.writable());
        if (read.total() == 0) {
            cmd.set(EMPTY + ".TextSpans", msg("empty"));
            cmd.set(EMPTY + ".Visible", true);
            cmd.set(PAGER + ".Visible", false);
            cmd.set(SAVE_BUTTON + ".Disabled", true);
            return;
        }
        int shown = read.page();
        cmd.set(PAGE_LABEL + ".TextSpans", msg("page", shown + 1, read.pages()));
        cmd.set(PREV_BUTTON + ".Visible", shown > 0);
        cmd.set(NEXT_BUTTON + ".Visible", shown < read.pages() - 1);
        if (shown > 0) {
            events.addEventBinding(CustomUIEventBindingType.Activating, PREV_BUTTON,
                    EventData.of(ACTION, TURN).append(PAGE, String.valueOf(shown - 1)), false);
        }
        if (shown < read.pages() - 1) {
            events.addEventBinding(CustomUIEventBindingType.Activating, NEXT_BUTTON,
                    EventData.of(ACTION, TURN).append(PAGE, String.valueOf(shown + 1)), false);
        }
        int at = 0;
        for (KillDropsPlan.Row row : read.rows()) {
            cmd.append(FIELDS, HEADING_TEMPLATE);
            cmd.set(FIELDS + "[" + at + "] #Title.TextSpans", Msg.raw(row.id()));
            at++;
            cmd.append(FIELDS, NOTE_TEMPLATE);
            cmd.set(FIELDS + "[" + at + "] #Note.TextSpans", note(row));
            at++;
            for (KillDropsPlan.Leaf leaf : row.leaves()) {
                String index = String.valueOf(leaf.field().index());
                FormLeaf drawn = formLeaf(leaf, leaf.shown(), leaf.set());
                rows.add(FormLeafPainter.append(cmd, events, FIELDS, at, drawn,
                        EventData.of(ACTION, CHANGE).append(FIELD, index),
                        EventData.of(ACTION, RESET).append(FIELD, index), this.playerRef));
                leaves.add(drawn);
                at++;
            }
        }
    }

    /** What a row covers and pays, one line each, and why it cannot be edited here when it cannot. */
    @Nonnull
    static Message note(@Nonnull KillDropsPlan.Row row) {
        BonusRowAsset asset = row.asset();
        List<Message> lines = new ArrayList<>();
        String match = asset.match();
        lines.add(match.isEmpty() || "*".equals(match) ? msg("row.mobs.every") : msg("row.mobs", Msg.raw(match)));
        String group = asset.getGroup();
        lines.add(group.isEmpty() ? msg("row.group.shared") : msg("row.group", Msg.raw(group)));
        KillDropsPlan.Pays pays = KillDropsPlan.paysOf(asset);
        if (pays.nothing()) {
            lines.add(msg("row.pays.nothing"));
        } else {
            if (pays.rolls() > 0) {
                lines.add(msg("row.pays", pays.rolls()));
            }
            if (!pays.tables().isEmpty()) {
                lines.add(msg("row.tables", Msg.raw(String.join(", ", pays.tables()))));
            }
            if (!pays.dropLists().isEmpty()) {
                lines.add(msg("row.droplists", Msg.raw(String.join(", ", pays.dropLists()))));
            }
            if (pays.commands() > 0) {
                lines.add(msg("row.commands", pays.commands()));
            }
        }
        if (!row.editable()) {
            lines.add(msg("row.readonly"));
        }
        Message out = lines.get(0);
        for (int i = 1; i < lines.size(); i++) {
            out = msg("row.join", out, lines.get(i));
        }
        return out;
    }

    /** The row {@code leaf} is drawn as with {@code value} in its control, set by the owner's file or not. */
    @Nonnull
    private static FormLeaf formLeaf(@Nonnull KillDropsPlan.Leaf leaf, @Nonnull String value, boolean overridden) {
        String word = KillDropsPlan.word(leaf.setting());
        boolean picked = leaf.setting().kind.picked();
        List<DropdownEntryInfo> choices = new ArrayList<>();
        if (picked) {
            choices.add(new DropdownEntryInfo(LocalizableString.fromMessageId(OwnerText.key("form.on")),
                    KillDropsPlan.ON));
            choices.add(new DropdownEntryInfo(LocalizableString.fromMessageId(OwnerText.key("form.off")),
                    KillDropsPlan.OFF));
        }
        return new FormLeaf(msg("field." + word), msg("field." + word + ".hint"),
                picked ? FormLeaf.Control.CHOICE : FormLeaf.Control.FIELD, value, choices, defaultLine(leaf),
                overridden, OwnerText.line(overridden ? "form.state.set" : "form.state.default"),
                OwnerText.line("form.reset"));
    }

    /** "Without your setting: 5", the switch's word, or "No value without your setting". */
    @Nonnull
    private static Message defaultLine(@Nonnull KillDropsPlan.Leaf leaf) {
        String raw = OwnerFormPlan.raw(leaf.setting().kind, leaf.below());
        if (raw.isEmpty()) {
            return OwnerText.line("form.default.none");
        }
        if (leaf.setting().kind == OwnerFormKind.SWITCH) {
            return OwnerText.line("form.default", OwnerText.line(KillDropsPlan.ON.equals(raw) ? "form.on" : "form.off"));
        }
        return OwnerText.line("form.default", Msg.raw(raw));
    }

    private void appendTemplate(@Nonnull UICommandBuilder cmd) {
        try {
            deps.theme().appendThemed(cmd, PAGE_TEMPLATE, PANEL);
            return;
        } catch (Throwable t) {
            SafeLog.warn("[loot] a page theme failed, so the kill drops screen renders plain: " + t.getMessage());
        }
        cmd.append(PAGE_TEMPLATE);
    }

    // ==================== events ====================

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull KillDropsAdminEventData data) {
        Player player = ref.isValid() ? store.getComponent(ref, Player.getComponentType()) : null;
        if (player == null || !route(ref, store, player, data)) {
            answer();
        }
    }

    /** Closes, opens or updates for one event; false when it sent nothing. */
    private boolean route(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Player player,
            @Nonnull KillDropsAdminEventData data) {
        String action = data.action == null ? "" : data.action;
        if (CLOSE.equals(action) || action.isEmpty()) {
            player.getPageManager().setPage(ref, store, Page.None);
            return true;
        }
        // Who may act is asked again on every event (a refusal is said and closes the page, answering the client).
        if (!OwnerPageGuard.stillAllowed(store, ref, player, this.playerRef, deps::mayOpenGuarded)) {
            return true;
        }
        if (BACK.equals(action)) {
            if (!deps.backGuarded(store, ref, player)) {
                player.getPageManager().setPage(ref, store, Page.None);
            }
            return true;
        }
        if (TURN.equals(action)) {
            player.getPageManager().openCustomPage(ref, store, new KillDropsAdminPage(this.playerRef,
                    pageOf(data.page)));
            return true;
        }
        KillDropsPlan current = plan;
        if (current == null) {
            return false;
        }
        return switch (action) {
            case CHANGE -> keep(current, data.field, data.value);
            case RESET -> reset(current, data.field);
            case SAVE -> save(current);
            default -> false;
        };
    }

    /**
     * Keep what was typed into a leaf's control, unsent; false always, so the caller answers. A value equal to what the
     * row shows is no change (a control may echo a value the page itself pushed), so it is never written.
     */
    private boolean keep(@Nonnull KillDropsPlan current, @Nullable String field, @Nullable String value) {
        KillDropsPlan.Leaf leaf = current.leaf(index(field));
        if (leaf == null || value == null) {
            return false;
        }
        int i = leaf.field().index();
        if (i < leaves.size() && value.equals(leaves.get(i).value())) {
            typed.remove(i);
        } else {
            typed.put(i, value);
        }
        return false;
    }

    /** Take one leaf's value out of the owner's file, then repaint its row. */
    private boolean reset(@Nonnull KillDropsPlan current, @Nullable String field) {
        KillDropsPlan.Leaf leaf = current.leaf(index(field));
        if (leaf == null) {
            return false;
        }
        int i = leaf.field().index();
        if (i >= leaves.size() || !leaves.get(i).overridden()) {
            return false;
        }
        OwnerFileBinding binding = current.binding();
        if (!current.writable() || binding == null) {
            return push(new UICommandBuilder(), cannotWrite(current), true);
        }
        Map<String, Object> out = OwnerFormWrite.reset(leaf.field());
        KillDropsPlan.Leaf alone = current.blankedAlone(out);
        if (alone != null) {
            return push(new UICommandBuilder(), msg("match.needed", label(alone)), true);
        }
        if (!OwnerFormWrite.write(binding, out)) {
            return push(new UICommandBuilder(), OwnerText.line("form.failed", binding.id()), true);
        }
        typed.remove(i);
        UICommandBuilder cmd = new UICommandBuilder();
        repaint(cmd, i, OwnerFormPlan.raw(leaf.setting().kind, leaf.below()), false);
        return push(cmd, OwnerText.line("form.reset.done"), false);
    }

    /** Write every changed leaf, or name the first one refused; then repaint the rows written. */
    private boolean save(@Nonnull KillDropsPlan current) {
        OwnerFileBinding binding = current.binding();
        if (!current.writable() || binding == null) {
            return push(new UICommandBuilder(), cannotWrite(current), true);
        }
        if (typed.isEmpty()) {
            return push(new UICommandBuilder(), OwnerText.line("form.unchanged"), false);
        }
        List<OwnerFormField> fields = current.fields();
        OwnerFormWrite.Collected collected = OwnerFormWrite.collect(fields, typed);
        if (!collected.ok()) {
            return push(new UICommandBuilder(), refusalLine(current, collected.refusal()), true);
        }
        KillDropsPlan.Leaf alone = current.blankedAlone(collected.leaves());
        if (alone != null) {
            return push(new UICommandBuilder(), msg("match.needed", label(alone)), true);
        }
        if (!OwnerFormWrite.write(binding, collected.leaves())) {
            return push(new UICommandBuilder(), OwnerText.line("form.failed", binding.id()), true);
        }
        UICommandBuilder cmd = new UICommandBuilder();
        for (OwnerFormField field : fields) {
            if (!typed.containsKey(field.index()) || !collected.leaves().containsKey(field.writePath())) {
                continue;
            }
            Object written = collected.leaves().get(field.writePath());
            KillDropsPlan.Leaf leaf = current.leaf(field.index());
            if (written == null) {
                repaint(cmd, field.index(), OwnerFormPlan.raw(field.kind(), leaf == null ? null : leaf.below()), false);
            } else {
                repaint(cmd, field.index(), OwnerFormPlan.raw(field.kind(), written), true);
            }
        }
        typed.clear();
        return push(cmd, OwnerText.line("form.saved"), false);
    }

    /** Repaint leaf {@code i}'s row with {@code value} in its control, set by the owner's file or not. */
    private void repaint(@Nonnull UICommandBuilder cmd, int i, @Nonnull String value, boolean overridden) {
        if (i < 0 || i >= leaves.size() || i >= rows.size()) {
            return;
        }
        FormLeaf next = leaves.get(i).with(value, overridden,
                OwnerText.line(overridden ? "form.state.set" : "form.state.default"));
        leaves.set(i, next);
        FormLeafPainter.paint(cmd, rows.get(i), next, this.playerRef);
    }

    /** Why this page writes nothing: no module bound the file, or the file cannot be read. */
    @Nonnull
    private static Message cannotWrite(@Nonnull KillDropsPlan current) {
        OwnerFileBinding binding = current.binding();
        return binding == null ? OwnerText.line("form.unbound") : OwnerText.line("form.malformed", binding.id());
    }

    /** The line naming a refused leaf and why, in the owner form's own words. */
    @Nonnull
    private static Message refusalLine(@Nonnull KillDropsPlan current, @Nullable OwnerFormWrite.Refusal refusal) {
        if (refusal == null) {
            return OwnerText.line("menu.failed");
        }
        KillDropsPlan.Leaf leaf = current.leaf(refusal.field().index());
        Message name = leaf == null ? OwnerText.line("form.path", refusal.field().writePath()) : label(leaf);
        OwnerFormField field = refusal.field();
        return switch (refusal.problem()) {
            case NUMBER -> OwnerText.line("form.bad.number", name);
            case WHOLE -> OwnerText.line("form.bad.whole", name);
            case CHANCE -> OwnerText.line("form.bad.chance", name);
            case BELOW -> OwnerText.line("form.bad.below", name, bound(field.min()));
            case ABOVE -> OwnerText.line("form.bad.above", name, bound(field.max()));
            case CHOICE -> OwnerText.line("form.bad.choice", name);
        };
    }

    /** A leaf's name with its row's: "Mob_Drop_Skeleton: Mobs". */
    @Nonnull
    private static Message label(@Nonnull KillDropsPlan.Leaf leaf) {
        String path = leaf.field().writePath();
        String row = path.substring(0, path.length() - leaf.setting().path.length() - 1);
        return msg("field.named", Msg.raw(row), msg("field." + KillDropsPlan.word(leaf.setting())));
    }

    /** A bound as a form file spells it ({@code 100}). */
    @Nonnull
    private static String bound(@Nullable Double value) {
        return OwnerFormPlan.raw(OwnerFormKind.NUMBER, value);
    }

    /** Show {@code status} on the status line (red for an error), or hide it for null. */
    private static void status(@Nonnull UICommandBuilder cmd, @Nullable Message status, boolean error) {
        SettingsUiUtil.setStatus(cmd, STATUS, status, error);
    }

    /** Send {@code cmd} with {@code status} on the status line; true, the answer sent. */
    private boolean push(@Nonnull UICommandBuilder cmd, @Nonnull Message status, boolean error) {
        status(cmd, status, error);
        this.sendUpdate(cmd, new UIEventBuilder(), false);
        return true;
    }

    private static int index(@Nullable String field) {
        if (field == null) {
            return -1;
        }
        try {
            return Integer.parseInt(field.trim());
        } catch (NumberFormatException e) {
            return -1;
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

    /** An empty update: the client always hears back. */
    private void answer() {
        this.sendUpdate(new UICommandBuilder(), new UIEventBuilder(), false);
    }
}
