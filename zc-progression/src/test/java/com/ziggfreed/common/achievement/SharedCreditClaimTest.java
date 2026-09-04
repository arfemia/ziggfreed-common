package com.ziggfreed.common.achievement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.subject.Subject;

/**
 * A server-first won under a shared credit is won by everybody carrying that credit, and by nobody
 * else: the whole of what {@link FirstClaims#claim} adds over the installed table.
 */
class SharedCreditClaimTest {

    private static final String FIRST = "first_warden_kill";
    private static final Subject ALICE = Subject.of(UUID.randomUUID(), "alice");
    private static final Subject BOB = Subject.of(UUID.randomUUID(), "bob");
    private static final Subject CAROL = Subject.of(UUID.randomUUID(), "carol");

    @BeforeEach
    @AfterEach
    void reset() {
        FirstClaims.resetForTests();
    }

    @Test
    void withNoCreditInScopeExactlyOneSubjectWins() {
        assertTrue(FirstClaims.claim(FIRST, ALICE));
        assertFalse(FirstClaims.claim(FIRST, BOB), "the table's own rule, untouched");
        assertTrue(FirstClaims.claim(FIRST, ALICE), "the holder keeps holding it");
    }

    @Test
    void everySubjectDispatchedUnderOneCreditWins() {
        String run = UUID.randomUUID().toString();
        FirstClaims.withSharedCredit(run, () -> {
            assertTrue(FirstClaims.claim(FIRST, ALICE), "the first of the party takes the table's claim");
            assertTrue(FirstClaims.claim(FIRST, BOB), "the second shares it");
            assertTrue(FirstClaims.claim(FIRST, CAROL), "and so does the third");
        });
    }

    @Test
    void aDifferentCreditRacesAsBefore() {
        FirstClaims.withSharedCredit(UUID.randomUUID().toString(),
                () -> assertTrue(FirstClaims.claim(FIRST, ALICE)));
        FirstClaims.withSharedCredit(UUID.randomUUID().toString(),
                () -> assertFalse(FirstClaims.claim(FIRST, BOB), "a second party is a second run"));
    }

    @Test
    void aReTestWithNoCreditNeverCoClaims() {
        FirstClaims.withSharedCredit("run-1", () -> assertTrue(FirstClaims.claim(FIRST, ALICE)));
        assertFalse(FirstClaims.claim(FIRST, BOB), "the login sweep carries no credit and wins nothing");
    }

    @Test
    void aSharedWinDoesNotAskTheTableAgain() {
        AtomicInteger asked = new AtomicInteger();
        FirstClaims.install((id, subjectId, name) -> {
            asked.incrementAndGet();
            return true;
        });
        FirstClaims.withSharedCredit("run-1", () -> {
            FirstClaims.claim(FIRST, ALICE);
            FirstClaims.claim(FIRST, BOB);
        });
        assertEquals(1, asked.get(), "one claim reaches the table per shared win; the rest read the memory");
    }

    @Test
    void theCreditInScopeIsRestoredWhenABodyReturns() {
        FirstClaims.withSharedCredit("outer", () -> {
            FirstClaims.withSharedCredit("inner",
                    () -> assertEquals("inner", FirstClaims.currentSharedCredit()));
            assertEquals("outer", FirstClaims.currentSharedCredit());
        });
        assertNull(FirstClaims.currentSharedCredit(), "nothing is left in scope after the outermost body");
        FirstClaims.withSharedCredit("  ", () -> assertNull(FirstClaims.currentSharedCredit(),
                "a blank key is no credit at all"));
    }
}
