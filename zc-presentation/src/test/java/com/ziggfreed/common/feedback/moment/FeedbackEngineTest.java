package com.ziggfreed.common.feedback.moment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.ziggfreed.common.i18n.ContentI18n;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.subject.Subject;

/**
 * What an authored moment decodes to, and what the engine does with a subject it cannot draw
 * anything for.
 *
 * <p>The drawing itself is packets, so it is validated in game rather than here; what is pinned
 * here is everything a broken authoring file or an absent player could otherwise turn into a throw
 * on the path of a state transition, plus the pure decisions the file drives: which variant a
 * moment resolves to, which key a line reads, and whether a progress tick is worth a toast.
 */
class FeedbackEngineTest {

    private Subject handleless;

    @BeforeEach
    void setUp() {
        FeedbackMomentConfig.getInstance().mergePackLayer(Map.of());
        ContentKeys.reset();
        handleless = Subject.of(UUID.randomUUID(), "tester");
    }

    @AfterEach
    void tearDown() {
        FeedbackMomentConfig.getInstance().mergePackLayer(Map.of());
        ContentKeys.reset();
    }

    @Nonnull
    private static FeedbackMomentAsset moment(@Nonnull String id, @Nonnull String json)
            throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(FeedbackMomentAsset.class, id, null);
        return FeedbackMomentAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(json), null, new AssetExtraInfo<>(data));
    }

    private static void install(@Nonnull String id, @Nonnull FeedbackMomentAsset asset) {
        FeedbackMomentConfig.getInstance().mergePackLayer(Map.of(id, asset));
    }

    private static void install(@Nonnull String id, @Nonnull String json) throws IOException {
        install(id, moment(id, json));
    }

    // ==================== the authoring surface ====================

    @Test
    void everyGroupIsOptionalAndTheAuthoredOnesDecode() throws IOException {
        FeedbackMomentAsset asset = moment("Quest_Completed", """
                { "Toast": { "Title": { "Key": "notify.quest_complete", "Args": ["title"],
                                        "Color": "#FFFF00" } },
                  "Sound": { "Id": "SFX_Discovery_Z2_Short" } }
                """);

        assertNotNull(asset.getToast());
        assertNotNull(asset.getToast().getTitle());
        assertEquals("notify.quest_complete", asset.getToast().getTitle().getKey());
        assertEquals(List.of("title"), List.of(asset.getToast().getTitle().getArgs()));
        assertEquals("#FFFF00", asset.getToast().getTitle().getColor());
        assertNull(asset.getToast().getSecondary(), "a one-line toast authors no second line");
        assertNull(asset.getToast().getEveryPercent(), "no mark means every tick shows");
        assertNotNull(asset.getSound());
        assertEquals("SFX_Discovery_Z2_Short", asset.getSound().getId());
        assertNull(asset.getBroadcast(), "a group nobody authored stays absent, not empty");
        assertNull(asset.getCommand());
        assertEquals(0, asset.getVariants().length, "no variants means one reading for every case");
    }

    @Test
    void aChildInheritsGroupByGroupFromItsParent() throws IOException {
        FeedbackMomentAsset parent = moment("Quest_Completed", """
                { "Toast": { "Title": { "Key": "notify.quest_complete", "Args": ["title"] } },
                  "Sound": { "Id": "SFX_Discovery_Z2_Short" } }
                """);
        AssetExtraInfo.Data data =
                new AssetExtraInfo.Data(FeedbackMomentAsset.class, "Quest_Parked", "Quest_Completed");
        FeedbackMomentAsset child = FeedbackMomentAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString("{ \"Sound\": { \"Id\": \"SFX_Discovery_Z1_Short\" } }"),
                parent, new AssetExtraInfo<>(data));

        assertNotNull(child.getSound());
        assertEquals("SFX_Discovery_Z1_Short", child.getSound().getId());
        assertNotNull(child.getToast(), "the group the child said nothing about carries over");
        assertNotNull(child.getToast().getTitle());
        assertEquals("notify.quest_complete", child.getToast().getTitle().getKey());
    }

    /**
     * ONE file says two things: the first variant whose {@code When} matches lays its authored
     * groups over the file's own and leaves the rest alone. A value under {@code When} may be
     * written bare (a boolean, a number) and is compared as text.
     */
    @Test
    void theFirstMatchingVariantOverlaysOnlyTheGroupsItRestates() throws IOException {
        FeedbackMomentAsset asset = moment("Quest_Parked", """
                { "Toast": { "Title": { "Key": "ready", "Args": ["title"], "Color": "#FFFF00" } },
                  "Sound": { "Id": "SFX_Discovery_Z1_Short" },
                  "Variants": [
                    { "When": { "reason": "no_space" },
                      "Toast": { "Title": { "Key": "full", "Args": ["title"], "Color": "#FF0000" } },
                      "Sound": {} },
                    { "When": { "turnIn": "accept_site", "parked": true },
                      "Toast": { "Title": { "Key": "board", "Args": ["title"] } } }
                  ] }
                """);

        FeedbackMomentAsset.Resolved full = asset.resolve(
                Map.of("reason", "no_space", "turnIn", "accept_site", "parked", Boolean.TRUE));
        assertEquals("full", full.toast().getTitle().getKey(), "the first match wins");
        assertNotNull(full.sound(), "an empty group is authored, so it replaces the file's own");
        assertNull(full.sound().getId(), "and it means silence for this case");

        FeedbackMomentAsset.Resolved board = asset.resolve(
                Map.of("reason", "collect", "turnIn", "ACCEPT_SITE", "parked", Boolean.TRUE));
        assertEquals("board", board.toast().getTitle().getKey(), "matched ignoring case");
        assertEquals("SFX_Discovery_Z1_Short", board.sound().getId(),
                "a group the variant did not restate is the file's own");

        FeedbackMomentAsset.Resolved plain = asset.resolve(Map.of("reason", "collect"));
        assertEquals("ready", plain.toast().getTitle().getKey(),
                "a moment matching no variant reads the file's own groups");

        FeedbackMomentAsset.Resolved sentence = asset.resolve(
                Map.of("reason", Msg.raw("no_space")));
        assertEquals("ready", sentence.toast().getTitle().getKey(),
                "a localized value is a sentence and never matches a When");
    }

    /**
     * A line may read its key from one of the moment's own values, so a per-content wording (an
     * achievement's own announcement) needs no file per achievement; a moment carrying no such
     * value falls back to the fixed key, and with neither the line shows nothing.
     */
    @Test
    void aLineReadsItsKeyFromANamedValueBeforeTheFixedOne() throws IOException {
        FeedbackMomentAsset asset = moment("Achievement_Unlocked", """
                { "Broadcast": { "Title": { "KeyArg": "announceKey", "Args": ["title"] },
                                 "Secondary": { "KeyArg": "bodyKey", "Key": "default.body",
                                                "Args": ["player", "title"] } } }
                """);
        FeedbackMomentAsset.Line title = asset.getBroadcast().getTitle();
        FeedbackMomentAsset.Line body = asset.getBroadcast().getSecondary();

        assertEquals("their.own.key", title.keyFor(Map.of("announceKey", "their.own.key")));
        assertNull(title.keyFor(Map.of("title", "x")),
                "no value and no fixed key: this line shows nothing for this achievement");
        assertNull(title.keyFor(Map.of("announceKey", Msg.raw("a sentence"))),
                "a localized value is not a key");
        assertEquals("default.body", body.keyFor(Map.of("title", "x")),
                "the fixed key answers when the moment carries no named value");
        assertEquals("theirs", body.keyFor(Map.of("bodyKey", "theirs")));

        Message line = FeedbackEngine.line(title, Map.of("announceKey", "their.own.key", "title", "T"));
        assertNotNull(line);
        assertEquals("their.own.key", line.getMessageId());
    }

    // ==================== what the engine does ====================

    @Test
    void aMomentNobodyAuthoredIsSilentRatherThanAFailure() {
        assertDoesNotThrow(() ->
                FeedbackEngine.fire("nothing.authored.this", handleless, Map.of("title", "x")));
    }

    @Test
    void aSubjectWithNoPlayerHandleIsAskedForOneAndThenLeftAlone() throws IOException {
        install("Quest_Completed", moment("Quest_Completed", """
                { "Toast": { "Title": { "Key": "notify.quest_complete", "Args": ["title"] } } }
                """));
        AskedHandle handle = new AskedHandle();
        Subject subject = new Subject(UUID.randomUUID(), "tester", handle);

        assertDoesNotThrow(() ->
                FeedbackEngine.fire("Quest_Completed", subject, Map.of("title", "A Quest")));

        assertTrue(handle.asked.contains(PlayerRef.class),
                "the toast resolves its player through the subject's own handle");
    }

    @Test
    void aMomentNamingAnArgumentItDidNotCarryIsStillHarmless() throws IOException {
        install("Quest_Completed", """
                { "Toast": { "Title": { "Key": "notify.quest_complete", "Args": ["title"] },
                             "Secondary": { "Key": "notify.quest_flavor", "Args": ["nobody"] } },
                  "Sound": { "Id": "SFX_Discovery_Z2_Short" } }
                """);
        AskedHandle handle = new AskedHandle();
        Subject subject = new Subject(UUID.randomUUID(), "tester", handle);

        assertDoesNotThrow(() -> FeedbackEngine.fire("Quest_Completed", subject, Map.of()));
        assertEquals(List.of(PlayerRef.class), handle.asked,
                "one player resolution for the whole moment however many parts it has, and no"
                        + " audience question at all for a subject there is no screen to ask about");
    }

    /**
     * The picture is read from ONE fixed argument name and nothing else. It is not an authored leaf,
     * so a moment file can neither point it somewhere else nor mis-spell it; a producer with a
     * picture offers one under that name, and a moment with none draws a toast without one.
     */
    @Test
    void theToastPictureComesFromTheFixedArgumentNameOrNowhere() {
        assertEquals("Trophy_Gold", FeedbackEngine.icon(Map.of("icon", "Trophy_Gold")));
        assertNull(FeedbackEngine.icon(Map.of("IconArg", "Trophy_Gold")),
                "no other name is read, so an authored pointer at one cannot exist");
        assertNull(FeedbackEngine.icon(Map.of("title", "A Quest")),
                "a moment carrying no picture draws a toast without one");
        assertNull(FeedbackEngine.icon(Map.of("icon", Msg.raw("A Quest"))),
                "a localized value is a sentence somebody's client renders, never an item id");
    }

    /** The subject's name is always reachable as {@code player}, unless the producer carried its own. */
    @Test
    void thePlayerNameIsAlwaysAvailableToALine() {
        Map<String, Object> values = FeedbackEngine.withPlayer(handleless, Map.of("title", "T"));
        assertEquals("tester", values.get(FeedbackEngine.PLAYER_ARG));
        assertEquals("T", values.get("title"));

        Map<String, Object> theirs = Map.of("player", "somebody else");
        assertSame(theirs, FeedbackEngine.withPlayer(handleless, theirs),
                "a producer's own value is kept, untouched");
    }

    /**
     * The authored key is UNPREFIXED, the way content is written everywhere else in this library,
     * and the consumer whose catalogue ships it lends it the namespace its client actually
     * registered. Handing the client the key as written is how a player ends up reading the key
     * itself instead of the sentence.
     */
    @Test
    void anAuthoredKeyIsResolvedThroughTheConsumerThatShipsIt() throws IOException {
        ContentKeys.install(new Fill("mmoskilltree.", "notify.quest_complete"));
        FeedbackMomentAsset asset = moment("Quest_Completed", """
                { "Toast": { "Title": { "Key": "notify.quest_complete", "Args": ["title"] } } }
                """);

        Message title = FeedbackEngine.line(asset.getToast().getTitle(),
                Map.of("title", "A Quest"));

        assertNotNull(title);
        assertEquals("mmoskilltree.notify.quest_complete", title.getMessageId());
    }

    /** A key nobody claims goes out exactly as authored, for a consumer pointing at a native id. */
    @Test
    void aKeyNoConsumerClaimsGoesOutAsWritten() throws IOException {
        FeedbackMomentAsset asset = moment("Quest_Completed", """
                { "Toast": { "Title": { "Key": "some.native.key", "Args": ["title"] } } }
                """);

        Message title = FeedbackEngine.line(asset.getToast().getTitle(),
                Map.of("title", "A Quest"));

        assertNotNull(title);
        assertEquals("some.native.key", title.getMessageId());
    }

    // ==================== who is toasted ====================

    @Nonnull
    private static FeedbackMomentAsset.Toast toastWith(@Nullable Integer everyPercent)
            throws IOException {
        String mark = everyPercent == null ? "" : ", \"EveryPercent\": " + everyPercent;
        return moment("Quest_Objective_Progressed", """
                { "Toast": { "Title": { "Key": "tick", "Args": ["step"] }%s } }
                """.formatted(mark)).getToast();
    }

    /**
     * A player who turned this consumer's own notifications down loses the personal toast and
     * nothing else: the subject's handle answers, because no authored file can read a setting that
     * lives on one player. Asserted on the ANSWER rather than on the asking, so deleting the branch
     * that honours it fails here.
     */
    @Test
    void aSubjectsOwnAnswerDecidesWhetherItIsToasted() throws IOException {
        Subject quiet = new Subject(UUID.randomUUID(), "tester", new QuietHandle());
        Subject noisy = new Subject(UUID.randomUUID(), "tester", (Subject.HandleFacets)
                type -> type == FeedbackAudience.class
                        ? (FeedbackAudience) (momentId, args) -> true : null);
        FeedbackMomentAsset.Toast toast = toastWith(null);

        assertFalse(FeedbackEngine.wantsToast(quiet, "Quest_Completed", toast, Map.of()),
                "a handle that says no is honoured");
        assertTrue(FeedbackEngine.wantsToast(noisy, "Quest_Completed", toast, Map.of()),
                "and a handle that says yes is too");
    }

    /** No opinion is not a refusal: a subject that answers for nothing gets whatever was authored. */
    @Test
    void aSubjectWithNoOpinionIsToasted() throws IOException {
        assertTrue(FeedbackEngine.wantsToast(handleless, "Quest_Completed", toastWith(null), Map.of()));
    }

    /** An opinion that throws is not allowed to cost the moment the toast it was authored to draw. */
    @Test
    void anOpinionThatThrowsStillLeavesTheToastAuthored() throws IOException {
        Subject broken = new Subject(UUID.randomUUID(), "tester", (Subject.HandleFacets)
                type -> type == FeedbackAudience.class
                        ? (FeedbackAudience) (momentId, args) -> {
                            throw new IllegalStateException("no");
                        }
                        : null);

        assertTrue(FeedbackEngine.wantsToast(broken, "Quest_Completed", toastWith(null), Map.of()));
    }

    /**
     * {@code EveryPercent} is the authored answer for a subject with no opinion: an ordinary tick
     * shows only when it crosses a mark, the finishing tick always does, and a moment that does not
     * report progress at all is untouched by it.
     */
    @Test
    void anAuthoredMarkDecidesWhichTicksASubjectWithNoOpinionSees() throws IOException {
        FeedbackMomentAsset.Toast quarters = toastWith(25);

        assertTrue(FeedbackEngine.wantsToast(handleless, "m", quarters, progress(1, 4, false)),
                "1 of 4 crosses the first quarter");
        assertFalse(FeedbackEngine.wantsToast(handleless, "m", quarters, progress(1, 8, false)),
                "1 of 8 is between marks");
        assertTrue(FeedbackEngine.wantsToast(handleless, "m", quarters, progress(2, 8, false)),
                "2 of 8 crosses it");
        assertTrue(FeedbackEngine.wantsToast(handleless, "m", quarters, progress(3, 3, true)),
                "the finishing tick always shows");
        assertTrue(FeedbackEngine.wantsToast(handleless, "m", quarters, Map.of("title", "x")),
                "a moment reporting no progress is not filtered by a mark it cannot measure");
        assertTrue(FeedbackEngine.wantsToast(handleless, "m", toastWith(null), progress(1, 8, false)),
                "no mark authored means every tick");
    }

    /**
     * A subject WITH an opinion is told whether the tick crossed the authored mark, under a fixed
     * name, and decides for itself: that is how a consumer offers "every tick", "the milestones",
     * "only finishes" or "nothing" as its own setting without the engine learning any of them.
     */
    @Test
    void aSubjectWithAnOpinionIsToldWhetherTheTickCrossedTheMark() throws IOException {
        List<Map<String, Object>> asked = new ArrayList<>();
        Subject curious = new Subject(UUID.randomUUID(), "tester", (Subject.HandleFacets)
                type -> type == FeedbackAudience.class
                        ? (FeedbackAudience) (momentId, args) -> {
                            asked.add(args);
                            return Boolean.TRUE.equals(args.get(FeedbackEngine.MILESTONE_ARG));
                        }
                        : null);
        FeedbackMomentAsset.Toast quarters = toastWith(25);

        assertFalse(FeedbackEngine.wantsToast(curious, "m", quarters, progress(1, 8, false)));
        assertTrue(FeedbackEngine.wantsToast(curious, "m", quarters, progress(2, 8, false)));
        assertEquals(Boolean.FALSE, asked.get(0).get(FeedbackEngine.MILESTONE_ARG));
        assertEquals(Boolean.TRUE, asked.get(1).get(FeedbackEngine.MILESTONE_ARG));
        assertEquals(1, asked.get(0).get("current"), "and everything the moment carried is there too");

        FeedbackEngine.wantsToast(curious, "m", toastWith(null), progress(1, 8, false));
        assertFalse(asked.get(2).containsKey(FeedbackEngine.MILESTONE_ARG),
                "no mark authored, nothing to report: absent rather than false");
    }

    @Nonnull
    private static Map<String, Object> progress(int current, int required, boolean finished) {
        return Map.of("step", "Break logs", "current", current, "required", required,
                "finished", finished);
    }

    /** A consumer catalogue claiming exactly the keys it was built with. */
    private record Fill(@Nonnull String prefix, @Nonnull String key) implements ContentI18n {

        @Override
        @Nonnull
        public String keyPrefix() {
            return prefix;
        }

        @Override
        public boolean hasKey(@Nonnull String unprefixedKey) {
            return key.equals(unprefixedKey);
        }
    }

    /** A handle whose player wants no personal notifications at all. */
    private static final class QuietHandle implements Subject.HandleFacets {

        @Override
        @Nullable
        public Object facet(@Nonnull Class<?> type) {
            return type == FeedbackAudience.class ? (FeedbackAudience) (momentId, args) -> false : null;
        }
    }

    /** A handle that answers for nothing, and records what it was asked for. */
    private static final class AskedHandle implements Subject.HandleFacets {

        private final List<Class<?>> asked = new ArrayList<>();

        @Override
        @Nullable
        public Object facet(@Nonnull Class<?> type) {
            asked.add(type);
            return null;
        }
    }
}
