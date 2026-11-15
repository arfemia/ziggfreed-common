package com.ziggfreed.common.authoring;

import java.util.List;

import javax.annotation.Nonnull;

/**
 * One numbered step of a wizard: its name (a full registered lang key, shown as "Step 2 of 3: Rewards") and its rows,
 * in order. A field id is unique across the whole wizard: the session keeps every step's values under them.
 */
public record WizardStep(@Nonnull String titleKey, @Nonnull List<WizardField> fields) {

    public WizardStep {
        fields = List.copyOf(fields);
    }

    /** A step from its rows. */
    @Nonnull
    public static WizardStep of(@Nonnull String titleKey, @Nonnull WizardField... fields) {
        return new WizardStep(titleKey, List.of(fields));
    }
}
