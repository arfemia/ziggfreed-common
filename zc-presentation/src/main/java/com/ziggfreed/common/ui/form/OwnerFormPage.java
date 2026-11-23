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
 * <p>Every event but Close asks again before it acts ({@link OwnerPageGuard}, which closes the page on a refusal):
 * Back the owner audience, every other event the owner audience and the form's {@code Requires}. Every exit answers
 * the client: Close closes, Back opens the menu, everything else gets an update.
 */
public final class OwnerFormPage extends InteractiveCustomUIPage<OwnerFormEventData> {

    @Nonnull private final String formId;
    @Nullable private final String menuFirst;
    @Nullable private OwnerFormPlan plan;
    @Nonnull private List<String> rows = List.of();
    @Nonnull private final List<FormLeaf> leaves = new ArrayList<>();
    @Nonnull private final Map<Integer, String> typed = new LinkedHashMap<>();

    OwnerFormPage(@Nonnull PlayerRef playerRef, @Nonnull String formId, @Nullable String menuFirst) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, OwnerFormEventData.CODEC);
        this.formId = formId;
        this.menuFirst = menuFirst;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull Store<EntityStore> store) {
        cmd.append(OwnerFormPaint.PAGE_TEMPLATE);
        OwnerFormPaint.bind(events);
        plan = null;
        rows = List.of();
        leaves.clear();
        typed.clear();
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
            OwnerFormPlan read = OwnerFormPlan.read(form);
            rows = OwnerFormPaint.paint(cmd, events, read, this.playerRef);
            for (OwnerFormPlan.Setting setting : read.settings()) {
                leaves.add(OwnerFormPaint.leaf(setting, setting.shown(), setting.set()));
            }
            plan = read;
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

    /** Closes, opens or updates for one event; false when it sent nothing. */
    private boolean route(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Player player,
            @Nonnull OwnerFormEventData data) {
        String action = data.action == null ? "" : data.action;
        if (OwnerFormPaint.CLOSE.equals(action)) {
            player.getPageManager().setPage(ref, store, Page.None);
            return true;
        }
        if (OwnerFormPaint.BACK.equals(action)) {
            // The menu's own audience alone: Back leaves the form, so its Requires is not asked.
            return !OwnerPageGuard.stillAllowed(store, ref, player, this.playerRef, OwnerAudience::mayAdminister)
                    || OwnerMenu.open(store, ref, player, menuFirst);
        }
        OwnerFormPlan current = plan;
        OwnerFormAsset form = OwnerFormConfig.getInstance().form(formId);
        if (!OwnerPageGuard.stillAllowed(store, ref, player, this.playerRef, (s, r, p) -> OwnerAudience.mayAdminister(
                s, r, p) && form != null && OwnerForms.requiresPass(form, this.playerRef, p))) {
            return true;
        }
        if (current == null) {
            return false;
        }
        return switch (action) {
            case OwnerFormPaint.CHANGE -> keep(current, data.field, data.value);
            case OwnerFormPaint.RESET -> reset(current, data.field);
            case OwnerFormPaint.SAVE -> save(current);
            default -> false;
        };
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

    /** Take one setting's value out of the owner's file, then repaint its row. */
    private boolean reset(@Nonnull OwnerFormPlan current, @Nullable String field) {
        OwnerFormPlan.Setting setting = current.setting(index(field));
        if (setting == null) {
            return false;
        }
        int i = setting.field().index();
        FormLeaf leaf = leaves.get(i);
        if (!leaf.overridden()) {
            return false;
        }
        OwnerFileBinding binding = current.binding();
        if (!current.writable() || binding == null) {
            return push(new UICommandBuilder(), cannotWrite(current), true);
        }
        if (!OwnerFormWrite.write(binding, OwnerFormWrite.reset(setting.field()))) {
            return push(new UICommandBuilder(), OwnerText.line("form.failed", binding.id()), true);
        }
        typed.remove(i);
        UICommandBuilder cmd = new UICommandBuilder();
        repaint(cmd, i, OwnerFormPlan.raw(setting.field().kind(), setting.below()), false);
        return push(cmd, OwnerText.line("form.reset.done"), false);
    }

    /** Write every changed setting, or name the first one refused; then repaint the rows written. */
    private boolean save(@Nonnull OwnerFormPlan current) {
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
            OwnerFormWrite.Refusal refusal = collected.refusal();
            return push(new UICommandBuilder(), refusalLine(current, refusal), true);
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
            OwnerFormPlan.Setting setting = current.setting(field.index());
            if (written == null) {
                repaint(cmd, field.index(), OwnerFormPlan.raw(field.kind(),
                        setting == null ? null : setting.below()), false);
            } else {
                repaint(cmd, field.index(), OwnerFormPlan.raw(field.kind(), written), true);
            }
        }
        typed.clear();
        return push(cmd, OwnerText.line("form.saved"), false);
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
        return binding == null ? OwnerText.line("form.unbound") : OwnerText.line("form.malformed", binding.id());
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
        };
    }

    /** A bound as the form file spells it ({@code 50}, {@code 0.5}). */
    @Nonnull
    private static String bound(@Nullable Double value) {
        return OwnerFormPlan.raw(OwnerFormKind.NUMBER, value);
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
