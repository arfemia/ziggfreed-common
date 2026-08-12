package com.ziggfreed.common.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.subject.Subject;

/**
 * The offer table's contract: several mods answering at one character, one broken mod costing only
 * its own offers, and the cheap "anything here" read stopping as soon as the answer is known.
 *
 * <p>The last one matters more than it looks: {@code hasOffersAt} runs wherever a surface decides
 * whether to show a marker or a greeting line, so building every provider's full list to answer a
 * yes/no is a cost paid per character on screen.
 */
class NpcOfferProvidersTest {

    private static final Subject PLAYER = Subject.of(UUID.randomUUID(), "Tester");
    private static final Set<String> HERE = Set.of("blacksmith");

    @BeforeEach
    @AfterEach
    void reset() {
        NpcOfferProviders.clear();
    }

    @Test
    void anEmptyTableOffersNothing() {
        assertFalse(NpcOfferProviders.hasAny());
        assertTrue(NpcOfferProviders.offersAt(PLAYER, HERE).isEmpty());
        assertFalse(NpcOfferProviders.hasOffersAt(PLAYER, HERE));
    }

    @Test
    void twoModsBothAnswerAtOneCharacter() {
        NpcOfferProviders.register("modA", "A",
                (subject, answersTo) -> List.of(NpcOffer.available("a_quest", "quest.a_quest.title")));
        NpcOfferProviders.register("modB", "B",
                (subject, answersTo) -> List.of(NpcOffer.available("b_quest", null)));

        List<String> ids = NpcOfferProviders.offersAt(PLAYER, HERE).stream().map(NpcOffer::id).toList();

        assertEquals(List.of("a_quest", "b_quest"), ids,
                "a player at one NPC must see both content mods' offers, neither knowing about the other");
    }

    @Test
    void aProviderIsHandedTheWholeAnswerSetRatherThanResolvingAliasesItself() {
        List<Integer> sizes = new ArrayList<>();
        NpcOfferProviders.register("mymod", "A", (subject, answersTo) -> {
            sizes.add(answersTo.size());
            return List.of();
        });

        NpcOfferProviders.offersAt(PLAYER, List.of("guide_wilds", "adventurers_guide"));

        assertEquals(List.of(2), sizes);
    }

    @Test
    void aLockedOfferIsStillAnOfferButNotSomethingToHailAbout() {
        NpcOfferProviders.register("mymod", "A", (subject, answersTo) ->
                List.of(NpcOffer.locked("gated", "quest.gated.title", List.of("ui.gate.locked_level"))));

        assertEquals(1, NpcOfferProviders.offersAt(PLAYER, HERE).size(),
                "a locked quest tells the player what to go and work towards, so it is shown");
        assertFalse(NpcOfferProviders.hasOffersAt(PLAYER, HERE),
                "but a greeting line must not promise work the player cannot take");
    }

    @Test
    void aProviderThatThrowsCostsOnlyItsOwnOffers() {
        NpcOfferProviders.register("broken", "A", (subject, answersTo) -> {
            throw new IllegalStateException("catalogue is mid-reload");
        });
        NpcOfferProviders.register("working", "B",
                (subject, answersTo) -> List.of(NpcOffer.available("still_here", null)));

        List<NpcOffer> offers = NpcOfferProviders.offersAt(PLAYER, HERE);

        assertEquals(List.of("still_here"), offers.stream().map(NpcOffer::id).toList());
        assertEquals(1L, NpcOfferProviders.info().get("broken").failures());
    }

    @Test
    void theCheapReadStopsAtTheFirstYes() {
        List<String> asked = new ArrayList<>();
        NpcOfferProviders.register("aaa_first", "A", (subject, answersTo) -> {
            asked.add("aaa_first");
            return List.of(NpcOffer.available("something", null));
        });
        NpcOfferProviders.register("zzz_second", "B", (subject, answersTo) -> {
            asked.add("zzz_second");
            return List.of();
        });

        assertTrue(NpcOfferProviders.hasOffersAt(PLAYER, HERE));
        assertEquals(List.of("aaa_first"), asked,
                "this runs per character on screen, so it must not build every provider's list");
    }

    @Test
    void aCharacterNothingNamesIsAskedOfNobody() {
        NpcOfferProviders.register("mymod", "A", (subject, answersTo) -> {
            throw new AssertionError("must not be asked about a nameless character");
        });
        assertTrue(NpcOfferProviders.offersAt(PLAYER, List.of()).isEmpty());
        assertFalse(NpcOfferProviders.hasOffersAt(PLAYER, List.of()));
    }
}
