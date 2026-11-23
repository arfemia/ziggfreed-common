package com.ziggfreed.common.ui.form;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.LocalizableString;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.ui.SettingsUiUtil;
import com.ziggfreed.common.ui.ZigFrame;
import com.ziggfreed.common.ui.kit.FormLeaf;
import com.ziggfreed.common.ui.kit.FormLeafPainter;
import com.ziggfreed.common.ui.kit.Hero;
import com.ziggfreed.common.ui.kit.HeroPainter;
import com.ziggfreed.common.ui.owner.OwnerText;

/**
 * Paints the owner form ({@code Pages/ZigOwnerFormPage.ui}) from its plan ({@link OwnerFormPlan}): the frame's hero
 * (the form's title and line), each section's heading and note on the settings form's own rows, and each setting on
 * the kit's owner leaf row ({@link FormLeafPainter}), with the words the row's state, Default line and Reset read. The
 * one place the form's selectors are spelt; {@code KitSelectorsTest} walks every one.
 */
public final class OwnerFormPaint {

    /** The page document. */
    public static final String PAGE_TEMPLATE = "Pages/ZigOwnerFormPage.ui";

    /** A section's heading and note: the settings form's own rows. */
    public static final String HEADING_TEMPLATE = "Pages/ZigFormHeaderRow.ui";
    public static final String NOTE_TEMPLATE = "Pages/ZigFormNoteRow.ui";

    /** What an event round-trips. */
    public static final String ACTION = "Action";
    public static final String FIELD = "Field";
    public static final String CLOSE = "close";
    public static final String BACK = "back";
    public static final String SAVE = "save";
    public static final String CHANGE = "field";
    public static final String RESET = "reset";

    static final String FIELDS = "#OwnerFormFields";
    static final String STATUS = "#OwnerFormStatus";
    static final String BACK_BUTTON = "#OwnerFormBack";
    static final String SAVE_BUTTON = "#OwnerFormSave";

    /** A switch's two entries' values, as {@link SettingsForm} reads a three-state field. */
    static final String ON = "on";
    static final String OFF = "off";

    private OwnerFormPaint() {
    }

    /** The page's own bindings: the frame's close button, Back and Save. */
    public static void bind(@Nonnull UIEventBuilder events) {
        events.addEventBinding(CustomUIEventBindingType.Activating, ZigFrame.CLOSE, EventData.of(ACTION, CLOSE));
        events.addEventBinding(CustomUIEventBindingType.Activating, BACK_BUTTON, EventData.of(ACTION, BACK));
        events.addEventBinding(CustomUIEventBindingType.Activating, SAVE_BUTTON, EventData.of(ACTION, SAVE));
    }

    /**
     * Paint the form, appended already: the hero, the status line (the file's trouble when it cannot be written),
     * then every line of {@code plan}. Save is switched off on a form that cannot write.
     *
     * @return each setting's row selector, by the setting's index
     */
    @Nonnull
    public static List<String> paint(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull OwnerFormPlan plan, @Nullable PlayerRef viewer) {
        OwnerFormAsset form = plan.form();
        HeroPainter.paint(cmd, new Hero(null, title(form), words(form.description()), null));
        if (plan.binding() == null) {
            status(cmd, OwnerText.line("form.unbound"), true);
        } else if (plan.malformed()) {
            status(cmd, OwnerText.line("form.malformed", plan.binding().id()), true);
        }
        cmd.set(SAVE_BUTTON + ".Disabled", !plan.writable());
        List<String> rows = new ArrayList<>();
        int at = 0;
        for (OwnerFormPlan.Line line : plan.lines()) {
            String row = FIELDS + "[" + at + "]";
            if (line.heading() != null) {
                cmd.append(FIELDS, HEADING_TEMPLATE);
                cmd.set(row + " #Title.TextSpans", ContentKeys.tr(line.heading()));
            } else if (line.note() != null) {
                cmd.append(FIELDS, NOTE_TEMPLATE);
                cmd.set(row + " #Note.TextSpans", ContentKeys.tr(line.note()));
            } else if (line.setting() != null) {
                OwnerFormPlan.Setting setting = line.setting();
                String index = String.valueOf(setting.field().index());
                rows.add(FormLeafPainter.append(cmd, events, FIELDS, at, leaf(setting, setting.shown(), setting.set()),
                        EventData.of(ACTION, CHANGE).append(FIELD, index),
                        EventData.of(ACTION, RESET).append(FIELD, index), viewer));
            } else {
                continue;
            }
            at++;
        }
        return rows;
    }

    /** The form for a player who may not see it, or a form gone from the server: the hero saying so, Save off. */
    public static void refused(@Nonnull UICommandBuilder cmd, @Nonnull Message why) {
        HeroPainter.paint(cmd, new Hero(null, OwnerText.line("menu.title"), why, null));
        cmd.set(SAVE_BUTTON + ".Disabled", true);
    }

    /** Show {@code status} on the status line (red for an error), or hide it for null. */
    public static void status(@Nonnull UICommandBuilder cmd, @Nullable Message status, boolean error) {
        SettingsUiUtil.setStatus(cmd, STATUS, status, error);
    }

    /**
     * The row {@code setting} is drawn as with {@code value} in its control: its words, its control and choices, the
     * value under the owner's word, and whether the owner's file sets it ({@code overridden}).
     */
    @Nonnull
    public static FormLeaf leaf(@Nonnull OwnerFormPlan.Setting setting, @Nonnull String value, boolean overridden) {
        OwnerFormAsset.Field authored = setting.authored();
        OwnerFormKind kind = setting.field().kind();
        Message label = authored.label() != null ? ContentKeys.tr(authored.label())
                : OwnerText.line("form.path", setting.field().writePath());
        return new FormLeaf(label, words(authored.hint()), kind.picked() ? FormLeaf.Control.CHOICE
                : FormLeaf.Control.FIELD, value, choices(authored, kind), defaultLine(setting),
                overridden, OwnerText.line(overridden ? "form.state.set" : "form.state.default"),
                OwnerText.line("form.reset"));
    }

    /** The form's title: its key, else its id in the untitled line. */
    @Nonnull
    static Message title(@Nonnull OwnerFormAsset form) {
        return form.title() != null ? ContentKeys.tr(form.title()) : OwnerText.line("form.untitled", form.getId());
    }

    @Nullable
    private static Message words(@Nullable String key) {
        return key == null ? null : ContentKeys.tr(key);
    }

    @Nonnull
    private static List<DropdownEntryInfo> choices(@Nonnull OwnerFormAsset.Field authored, @Nonnull OwnerFormKind kind) {
        List<DropdownEntryInfo> out = new ArrayList<>();
        if (kind == OwnerFormKind.SWITCH) {
            out.add(new DropdownEntryInfo(LocalizableString.fromMessageId(OwnerText.key("form.on")), ON));
            out.add(new DropdownEntryInfo(LocalizableString.fromMessageId(OwnerText.key("form.off")), OFF));
        } else if (kind == OwnerFormKind.CHOICE) {
            for (OwnerFormAsset.Choice choice : authored.choices()) {
                String label = choice.label();
                out.add(new DropdownEntryInfo(label != null
                        ? LocalizableString.fromMessageId(ContentKeys.resolved(label))
                        : LocalizableString.fromString(choice.value()), choice.value()));
            }
        }
        return out;
    }

    /** "Default: 200", the switch's word, the choice's words, or "No default" when the layer under says nothing. */
    @Nonnull
    private static Message defaultLine(@Nonnull OwnerFormPlan.Setting setting) {
        Object below = setting.below();
        OwnerFormKind kind = setting.field().kind();
        String raw = OwnerFormPlan.raw(kind, below);
        if (raw.isEmpty()) {
            return OwnerText.line("form.default.none");
        }
        if (kind == OwnerFormKind.SWITCH) {
            return OwnerText.line("form.default", OwnerText.line(ON.equals(raw) ? "form.on" : "form.off"));
        }
        if (kind == OwnerFormKind.CHOICE) {
            for (OwnerFormAsset.Choice choice : setting.authored().choices()) {
                if (raw.equals(choice.value()) && choice.label() != null) {
                    return OwnerText.line("form.default", ContentKeys.tr(choice.label()));
                }
            }
        }
        return OwnerText.line("form.default", raw);
    }
}
