package com.ziggfreed.common.objectives.bonus;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.factor.FactorRegistry;
import com.ziggfreed.common.loot.LootGrants;
import com.ziggfreed.common.loot.LootRef;
import com.ziggfreed.common.loot.Roll;
import com.ziggfreed.common.loot.reward.LootRewardKinds;
import com.ziggfreed.common.loot.trigger.BonusMoment;
import com.ziggfreed.common.loot.trigger.BonusRowAsset;
import com.ziggfreed.common.loot.trigger.BonusRowConfig;
import com.ziggfreed.common.objectives.producer.BlockBreakPayload;
import com.ziggfreed.common.objectives.producer.MobKillPayload;
import com.ziggfreed.common.objectives.producer.PickupPayload;
import com.ziggfreed.common.objectives.producer.ZigBlockBreakProducer;
import com.ziggfreed.common.objectives.producer.ZigMobKillProducer;
import com.ziggfreed.common.objectives.producer.ZigPickupProducer;
import com.ziggfreed.common.objectives.runtime.ProgressionDefaults;
import com.ziggfreed.common.progress.runtime.Moment;
import com.ziggfreed.common.progress.runtime.MomentPayload;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.subject.Subject;

/**
 * The library's bonus rows on the produced moments, driven over hand-built moments with no ECS:
 * what the listener decides BEFORE it touches a world handle, and how it is registered.
 */
class BonusRowReactionsTest {

    /** A payload of a shape no bonus moment reads: a fourth party's own record. */
    private record SomebodyElsesPayload(@Nonnull String note) implements MomentPayload {
    }

    @AfterEach
    void reset() {
        BonusRowConfig.getInstance().mergePackLayer(Map.of());
    }

    private static Moment moment(String kind, String target, @Nullable MomentPayload payload) {
        Subject player = Subject.of(UUID.randomUUID(), "tester");
        return new Moment(kind, target, null, 1L, null, (Store<EntityStore>) null,
                new Ref<EntityStore>((Store<EntityStore>) null), null, player, player, payload);
    }

    private static LootRef saying() {
        return LootRef.of(null, new Roll[] {Roll.of(null, null, null, null,
                LootGrants.of(null, null, new String[] {"say found"}, null), null)});
    }

    @Test
    void theThreeProducedKindsAreTheThreeBonusMoments() {
        assertEquals(BonusMoment.BREAK_BLOCK, BonusRowReactions.momentFor(ZigBlockBreakProducer.KIND));
        assertEquals(BonusMoment.PICKUP_ITEM, BonusRowReactions.momentFor(ZigPickupProducer.KIND));
        assertEquals(BonusMoment.KILL_MOB, BonusRowReactions.momentFor(ZigMobKillProducer.KIND));
        assertNull(BonusRowReactions.momentFor("CRAFT_ITEM"));
    }

    @Test
    void eachMomentReadsItsOwnProducersRecord() {
        assertEquals(BlockBreakPayload.class, BonusRowReactions.payloadOf(BonusMoment.BREAK_BLOCK));
        assertEquals(MobKillPayload.class, BonusRowReactions.payloadOf(BonusMoment.KILL_MOB));
        assertEquals(PickupPayload.class, BonusRowReactions.payloadOf(BonusMoment.PICKUP_ITEM));
    }

    @Test
    void aCoveredMomentWithoutItsProducersRecordIsNoRow() {
        BonusRowConfig.getInstance().mergePackLayer(Map.of(
                "fixture_rock", BonusRowAsset.of(BonusMoment.BREAK_BLOCK, "Rock_*", null, saying(), null)));

        assertNull(BonusRowReactions.rowFor(moment(ZigBlockBreakProducer.KIND, "Rock_Stone", null)));
        assertNull(BonusRowReactions.rowFor(moment(ZigBlockBreakProducer.KIND, "Rock_Stone",
                new SomebodyElsesPayload("hand-fired"))));
        assertNull(BonusRowReactions.rowFor(moment("CRAFT_ITEM", "Rock_Stone", null)));
        assertDoesNotThrow(() -> BonusRowReactions.INSTANCE.react(moment(ZigBlockBreakProducer.KIND, "Rock_Stone", null)),
                "a moment the listener cannot pay is left alone before any world handle is read");
    }

    @Test
    void placeholdersNameThePlayerAndTheMomentsTarget() {
        Subject ash = Subject.of(UUID.randomUUID(), "Ash");

        assertEquals(Map.of("player", "Ash", "block", "Rock_Stone"),
                BonusRowReactions.placeholders(ash, "block", "Rock_Stone"));
    }

    @Test
    void theRuntimesOwnSubjectPaysWhenItBuiltOne() {
        Moment moment = moment(ZigMobKillProducer.KIND, "Skeleton_Burnt_Archer", null);

        assertSame(moment.questSubject(), BonusRowReactions.subjectOf(moment));
    }

    @Test
    void aRowReadsTheLootVocabularyOnceTheRootInstalledIt() {
        FactorRegistry before = LootRewardKinds.installedFactors();
        try {
            LootRewardKinds.factors(null);
            assertNotNull(BonusRowReactions.factors(), "a vocabulary of contributed ids before the root installs one");
            FactorRegistry loot = new FactorRegistry("bonus-row-reactions-test");
            LootRewardKinds.factors(loot);
            assertSame(loot, BonusRowReactions.factors());
        } finally {
            LootRewardKinds.factors(before);
        }
    }

    @Test
    void theBootstrapHangsTheReactionsOnTheRuntime() {
        ProgressionRuntime.resetForTests();
        try {
            BonusRowBootstrap.registerReactions();
            assertTrue(ProgressionRuntime.momentListenerOwners().contains(ProgressionDefaults.OWNER),
                    ProgressionRuntime.momentListenerOwners().toString());
        } finally {
            ProgressionRuntime.resetForTests();
        }
    }

    @Test
    void theBonusCodeNeverRewritesOrRefiresAPickup() throws IOException {
        List<Path> roots = List.of(
                Path.of("src", "main", "java", "com", "ziggfreed", "common", "objectives", "bonus"),
                Path.of("..", "zc-loot", "src", "main", "java", "com", "ziggfreed", "common", "loot", "trigger"));
        for (Path root : roots) {
            assertTrue(Files.isDirectory(root), "missing " + root.toAbsolutePath());
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String source = Files.readString(file, StandardCharsets.UTF_8);
                    assertFalse(source.contains(".setItemStack("), file + " replaces the pickup event's stack");
                    assertFalse(source.contains("new InteractivelyPickupItemEvent("), file + " fires a pickup of its own");
                    assertFalse(source.contains("interactivelyPickupItem("), file + " re-enters the engine's pickup");
                }
            }
        }
    }
}
