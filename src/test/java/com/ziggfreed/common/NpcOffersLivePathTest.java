package com.ziggfreed.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.objectives.runtime.ProgressionDefaults;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.progress.runtime.ProgressionGates;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.quest.NpcOffer;
import com.ziggfreed.common.quest.NpcOfferProviders;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.subject.Subject;

/**
 * What a character is holding out, asked the way every surface actually asks it.
 *
 * <p>This walks the LIVE path end to end - the registered default provider, over the shared runtime
 * catalogue, through the registered gate - because that is the path a conversation's "have you
 * anything for me" line, an NPC panel and a hail marker all take. A guarantee pinned one layer
 * inside that path is a guarantee that survives the path being replaced, which is exactly what
 * happened here: the answer moved and its guard stayed behind on the copy nothing calls.
 *
 * <p>The case that drove it: content marked out of sight. Being out of sight belongs to a BROWSABLE
 * listing; at the one character whose business the quest is, marking it out of sight leaves them
 * standing silently beside the thing they exist to hand out, and a whole authored chain becomes
 * unreachable with nothing anywhere reporting it.
 */
class NpcOffersLivePathTest {

    private static final String OWNER = "npc-offers-live-path-test";
    private static final String GIVER = "The_Guide";

    private final Subject player = Subject.of(UUID.randomUUID(), "tester");

    @BeforeEach
    void setUp() {
        ProgressionRuntime.resetForTests();
        NpcOfferProviders.clear();
        ProgressionDefaults.reset();
        ProgressionGates.resetForTests();
        ProgressionDefaults.register();
    }

    @AfterEach
    void tearDown() {
        ProgressionRuntime.resetForTests();
        NpcOfferProviders.clear();
        ProgressionDefaults.reset();
        ProgressionGates.resetForTests();
    }

    /**
     * A quest kept out of an open listing until it is relevant is still the giver's business. It
     * asks its requirements first - that is what "until it is relevant" means - and once they are
     * met the character has it to hand out.
     */
    @Test
    void aQuestKeptOffOpenListingsIsStillOfferedAtItsOwnGiver() {
        Quest ready = Quest.builder("side_errand")
                .visibility(new Quest.Visibility(true, true))
                .npcViewId(GIVER)
                .build();
        publish(ready);

        List<NpcOffer> offers = NpcOfferProviders.offersAt(player, List.of(GIVER));
        assertEquals(1, offers.size(),
                "a character with an authored quest and a player past its requirements has"
                        + " something to say");
        assertEquals("side_errand", offers.get(0).id());
        assertTrue(offers.get(0).available(), "and it is takeable right now");
    }

    /** Requirements not met, so the character genuinely has nothing to hand this player yet. */
    @Test
    void aQuestWhoseRequirementsAreUnmetIsNotOfferedYet() {
        Quest locked = Quest.builder("second_errand")
                .visibility(new Quest.Visibility(true, true))
                .npcViewId(GIVER)
                .requires(GateSpec.of(null, null, new String[] {"first_errand"}, null, null, null,
                        null))
                .build();
        publish(locked);

        assertTrue(NpcOfferProviders.offersAt(player, List.of(GIVER)).isEmpty());
        assertFalse(NpcOfferProviders.hasOffersAt(player, List.of(GIVER)),
                "and the quick answer agrees, so the character does not greet the player with work"
                        + " they cannot be handed");
    }

    /**
     * The two reads must agree. One decides whether to greet the player at all and the other draws
     * the list; a giver who says they have work and then shows an empty panel is the in-game bug.
     */
    @Test
    void theQuickAnswerAndTheListAgree() {
        publish(Quest.builder("errand").npcViewId(GIVER).build());

        assertEquals(!NpcOfferProviders.offersAt(player, List.of(GIVER)).isEmpty(),
                NpcOfferProviders.hasOffersAt(player, List.of(GIVER)));
        assertTrue(NpcOfferProviders.hasOffersAt(player, List.of(GIVER)));

        assertFalse(NpcOfferProviders.hasOffersAt(player, List.of("Somebody_Else")));
        assertTrue(NpcOfferProviders.offersAt(player, List.of("Somebody_Else")).isEmpty());
    }

    private void publish(@Nonnull Quest quest) {
        ProgressionRuntime.publishQuests(OWNER, List.of(quest));
        ProgressionRuntime.ensureBuilt();
    }
}
