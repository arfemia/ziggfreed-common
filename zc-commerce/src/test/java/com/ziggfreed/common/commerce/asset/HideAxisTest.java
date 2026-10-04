package com.ziggfreed.common.commerce.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.factor.FeatureFlags;
import com.ziggfreed.common.progress.gate.GateSpec;

/**
 * The hide axis every commerce type reads: a plain top-level feature condition decides whether the
 * thing exists, live, and everything else in the block stays its lock.
 *
 * <p>The namespace is unique to this class: a feature contribution is process-wide and cannot be
 * withdrawn, so no two test classes may claim the same one.
 */
class HideAxisTest {

    private static final String NAMESPACE = "hideaxis_commerce";
    private static final String ON_FEATURE =
            "{ \"Factor\": \"hideaxis_commerce:feature\", \"Param\": \"Spooky\", \"Min\": 1 }";

    private AtomicBoolean spooky;

    @BeforeEach
    void declareTheFeature() {
        spooky = new AtomicBoolean(true);
        FeatureFlags.register(NAMESPACE, "spooky", "test", spooky::get);
    }

    @AfterEach
    void forgetDeclaredFeatures() {
        FeatureFlags.reset();
    }

    private static GateSpec gate(String json) throws IOException {
        return GateSpec.CODEC.decodeJson(RawJsonReader.fromJsonString(json), new ExtraInfo());
    }

    @Test
    @DisplayName("nothing authored is present while switched on, and locks nothing")
    void nothingAuthoredIsPresent() {
        assertTrue(HideAxis.present(true, null));
        assertFalse(HideAxis.present(false, null), "Enabled false is the owner's off switch");
        assertNull(HideAxis.lock(null));
    }

    @Test
    @DisplayName("a plain feature condition decides presence, read live on every look")
    void aPlainFeatureConditionDecidesPresenceLive() throws IOException {
        GateSpec requires = gate("{ \"Factors\": [ " + ON_FEATURE + " ] }");

        assertTrue(HideAxis.present(true, requires));
        spooky.set(false);
        assertFalse(HideAxis.present(true, requires), "off means absent, with nothing rebuilt");
        spooky.set(true);
        assertTrue(HideAxis.present(true, requires));
        assertFalse(HideAxis.present(false, requires), "Enabled false wins whatever the feature says");
        assertNull(HideAxis.lock(requires), "the feature was the whole block, so nothing is left to lock");
    }

    @Test
    @DisplayName("the bounds-less form hides exactly like Min 1")
    void theBoundsLessFormHidesLikeMinOne() throws IOException {
        GateSpec requires = gate(
                "{ \"Factors\": [ { \"Factor\": \"hideaxis_commerce:feature\", \"Param\": \"Spooky\" } ] }");
        spooky.set(false);

        assertFalse(HideAxis.present(true, requires),
                "read as a lock, a bounds-less condition passes on the 0 an off feature reads");
        assertNull(HideAxis.lock(requires));
    }

    @Test
    @DisplayName("the lock keeps everything but the lifted condition")
    void theLockKeepsTheRest() throws IOException {
        GateSpec requires = gate("{ \"Factors\": [ " + ON_FEATURE
                + ", { \"Factor\": \"yourmod:rank\", \"Min\": 5 } ], \"Permission\": \"shop.vip\" }");

        GateSpec lock = HideAxis.lock(requires);

        assertNotNull(lock);
        assertEquals(1, lock.factorsOrEmpty().length);
        assertEquals("yourmod:rank", lock.factorsOrEmpty()[0].getFactor());
        assertEquals("shop.vip", lock.getPermission());
    }

    @Test
    @DisplayName("a nested or upper-bounded feature condition stays a lock")
    void aNestedOrUpperBoundedConditionStaysALock() throws IOException {
        GateSpec either = gate("{ \"AnyOf\": [ { \"Factors\": [ " + ON_FEATURE + " ] },"
                + " { \"Permission\": \"shop.vip\" } ] }");
        GateSpec onlyWhileOff = gate("{ \"Factors\": [ { \"Factor\": \"hideaxis_commerce:feature\","
                + " \"Param\": \"Spooky\", \"Max\": 0 } ] }");
        spooky.set(false);

        assertTrue(HideAxis.present(true, either), "an either-or a player can meet is a lock");
        assertSame(either, HideAxis.lock(either));
        assertTrue(HideAxis.present(true, onlyWhileOff), "'only while it is off' is a real requirement");
        assertSame(onlyWhileOff, HideAxis.lock(onlyWhileOff));
    }

    @Test
    @DisplayName("a feature of a namespace nothing declared stays a lock rather than a hide")
    void anUndeclaredNamespaceStaysALock() throws IOException {
        GateSpec foreign = gate(
                "{ \"Factors\": [ { \"Factor\": \"nobody_declared:feature\", \"Param\": \"x\", \"Min\": 1 } ] }");

        assertTrue(HideAxis.present(true, foreign), "a namespace that may simply not be installed yet");
        assertSame(foreign, HideAxis.lock(foreign), "so fail-closed keeps the content locked instead");
    }
}
