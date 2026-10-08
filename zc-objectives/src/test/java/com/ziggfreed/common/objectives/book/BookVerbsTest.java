package com.ziggfreed.common.objectives.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;

import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.quest.QuestGates;
import com.ziggfreed.common.quest.QuestStatus;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.route.Destination;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.ui.toast.ToastSpec;

/**
 * The book's quest verbs over THE shared runtime, for a player the host names. A quest that arms itself is taken from
 * the book though it names a giver, and taken the way the engine arms it, at that giver, so where it is handed in
 * reads the same; a giver's quest that does not arm itself is still refused with the hint naming where it is taken;
 * and the engine's own refusal still stands.
 */
class BookVerbsTest {

    private static final String GIVER = "guide";

    private Subject player;
    private Host host;

    /** A host acting for {@link #player}, keeping the toasts a verb raises. */
    private final class Host implements BookContext.Host {

        final List<ToastSpec> toasts = new ArrayList<>();

        @Override
        public void send(@Nullable UICommandBuilder cmd, @Nullable UIEventBuilder events) {
        }

        @Override
        public void reopen(@Nonnull BookState next) {
        }

        @Override
        public void close() {
        }

        @Override
        public void toast(@Nonnull ToastSpec spec) {
            toasts.add(spec);
        }

        @Override
        public boolean openDestination(@Nonnull Destination destination) {
            return false;
        }

        @Override
        public void keep(@Nonnull BookState state) {
        }

        @Nullable
        @Override
        public Subject subject(boolean achievements) {
            return player;
        }
    }

    private static final BookTab QUESTS = new BookTab() {
        @Nonnull
        @Override
        public String id() {
            return ObjectiveBookPage.TAB_QUESTS;
        }

        @Nonnull
        @Override
        public String document() {
            return "Pages/Test.ui";
        }

        @Override
        public void build(@Nonnull BookContext ctx) {
        }

        @Override
        public boolean handle(@Nonnull BookContext ctx, @Nonnull String action, @Nonnull ObjectiveBookEventData data) {
            return false;
        }
    };

    @BeforeEach
    void runtime() {
        ProgressionRuntime.resetForTests();
        player = Subject.of(UUID.randomUUID(), "tester");
        host = new Host();
    }

    @AfterEach
    void reset() {
        ProgressionRuntime.resetForTests();
    }

    /** One report-back step, handed in at the giver. */
    private static Quest.Builder reportBack(String id) {
        return Quest.builder(id).npcViewId(GIVER)
                .objective(ObjectiveDef.builder("tell", "TURN_IN").target("").amount(1).turnInLockId(GIVER).build());
    }

    /** The verbs, over a runtime publishing {@code quests}. */
    private BookVerbs verbs(Quest... quests) {
        ProgressionRuntime.publishQuests("test", List.of(quests));
        return BookContext.forEvent(host, QUESTS, BookState.of(ObjectiveBookPage.TAB_QUESTS),
                ObjectiveBookDeps.DEFAULTS, null, null, null, null, 0L).verbs();
    }

    @Test
    void aQuestThatArmsItselfIsAcceptedFromTheBookAtItsGiversWord() {
        Quest selfArming = reportBack("q_meet").autoAccept(true).build();
        BookVerbs verbs = verbs(selfArming);

        assertTrue(verbs.accept(selfArming), "the engine puts it in the log, so the book may take it back");
        QuestEngine engine = ProgressionRuntime.quests();
        assertEquals(QuestStatus.ACTIVE, engine.status(player, selfArming));
        assertEquals(GIVER, engine.acceptSiteOf(player, selfArming.id()),
                "taken as the engine arms it, at its giver, so where it is handed in reads the same");
        assertEquals(ToastKind.SUCCESS, host.toasts.get(0).kind());
    }

    @Test
    void thePrimaryVerbTakesAQuestThatArmsItself() {
        Quest selfArming = reportBack("q_meet").autoAccept(true).build();
        BookVerbs verbs = verbs(selfArming);

        assertTrue(verbs.primary(selfArming.id()));
        assertEquals(QuestStatus.ACTIVE, ProgressionRuntime.quests().status(player, selfArming));
    }

    @Test
    void aGiversQuestThatDoesNotArmItselfIsStillTakenAtItsGiver() {
        Quest giver = reportBack("q_giver").build();
        BookVerbs verbs = verbs(giver);

        assertFalse(verbs.accept(giver));
        assertEquals(QuestStatus.NOT_STARTED, ProgressionRuntime.quests().status(player, giver));
        assertEquals(1, host.toasts.size());
        assertEquals(ToastKind.INFO, host.toasts.get(0).kind(), "the hint naming where it is taken");
        assertTrue(host.toasts.get(0).message().getMessageId()
                .startsWith("ziggfreedcommon.progression.book.quests.giver_hint"));
    }

    @Test
    void aQuestThatArmsItselfIsNotTakenWhileTheEngineRefusesIt() {
        ProgressionRuntime.registrar("test").questGates(new QuestGates() {
            @Override
            public boolean accepts(@Nonnull Subject subject, @Nonnull Quest quest, @Nonnull List<String> reasons) {
                reasons.add(QuestGates.REASON_PREREQUISITES);
                return false;
            }
        });
        Quest selfArming = reportBack("q_meet").autoAccept(true).build();
        BookVerbs verbs = verbs(selfArming);

        assertFalse(verbs.accept(selfArming));
        assertEquals(QuestStatus.NOT_STARTED, ProgressionRuntime.quests().status(player, selfArming));
        assertTrue(host.toasts.isEmpty(), "no word of its giver: it is never taken there");
    }
}
