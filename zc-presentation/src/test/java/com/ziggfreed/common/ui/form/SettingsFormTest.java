package com.ziggfreed.common.ui.form;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.Message;

/**
 * Unit tests for the pure-Java surface of {@link SettingsForm}: the value cache
 * ({@code cache}/{@code value}/{@code seed}/{@code seedValue}/{@code spec}), the
 * {@code collectLeaves} parsing/validation rules per {@link FieldKind}, and {@link FieldSpec#withHint}
 * (the immutable-copy + header/note-rejection contract). Deliberately does not touch
 * {@code buildRows}/{@code applyValues}/{@code applyHint} (those need a live
 * {@code UICommandBuilder}/{@code UIEventBuilder} batch) - only the constructor's two {@link Message}
 * labels are engine-typed, and {@link Message#raw} is a plain-data constructor with no i18n/logger
 * dependency, so this test runs in a log-manager-less unit JVM like every other pure-logic test in
 * this module.
 */
class SettingsFormTest {

    private static final Message ON = Message.raw("On");
    private static final Message OFF = Message.raw("Off");

    private static SettingsForm formOf(FieldSpec... specs) {
        return new SettingsForm(List.of(specs), ON, OFF);
    }

    // ---------------------------------------------------------------------
    // cache / value / seed / seedValue / spec
    // ---------------------------------------------------------------------

    @Test
    void valueDefaultsToEmptyStringForEveryLeafBearingField() {
        SettingsForm form = formOf(FieldSpec.text("name", "Name", "lbl.name"));
        assertEquals("", form.value("name"));
        assertEquals("", form.value("unknown-id")); // unknown id also defaults to ""
    }

    @Test
    void cacheStoresValidUpdateAndReturnsTrue() {
        SettingsForm form = formOf(FieldSpec.text("name", "Name", "lbl.name"));
        assertTrue(form.cache("name", "Alice"));
        assertEquals("Alice", form.value("name"));
    }

    @Test
    void cacheRejectsUnknownFieldId() {
        SettingsForm form = formOf(FieldSpec.text("name", "Name", "lbl.name"));
        assertFalse(form.cache("does-not-exist", "value"));
        assertEquals("", form.value("does-not-exist"));
    }

    @Test
    void cacheRejectsNullValue() {
        SettingsForm form = formOf(FieldSpec.text("name", "Name", "lbl.name"));
        assertTrue(form.cache("name", "Alice"));
        assertFalse(form.cache("name", null)); // false + no-op, existing value untouched
        assertEquals("Alice", form.value("name"));
    }

    @Test
    void seedReplacesWholeStateAndDefaultsMissingKeysToEmpty() {
        SettingsForm form = formOf(
                FieldSpec.text("a", "A", "lbl.a"),
                FieldSpec.text("b", "B", "lbl.b"));
        form.cache("a", "was-a");
        form.cache("b", "was-b");
        form.seed(Map.of("a", "new-a")); // "b" omitted -> resets to ""
        assertEquals("new-a", form.value("a"));
        assertEquals("", form.value("b"));
    }

    @Test
    void seedSkipsHeaderAndNoteSpecsButSpecLookupStillResolvesThem() {
        // A HEADER/NOTE spec carries no cache entry at all; seed must not choke walking them.
        SettingsForm form = formOf(
                FieldSpec.header("h", "lbl.h"),
                FieldSpec.note("n", "lbl.n"),
                FieldSpec.text("a", "A", "lbl.a"));
        form.seed(Map.of("a", "value"));
        assertEquals("value", form.value("a"));
        assertEquals(FieldKind.HEADER, form.spec("h").kind()); // spec() resolves every id, leaf-bearing or not
        assertEquals(FieldKind.NOTE, form.spec("n").kind());
        assertEquals("", form.value("h")); // never cached, but value() still defaults safely
    }

    @Test
    void seedValueSetsOneFieldAndIgnoresUnknownId() {
        SettingsForm form = formOf(FieldSpec.text("a", "A", "lbl.a"));
        form.seedValue("a", "pre-loaded");
        assertEquals("pre-loaded", form.value("a"));
        form.seedValue("unknown", "ignored"); // no-op, no exception
        assertEquals("", form.value("unknown"));
    }

    @Test
    void specResolvesKnownIdAndNullsForUnknown() {
        FieldSpec textSpec = FieldSpec.text("a", "A", "lbl.a");
        SettingsForm form = formOf(textSpec);
        assertEquals(textSpec, form.spec("a"));
        assertEquals(FieldKind.TEXT, form.spec("a").kind());
        assertNull(form.spec("nope"));
    }

    // ---------------------------------------------------------------------
    // FieldSpec.withHint
    // ---------------------------------------------------------------------

    @Test
    void hintKeyDefaultsToNullForEveryFactory() {
        assertNull(FieldSpec.text("a", "A", "lbl.a").hintKey());
        assertNull(FieldSpec.number("b", "B", "lbl.b").hintKey());
        assertNull(FieldSpec.chance("c", "C", "lbl.c").hintKey());
        assertNull(FieldSpec.integer("d", "D", "lbl.d").hintKey());
        assertNull(FieldSpec.csv("e", "E", "lbl.e").hintKey());
        assertNull(FieldSpec.toggle("f", "lbl.f").hintKey());
        assertNull(FieldSpec.tristate("g", "G", "lbl.g").hintKey());
        assertNull(FieldSpec.dropdown("h", "H", "lbl.h", new String[] {"x"}).hintKey());
        assertNull(FieldSpec.header("i", "lbl.i").hintKey());
        assertNull(FieldSpec.note("j", "lbl.j").hintKey());
    }

    @Test
    void withHintReturnsDistinctCopyLeavingOriginalUntouchedAndHintKeyRoundTrips() {
        FieldSpec original = FieldSpec.text("name", "Name", "lbl.name");
        FieldSpec hinted = original.withHint("lbl.name.hint");

        assertNotSame(original, hinted); // a genuinely distinct instance
        assertNull(original.hintKey()); // original is unchanged (FieldSpec stays immutable)
        assertEquals("lbl.name.hint", hinted.hintKey()); // hintKey round-trips on the copy

        // every other field carries over unchanged onto the copy
        assertEquals(original.id(), hinted.id());
        assertEquals(original.kind(), hinted.kind());
        assertEquals(original.leafPath(), hinted.leafPath());
        assertEquals(original.labelKey(), hinted.labelKey());
    }

    @Test
    void withHintWorksForEveryNonHeaderNonNoteKind() {
        assertEquals("h1", FieldSpec.number("a", "A", "lbl.a").withHint("h1").hintKey());
        assertEquals("h2", FieldSpec.chance("b", "B", "lbl.b").withHint("h2").hintKey());
        assertEquals("h3", FieldSpec.integer("c", "C", "lbl.c").withHint("h3").hintKey());
        assertEquals("h4", FieldSpec.csv("d", "D", "lbl.d").withHint("h4").hintKey());
        assertEquals("h5", FieldSpec.toggle("e", "lbl.e").withHint("h5").hintKey());
        assertEquals("h6", FieldSpec.tristate("f", "F", "lbl.f").withHint("h6").hintKey());
        assertEquals("h7", FieldSpec.dropdown("g", "G", "lbl.g", new String[] {"x"}).withHint("h7").hintKey());
    }

    @Test
    void withHintOnHeaderThrows() {
        FieldSpec header = FieldSpec.header("h", "lbl.h");
        assertThrows(IllegalArgumentException.class, () -> header.withHint("lbl.h.hint"));
    }

    @Test
    void withHintOnNoteThrows() {
        FieldSpec note = FieldSpec.note("n", "lbl.n");
        assertThrows(IllegalArgumentException.class, () -> note.withHint("lbl.n.hint"));
    }

    // ---------------------------------------------------------------------
    // collectLeaves: TEXT
    // ---------------------------------------------------------------------

    @Test
    void textTrimsAndCollectsNonBlank() {
        SettingsForm form = formOf(FieldSpec.text("name", "Name", "lbl.name"));
        form.cache("name", "  Alice  ");
        FormResult result = form.collectLeaves(true);
        assertTrue(result.ok());
        assertEquals("Alice", result.leaves().get("Name"));
    }

    @Test
    void textBlankWithBlankIsInheritYieldsNullLeaf() {
        SettingsForm form = formOf(FieldSpec.text("name", "Name", "lbl.name"));
        FormResult result = form.collectLeaves(true);
        assertTrue(result.ok());
        assertTrue(result.leaves().containsKey("Name"));
        assertNull(result.leaves().get("Name"));
    }

    @Test
    void textBlankWithoutBlankIsInheritYieldsEmptyStringLeafNeverError() {
        SettingsForm form = formOf(FieldSpec.text("name", "Name", "lbl.name"));
        FormResult result = form.collectLeaves(false);
        assertTrue(result.ok()); // TEXT never errors
        assertEquals("", result.leaves().get("Name"));
    }

    // ---------------------------------------------------------------------
    // collectLeaves: NUMBER
    // ---------------------------------------------------------------------

    @Test
    void numberParsesNonNegativeDouble() {
        SettingsForm form = formOf(FieldSpec.number("mult", "Mult", "lbl.mult"));
        form.cache("mult", "1.5");
        FormResult result = form.collectLeaves(false);
        assertTrue(result.ok());
        assertEquals(1.5, (Double) result.leaves().get("Mult"), 1e-9);
    }

    @Test
    void numberBlankWithBlankIsInheritYieldsNullLeaf() {
        SettingsForm form = formOf(FieldSpec.number("mult", "Mult", "lbl.mult"));
        FormResult result = form.collectLeaves(true);
        assertTrue(result.ok());
        assertNull(result.leaves().get("Mult"));
    }

    @Test
    void numberBlankWithoutBlankIsInheritErrors() {
        SettingsForm form = formOf(FieldSpec.number("mult", "Mult", "lbl.mult"));
        FormResult result = form.collectLeaves(false);
        assertFalse(result.ok());
        assertEquals("lbl.mult", result.errorLabelKey());
        assertEquals("number", result.errorKind());
        assertTrue(result.leaves().isEmpty());
    }

    @Test
    void numberNegativeErrors() {
        SettingsForm form = formOf(FieldSpec.number("mult", "Mult", "lbl.mult"));
        form.cache("mult", "-1");
        FormResult result = form.collectLeaves(false);
        assertFalse(result.ok());
        assertEquals("number", result.errorKind());
    }

    @Test
    void numberNaNErrors() {
        SettingsForm form = formOf(FieldSpec.number("mult", "Mult", "lbl.mult"));
        form.cache("mult", "NaN"); // Double.parseDouble("NaN") succeeds but must still be rejected
        FormResult result = form.collectLeaves(false);
        assertFalse(result.ok());
        assertEquals("number", result.errorKind());
    }

    @Test
    void numberUnparseableErrors() {
        SettingsForm form = formOf(FieldSpec.number("mult", "Mult", "lbl.mult"));
        form.cache("mult", "not-a-number");
        FormResult result = form.collectLeaves(false);
        assertFalse(result.ok());
        assertEquals("number", result.errorKind());
    }

    // ---------------------------------------------------------------------
    // collectLeaves: CHANCE
    // ---------------------------------------------------------------------

    @Test
    void chanceAtUpperBoundaryIsOk() {
        SettingsForm form = formOf(FieldSpec.chance("p", "P", "lbl.p"));
        form.cache("p", "1.0");
        FormResult result = form.collectLeaves(false);
        assertTrue(result.ok());
        assertEquals(1.0, (Double) result.leaves().get("P"), 1e-9);
    }

    @Test
    void chanceAboveOneErrors() {
        SettingsForm form = formOf(FieldSpec.chance("p", "P", "lbl.p"));
        form.cache("p", "1.01");
        FormResult result = form.collectLeaves(false);
        assertFalse(result.ok());
        assertEquals("chance", result.errorKind());
    }

    @Test
    void chanceNegativeErrors() {
        SettingsForm form = formOf(FieldSpec.chance("p", "P", "lbl.p"));
        form.cache("p", "-0.1");
        FormResult result = form.collectLeaves(false);
        assertFalse(result.ok());
        assertEquals("chance", result.errorKind());
    }

    // ---------------------------------------------------------------------
    // collectLeaves: INT
    // ---------------------------------------------------------------------

    @Test
    void intAllowsNegativeValues() {
        SettingsForm form = formOf(FieldSpec.integer("offset", "Offset", "lbl.offset"));
        form.cache("offset", "-42");
        FormResult result = form.collectLeaves(false);
        assertTrue(result.ok());
        assertEquals(-42, (Integer) result.leaves().get("Offset"));
    }

    @Test
    void intBlankWithBlankIsInheritYieldsNullLeaf() {
        SettingsForm form = formOf(FieldSpec.integer("offset", "Offset", "lbl.offset"));
        FormResult result = form.collectLeaves(true);
        assertTrue(result.ok());
        assertNull(result.leaves().get("Offset"));
    }

    @Test
    void intBlankWithoutBlankIsInheritErrors() {
        SettingsForm form = formOf(FieldSpec.integer("offset", "Offset", "lbl.offset"));
        FormResult result = form.collectLeaves(false);
        assertFalse(result.ok());
        assertEquals("int", result.errorKind());
    }

    @Test
    void intUnparseableErrors() {
        SettingsForm form = formOf(FieldSpec.integer("offset", "Offset", "lbl.offset"));
        form.cache("offset", "3.5"); // a decimal is not a valid int
        FormResult result = form.collectLeaves(false);
        assertFalse(result.ok());
        assertEquals("int", result.errorKind());
    }

    // ---------------------------------------------------------------------
    // collectLeaves: CSV
    // ---------------------------------------------------------------------

    @Test
    void csvSplitsTrimsAndDropsEmptiesPreservingOrder() {
        SettingsForm form = formOf(FieldSpec.csv("ids", "Ids", "lbl.ids"));
        form.cache("ids", " foo ,, bar,baz ,");
        FormResult result = form.collectLeaves(false);
        assertTrue(result.ok());
        assertEquals(List.of("foo", "bar", "baz"), result.leaves().get("Ids"));
    }

    @Test
    void csvBlankYieldsNullLeafRegardlessOfBlankIsInherit() {
        SettingsForm form = formOf(FieldSpec.csv("ids", "Ids", "lbl.ids"));
        assertNull(form.collectLeaves(true).leaves().get("Ids"));
        assertTrue(form.collectLeaves(true).ok());
        assertNull(form.collectLeaves(false).leaves().get("Ids"));
        assertTrue(form.collectLeaves(false).ok()); // CSV never errors on blank either way
    }

    // ---------------------------------------------------------------------
    // collectLeaves: TRISTATE / DROPDOWN / TOGGLE / HEADER / NOTE
    // ---------------------------------------------------------------------

    @Test
    void tristateOnAndOffMapToBooleans() {
        SettingsForm form = formOf(FieldSpec.tristate("flag", "Flag", "lbl.flag"));
        form.cache("flag", "on");
        assertEquals(Boolean.TRUE, form.collectLeaves(false).leaves().get("Flag"));
        form.cache("flag", "off");
        assertEquals(Boolean.FALSE, form.collectLeaves(false).leaves().get("Flag"));
    }

    @Test
    void tristateInheritAndBlankMapToNullLeaf() {
        SettingsForm form = formOf(FieldSpec.tristate("flag", "Flag", "lbl.flag"));
        form.cache("flag", "inherit");
        assertNull(form.collectLeaves(false).leaves().get("Flag"));
        form.cache("flag", "");
        assertNull(form.collectLeaves(false).leaves().get("Flag"));
    }

    @Test
    void dropdownBlankOrInheritMapToNullElseTheString() {
        SettingsForm form = formOf(FieldSpec.dropdown("mode", "Mode", "lbl.mode",
                new String[] {"small", "medium", "large"}));
        assertNull(form.collectLeaves(false).leaves().get("Mode")); // unset -> blank
        form.cache("mode", "inherit");
        assertNull(form.collectLeaves(false).leaves().get("Mode"));
        form.cache("mode", "medium");
        assertEquals("medium", form.collectLeaves(false).leaves().get("Mode"));
    }

    @Test
    void toggleHeaderAndNoteAreNeverCollected() {
        SettingsForm form = formOf(
                FieldSpec.header("h", "lbl.h"),
                FieldSpec.note("n", "lbl.n"),
                FieldSpec.toggle("t", "lbl.t"));
        form.cache("t", "on");
        FormResult result = form.collectLeaves(false);
        assertTrue(result.ok());
        assertTrue(result.leaves().isEmpty());
    }

    // ---------------------------------------------------------------------
    // collectLeaves: first-error-aborts, spec order
    // ---------------------------------------------------------------------

    @Test
    void firstErrorAbortsAndDiscardsAlreadyCollectedLeaves() {
        SettingsForm form = formOf(
                FieldSpec.text("first", "First", "lbl.first"),
                FieldSpec.number("second", "Second", "lbl.second"),
                FieldSpec.csv("third", "Third", "lbl.third"));
        form.cache("first", "collected-before-the-failure");
        form.cache("second", "not-a-number");
        form.cache("third", "a,b,c");
        FormResult result = form.collectLeaves(false);
        assertFalse(result.ok());
        assertEquals("lbl.second", result.errorLabelKey());
        assertEquals("number", result.errorKind());
        assertTrue(result.leaves().isEmpty()); // "first"'s already-collected leaf is discarded too
    }

    @Test
    void leavesPreserveSpecOrder() {
        SettingsForm form = formOf(
                FieldSpec.text("z", "Z", "lbl.z"),
                FieldSpec.text("a", "A", "lbl.a"),
                FieldSpec.text("m", "M", "lbl.m"));
        form.cache("z", "1");
        form.cache("a", "2");
        form.cache("m", "3");
        FormResult result = form.collectLeaves(false);
        assertTrue(result.ok());
        assertEquals(List.of("Z", "A", "M"), List.copyOf(result.leaves().keySet()));
    }
}
