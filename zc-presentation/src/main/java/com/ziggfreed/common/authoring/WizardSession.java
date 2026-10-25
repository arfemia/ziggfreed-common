package com.ziggfreed.common.authoring;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.ui.form.FieldSpec;
import com.ziggfreed.common.ui.form.FormResult;
import com.ziggfreed.common.ui.form.SettingsForm;
import com.ziggfreed.common.ui.toast.ToastSpec;
import com.ziggfreed.common.util.SafeLog;

/**
 * One wizard open for one admin: every field's raw value across every step (the form's own strings, as
 * {@link SettingsForm} keeps them: a toggle is {@code "on"} or blank), the step showing, the picker search, and the
 * id of what it makes. It outlives its page: a pick from the world closes the page and the next click opens a new
 * one on this same session. World thread.
 */
public final class WizardSession {

    @Nonnull private final String wizardId;
    @Nonnull private final AuthoringWizard wizard;
    @Nonnull private final Map<String, String> values = new LinkedHashMap<>();
    private int step;
    @Nonnull private String filter = "";
    @Nullable private String id;
    private boolean saved;
    private volatile boolean saving;
    @Nullable private ToastSpec notice;

    WizardSession(@Nonnull String wizardId, @Nonnull AuthoringWizard wizard) {
        this.wizardId = wizardId;
        this.wizard = wizard;
    }

    /** The id the wizard is registered under, which names the author node its Save asks for. */
    @Nonnull
    public String wizardId() {
        return wizardId;
    }

    @Nonnull
    public AuthoringWizard wizard() {
        return wizard;
    }

    // ---------------------------------------------------------------- values

    /** The raw value of {@code fieldId}, or {@code ""} when it holds none. */
    @Nonnull
    public String value(@Nonnull String fieldId) {
        String v = values.get(fieldId);
        return v == null ? "" : v;
    }

    /** Set (or with null, clear) the raw value of {@code fieldId}. */
    public void put(@Nonnull String fieldId, @Nullable String value) {
        if (value == null) {
            values.remove(fieldId);
        } else {
            values.put(fieldId, value);
        }
    }

    /** True when {@code fieldId}'s toggle is on. */
    public boolean isOn(@Nonnull String fieldId) {
        return "on".equals(value(fieldId));
    }

    /** Every raw value, by field id. A read-only view. */
    @Nonnull
    public Map<String, String> values() {
        return Collections.unmodifiableMap(values);
    }

    /**
     * Every step's values parsed into leaves by their fields' kinds and leaf paths, the form's own parse
     * ({@link SettingsForm#collectLeaves}): the first value that does not read is the result's error. A toggle is
     * never a leaf; read it with {@link #isOn}.
     */
    @Nonnull
    public FormResult collect(boolean blankIsInherit) {
        List<FieldSpec> specs = new ArrayList<>();
        for (WizardStep s : steps()) {
            for (WizardField field : s.fields()) {
                specs.add(field.specFor(null, value(field.id())));
            }
        }
        SettingsForm form = new SettingsForm(specs, Msg.raw(""), Msg.raw(""));
        form.seed(values);
        return form.collectLeaves(blankIsInherit);
    }

    // ---------------------------------------------------------------- the id

    /** The id of what this session makes or edits, or null until one is minted or kept. */
    @Nullable
    public synchronized String id() {
        return id;
    }

    /** The id, minted by {@code mint} the first time ({@code ServerContent.mintId}) and the same every time after. */
    @Nonnull
    public synchronized String idOr(@Nonnull Supplier<String> mint) {
        if (id == null) {
            id = mint.get();
        }
        return id;
    }

    /** Edit the saved asset {@code existing}: its id is kept and never renamed. Ignored once the session has an id. */
    public synchronized void keepId(@Nonnull String existing) {
        if (id == null && !existing.isBlank()) {
            id = existing;
        }
    }

    /** True once a save of this session has landed. */
    public boolean saved() {
        return saved;
    }

    // ---------------------------------------------------------------- the frame's own state

    /** The step showing, from 0. */
    public int step() {
        return step;
    }

    /** Show step {@code index} (kept inside the wizard's steps when the page paints). */
    public void step(int index) {
        this.step = Math.max(0, index);
    }

    /** The steps as they stand now: the wizard's, guarded, and never empty. */
    @Nonnull
    List<WizardStep> steps() {
        List<WizardStep> steps;
        try {
            steps = wizard.steps(this);
        } catch (Throwable t) {
            SafeLog.warn("[authoring] a wizard's steps could not be read: " + t.getMessage());
            steps = null;
        }
        return steps == null || steps.isEmpty() ? List.of(new WizardStep(AuthoringText.key("wizard.preview.empty"),
                List.of())) : steps;
    }

    /** The field {@code fieldId} on any step, or null. */
    @Nullable
    WizardField field(@Nonnull String fieldId) {
        for (WizardStep s : steps()) {
            for (WizardField field : s.fields()) {
                if (field.id().equals(fieldId)) {
                    return field;
                }
            }
        }
        return null;
    }

    @Nonnull
    String filter() {
        return filter;
    }

    void filter(@Nullable String text) {
        this.filter = text == null ? "" : text.trim();
    }

    boolean saving() {
        return saving;
    }

    void saving(boolean now) {
        this.saving = now;
    }

    void markSaved() {
        this.saved = true;
    }

    /** A toast for the page's next paint (a pick's outcome), handed over once. */
    void notice(@Nullable ToastSpec spec) {
        this.notice = spec;
    }

    @Nullable
    ToastSpec takeNotice() {
        ToastSpec spec = notice;
        notice = null;
        return spec;
    }
}
