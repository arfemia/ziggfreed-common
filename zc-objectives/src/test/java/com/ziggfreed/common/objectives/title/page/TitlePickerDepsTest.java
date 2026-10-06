package com.ziggfreed.common.objectives.title.page;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.ui.route.Destinations;

/** The picker's consumer seams: plain by default with Back to the Settings tab, and fail-safe when a consumer's throw. */
class TitlePickerDepsTest {

    @AfterEach
    void reset() {
        TitlePickerPages.deps(null);
    }

    @Test
    void aBareServerGetsAPlainPickerWhoseBackReturnsToSettings() {
        assertSame(TitlePickerDeps.DEFAULTS, TitlePickerPages.resolvedDeps());
        assertSame(TitlePickerDeps.PLAIN_THEME, TitlePickerDeps.DEFAULTS.theme());
        assertSame(TitlePickerDeps.TO_SETTINGS, TitlePickerDeps.DEFAULTS.back());
    }

    @Test
    void aNullKnobRestoresItsDefault() {
        TitlePickerDeps deps = TitlePickerDeps.builder().theme(null).back(null).build();

        assertSame(TitlePickerDeps.PLAIN_THEME, deps.theme());
        assertSame(TitlePickerDeps.TO_SETTINGS, deps.back());
    }

    @Test
    void aThrowingBackHandlerTakesNothingAndAThrowingSupplierFallsBackToTheDefaults() {
        TitlePickerDeps deps = TitlePickerDeps.builder()
                .back((store, ref, player) -> {
                    throw new IllegalStateException("boom");
                })
                .build();
        assertFalse(deps.backGuarded(null, null, null), "the page closes instead");

        TitlePickerPages.deps(() -> {
            throw new IllegalStateException("boom");
        });
        assertSame(TitlePickerDeps.DEFAULTS, TitlePickerPages.resolvedDeps());
    }

    @Test
    void withNothingToOpenBackClosesThePicker() {
        Destinations.clearForTests();

        assertFalse(TitlePickerDeps.DEFAULTS.backGuarded(null, null, null),
                "no Settings tab registered: Back takes nothing and the picker closes");
    }
}
