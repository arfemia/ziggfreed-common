package com.ziggfreed.common.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.protocol.LongParamValue;
import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.factor.DerivedFactorAsset;
import com.ziggfreed.common.factor.DerivedFactorConfig;
import com.ziggfreed.common.i18n.PlainText;
import com.ziggfreed.common.progress.gate.GateEvaluator;
import com.ziggfreed.common.progress.gate.GateRefusal;
import com.ziggfreed.common.text.ContentTextAsset;

/**
 * The one token-and-record-to-line mapping every locked surface reads. What is pinned here is the
 * DECISION - which line a refusal renders as, what deduplicates, what a named factor changes -
 * because the lines themselves are translations.
 */
class LockReasonsTest {

    private static final String NS = "ziggfreedcommon.progress.";

    /** The en-US-shaped values the composed-sentence assertions read against. */
    private static final Function<String, String> CATALOGUE = Map.of(
            NS + "lock.any_of", "Unlocked by: {0}",
            NS + "lock.any_of.join", "or",
            NS + "lock.not.met", "Unavailable while this is met: {0}",
            NS + "lock.factor.bound", "Requires {0} {1}",
            NS + "lock.quest", "Complete quest: {0}",
            "ziggfreedcommon.fmt.cat", "{0}{1}")::get;

    @AfterEach
    void clearTheProcessWideConfig() {
        DerivedFactorConfig.getInstance().mergePackLayer(Map.of());
    }

    /** Name {@code factorId} through a plain-fallback overlay, so no catalogue is needed. */
    private static void name(String factorId, String plainName) {
        DerivedFactorConfig.getInstance().mergePackLayer(Map.of(
                "overlay", DerivedFactorAsset.of("overlay", null, factorId, null,
                        ContentTextAsset.of(null, null, plainName), null)));
    }

    /** Two named factors at once - one merge, so neither overlay wipes the other. */
    private static void nameTwo() {
        DerivedFactorConfig.getInstance().mergePackLayer(Map.of(
                "rank_overlay", DerivedFactorAsset.of("rank_overlay", null, "yourmod:rank", null,
                        ContentTextAsset.of(null, null, "Rank"), null),
                "fame_overlay", DerivedFactorAsset.of("fame_overlay", null, "yourmod:fame", null,
                        ContentTextAsset.of(null, null, "Fame"), null)));
    }

    @Test
    void aQuestGateNamesTheQuestAndOutranksTheFlatRequirementsLine() {
        List<Message> lines = LockReasons.lines(List.of(
                QuestGates.REASON_PREREQUISITES, GateEvaluator.REASON_QUEST + "intro_1"));

        assertEquals(1, lines.size(),
                "the flat token is dropped when a specific line already covers the same gate");
        assertEquals(NS + "lock.quest", lines.get(0).getMessageId());
    }

    @Test
    void anUnnamedFactorReadsAsTheGenericRequirementsLine() {
        assertEquals(NS + "lock.prerequisites",
                LockReasons.line(GateEvaluator.REASON_FACTOR + "yourmod:unnamed").getMessageId(),
                "a factor no asset names is never painted at a player as its id");
    }

    @Test
    void aNamedFactorReadsWithItsNameAndItsBound() {
        name("yourmod:rank", "Rank");

        assertEquals(NS + "lock.factor.bound",
                LockReasons.line(GateRefusal.factor("yourmod:rank", null, 25.0, null)).getMessageId());
        assertEquals(NS + "lock.factor",
                LockReasons.line(GateRefusal.factor("yourmod:rank", null, null, null)).getMessageId(),
                "no bound to quote reads as the bare requirement");
        assertEquals(NS + "lock.factor",
                LockReasons.line(GateRefusal.factor("yourmod:rank", null, 1.0, null)).getMessageId(),
                "a Min of exactly 1 is the presence idiom, and 'Requires X 1' would read as noise");
        assertEquals(NS + "lock.factor.bound",
                LockReasons.line(GateRefusal.factor("yourmod:rank", null, null, 3.0)).getMessageId(),
                "a Max-only bound still quotes its number");
    }

    @Test
    void aNamedFactorTokenReadsNamedEvenThroughTheStringPath() {
        name("yourmod:rank", "Rank");

        assertEquals(NS + "lock.factor",
                LockReasons.line(GateEvaluator.REASON_FACTOR + "yourmod:rank").getMessageId(),
                "a token carries no bound, so the string path reads the unbounded form");
        List<Message> lines = LockReasons.lines(List.of(
                QuestGates.REASON_PREREQUISITES, GateEvaluator.REASON_FACTOR + "yourmod:rank"));
        assertEquals(1, lines.size(),
                "a factor line that NAMES its factor is specific, so the flat line drops");
    }

    @Test
    void recordsDeduplicateButDifferentBoundsStayDifferentLines() {
        name("yourmod:rank", "Rank");

        List<Message> lines = LockReasons.linesOf(List.of(
                GateRefusal.factor("yourmod:rank", null, 25.0, null),
                GateRefusal.factor("yourmod:rank", null, 25.0, null),
                GateRefusal.factor("yourmod:rank", null, 60.0, null),
                GateRefusal.PERMISSION,
                GateRefusal.custom("yourmod:reputation")));

        assertEquals(4, lines.size(), "two identical asks render once; the permission reads its "
                + "own sentence; the custom kind folds to the generic requirements line");
        assertEquals(NS + "lock.factor.bound", lines.get(0).getMessageId());
        assertEquals(NS + "lock.factor.bound", lines.get(1).getMessageId());
        assertEquals(NS + "lock.permission", lines.get(2).getMessageId());
        assertEquals(NS + "lock.prerequisites", lines.get(3).getMessageId());
    }

    @Test
    void aNamedFactorWithABoundAndAResolvedValueQuotesWhereThePlayerStands() {
        name("yourmod:rank", "Rank");

        assertEquals(NS + "lock.factor.bound.current",
                LockReasons.line(GateRefusal.factor("yourmod:rank", null, 25.0, null, 7.0)).getMessageId(),
                "a refusal carrying the reading the walk resolved adds the (currently N) arm");
        assertEquals(NS + "lock.factor.bound",
                LockReasons.line(GateRefusal.factor("yourmod:rank", null, 25.0, null, null)).getMessageId(),
                "no resolvable value renders the plain bounded line");
        assertEquals(NS + "lock.factor",
                LockReasons.line(GateRefusal.factor("yourmod:rank", null, 1.0, null, 0.0)).getMessageId(),
                "the presence idiom stays the unbounded line - the arm rides only on a real bound");
    }

    @Test
    void theMembershipFactorSpellingsNameTheirContentLikeTheLeafForms() {
        assertEquals(NS + "lock.quest",
                LockReasons.line(GateRefusal.factor("ziggfreedcommon:quest_completed",
                        "intro_1", 1.0, null)).getMessageId(),
                "the factor spelling of a quest prerequisite is the same requirement, so it reads "
                        + "as the same sentence");
        assertEquals(NS + "lock.achievement",
                LockReasons.line(GateRefusal.factor("ziggfreedcommon:achievement_earned",
                        "first_blood", 1.0, null)).getMessageId());
        assertEquals(NS + "lock.prerequisites",
                LockReasons.line(GateRefusal.factor("ziggfreedcommon:quest_completed",
                        null, 1.0, null)).getMessageId(),
                "with no Param there is no content to name, and no overlay is folded here");

        List<Message> lines = LockReasons.linesOf(List.of(
                GateRefusal.quest("intro_1"),
                GateRefusal.factor("ziggfreedcommon:quest_completed", "intro_1", 1.0, null)));
        assertEquals(1, lines.size(),
                "both spellings of one quest requirement render the identical sentence once");
    }

    @Test
    void aPermissionRefusalReadsItsOwnSentenceHoweverItIsSpelled() {
        assertEquals(NS + "lock.permission",
                LockReasons.line(GateRefusal.PERMISSION).getMessageId(),
                "a missing permission is a different kind of answer from a numeric bound, so it "
                        + "never squeezes into the factor frame");
        assertEquals(NS + "lock.permission",
                LockReasons.line(GateRefusal.factor(GateEvaluator.PERMISSION_FACTOR,
                        "my.node", 1.0, null)).getMessageId(),
                "the long factor spelling of a Permission leaf is the same question, so it reads "
                        + "the same sentence");
        assertEquals(NS + "lock.permission",
                LockReasons.line(GateEvaluator.REASON_PERMISSION).getMessageId(),
                "the token path folds the same way");

        assertEquals(1, LockReasons.linesOf(List.of(
                        GateRefusal.PERMISSION,
                        GateRefusal.factor(GateEvaluator.PERMISSION_FACTOR, "my.node", 1.0, null)))
                .size(), "both spellings render the identical sentence once");
        List<Message> lines = LockReasons.lines(List.of(
                QuestGates.REASON_PREREQUISITES, GateEvaluator.REASON_PERMISSION));
        assertEquals(1, lines.size(),
                "the permission line is specific, so the flat requirements line drops");
        assertEquals(NS + "lock.permission", lines.get(0).getMessageId());
    }

    @Test
    void anAnyOfRefusalReadsItsRoutesAsOneEitherOrList() {
        nameTwo();

        Message line = LockReasons.line(GateRefusal.anyOf(List.of(
                GateRefusal.factor("yourmod:rank", null, 25.0, null),
                GateRefusal.factor("yourmod:fame", null, 50.0, null))));

        assertEquals(NS + "lock.any_of", line.getMessageId());
        assertEquals("Unlocked by: Requires Rank 25 or Requires Fame 50",
                PlainText.render(line.getFormattedMessage(), CATALOGUE),
                "the routes join through the translatable joiner, each reading exactly as it "
                        + "would at top level");

        // The folded routes land in the sentence's {0} PARAM position, and a param renders only
        // when it carries rawText or messageId - the Msg.cat contract; a bare Msg.join fold here
        // would render EMPTY on every client.
        FormattedMessage folded = line.getFormattedMessage().messageParams.get("0");
        assertNotNull(folded, "the routes ride the sentence as its {0}");
        assertTrue(folded.messageId != null || folded.rawText != null,
                "every fold node must carry rawText or a messageId to render as a param");
    }

    @Test
    void aSingleRouteAnyOfReadsAsItsOwnLineNeverAsAList() {
        name("yourmod:rank", "Rank");

        assertEquals(NS + "lock.factor.bound",
                LockReasons.line(GateRefusal.anyOf(List.of(
                        GateRefusal.factor("yourmod:rank", null, 25.0, null)))).getMessageId(),
                "one route is not a choice, so the sentence is the route's own");
        assertEquals(NS + "lock.prerequisites",
                LockReasons.line(GateRefusal.ANY_OF).getMessageId(),
                "a record lifted from a token has no children, and keeps the generic line");
        assertEquals(NS + "lock.prerequisites",
                LockReasons.line(GateEvaluator.REASON_ANY_OF).getMessageId());
    }

    @Test
    void aMultiAskRouteStaysOneBundleInsideTheList() {
        nameTwo();

        Message line = LockReasons.line(GateRefusal.anyOf(List.of(
                GateRefusal.allOf(List.of(
                        GateRefusal.factor("yourmod:rank", null, 25.0, null),
                        GateRefusal.quest("intro_1"))),
                GateRefusal.factor("yourmod:fame", null, 50.0, null))));

        assertEquals("Unlocked by: Requires Rank 25, Complete quest: intro_1 or Requires Fame 50",
                PlainText.render(line.getFormattedMessage(), CATALOGUE),
                "a route asking several things at once stays one comma-joined bundle, never "
                        + "flattened into interchangeable alternatives");
    }

    @Test
    void aNotRefusalReadsTheNegatedSentenceNamingWhatIsMet() {
        name("yourmod:rank", "Rank");

        Message line = LockReasons.line(GateRefusal.not(List.of(
                GateRefusal.factor("yourmod:rank", null, 25.0, null))));
        assertEquals(NS + "lock.not.met", line.getMessageId());
        assertEquals("Unavailable while this is met: Requires Rank 25",
                PlainText.render(line.getFormattedMessage(), CATALOGUE));

        assertEquals(NS + "lock.not", LockReasons.line(GateRefusal.NOT).getMessageId(),
                "a record lifted from a token has no children, and keeps the fixed negated line");
        assertEquals(NS + "lock.not", LockReasons.line(GateEvaluator.REASON_NOT).getMessageId());
    }

    @Test
    void compositesDeduplicateByTheirComposedSentence() {
        nameTwo();

        List<Message> lines = LockReasons.linesOf(List.of(
                GateRefusal.anyOf(List.of(
                        GateRefusal.factor("yourmod:rank", null, 25.0, null),
                        GateRefusal.factor("yourmod:fame", null, 50.0, null))),
                GateRefusal.anyOf(List.of(
                        GateRefusal.factor("yourmod:rank", null, 25.0, null),
                        GateRefusal.factor("yourmod:fame", null, 50.0, null))),
                GateRefusal.anyOf(List.of(
                        GateRefusal.factor("yourmod:rank", null, 60.0, null),
                        GateRefusal.factor("yourmod:fame", null, 50.0, null)))));

        assertEquals(2, lines.size(),
                "two identical composed sentences render once; a different bound inside a route "
                        + "is a different sentence");
    }

    @Test
    void theFlatTokensKeepTheirFixedLinesAndAnUnknownTokenReadsAsOther() {
        assertEquals(NS + "lock.unavailable",
                LockReasons.line(QuestGates.REASON_UNAVAILABLE).getMessageId());
        assertEquals(NS + "lock.on_cooldown",
                LockReasons.line(QuestGates.REASON_ON_COOLDOWN).getMessageId());
        assertEquals(NS + "lock.log_full",
                LockReasons.line(QuestGates.REASON_LOG_FULL).getMessageId());
        assertEquals(NS + "lock.other",
                LockReasons.line("nothing_has_ever_heard_of_this").getMessageId());
        assertEquals(NS + "lock.other", LockReasons.line((String) null).getMessageId());
    }

    // ==================== every engine token has its own sentence ====================

    /**
     * The four tokens that used to collapse onto the catch-all: a daily finished today, a lifetime
     * cap spent, a quest already carried, quests switched off. Each reads its OWN line, because
     * "Not available yet" tells a player nothing about what to do. The requirements token, which
     * always had a line of its own, is asserted beside them so a change that reshuffled this
     * mapping could not quietly fold it in with the rest.
     */
    @Test
    void everyEngineTokenReadsItsOwnLineRatherThanTheCatchAll() {
        assertEquals(NS + "lock.period_spent",
                LockReasons.line(QuestGates.REASON_PERIOD_SPENT).getMessageId());
        assertEquals(NS + "lock.max_completions",
                LockReasons.line(QuestGates.REASON_MAX_COMPLETIONS).getMessageId());
        assertEquals(NS + "lock.already_started",
                LockReasons.line(QuestGates.REASON_ALREADY_STARTED).getMessageId());
        assertEquals(NS + "lock.system_disabled",
                LockReasons.line(QuestGates.REASON_SYSTEM_DISABLED).getMessageId());
        assertEquals(NS + "lock.prerequisites",
                LockReasons.line(QuestGates.REASON_PREREQUISITES).getMessageId());
    }

    @Test
    void theEngineTokensDeduplicateEachUnderTheirOwnSentence() {
        List<Message> lines = LockReasons.lines(List.of(
                QuestGates.REASON_ALREADY_STARTED, QuestGates.REASON_SYSTEM_DISABLED,
                QuestGates.REASON_PERIOD_SPENT, QuestGates.REASON_PERIOD_SPENT));

        assertEquals(3, lines.size(),
                "three different sentences stay three lines; the repeated token renders once");
        assertEquals(NS + "lock.already_started", lines.get(0).getMessageId());
        assertEquals(NS + "lock.system_disabled", lines.get(1).getMessageId());
        assertEquals(NS + "lock.period_spent", lines.get(2).getMessageId());
    }

    // ==================== the clock lines quote the wait ====================

    private static final long MINUTE = 60_000L;
    private static final long HOUR = 60L * MINUTE;
    private static final long DAY = 24L * HOUR;

    @Test
    void aClockTokenQuotesTheWaitWhenOneIsSuppliedAndFallsBackToItsTwinWhenNoneIs() {
        Message cooldown = LockReasons.line(QuestGates.REASON_ON_COOLDOWN, 2 * HOUR + 5 * MINUTE);
        assertEquals(NS + "lock.on_cooldown.in", cooldown.getMessageId());
        Message spent = LockReasons.line(QuestGates.REASON_PERIOD_SPENT, 5 * HOUR + 12 * MINUTE);
        assertEquals(NS + "lock.period_spent.in", spent.getMessageId());

        assertEquals(NS + "lock.on_cooldown",
                LockReasons.line(QuestGates.REASON_ON_COOLDOWN, 0L).getMessageId(),
                "no wait to hand over reads the no-clock twin, exactly as the token-only form does");
        assertEquals(NS + "lock.period_spent",
                LockReasons.line(QuestGates.REASON_PERIOD_SPENT, 0L).getMessageId());
        assertEquals(NS + "lock.on_cooldown",
                LockReasons.line(QuestGates.REASON_ON_COOLDOWN).getMessageId(),
                "the token-only entry point keeps answering the no-clock line");

        // The wait rides the sentence as its {0} PARAM, and a param renders only when it carries
        // rawText or a messageId - so the wait must be a translation, never a bare join.
        FormattedMessage wait = spent.getFormattedMessage().messageParams.get("0");
        assertNotNull(wait, "the wait rides the lock line as its {0}");
        assertEquals(NS + "wait.hours", wait.messageId);
    }

    @Test
    void aWaitOfForeverReadsAsFinishedForGoodRatherThanAsAHugeNumber() {
        assertEquals(NS + "lock.max_completions",
                LockReasons.line(QuestGates.REASON_ON_COOLDOWN, Long.MAX_VALUE).getMessageId());
        assertEquals(NS + "lock.max_completions",
                LockReasons.line(QuestGates.REASON_PERIOD_SPENT, Long.MAX_VALUE).getMessageId());
        assertEquals(NS + "lock.max_completions",
                LockReasons.bestLine(List.of(QuestGates.REASON_MAX_COMPLETIONS), Long.MAX_VALUE)
                        .getMessageId());
    }

    @Test
    void theWaitOnlyEverChangesTheClockTokens() {
        assertEquals(NS + "lock.prerequisites",
                LockReasons.line(QuestGates.REASON_PREREQUISITES, 3 * HOUR).getMessageId());
        assertEquals(NS + "lock.already_started",
                LockReasons.line(QuestGates.REASON_ALREADY_STARTED, Long.MAX_VALUE).getMessageId());
        assertEquals(NS + "lock.log_full",
                LockReasons.line(QuestGates.REASON_LOG_FULL, 3 * HOUR).getMessageId());
    }

    @Test
    void theWaitLinePicksTheMagnitudeARepeatActuallyUsesAndBindsWholeNumbersAsTypedParams() {
        Message days = LockReasons.waitLine(2 * DAY + 5 * HOUR + 30 * MINUTE);
        assertEquals(NS + "wait.days", days.getMessageId());
        assertNumber(days, "0", 2L);
        assertNumber(days, "1", 5L);

        Message hours = LockReasons.waitLine(5 * HOUR + 12 * MINUTE + 40_000L);
        assertEquals(NS + "wait.hours", hours.getMessageId());
        assertNumber(hours, "0", 5L);
        assertNumber(hours, "1", 12L);

        Message minutes = LockReasons.waitLine(7 * MINUTE + 59_000L);
        assertEquals(NS + "wait.minutes", minutes.getMessageId());
        assertNumber(minutes, "0", 7L);

        assertEquals(NS + "wait.under_minute", LockReasons.waitLine(59_999L).getMessageId(),
                "a wait about to elapse reads as under a minute, never as a count of nothing");
        assertEquals(NS + "wait.under_minute", LockReasons.waitLine(0L).getMessageId());
        assertEquals(NS + "wait.minutes", LockReasons.waitLine(MINUTE).getMessageId(),
                "exactly one minute is a minute");
        assertEquals(NS + "wait.days", LockReasons.waitLine(DAY).getMessageId(),
                "exactly one day is a day, with zero hours beside it");
        assertEquals(NS + "lock.max_completions", LockReasons.waitLine(Long.MAX_VALUE).getMessageId(),
                "a wait that never ends reads as the finished-for-good line, never as the hundred"
                        + " billion days the arithmetic would otherwise produce");
    }

    /** A wait's number binds as a TYPED numeric param, so each client writes its own digits. */
    private static void assertNumber(Message line, String slot, long expected) {
        FormattedMessage formatted = line.getFormattedMessage();
        assertTrue(formatted.params != null && formatted.params.containsKey(slot),
                "slot " + slot + " binds as a scalar param, never as raw text");
        assertTrue(formatted.params.get(slot) instanceof LongParamValue,
                "slot " + slot + " is a typed number, not a server-formatted string");
        assertEquals(expected, ((LongParamValue) formatted.params.get(slot)).value);
    }

    // ==================== the engine's own answer, wait included ====================

    @Test
    void theAcceptCheckFormsCarryTheEngineWaitThroughToTheLine() {
        QuestEngine.AcceptCheck spentToday = new QuestEngine.AcceptCheck(false,
                List.of(QuestGates.REASON_PERIOD_SPENT), 5 * HOUR + 12 * MINUTE);
        assertEquals(NS + "lock.period_spent.in", LockReasons.bestLine(spentToday).getMessageId());
        assertEquals(NS + "lock.period_spent.in", LockReasons.lines(spentToday).get(0).getMessageId());

        QuestEngine.AcceptCheck lifted = new QuestEngine.AcceptCheck(false,
                List.of(QuestGates.REASON_ON_COOLDOWN));
        assertEquals(NS + "lock.on_cooldown", LockReasons.bestLine(lifted).getMessageId(),
                "the two-argument construction carries no wait, so the twin reads");
    }
}
