package com.ziggfreed.common.objectives.flair;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.loot.reward.RewardHandler;
import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.subject.Subject;

/**
 * The {@code Flair} reward kind's contract with the shared issuance pass: what it reads, what it
 * refuses, and - the load-bearing half - that a grant with nobody to grant to fails LOUD with a
 * replayable line, so the consumer's retry queue can hand it over on the player's next connect,
 * while a spec that could never be granted offers no line at all.
 */
class FlairRewardKindTest {

    private static final Subject OFFLINE = Subject.of(UUID.randomUUID(), "Tester");

    private static RewardHandler kind() {
        RewardKindRegistry kinds = new RewardKindRegistry();
        FlairRewardKind.registerInto(kinds);
        RewardHandler handler = kinds.handler("flair");
        assertNotNull(handler, "the kind id matches case-insensitively like every other");
        return handler;
    }

    @Test
    void theFlairIsReadUnderEitherSpelling() {
        assertEquals("sawmill_gold",
                FlairRewardKind.flairOf(RewardSpec.of("Flair", Map.of("Flair", " sawmill_gold "))));
        assertEquals("sawmill_gold",
                FlairRewardKind.flairOf(RewardSpec.of("Flair", Map.of("FlairId", "sawmill_gold"))));
        assertEquals("", FlairRewardKind.flairOf(RewardSpec.of("Flair")));
    }

    @Test
    void noLivePlayerFailsLoudAndOffersTheGrantLine() {
        RewardSpec spec = RewardSpec.of("Flair", Map.of("Flair", "sawmill_gold"));

        assertThrows(IllegalStateException.class, () -> kind().grant(spec, OFFLINE));
        assertEquals("zigflair grant --player=Tester --flair=sawmill_gold",
                kind().retryCommand(spec, OFFLINE, "quest:timber_rights"),
                "built from the SPEC, in the named-arg form the engine parser binds");
    }

    @Test
    void aSpecNamingNoFlairIsRefusedAndNotReplayable() {
        RewardSpec spec = RewardSpec.of("Flair");

        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> kind().grant(spec, OFFLINE));
        assertTrue(refusal.getMessage().contains("Flair"), "the refusal names the parameter to write");
        assertNull(kind().retryCommand(spec, OFFLINE, "quest:timber_rights"),
                "a retry would refuse on every attempt, so it is reported lost instead");
    }

    @Test
    void anIdTheSaveFormatCannotHoldIsRefusedAndNotReplayable() {
        for (String bad : new String[] {"sawmill|gold", "stations:sawmill_gold"}) {
            RewardSpec spec = RewardSpec.of("Flair", Map.of("Flair", bad));
            assertThrows(IllegalStateException.class, () -> kind().grant(spec, OFFLINE), bad);
            assertNull(kind().retryCommand(spec, OFFLINE, "quest:timber_rights"), bad);
        }
    }
}
