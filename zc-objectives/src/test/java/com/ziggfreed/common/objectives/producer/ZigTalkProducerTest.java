package com.ziggfreed.common.objectives.producer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.npc.TalkCredit;
import com.ziggfreed.common.progress.DispatchOptions;
import com.ziggfreed.common.progress.asset.ObjectiveKindAsset;

/**
 * The whole decision this producer makes about one credited conversation, with no server anywhere
 * near it: the primary takes the FULL dispatch, every reaction included, with the conversation as
 * its payload; each further id the character answers to takes the targeted-only engine route, and
 * only once that id's own re-trigger window is claimed; the qualifier rides on every fire. The
 * engine half (the live store, the engines) is {@link ProgressDispatch}'s and is tested there.
 */
class ZigTalkProducerTest {

    private static final UUID PLAYER = UUID.randomUUID();

    /** One recorded fire: which route it took, the values an author addresses, and what rode along. */
    private record Fired(@Nonnull String route, @Nonnull String kind, @Nonnull String target,
            @Nullable String qualifier, long amount, @Nullable TalkPayload payload,
            @Nullable DispatchOptions options) {
    }

    /** A credit with no live engine handles: the fan-out never touches them. */
    private static TalkCredit credit(@Nullable String qualifier, @Nonnull String primary, String... aliases) {
        List<String> answers = new ArrayList<>();
        answers.add(primary);
        answers.addAll(List.of(aliases));
        return new TalkCredit(null, null, null, primary, answers, qualifier);
    }

    /** Record every fire, in order, and every claim asked, answering each claim from {@code open}. */
    private static final class Recorder implements ZigTalkProducer.Sink {

        final List<Fired> fired = new ArrayList<>();
        final List<String> log = new ArrayList<>();

        @Override
        public void primary(@Nonnull TalkCredit credit, @Nonnull String kindId, @Nonnull String target,
                @Nullable String qualifier, long amount, @Nonnull TalkPayload payload) {
            fired.add(new Fired("primary", kindId, target, qualifier, amount, payload, null));
            log.add("fire:" + target);
        }

        @Override
        public void alias(@Nonnull TalkCredit credit, @Nonnull String kindId, @Nonnull String target,
                @Nullable String qualifier, long amount, @Nonnull DispatchOptions options) {
            fired.add(new Fired("alias", kindId, target, qualifier, amount, null, options));
            log.add("fire:" + target);
        }

        int fanOut(@Nonnull TalkCredit credit, @Nullable UUID playerId, @Nonnull Set<String> windowStillOpen) {
            return ZigTalkProducer.fanOut(credit, playerId, (player, id) -> {
                log.add("claim:" + id);
                return !windowStillOpen.contains(id);
            }, this);
        }
    }

    @Test
    void thePrimaryTakesTheFullDispatchCarryingTheConversation() {
        TalkCredit credit = credit(null, "Guide_Wilds");
        Recorder recorder = new Recorder();

        assertEquals(1, recorder.fanOut(credit, PLAYER, Set.of()));

        assertEquals(1, recorder.fired.size());
        Fired primary = recorder.fired.get(0);
        assertEquals("primary", primary.route(), "the primary goes through the producer form, so reactions see it");
        assertEquals(ZigTalkProducer.KIND, primary.kind());
        assertEquals("Guide_Wilds", primary.target());
        assertEquals(1L, primary.amount());
        assertNotNull(primary.payload(), "a reaction tells this producer's moment from a hand-fired one by its payload");
        assertSame(credit, primary.payload().credit());
        assertEquals(List.of("fire:Guide_Wilds"), recorder.log, "the primary's window was already taken by TalkCredits");
    }

    @Test
    void eachAliasIsTargetedOnlyAndFiresOnlyAfterItsOwnWindowIsClaimed() {
        Recorder recorder = new Recorder();

        int fired = recorder.fanOut(credit(null, "Guide_Wilds", "Adventurers_Guide", "Town_Guide"), PLAYER, Set.of());

        assertEquals(3, fired);
        List<Fired> aliases = recorder.fired.stream().filter(f -> f.route().equals("alias")).toList();
        assertEquals(List.of("Adventurers_Guide", "Town_Guide"), aliases.stream().map(Fired::target).toList());
        for (Fired alias : aliases) {
            assertEquals(ZigTalkProducer.KIND, alias.kind());
            assertEquals(DispatchOptions.TARGETED_ONLY, alias.options(),
                    "a match-all step already counted the primary, so an alias may move only a step that names it");
            assertNull(alias.payload(), "the alias route reaches the engines only, never a reaction");
        }
        assertEquals(List.of("fire:Guide_Wilds", "claim:Adventurers_Guide", "fire:Adventurers_Guide",
                "claim:Town_Guide", "fire:Town_Guide"), recorder.log,
                "every alias claims its own window before it fires");
    }

    @Test
    void anAliasWhoseWindowIsStillOpenIsSkippedAndTheRestStillFire() {
        Recorder recorder = new Recorder();

        int fired = recorder.fanOut(credit(null, "Guide_Wilds", "Adventurers_Guide", "Town_Guide"), PLAYER,
                Set.of("Adventurers_Guide"));

        assertEquals(2, fired);
        assertEquals(List.of("Guide_Wilds", "Town_Guide"), recorder.fired.stream().map(Fired::target).toList(),
                "an alias swallowed by its own window costs only itself");
    }

    @Test
    void theQualifierRidesOnEveryFire() {
        Recorder recorder = new Recorder();

        recorder.fanOut(credit("Feast_Invite", "Guide_Wilds", "Adventurers_Guide"), PLAYER, Set.of());

        assertEquals(2, recorder.fired.size());
        for (Fired fire : recorder.fired) {
            assertEquals("Feast_Invite", fire.qualifier(), fire.route() + " must carry the beat's qualifier");
        }
    }

    @Test
    void thePrimaryIsNeverFiredAgainInTheAliasPassHoweverItIsSpelled() {
        Recorder recorder = new Recorder();

        recorder.fanOut(credit(null, "Guide_Wilds", "guide_wilds", "Adventurers_Guide"), PLAYER, Set.of());

        assertEquals(List.of("Guide_Wilds", "Adventurers_Guide"), recorder.fired.stream().map(Fired::target).toList(),
                "one conversation counts once for its primary");
    }

    @Test
    void withNoPlayerToClaimForOnlyThePrimaryGoes() {
        Recorder recorder = new Recorder();

        int fired = recorder.fanOut(credit(null, "Guide_Wilds", "Adventurers_Guide"), null, Set.of());

        assertEquals(1, fired);
        assertEquals(List.of("fire:Guide_Wilds"), recorder.log,
                "an alias cannot take a window for nobody, so it never fires unclaimed");
    }

    @Test
    void theKindIsTheShippedTalkKind() throws Exception {
        String path = "/Server/ZiggfreedCommon/ObjectiveKinds/Talk_To_Npc.json";
        String json;
        try (InputStream in = ZigTalkProducerTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "zc-progression ships " + path);
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        ObjectiveKindAsset kind = ObjectiveKindAsset.CODEC.decodeJsonAsset(RawJsonReader.fromJsonString(json),
                new AssetExtraInfo<>(new AssetExtraInfo.Data(ObjectiveKindAsset.class, "Talk_To_Npc", null)));
        assertTrue(ZigTalkProducer.KIND.equalsIgnoreCase(kind.getId()), "the file name is the kind id");
    }
}
