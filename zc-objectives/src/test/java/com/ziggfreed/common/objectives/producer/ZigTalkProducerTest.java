package com.ziggfreed.common.objectives.producer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.achievement.InMemoryAchievementProgressStore;
import com.ziggfreed.common.npc.TalkCredit;
import com.ziggfreed.common.npc.TalkCredits;
import com.ziggfreed.common.progress.MatchMode;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.ObjectiveProgressState;
import com.ziggfreed.common.progress.runtime.Moment;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.progress.runtime.ProgressionSubjectSource;
import com.ziggfreed.common.quest.InMemoryQuestProgressStore;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.subject.Subject;

/**
 * A credited conversation, as quest and achievement progress on a server running the library alone:
 * {@code TALK_TO_NPC} once for the character the player is talking to, which every reaction hears,
 * then once more for each further id the character answers to, which only content naming that id
 * counts.
 *
 * <p>Driven through the shared runtime over an in-memory store, with a subject source standing in
 * for the player and a recording reaction, so the dispatch under test is the real one. The talk-credit
 * engine's own window decides whether a conversation happened at all; what is pinned here is what one
 * that did is worth.
 */
class ZigTalkProducerTest {

    private static final String OWNER = "talk-producer-test";
    private static final String JACK = "old_jack";
    private static final String WREN = "wren";

    private final Subject player = Subject.of(UUID.randomUUID(), "tester");
    private final List<Moment> heard = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ProgressionRuntime.resetForTests();
        ProgressionRuntime.registrar(OWNER)
                .questStore(new InMemoryQuestProgressStore())
                .achievementStore(new InMemoryAchievementProgressStore())
                .subjects(new ProgressionSubjectSource() {
                    @Override
                    @Nullable
                    public Subject questSubject(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
                        return player;
                    }

                    @Override
                    @Nullable
                    public Subject achievementSubject(@Nonnull Store<EntityStore> store,
                            @Nonnull Ref<EntityStore> ref) {
                        return player;
                    }
                })
                .momentListener(heard::add)
                .warn(message -> { });
        ProgressionRuntime.publishQuests(OWNER, List.of(talk("q_anyone", ""), talk("q_jack", JACK),
                talk("q_wren", WREN)));
        QuestEngine engine = ProgressionRuntime.quests();
        for (Quest quest : engine.quests()) {
            assertTrue(engine.accept(player, quest));
        }
    }

    @AfterEach
    void tearDown() {
        ProgressionRuntime.resetForTests();
    }

    @Test
    void aConversationCountsOnceForAnyoneAndOnceForEachIdTheCharacterAnswersTo() {
        ZigTalkProducer.credit(credit(JACK, WREN), UUID.randomUUID());

        assertEquals(1, progress("q_anyone"), "talking to anybody counts the conversation once, not once per id");
        assertEquals(1, progress("q_jack"), "the character's own id counts");
        assertEquals(1, progress("q_wren"), "and so does the id it shares, for content that names it");
    }

    @Test
    void everyReactionHearsTheConversationOnceWithTheLibrarysOwnRecord() {
        ZigTalkProducer.credit(credit(JACK, WREN), UUID.randomUUID());

        assertEquals(1, heard.size(), () -> "an alias reaches the engines only, so a lifetime counter counts one: "
                + heard.stream().map(Moment::target).toList());
        Moment moment = heard.get(0);
        assertEquals(ZigTalkProducer.KIND, moment.kindId());
        assertEquals(JACK, moment.target(), "the primary, the id a nameplate reads");
        TalkPayload payload = moment.payload(TalkPayload.class);
        assertNotNull(payload, "the record that proves the moment is the library's own");
        assertEquals(JACK, payload.credit().npcId());
    }

    @Test
    void anAliasWhoseWindowIsStillOpenIsNotCountedAgain() {
        UUID playerId = UUID.randomUUID();
        assertTrue(TalkCredits.claim(playerId, WREN), "a conversation with the shared id a moment ago");

        ZigTalkProducer.credit(credit(JACK, WREN), playerId);

        assertEquals(1, progress("q_jack"));
        assertEquals(0, progress("q_wren"), "the alias takes the re-trigger window on its own terms");
    }

    @Test
    void withNoPlayerToNameOnlyThePrimaryCounts() {
        ZigTalkProducer.credit(credit(JACK, WREN), null);

        assertEquals(1, progress("q_jack"));
        assertEquals(1, progress("q_anyone"));
        assertEquals(0, progress("q_wren"), "an alias window is claimed per player, so none is claimed for nobody");
    }

    @Test
    void theLibraryRegistersItsSinkWithTheTalkCreditEngine() {
        ZigTalkProducer.install();

        assertTrue(TalkCredits.hasAny(), "a conversation's MarkTalked beat credits on a server running only the library");
        assertTrue(TalkCredits.info().containsKey(ZigTalkProducer.SINK_ID));
    }

    // ==================== helpers ====================

    @Nonnull
    private static Quest talk(@Nonnull String id, @Nonnull String target) {
        return Quest.builder(id)
                .objective(ObjectiveDef.builder("speak", ZigTalkProducer.KIND).target(target)
                        .matchMode(MatchMode.EXACT).amount(5).build())
                .build();
    }

    @Nonnull
    private static TalkCredit credit(@Nonnull String npcId, @Nonnull String alias) {
        return new TalkCredit((Store<EntityStore>) null, new Ref<>((Store<EntityStore>) null, 0), null, npcId,
                List.of(npcId, alias), null);
    }

    private int progress(@Nonnull String questId) {
        ObjectiveProgressState state = ProgressionRuntime.quests().progressOf(player, questId, "speak");
        return state == null ? 0 : state.current();
    }
}
