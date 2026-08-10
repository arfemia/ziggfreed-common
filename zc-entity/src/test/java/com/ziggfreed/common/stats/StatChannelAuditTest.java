package com.ziggfreed.common.stats;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * {@link StatChannelAudit#audit} never throws, even though a unit JVM has no live {@link
 * com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType} asset store - every
 * fixture id in this JVM resolves as a miss (the real "report a miss" behavior; there is no
 * registered channel to succeed against outside a running server), and the audit pass must
 * degrade to a graceful, exception-free {@code SEVERE} log per miss rather than crash the caller.
 */
class StatChannelAuditTest {

    @Test
    void auditNeverThrowsOnAFixtureMissingId() {
        assertDoesNotThrow(() -> StatChannelAudit.audit(List.of("MMO_Totally_Unregistered_Channel")));
    }

    @Test
    void auditNeverThrowsOnMultipleFixtureIds() {
        assertDoesNotThrow(() -> StatChannelAudit.audit(
                Arrays.asList("MMO_Level_MINING", "MMO_CombatLevel", "MMO_Luck")));
    }

    @Test
    void auditSkipsNullAndBlankIdsWithoutThrowing() {
        assertDoesNotThrow(() -> StatChannelAudit.audit(Arrays.asList(null, "", "MMO_Luck")));
    }

    @Test
    void auditOnAnEmptyCollectionIsANoOp() {
        assertDoesNotThrow(() -> StatChannelAudit.audit(List.of()));
    }
}
