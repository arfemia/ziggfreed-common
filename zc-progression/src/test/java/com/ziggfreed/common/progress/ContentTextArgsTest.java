package com.ziggfreed.common.progress;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.achievement.asset.AchievementDefinition;
import com.ziggfreed.common.i18n.ContentI18n;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.progress.asset.ContentTextAsset;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.asset.QuestDefinition;

/**
 * What an author wrote into a key's numbered slots has to reach the line the player reads.
 *
 * <p>A whole ladder of content is usually ONE translated line with each rung supplying its own
 * number, which is what {@code TextArgs} is for. A fold that resolves the key and drops the args
 * renders that line with an empty slot where the number belonged, on every rung at once - and the
 * content file it came from still reads as perfectly correct, so the search starts in the wrong
 * place. Both content kinds are pinned here, because a shared group whose two carriers drift is
 * worse than no shared group.
 *
 * <p>The binding happens once, where the content is folded, and the result rides the runtime object
 * - so this asserts the real values on the real object rather than the shape of the code that put
 * them there.
 */
class ContentTextArgsTest {

    /** The one sentinel the shared schema names, answered with a grouped RAW number. */
    @Test
    void theAmountSentinelIsAnsweredWithARawNumber() {
        Object[] args = ContentText.amountArgs(List.of(ContentTextAsset.ARG_AMOUNT, "Iron"), 12_500L);

        assertEquals(2, args.length);
        assertEquals("12,500", args[0],
                "the value of @amount is a rendering decision, and it is the number the content asks"
                        + " for, grouped for readability");
        assertTrue(args[0] instanceof String,
                "a digit needs no translating: it goes in as a raw value, never as a message the"
                        + " client would be asked to resolve");
        assertEquals("Iron", args[1],
                "anything else an author wrote is passed through exactly as typed, so a sentinel"
                        + " nothing answers shows up in the line instead of leaving a blank");
    }

    /** A quest's authored args ride its runtime object, bound to the amount its first step asks for. */
    @Test
    void aQuestHandsItsAuthoredArgsToTheKey() {
        Quest runtime = questDefinition(List.of(ContentTextAsset.ARG_AMOUNT),
                List.of(ContentTextAsset.ARG_AMOUNT, "ore")).quest();

        assertArrayEquals(new Object[] {"64"}, runtime.text().titleArgs());
        assertArrayEquals(new Object[] {"64", "ore"}, runtime.text().flavorArgs());
    }

    /** And an achievement's do too, off ITS first criterion, through the same shared group. */
    @Test
    void anAchievementHandsItsAuthoredArgsToTheKey() {
        Achievement runtime = achievementDefinition(List.of(ContentTextAsset.ARG_AMOUNT),
                List.of("flat")).achievement();

        assertArrayEquals(new Object[] {"100"}, runtime.text().titleArgs());
        assertArrayEquals(new Object[] {"flat"}, runtime.text().flavorArgs());
    }

    /**
     * The shared "carries no words" constant answers its own argument readers.
     *
     * <p>Static fields initialize in source order, so a constant built before the empty array it
     * points at carries null args instead - and the two {@code @Nonnull} readers throw the moment a
     * surface asks a nameless row for its arguments, which is nothing an author or a caller could
     * have done differently.
     */
    @Test
    void theSharedEmptyAnswersItsArgumentReaders() {
        assertNotNull(ContentText.EMPTY.titleArgs());
        assertNotNull(ContentText.EMPTY.flavorArgs());
    }

    /** A step names itself only when it authored a key or its fold composed a line for it. */
    @Test
    void aStepIsNamedOnlyByItsOwnKeyOrAComposedLine() {
        Quest runtime = questDefinition(List.of(), List.of()).quest();

        assertNull(runtime.text().objective("nothing_authored_for_this_one"));

        ContentText composed = ContentText.builder()
                .objectiveLine("collect", () -> Msg.raw("Gather 64 Ore"))
                .objectiveLine("broken", () -> {
                    throw new IllegalStateException("the composer is not installed here");
                })
                .build();
        assertEquals("Gather 64 Ore", composed.objective("collect").getRawText(),
                "a fold that already knows how its own steps read is the one layer that can say so,"
                        + " and a step with no line at all reads as an unnamed step to the player");
        assertNull(composed.objective("broken"),
                "and a composer that blows up costs its own line, never the screen");
    }

    /**
     * The words are resolved THROUGH the authored-key seam, not handed over as typed. A key with no
     * namespace is one the client cannot resolve, so the player reads the key itself - and the file
     * it came from still looks perfectly correct.
     */
    @Test
    void aTitleIsResolvedThroughTheAuthoredKeySeam() {
        ContentKeys.reset();
        ContentKeys.install(new ContentI18n() {
            @Override
            @Nonnull
            public String keyPrefix() {
                return "fixture.";
            }

            @Override
            public boolean hasKey(@Nonnull String unprefixedKey) {
                return "quest.ladder_rung.title".equals(unprefixedKey);
            }
        });
        try {
            Quest runtime = questDefinition(List.of(ContentTextAsset.ARG_AMOUNT), List.of()).quest();
            Message title = runtime.text().title();

            assertEquals("fixture.quest.ladder_rung.title", title.getMessageId(),
                    "the key reaches the client under the namespace its own mod ships it in;"
                            + " handed over as authored it is an id nothing resolves, and the player"
                            + " reads the key itself");
        } finally {
            ContentKeys.reset();
        }
    }

    private static QuestDefinition questDefinition(List<String> titleArgs, List<String> flavorArgs) {
        Quest quest = Quest.builder("ladder_rung")
                .objective(ObjectiveDef.builder("collect", "PICKUP_ITEM").target("Ore")
                        .amount(64).build())
                .build();
        return new QuestDefinition("ladder_rung", quest, "quest.ladder_rung.title",
                "quest.ladder_rung.flavor", null, titleArgs, flavorArgs, null, 0, List.of(), null,
                null, null, GateSpec.OPEN, Map.of(), List.of(), null, Map.of());
    }

    private static AchievementDefinition achievementDefinition(List<String> titleArgs,
            List<String> flavorArgs) {
        Achievement achievement = Achievement.builder("ladder_rung")
                .criterion(ObjectiveDef.builder("0", "PICKUP_ITEM").target("Ore").amount(100).build())
                .build();
        return new AchievementDefinition("ladder_rung", achievement, "achievement.ladder_rung.title",
                "achievement.ladder_rung.flavor", null, titleArgs, flavorArgs, null, null, 0,
                List.of(), null, GateSpec.OPEN, Map.of(), Map.of());
    }
}
