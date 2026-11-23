package com.ziggfreed.common.ui.form;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.owner.OwnerAudience;
import com.ziggfreed.common.owner.OwnerFileBinding;
import com.ziggfreed.common.ui.kit.FormLeaf;
import com.ziggfreed.common.ui.kit.FormLeafPainter;
import com.ziggfreed.common.ui.menu.ZigMenu;
import com.ziggfreed.common.ui.owner.OwnerMenu;
import com.ziggfreed.common.ui.owner.OwnerPageGuard;
import com.ziggfreed.common.ui.owner.OwnerText;
import com.ziggfreed.common.util.SafeLog;

/**
 * The one owner form page: any {@link OwnerFormAsset} drawn by {@link OwnerFormPaint}, each setting showing the value
 * in force, the value under the owner's word and whether the owner's file sets it. Open it through
 * {@link OwnerForms#open}.
 *
 * <p>A change to a control is kept on the page as typed (no repaint, so the field keeps its focus) until Save, which
 * reads every changed setting through {@link OwnerFormWrite}, holds it to its bounds and writes only those leaves
 * through the file's binding, which reads the file again; a refusal names the setting and changes nothing. A Reset
 * takes that one setting's value out of the owner's file at once. Both repaint the rows in place.
 *
 * <p><b>A form of the Entries shape</b> opens on its list ({@link OwnerFormEntries}): Edit opens that entry's settings
 * on this page ({@link OwnerFormPlan#read(OwnerFormAsset, String)}), written through the store's own entry writer, its
 * Back returning to the list; a row's Reset takes the form's settings for that entry out of the owner's file, Remove
 * takes out an entry only the owner's file holds, and Add (where the form allows it) writes a new entry under an id the
 * store's id pattern accepts and no entry has yet, then opens it. An entry is acted on only while the form still lists
 * it, asked again before every write. A setting of the form naming the file's reserved setting (its binding's one
 * {@code $} key, such as a switch for every entry at once) is drawn above the list, with Save shown for it, and changed,
 * saved and reset there like any setting.
 *
 * <p>A form naming an audit shows its problem count on every draw ({@link OwnerFormAudits}); one naming a preview
 * shows its line under the settings ({@link OwnerFormPreviews}), sent again after every change to a control, so it
 * follows what is typed before anything is saved.
 *
 * <p>Every event but Close asks again before it acts ({@link OwnerPageGuard}, which closes the page on a refusal):
 * Back from the form the owner audience, every other event the owner audience and the form's {@code Requires}. Every
 * exit answers the client: Close closes, Back opens the menu or the list, everything else gets an update or a rebuild.
 */
public final class OwnerFormPage extends InteractiveCustomUIPage<OwnerFormEventData> {

    @Nonnull private final String formId;
    @Nullable private final String menuFirst;
    @Nullable private OwnerFormPlan plan;
    @Nonnull private List<String> rows = List.of();
    @Nonnull private final List<FormLeaf> leaves = new ArrayList<>();
    @Nonnull private final Map<Integer, String> typed = new LinkedHashMap<>();

    /** The entry whose settings are open, for a form of the Entries shape; null on its list (or for any other form). */
    @Nullable private String entryId;
    /** The list as last drawn, for a form of the Entries shape; null otherwise. */
    @Nullable private OwnerFormEntries entries;
    /** The line the next build shows, once (what an action on the list answered). */
    @Nullable private Message pending;
    private boolean pendingError;

    OwnerFormPage(@Nonnull PlayerRef playerRef, @Nonnull String formId, @Nullable String menuFirst) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, OwnerFormEventData.CODEC);
        this.formId = formId;
        this.menuFirst = menuFirst;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull Store<EntityStore> store) {
        // The menu theme's paint, the way every framed page of the family takes it (a consumer's theme, else the palette).
        ZigMenu.appendThemed(cmd, OwnerFormPaint.PAGE_TEMPLATE);
        OwnerFormPaint.bind(events);
        plan = null;
        entries = null;
        rows = List.of();
        leaves.clear();
        typed.clear();
        Message status = pending;
        boolean statusError = pendingError;
        pending = null;
        pendingError = false;
        try {
            Player player = store.getComponent(ref, Player.getComponentType());
            OwnerFormAsset form = OwnerFormConfig.getInstance().form(formId);
            if (player == null || !OwnerAudience.mayAdminister(store, ref, player)) {
                OwnerFormPaint.refused(cmd, OwnerText.line("menu.refused"));
                return;
            }
            if (form == null || !OwnerForms.requiresPass(form, this.playerRef, player)) {
                OwnerFormPaint.refused(cmd, OwnerText.line("form.missing"));
                return;
            }
            if (form.entries() != null && (entryId == null || !OwnerFormEntries.lists(form, entryId))) {
                entryId = null;
                OwnerFormEntries read = OwnerFormEntries.read(form);
                // The file's own reserved setting, when its binding declares one the form names, above the list.
                OwnerFormPlan file = OwnerFormPlan.read(form, null,
                        setting -> OwnerForms.fieldShownTo(setting, this.playerRef, player));
                rows = OwnerFormPaint.paintEntries(cmd, events, read, file, this.playerRef);
                entries = read;
                if (!file.settings().isEmpty()) {
                    for (OwnerFormPlan.Setting setting : file.settings()) {
                        leaves.add(OwnerFormPaint.leaf(setting, setting.shown(), setting.set()));
                    }
                    plan = file;
                }
            } else {
                OwnerFormPlan read = OwnerFormPlan.read(form, form.entries() == null ? null : entryId,
                        setting -> OwnerForms.fieldShownTo(setting, this.playerRef, player));
                rows = read.entryId() == null ? OwnerFormPaint.paint(cmd, events, read, this.playerRef)
                        : OwnerFormPaint.paint(cmd, events, read, this.playerRef, OwnerText.line("form.entries.editing",
                                OwnerFormPaint.entryName(read.entryId(),
                                        OwnerFormEntries.nameKeyOf(form, read.entryId()))));
                for (OwnerFormPlan.Setting setting : read.settings()) {
                    leaves.add(OwnerFormPaint.leaf(setting, setting.shown(), setting.set()));
                }
                plan = read;
                OwnerFormPaint.preview(cmd, previewLine(read));
            }
            OwnerFormPaint.audit(cmd, OwnerFormAudits.problems(form.audit()));
            if (status != null) {
                OwnerFormPaint.status(cmd, status, statusError);
            }
        } catch (Throwable t) {
            SafeLog.warn("[owner] the owner form '" + formId + "' could not paint everything", t);
        }
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull OwnerFormEventData data) {
        Player player = ref.isValid() ? store.getComponent(ref, Player.getComponentType()) : null;
        if (player == null || !route(ref, store, player, data)) {
            answer();
        }
    }

    /** Closes, opens, rebuilds or updates for one event; false when it sent nothing. */
    private boolean route(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Player player,
            @Nonnull OwnerFormEventData data) {
        String action = data.action == null ? "" : data.action;
        if (OwnerFormPaint.CLOSE.equals(action)) {
            player.getPageManager().setPage(ref, store, Page.None);
            return true;
        }
        OwnerFormAsset form = OwnerFormConfig.getInstance().form(formId);
        if (OwnerFormPaint.BACK.equals(action)) {
            if (entryId != null && form != null && form.entries() != null) {
                // Back from one entry's settings: the list, asked as every event on the form is.
                if (!OwnerPageGuard.stillAllowed(store, ref, player, this.playerRef, (s, r, p) -> OwnerAudience
                        .mayAdminister(s, r, p) && OwnerForms.requiresPass(form, this.playerRef, p))) {
                    return true;
                }
                entryId = null;
                return rebuild(ref, store, player);
            }
            // The menu's own audience alone: Back leaves the form, so its Requires is not asked.
            return !OwnerPageGuard.stillAllowed(store, ref, player, this.playerRef, OwnerAudience::mayAdminister)
                    || OwnerMenu.open(store, ref, player, menuFirst);
        }
        if (!OwnerPageGuard.stillAllowed(store, ref, player, this.playerRef, (s, r, p) -> OwnerAudience.mayAdminister(
                s, r, p) && form != null && OwnerForms.requiresPass(form, this.playerRef, p))) {
            return true;
        }
        OwnerFormEntries list = entries;
        if (list != null) {
            switch (action) {
                case OwnerFormPaint.OPEN:
                    return open(ref, store, player, list, data.field);
                case OwnerFormPaint.DROP:
                    return drop(ref, store, player, list, data.field);
                case OwnerFormPaint.ADD:
                    return add(ref, store, player, list, data.value);
                default:
                    // A change, Reset or Save on the list is the file's own setting above it (plan), when it has one.
                    break;
            }
        }
        OwnerFormPlan current = plan;
        if (current == null) {
            return false;
        }
        if (current.entryId() != null && !OwnerFormPaint.CHANGE.equals(action)
                && !OwnerFormEntries.lists(current.form(), current.entryId())) {
            // The entry left the form since it opened (a feature switched off, the entry gone): back to the list.
            return rebuild(ref, store, player, OwnerText.line("form.entries.gone"), true);
        }
        return switch (action) {
            case OwnerFormPaint.CHANGE -> keep(current, data.field, data.value) || pushPreview(current);
            case OwnerFormPaint.RESET -> reset(ref, store, player, current, data.field);
            case OwnerFormPaint.SAVE -> save(ref, store, player, current);
            default -> false;
        };
    }

    /** Open the settings of the entry on row {@code field} of the list, while the form still lists it. */
    private boolean open(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Player player,
            @Nonnull OwnerFormEntries list, @Nullable String field) {
        OwnerFormEntries.Row row = list.row(index(field));
        if (row == null) {
            return false;
        }
        if (!OwnerFormEntries.lists(list.form(), row.id())) {
            return rebuild(ref, store, player, OwnerText.line("form.entries.gone"), true);
        }
        entryId = row.id();
        return rebuild(ref, store, player);
    }

    /**
     * Row {@code field}'s Reset (the form's settings for that entry out of the owner's file) or Remove (the whole entry,
     * one only the owner's file holds, where the form allows it), then the list again.
     */
    private boolean drop(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Player player,
            @Nonnull OwnerFormEntries list, @Nullable String field) {
        OwnerFormEntries.Row row = list.row(index(field));
        OwnerFileBinding binding = list.binding();
        if (row == null || binding == null) {
            return false;
        }
        if (!OwnerFormEntries.lists(list.form(), row.id())) {
            return rebuild(ref, store, player, OwnerText.line("form.entries.gone"), true);
        }
        if (list.removable(row)) {
            boolean removed = OwnerFormWrite.removeEntry(binding, row.id());
            return rebuild(ref, store, player, removed ? OwnerText.line("form.entries.removed")
                    : OwnerText.line("form.failed", binding.id()), !removed);
        }
        if (list.resettable(row)) {
            boolean reset = OwnerFormWrite.writeEntry(binding, row.id(), OwnerFormEntries.resetLeaves(list.form(),
                    setting -> OwnerForms.fieldShownTo(setting, this.playerRef, player)));
            return rebuild(ref, store, player, reset ? OwnerText.line("form.reset.done")
                    : OwnerText.line("form.failed", binding.id()), !reset);
        }
        return false;
    }

    /**
     * Add an entry under {@code typedId}, where the form allows it: refused, nothing written, for an id the store's id
     * pattern does not accept or one an entry already has; else written (holding the form's {@code Having} path) and
     * opened, once the form lists it.
     */
    private boolean add(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Player player,
            @Nonnull OwnerFormEntries list, @Nullable String typedId) {
        OwnerFileBinding binding = list.binding();
        if (!list.addable() || binding == null) {
            return false;
        }
        String id = typedId == null ? "" : typedId.trim();
        if (!OwnerFormAsset.isEntry(id) || !binding.acceptsEntryId(id)) {
            return push(new UICommandBuilder(), OwnerText.line("form.entries.bad_id"), true);
        }
        if (OwnerFormEntries.taken(binding, id)) {
            return push(new UICommandBuilder(), OwnerText.line("form.entries.taken", id), true);
        }
        if (!OwnerFormWrite.writeEntry(binding, id, OwnerFormEntries.newEntryLeaves(list.form()))) {
            return push(new UICommandBuilder(), OwnerText.line("form.failed", binding.id()), true);
        }
        if (OwnerFormEntries.lists(list.form(), id)) {
            entryId = id;
        }
        return rebuild(ref, store, player, OwnerText.line("form.entries.added", id), false);
    }

    /**
     * Keep what was typed into a setting's control, unsent; false always, so the caller answers. A value equal to what
     * the row shows is no change (a control may echo a value the page itself pushed), so it is never written.
     */
    private boolean keep(@Nonnull OwnerFormPlan current, @Nullable String field, @Nullable String value) {
        OwnerFormPlan.Setting setting = current.setting(index(field));
        if (setting == null || value == null) {
            return false;
        }
        int i = setting.field().index();
        if (i < leaves.size() && value.equals(leaves.get(i).value())) {
            typed.remove(i);
        } else {
            typed.put(i, value);
        }
        return false;
    }

    /**
     * Take one setting's value out of the owner's file, then repaint its row (or, for a file its binding writes in a
     * shape of its own, draw the form again from the file, since the write may have moved other rows too).
     */
    private boolean reset(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Player player,
            @Nonnull OwnerFormPlan current, @Nullable String field) {
        OwnerFormPlan.Setting setting = current.setting(index(field));
        if (setting == null) {
            return false;
        }
        int i = setting.field().index();
        FormLeaf leaf = leaves.get(i);
        if (!leaf.overridden()) {
            return false;
        }
        if (!OwnerForms.fieldShownTo(setting.authored(), this.playerRef, player)) {
            return push(new UICommandBuilder(), OwnerText.line("form.locked", OwnerFormPaint.leaf(setting, "", false)
                    .label()), true);
        }
        OwnerFileBinding binding = current.binding();
        if (!current.writable() || binding == null) {
            return push(new UICommandBuilder(), cannotWrite(current), true);
        }
        if (!write(current, binding, OwnerFormWrite.reset(setting.field()))) {
            return push(new UICommandBuilder(), OwnerText.line("form.failed", binding.id()), true);
        }
        typed.remove(i);
        if (binding.reshapes()) {
            return rebuild(ref, store, player, OwnerText.line("form.reset.done"), false);
        }
        UICommandBuilder cmd = new UICommandBuilder();
        repaint(cmd, i, OwnerFormPlan.raw(setting.field().kind(), setting.below()), false);
        paintPreview(cmd, current);
        return push(cmd, OwnerText.line("form.reset.done"), false);
    }

    /**
     * Write every changed setting, or name the first one refused; then repaint the rows written (or, for a file its
     * binding writes in a shape of its own, draw the form again from the file).
     */
    private boolean save(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Player player,
            @Nonnull OwnerFormPlan current) {
        OwnerFileBinding binding = current.binding();
        if (!current.writable() || binding == null) {
            return push(new UICommandBuilder(), cannotWrite(current), true);
        }
        if (typed.isEmpty()) {
            return push(new UICommandBuilder(), OwnerText.line("form.unchanged"), false);
        }
        for (Integer i : typed.keySet()) {
            // A setting's own Requires, asked again: the owner may have lost what it asks since the form was drawn.
            OwnerFormPlan.Setting setting = current.setting(i);
            if (setting != null && !OwnerForms.fieldShownTo(setting.authored(), this.playerRef, player)) {
                return push(new UICommandBuilder(), OwnerText.line("form.locked",
                        OwnerFormPaint.leaf(setting, "", false).label()), true);
            }
        }
        List<OwnerFormField> fields = current.fields();
        OwnerFormWrite.Collected collected = OwnerFormWrite.collect(fields, typed);
        if (!collected.ok()) {
            OwnerFormWrite.Refusal refusal = collected.refusal();
            return push(new UICommandBuilder(), refusalLine(current, refusal), true);
        }
        if (!write(current, binding, collected.leaves())) {
            return push(new UICommandBuilder(), OwnerText.line("form.failed", binding.id()), true);
        }
        if (binding.reshapes()) {
            typed.clear();
            return rebuild(ref, store, player, OwnerText.line("form.saved"), false);
        }
        UICommandBuilder cmd = new UICommandBuilder();
        for (OwnerFormField field : fields) {
            if (!typed.containsKey(field.index()) || !collected.leaves().containsKey(field.writePath())) {
                continue;
            }
            Object written = collected.leaves().get(field.writePath());
            OwnerFormPlan.Setting setting = current.setting(field.index());
            if (written == null) {
                repaint(cmd, field.index(), OwnerFormPlan.raw(field.kind(),
                        setting == null ? null : setting.below()), false);
            } else {
                repaint(cmd, field.index(), OwnerFormPlan.raw(field.kind(), written), true);
            }
        }
        typed.clear();
        paintPreview(cmd, current);
        return push(cmd, OwnerText.line("form.saved"), false);
    }

    /**
     * The form's preview for the values on the screen now (each setting's typed text, else what its row shows), or
     * null when the form names none or it has nothing to say.
     */
    @Nullable
    private Message previewLine(@Nonnull OwnerFormPlan current) {
        String id = current.form().preview();
        if (id == null) {
            return null;
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (OwnerFormPlan.Setting setting : current.settings()) {
            int i = setting.field().index();
            String path = setting.authored().path();
            if (path == null) {
                continue;
            }
            String text = typed.containsKey(i) ? typed.get(i) : i < leaves.size() ? leaves.get(i).value()
                    : setting.shown();
            values.put(path, text == null ? "" : text);
        }
        String entry = current.entryId() != null ? current.entryId() : current.form().entry();
        return OwnerFormPreviews.line(id, entry, values);
    }

    /** The preview line into {@code cmd}, for a form that names one. */
    private void paintPreview(@Nonnull UICommandBuilder cmd, @Nonnull OwnerFormPlan current) {
        if (current.form().preview() != null) {
            OwnerFormPaint.preview(cmd, previewLine(current));
        }
    }

    /**
     * After a control changed, the preview kept up with what was typed (the row itself is left alone, so the field
     * keeps its focus); false, nothing sent, for a form that names no preview.
     */
    private boolean pushPreview(@Nonnull OwnerFormPlan current) {
        if (current.form().preview() == null) {
            return false;
        }
        UICommandBuilder cmd = new UICommandBuilder();
        OwnerFormPaint.preview(cmd, previewLine(current));
        this.sendUpdate(cmd, new UIEventBuilder(), false);
        return true;
    }

    /**
     * Write {@code leaves}: inside the plan's entry through the store's entry writer for one entry of an Entries form,
     * else from the file's root through the file's binding.
     */
    private static boolean write(@Nonnull OwnerFormPlan current, @Nonnull OwnerFileBinding binding,
            @Nonnull Map<String, Object> leaves) {
        String entry = current.entryId();
        if (entry == null) {
            return OwnerFormWrite.write(binding, leaves);
        }
        return leaves.isEmpty() || OwnerFormWrite.writeEntry(binding, entry, leaves);
    }

    /** Repaint setting {@code i}'s row with {@code value} in its control, set by the owner's file or not. */
    private void repaint(@Nonnull UICommandBuilder cmd, int i, @Nonnull String value, boolean overridden) {
        if (i < 0 || i >= leaves.size() || i >= rows.size()) {
            return;
        }
        FormLeaf next = leaves.get(i).with(value, overridden,
                OwnerText.line(overridden ? "form.state.set" : "form.state.default"));
        leaves.set(i, next);
        FormLeafPainter.paint(cmd, rows.get(i), next, this.playerRef);
    }

    /** Why this form writes nothing: no installed mod registered its file, or the file cannot be read. */
    @Nonnull
    private static Message cannotWrite(@Nonnull OwnerFormPlan current) {
        OwnerFileBinding binding = current.binding();
        if (binding == null) {
            return OwnerText.line("form.unbound");
        }
        return current.entryId() != null && !binding.keyed() ? OwnerText.line("form.entries.unkeyed", binding.id())
                : OwnerText.line("form.malformed", binding.id());
    }

    /** The line naming a refused setting and why. */
    @Nonnull
    private static Message refusalLine(@Nonnull OwnerFormPlan current, @Nullable OwnerFormWrite.Refusal refusal) {
        if (refusal == null) {
            return OwnerText.line("menu.failed");
        }
        OwnerFormPlan.Setting setting = current.setting(refusal.field().index());
        Message name = setting == null ? OwnerText.line("form.path", refusal.field().writePath())
                : OwnerFormPaint.leaf(setting, "", false).label();
        OwnerFormField field = refusal.field();
        return switch (refusal.problem()) {
            case NUMBER -> OwnerText.line("form.bad.number", name);
            case WHOLE -> OwnerText.line("form.bad.whole", name);
            case CHANCE -> OwnerText.line("form.bad.chance", name);
            case BELOW -> OwnerText.line("form.bad.below", name, bound(field.min()));
            case ABOVE -> OwnerText.line("form.bad.above", name, bound(field.max()));
            case CHOICE -> OwnerText.line("form.bad.choice", name);
            case SIGNED -> OwnerText.line("form.bad.signed", name);
        };
    }

    /** A bound as the form file spells it ({@code 50}, {@code 0.5}). */
    @Nonnull
    private static String bound(@Nullable Double value) {
        return OwnerFormPlan.raw(OwnerFormKind.NUMBER, value);
    }

    /** Draw the page again (the list, or an entry's settings); true, the answer sent. */
    private boolean rebuild(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull Player player) {
        player.getPageManager().openCustomPage(ref, store, this);
        return true;
    }

    /** {@link #rebuild(Ref, Store, Player)} with {@code status} on the status line once drawn. */
    private boolean rebuild(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull Player player, @Nonnull Message status, boolean error) {
        pending = status;
        pendingError = error;
        return rebuild(ref, store, player);
    }

    /** Send {@code cmd} with {@code status} on the status line; true, the answer sent. */
    private boolean push(@Nonnull UICommandBuilder cmd, @Nonnull Message status, boolean error) {
        OwnerFormPaint.status(cmd, status, error);
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

    /** An empty update: the client always hears back. */
    private void answer() {
        this.sendUpdate(new UICommandBuilder(), new UIEventBuilder(), false);
    }
}
