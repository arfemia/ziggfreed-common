package com.ziggfreed.common.authoring;

import java.util.List;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.ui.form.FieldKind;
import com.ziggfreed.common.ui.form.FieldSpec;

/**
 * One row of a wizard step: a {@link FieldSpec} as the settings form draws it, or a picker (a dropdown of a
 * {@link Pickers.Source}'s ids), and optionally a reading the world can fill it from, which adds a "Pick in the
 * world" button under the step's rows. Immutable; the {@code with...} and {@link #fromWorld} calls return copies.
 *
 * <p>A label or hint key is a full registered lang key, resolved on the client ({@code mymod.wizard.giver}). A row
 * whose words are built as it paints (a numbered heading, a parameter a registry names) carries its own label
 * instead ({@link #withLabel}), with its field id as the spec's label key so no two rows share one.
 */
public final class WizardField {

    @Nonnull private final FieldSpec spec;
    @Nullable private final Pickers.Source picker;
    @Nullable private final Function<WorldPick, String> fromWorld;
    @Nullable private final Message label;

    private WizardField(@Nonnull FieldSpec spec, @Nullable Pickers.Source picker,
            @Nullable Function<WorldPick, String> fromWorld, @Nullable Message label) {
        this.spec = spec;
        this.picker = picker;
        this.fromWorld = fromWorld;
        this.label = label;
    }

    /** A settings-form row as it is: text, number, toggle, dropdown, header or note. */
    @Nonnull
    public static WizardField of(@Nonnull FieldSpec spec) {
        return new WizardField(spec, null, null, null);
    }

    /**
     * A picker: a dropdown of {@code source}'s ids, read each time the step paints and narrowed by the step's search
     * row; collected like any dropdown (blank for none).
     */
    @Nonnull
    public static WizardField picker(@Nonnull String id, @Nonnull String leafPath, @Nonnull String labelKey,
            @Nonnull Pickers.Source source) {
        return new WizardField(FieldSpec.dropdown(id, leafPath, labelKey, new String[0]), source, null, null);
    }

    /**
     * This field, filled from what an authoring tool points at: a "Pick in the world" button closes the page, the
     * next click with a tool reads {@code reading} off the pick ({@code WorldPick::blockId}, {@code
     * WorldPick::npcRole}, {@code WorldPick::itemId}, {@code WorldPick::position}) and the page opens again with it
     * filled. A reading that answers null or blank fills nothing and says the pick does not fit.
     *
     * @throws IllegalArgumentException for a header or note, which holds no value
     */
    @Nonnull
    public WizardField fromWorld(@Nonnull Function<WorldPick, String> reading) {
        if (spec.kind() == FieldKind.HEADER || spec.kind() == FieldKind.NOTE) {
            throw new IllegalArgumentException("a " + spec.kind() + " row holds no value to pick: " + spec.id());
        }
        return new WizardField(spec, picker, reading, label);
    }

    /** This field with a hint line under it ({@link FieldSpec#withHint}). */
    @Nonnull
    public WizardField withHint(@Nonnull String hintKey) {
        return new WizardField(spec.withHint(hintKey), picker, fromWorld, label);
    }

    /**
     * This field labelled with {@code words} in place of its spec's label key: the row, its "Pick in the world"
     * button and the pick's notice all say them. Give the spec a label key no other row of the step uses (its field
     * id), since the frame finds the words by it.
     */
    @Nonnull
    public WizardField withLabel(@Nonnull Message words) {
        return new WizardField(spec, picker, fromWorld, words);
    }

    /** What the row is called: its own label ({@link #withLabel}), else its spec's label key resolved on the client. */
    @Nonnull
    public Message label() {
        return label != null ? label : AuthoringWizardPage.label(spec.labelKey());
    }

    /** True when the row carries its own label ({@link #withLabel}). */
    boolean labelledOwn() {
        return label != null;
    }

    @Nonnull
    public String id() {
        return spec.id();
    }

    /** The form row as authored; a picker's carries no entries until {@link #specFor} fills them. */
    @Nonnull
    public FieldSpec spec() {
        return spec;
    }

    @Nullable
    public Pickers.Source picker() {
        return picker;
    }

    @Nullable
    public Function<WorldPick, String> fromWorld() {
        return fromWorld;
    }

    /** True for a header or note, which holds no value. */
    boolean isLabelOnly() {
        return spec.kind() == FieldKind.HEADER || spec.kind() == FieldKind.NOTE;
    }

    /**
     * The row to draw now: the spec as it is, or a picker's dropdown filled from its source under {@code filter},
     * keeping {@code current}. A source that throws lists nothing but the current value.
     */
    @Nonnull
    FieldSpec specFor(@Nullable String filter, @Nullable String current) {
        if (picker == null) {
            return spec;
        }
        List<String> entries;
        try {
            entries = Pickers.entries(picker.ids(), filter, current, Pickers.LIMIT);
        } catch (Throwable t) {
            entries = Pickers.entries(List.of(), null, current, Pickers.LIMIT);
        }
        String leafPath = spec.leafPath();
        FieldSpec filled = FieldSpec.dropdown(spec.id(), leafPath == null ? spec.id() : leafPath, spec.labelKey(),
                entries.toArray(new String[0]));
        return spec.hintKey() == null ? filled : filled.withHint(spec.hintKey());
    }
}
