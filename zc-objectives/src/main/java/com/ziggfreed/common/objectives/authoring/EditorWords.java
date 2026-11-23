package com.ziggfreed.common.objectives.authoring;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.authoring.Pickers;
import com.ziggfreed.common.authoring.WizardField;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.ui.form.FieldSpec;

/**
 * Where one editor's words resolve (its shipped lang file, the file name being the key prefix), and the wizard rows
 * labelled from it. The shared slot pages ({@link ObjectiveSlotPages}, {@link RewardSlotPages}) read the same key
 * names from whichever editor's words they are given, so a consumer's own editor reuses them by handing over words
 * of its own whose lang file holds those key names.
 */
public final class EditorWords {

    /** The key family every line resolves under (the shipped file name, minus {@code .lang}, and a dot). */
    @Nonnull private final String prefix;

    /** Words resolving under {@code prefix}: a lang file's name and a dot ({@code yourmod.youreditor.}). */
    public EditorWords(@Nonnull String prefix) {
        this.prefix = prefix;
    }

    /** One of the editor's lines, {@code {0}, {1}...} bound from {@code args}. */
    @Nonnull
    public Message line(@Nonnull String key, @Nonnull Object... args) {
        return Msg.tr(prefix, key, args);
    }

    /** The full key of one of the editor's lines, for a field label, a hint or a step name. */
    @Nonnull
    public String key(@Nonnull String key) {
        return prefix + key;
    }

    // ---------------------------------------------------------------- rows

    @Nonnull
    public WizardField text(@Nonnull String id, @Nonnull String labelKey) {
        return WizardField.of(FieldSpec.text(id, id, key(labelKey)));
    }

    @Nonnull
    public WizardField integer(@Nonnull String id, @Nonnull String labelKey) {
        return WizardField.of(FieldSpec.integer(id, id, key(labelKey)));
    }

    @Nonnull
    public WizardField number(@Nonnull String id, @Nonnull String labelKey) {
        return WizardField.of(FieldSpec.number(id, id, key(labelKey)));
    }

    @Nonnull
    public WizardField toggle(@Nonnull String id, @Nonnull String labelKey) {
        return WizardField.of(FieldSpec.toggle(id, key(labelKey)));
    }

    @Nonnull
    public WizardField note(@Nonnull String id, @Nonnull String labelKey) {
        return WizardField.of(FieldSpec.note(id, key(labelKey)));
    }

    @Nonnull
    public WizardField header(@Nonnull String id, @Nonnull String labelKey) {
        return WizardField.of(FieldSpec.header(id, key(labelKey)));
    }

    @Nonnull
    public WizardField dropdown(@Nonnull String id, @Nonnull String labelKey, @Nonnull String[] values) {
        return WizardField.of(FieldSpec.dropdown(id, id, key(labelKey), values));
    }

    @Nonnull
    public WizardField picker(@Nonnull String id, @Nonnull String labelKey, @Nonnull Pickers.Source source) {
        return WizardField.picker(id, id, key(labelKey), source);
    }

    /** A row labelled with words built as it paints (its spec's label key is its field id). */
    @Nonnull
    public static WizardField own(@Nonnull FieldSpec spec, @Nonnull Message words) {
        return WizardField.of(spec).withLabel(words);
    }
}
