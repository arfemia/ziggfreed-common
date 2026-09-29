package com.ziggfreed.common.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The one list of metadata keys a mod has declared safe to destroy with their item. It is global and
 * never retracted, so every test here declares keys of its own and asks only about those.
 */
class DisposableItemMetadataTest {

    @Test
    void aDeclaredKeyIsDeclaredExactlyAsSpelled() {
        DisposableItemMetadata.declare("Test_Exact_Key");

        assertTrue(DisposableItemMetadata.isDeclared("Test_Exact_Key"));
        assertFalse(DisposableItemMetadata.isDeclared("test_exact_key"),
                "metadata keys are BSON document keys, so another case is another key");
        assertFalse(DisposableItemMetadata.isDeclared(null));
    }

    @Test
    void aNullOrBlankKeyIsIgnored() {
        DisposableItemMetadata.declare("Test_Kept_Key", null, "  ", "");
        DisposableItemMetadata.declare((List<String>) null);

        assertTrue(DisposableItemMetadata.isDeclared("Test_Kept_Key"));
        assertFalse(DisposableItemMetadata.declared().contains("  "));
        assertFalse(DisposableItemMetadata.declared().contains(""));
    }

    @Test
    void undeclaredAnswersTheKeysNobodyDeclaredInTheOrderGiven() {
        DisposableItemMetadata.declare(List.of("Test_Stamp_Record", "Test_Stamp_Tooltip"));

        Set<String> undeclared = DisposableItemMetadata.undeclared(
                List.of("Test_Foreign_B", "Test_Stamp_Record", "Test_Foreign_A", "Test_Stamp_Tooltip"));

        assertEquals(List.of("Test_Foreign_B", "Test_Foreign_A"), new ArrayList<>(undeclared));
        assertThrows(UnsupportedOperationException.class, () -> undeclared.add("x"));
    }

    @Test
    void aBareStackOrAFullyDeclaredOneLeavesNothingUndeclared() {
        DisposableItemMetadata.declare("Test_Only_Key");

        assertTrue(DisposableItemMetadata.undeclared(List.of()).isEmpty(), "a bare stack carries no key at all");
        assertTrue(DisposableItemMetadata.undeclared(List.of("Test_Only_Key")).isEmpty());
    }

    @Test
    void theDeclaredSnapshotIsImmutableAndAdditive() {
        DisposableItemMetadata.declare("Test_Snapshot_Key");
        Set<String> snapshot = DisposableItemMetadata.declared();

        assertTrue(snapshot.contains("Test_Snapshot_Key"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add("x"));
        DisposableItemMetadata.declare("Test_Snapshot_Key");
        assertTrue(DisposableItemMetadata.isDeclared("Test_Snapshot_Key"), "declaring twice changes nothing");
    }
}
