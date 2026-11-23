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
import com.ziggfreed.common.ui.ZigRichButton;
import com.ziggfreed.common.ui.kit.FormLeaf;
import com.ziggfreed.common.ui.kit.FormLeafPainter;
import com.ziggfreed.common.ui.kit.Hero;
import com.ziggfreed.common.ui.kit.HeroPainter;
import com.ziggfreed.common.ui.kit.ZigStyles;
import com.ziggfreed.common.ui.owner.OwnerText;

/**
 * Paints the owner form ({@code Pages/ZigOwnerFormPage.ui}) from its plan ({@link OwnerFormPlan}): the frame's hero
 * (the form's title and line), each section's heading and note on the settings form's own rows, and each setting on
 * the kit's owner leaf row ({@link FormLeafPainter}), with the words the row's state, Default line and Reset read; and
 * a form of the Entries shape's list ({@link OwnerFormEntries}), an entry row ({@code Pages/ZigFormEntryRow.ui}) each,
 * with the line for adding one; and the two lines a form may name: its audit's problem count and its preview. The one
 * place the form's selectors are spelt; {@code KitSelectorsTest} walks every one.
 */
public final class OwnerFormPaint {

    /** The page document. */
    public static final String PAGE_TEMPLATE = "Pages/ZigOwnerFormPage.ui";

    /** A section's heading and note: the settings form's own rows. */
    public static final String HEADING_TEMPLATE = "Pages/ZigFormHeaderRow.ui";
    public static final String NOTE_TEMPLATE = "Pages/ZigFormNoteRow.ui";

    /** One entry of a form of the Entries shape. */
    public static final String ENTRY_TEMPLATE = "Pages/ZigFormEntryRow.ui";

    /** What an event round-trips. */
    public static final String ACTION = "Action";
    public static final String FIELD = "Field";
    public static final String CLOSE = "close";
    public static final String BACK = "back";
    public static final String SAVE = "save";
    public static final String CHANGE = "field";
    public static final String RESET = "reset";
    /** An entry row's Edit, its index in {@link #FIELD}. */
    public static final String OPEN = "open";
    /** An entry row's Reset or Remove, its index in {@link #FIELD}. */
    public static final String DROP = "drop";
    /** The add line's button, the typed id under {@link FormLeafPainter#VALUE_KEY}. */
    public static final String ADD = "add";

    static final String FIELDS = "#OwnerFormFields";
    static final String STATUS = "#OwnerFormStatus";
    static final String AUDIT = "#OwnerFormAudit";
    static final String PREVIEW = "#OwnerFormPreview";
    static final String BACK_BUTTON = "#OwnerFormBack";
    static final String SAVE_BUTTON = "#OwnerFormSave";
    static final String ADD_ROW = "#OwnerFormAdd";
    static final String ADD_ID = "#OwnerFormAddId";
    static final String ADD_BUTTON = "#OwnerFormAddButton";

    /** A switch's two entries' values, as {@link SettingsForm} reads a three-state field. */
    static final String ON = "on";
    static final String OFF = "off";

    private OwnerFormPaint() {
    }

    /**
     * The page's own bindings: the frame's close button, Back, Save, and the add line's button (bound even where the
     * line stays hidden), which carries the typed id.
     */
    public static void bind(@Nonnull UIEventBuilder events) {
        events.addEventBinding(CustomUIEventBindingType.Activating, ZigFrame.CLOSE, EventData.of(ACTION, CLOSE));
        events.addEventBinding(CustomUIEventBindingType.Activating, BACK_BUTTON, EventData.of(ACTION, BACK));
        events.addEventBinding(CustomUIEventBindingType.Activating, SAVE_BUTTON, EventData.of(ACTION, SAVE));
        events.addEventBinding(CustomUIEventBindingType.Activating, ADD_BUTTON,
                EventData.of(ACTION, ADD).append(FormLeafPainter.VALUE_KEY, ADD_ID + ".Value"), false);
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
        return paint(cmd, events, plan, viewer, words(plan.form().description()));
    }

    /**
     * {@link #paint(UICommandBuilder, UIEventBuilder, OwnerFormPlan, PlayerRef)} with {@code heroLine} under the
     * form's title in place of its description: one entry's settings say which entry they are.
     */
    @Nonnull
    public static List<String> paint(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull OwnerFormPlan plan, @Nullable PlayerRef viewer, @Nullable Message heroLine) {
        OwnerFormAsset form = plan.form();
        HeroPainter.paint(cmd, new Hero(null, title(form), heroLine, null));
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

    /**
     * Paint a form of the Entries shape's list, appended already: the hero, the status line (the file's trouble when
     * it cannot be written), the add line where the form allows adding, then an entry row for each listed entry (or a
     * line saying there is none), each row's Edit and Reset or Remove carrying its index. Save is hidden: each entry
     * saves on its own settings.
     */
    public static void paintEntries(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull OwnerFormEntries entries, @Nullable PlayerRef viewer) {
        OwnerFormAsset form = entries.form();
        HeroPainter.paint(cmd, new Hero(null, title(form), words(form.description()), null));
        if (entries.binding() == null) {
            status(cmd, OwnerText.line("form.unbound"), true);
        } else if (!entries.keyed()) {
            status(cmd, OwnerText.line("form.entries.unkeyed", entries.binding().id()), true);
        } else if (entries.malformed()) {
            status(cmd, OwnerText.line("form.malformed", entries.binding().id()), true);
        }
        cmd.set(SAVE_BUTTON + ".Visible", false);
        cmd.set(ADD_ROW + ".Visible", entries.addable());
        List<OwnerFormEntries.Row> rows = entries.rows();
        if (rows.isEmpty()) {
            cmd.append(FIELDS, NOTE_TEMPLATE);
            cmd.set(FIELDS + "[0] #Note.TextSpans", OwnerText.line("form.entries.empty"));
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            OwnerFormEntries.Row row = rows.get(i);
            String at = FIELDS + "[" + i + "]";
            cmd.append(FIELDS, ENTRY_TEMPLATE);
            cmd.set(at + " #Title.TextSpans", entryName(row.id(), row.nameKey()));
            if (row.nameKey() != null) {
                cmd.set(at + " #Line.TextSpans", OwnerText.line("form.entries.id", row.id()));
                cmd.set(at + " #Line.Visible", true);
            }
            boolean ownWord = row.set() || row.added();
            cmd.set(at + " #State.TextSpans", OwnerText.line(row.added() ? "form.state.added"
                    : row.set() ? "form.state.set" : "form.state.default"));
            ZigStyles.apply(cmd, at + " #State.Style",
                    ownWord ? ZigStyles.Name.STATE_ACTIVE : ZigStyles.Name.STATE_NEUTRAL, viewer);
            ZigRichButton.text(cmd, at + " #Open", OwnerText.line("form.entries.edit"));
            boolean remove = entries.removable(row);
            if (remove || entries.resettable(row)) {
                ZigRichButton.text(cmd, at + " #Reset", OwnerText.line(remove ? "form.entries.remove" : "form.reset"));
                cmd.set(at + " #Reset.Visible", true);
            }
            String index = String.valueOf(i);
            events.addEventBinding(CustomUIEventBindingType.Activating, at + " #Open",
                    EventData.of(ACTION, OPEN).append(FIELD, index), false);
            events.addEventBinding(CustomUIEventBindingType.Activating, at + " #Reset",
                    EventData.of(ACTION, DROP).append(FIELD, index), false);
        }
    }

    /** An entry's name: the key its form's {@code Name} path reads, else its id. */
    @Nonnull
    public static Message entryName(@Nonnull String id, @Nullable String nameKey) {
        return nameKey != null ? ContentKeys.tr(nameKey) : OwnerText.line("form.entries.name", id);
    }

    /** Show {@code status} on the status line (red for an error), or hide it for null. */
    public static void status(@Nonnull UICommandBuilder cmd, @Nullable Message status, boolean error) {
        SettingsUiUtil.setStatus(cmd, STATUS, status, error);
    }

    /**
     * Show how many problems the form's audit finds ({@code problems}, red above none), or hide the line for null (a
     * form naming no audit, or one no installed mod registered).
     */
    public static void audit(@Nonnull UICommandBuilder cmd, @Nullable Integer problems) {
        if (problems == null) {
            SettingsUiUtil.setStatus(cmd, AUDIT, null, false);
            return;
        }
        SettingsUiUtil.setStatus(cmd, AUDIT, problems > 0 ? OwnerText.line("form.audit.problems", problems)
                : OwnerText.line("form.audit.none"), problems > 0);
    }

    /** Show the form's preview line under its settings, in the body ink, or hide it for null. */
    public static void preview(@Nonnull UICommandBuilder cmd, @Nullable Message line) {
        if (line == null) {
            cmd.set(PREVIEW + ".Visible", false);
            return;
        }
        cmd.set(PREVIEW + ".TextSpans", line);
        cmd.set(PREVIEW + ".Visible", true);
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
