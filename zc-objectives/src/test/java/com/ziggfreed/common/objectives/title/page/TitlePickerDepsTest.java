package com.ziggfreed.common.objectives.title.page;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The picker's consumer seams: plain and closable by default, and fail-safe when a consumer's throw. */
class TitlePickerDepsTest {

    @AfterEach
    void reset() {
        TitlePickerPages.deps(null);
    }

    @Test
    void aBareServerGetsAPlainClosablePicker() {
        assertSame(TitlePickerDeps.DEFAULTS, TitlePickerPages.resolvedDeps());
        assertSame(TitlePickerDeps.PLAIN_THEME, TitlePickerDeps.DEFAULTS.theme());
        assertSame(TitlePickerDeps.CLOSE_PAGE, TitlePickerDeps.DEFAULTS.back());
    }

    @Test
    void aNullKnobRestoresItsDefault() {
        TitlePickerDeps deps = TitlePickerDeps.builder().theme(null).back(null).build();

        assertSame(TitlePickerDeps.PLAIN_THEME, deps.theme());
        assertSame(TitlePickerDeps.CLOSE_PAGE, deps.back());
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
}
