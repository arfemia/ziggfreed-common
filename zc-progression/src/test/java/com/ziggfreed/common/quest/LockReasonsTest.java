package com.ziggfreed.common.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.factor.DerivedFactorAsset;
import com.ziggfreed.common.factor.DerivedFactorConfig;
import com.ziggfreed.common.progress.asset.ContentTextAsset;
import com.ziggfreed.common.progress.gate.GateEvaluator;
import com.ziggfreed.common.progress.gate.GateRefusal;

/**
 * The one token-and-record-to-line mapping every locked surface reads. What is pinned here is the
 * DECISION - which line a refusal renders as, what deduplicates, what a named factor changes -
 * because the lines themselves are translations.
 */
class LockReasonsTest {

    private static final String NS = "ziggfreedcommon.progress.";

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

        assertEquals(3, lines.size(), "two identical asks render once; permission and the custom "
                + "kind fold into one generic requirements line");
        assertEquals(NS + "lock.factor.bound", lines.get(0).getMessageId());
        assertEquals(NS + "lock.factor.bound", lines.get(1).getMessageId());
        assertEquals(NS + "lock.prerequisites", lines.get(2).getMessageId());
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
}
