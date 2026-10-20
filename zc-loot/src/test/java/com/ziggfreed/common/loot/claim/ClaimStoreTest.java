package com.ziggfreed.common.loot.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ziggfreed.common.instance.reward.DeferredRewards;
import com.ziggfreed.common.instance.reward.InstanceReward;
import com.ziggfreed.common.instance.reward.InstanceRewardGranter;
import com.ziggfreed.common.loot.LootGrants;
import com.ziggfreed.common.loot.diminish.DiminishLedger;
import com.ziggfreed.common.loot.diminish.DiminishSpec;
import com.ziggfreed.common.loot.reward.LootRewardKinds;
import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.subject.Subject;

/**
 * The Claims file speaks a versioned shape and keeps a Claim whole across a restart: its id, source,
 * title and arguments, difficulty, every reward and when it was written. A diminished Claim pays what it
 * shows: a line the payout fills from the quantity is scaled, and one it cannot fill is kept whole or dropped.
 *
 * <p>Tagged {@code engine-items}: the write goes through the engine's own atomic writer
 * ({@code FileUtil.writeStringAtomic}), which on Update 7 logs at class init and so loads only under the
 * engine's log manager ({@code engineItemTest}); so does the announcement a written Claim raises
 * ({@code HytaleServer}).
 */
class ClaimStoreTest {

    private static final UUID OWED = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    @Tag("engine-items")
    @Test
    void aClaimSurvivesARestartWhole(@TempDir Path dir) throws IOException {
        Claim claim = new Claim("test:boss:run-1:daily:" + OWED, "test:boss", "test.claims.title",
                List.of("Brood Queen"), "Normal",
                List.of(InstanceReward.item("Ingredient_Bar_Iron", 3, null),
                        InstanceReward.command("give {player} Ingredient_Bar_Copper", 2, "test.chip", "Icon_Item")),
                1_760_000_000_000L);
        ClaimStore store = new ClaimStore("claims", new DiminishLedger("claims-diminish"));
        store.init(dir);
        assertTrue(store.award(OWED, claim, null, "test:boss", 1_760_000_000_000L));

        JsonObject root = JsonParser.parseString(Files.readString(dir.resolve("claims.json"))).getAsJsonObject();
        assertEquals(1, root.get("version").getAsInt(), "every write carries the version");

        ClaimStore restarted = new ClaimStore("claims", new DiminishLedger("claims-diminish"));
        restarted.init(dir);
        assertEquals(List.of(claim), restarted.list(OWED), "the Claim reads back exactly as it was written");
    }

    /**
     * A fight's Claim keeps the fight that paid it across a restart, and a row written before the leaf existed
     * (no {@code fight} key) reads whole as a Claim no fight paid, under the same version.
     */
    @Tag("engine-items")
    @Test
    void aClaimKeepsItsFightAndARowWithoutOneReadsAsNoFight(@TempDir Path dir) throws IOException {
        String run = "4b1d0c6e-0000-0000-0000-00000000f1a7";
        Claim claim = new Claim("test:boss:" + run + ":OnDefeat:" + OWED, "test:boss", null, List.of(), null,
                List.of(InstanceReward.item("Ingredient_Bar_Iron", 2, null)), 1_760_000_000_000L, run);
        ClaimStore store = new ClaimStore("claims", new DiminishLedger("claims-diminish"));
        store.init(dir);
        assertTrue(store.award(OWED, claim, null, "test:boss", 1_760_000_000_000L));

        ClaimStore restarted = new ClaimStore("claims", new DiminishLedger("claims-diminish"));
        restarted.init(dir);
        Claim read = restarted.list(OWED).get(0);
        assertEquals(claim, read, "the Claim reads back with its fight");
        assertTrue(read.fromFight(run.toUpperCase(Locale.ROOT)), "a fight matches without regard to case");

        Path older = dir.resolve("older");
        Files.createDirectories(older);
        Files.writeString(older.resolve("claims.json"), "{\"version\": 1, \"players\": {\"" + OWED + "\": [{"
                + "\"id\": \"test:old:1\", \"source\": \"test:old\", \"titleArgs\": [], \"rewards\": [],"
                + " \"createdAt\": 1760000000000}]}}");
        ClaimStore old = new ClaimStore("claims", new DiminishLedger("claims-diminish"));
        old.init(older);
        assertEquals(1, old.list(OWED).size(), "a row from before the leaf still loads");
        assertNull(old.list(OWED).get(0).fight(), "and no fight paid it");
        assertFalse(old.list(OWED).get(0).fromFight(run));
    }

    /**
     * A reward keeps the chance of the drop that paid it across a restart, one no roll decided keeps none, and a row
     * written before the chance was kept (no {@code chance} key) reads whole as none, under the same version.
     */
    @Tag("engine-items")
    @Test
    void aRewardKeepsItsDropChanceAndARowWithoutOneReadsAsNone(@TempDir Path dir) throws IOException {
        Claim claim = new Claim("test:boss:run-2:" + OWED, "test:boss", null, List.of(), null,
                List.of(InstanceReward.item("Ingredient_Bar_Iron", 2, null).withDropChance(0.8),
                        InstanceReward.item("Ingredient_Bar_Copper", 1, null)), NOW);
        ClaimStore store = new ClaimStore("claims", new DiminishLedger("claims-diminish"));
        store.init(dir);
        assertTrue(store.award(OWED, claim, null, "test:boss", NOW));
        JsonObject root = JsonParser.parseString(Files.readString(dir.resolve("claims.json"))).getAsJsonObject();
        assertEquals(1, root.get("version").getAsInt(), "the chance moves no version");

        ClaimStore restarted = new ClaimStore("claims", new DiminishLedger("claims-diminish"));
        restarted.init(dir);
        List<InstanceReward> read = restarted.list(OWED).get(0).rewards();
        assertEquals(claim.rewards(), read, "every reward reads back as it was written");
        assertNotNull(read.get(0).dropChance(), "a drop's chance reads back");
        assertEquals(0.8, read.get(0).dropChance(), 1e-9);
        assertNull(read.get(1).dropChance(), "a reward no roll decided keeps none");

        Path older = dir.resolve("older");
        Files.createDirectories(older);
        Files.writeString(older.resolve("claims.json"), "{\"version\": 1, \"players\": {\"" + OWED + "\": [{"
                + "\"id\": \"test:old:2\", \"source\": \"test:old\", \"titleArgs\": [], \"rewards\": [{\"kind\":"
                + " \"ITEM\", \"id\": \"Ingredient_Bar_Iron\", \"quantity\": 3}], \"createdAt\": 1760000000000}]}}");
        ClaimStore old = new ClaimStore("claims", new DiminishLedger("claims-diminish"));
        old.init(older);
        List<InstanceReward> held = old.list(OWED).get(0).rewards();
        assertEquals(List.of(InstanceReward.item("Ingredient_Bar_Iron", 3, null)), held,
                "a row from before the chance still loads whole");
        assertNull(held.get(0).dropChance(), "and reads as no chance, so its card keeps the item's own quality");
    }

    /** Half pay, the step a repeat clear under {@code "Diminish": {"Steps": [1, 0.5]}} earns. */
    private static final DiminishSpec HALF = DiminishSpec.of(DiminishSpec.Window.DAILY, 0.5);

    private static final long NOW = 1_760_000_000_000L;

    @Tag("engine-items")
    @Test
    void aHalfStepPaysTheHalfItShows() {
        RewardKindRegistry kinds = new RewardKindRegistry("test");
        LootRewardKinds.registerInto(kinds);
        LootGrants rolled = LootGrants.of(null, null, null, new LootGrants.Reward[] {LootGrants.Reward.of(
                LootRewardKinds.KIND_ITEM, Map.of("Item", "Ingredient_Bar_Copper", "Count", "10"))});
        List<InstanceReward> deferred = DeferredRewards.from(rolled, kinds, Subject.of(OWED, "{player}"),
                "test:graveyard", null);
        ClaimStore store = new ClaimStore("claims", new DiminishLedger("claims-diminish"), () -> 0.0);
        assertTrue(store.award(OWED, claimOf(deferred), HALF, "test:graveyard", NOW));

        List<InstanceReward> kept = store.list(OWED).get(0).rewards();
        assertEquals(1, kept.size());
        assertEquals(5, kept.get(0).quantity(), "the chip shows half of ten");
        List<String> ran = new ArrayList<>();
        // A command reward never reads the player's store, so the recording sink stands in for the player.
        InstanceRewardGranter.grantAll(kept, null, null, recording(ran));
        assertEquals(List.of("give {player} Ingredient_Bar_Copper --quantity=5"), ran,
                "the line the Claim runs pays the five its chip shows");
    }

    @Tag("engine-items")
    @Test
    void aLineWithNoAmountToFillIsKeptWholeOrNotAtAll() {
        InstanceReward whole = InstanceReward.command("give {player} Ingredient_Bar_Copper --quantity=10", 10,
                "test.chip", null);

        ClaimStore under = new ClaimStore("claims", new DiminishLedger("claims-diminish"), () -> 0.25);
        assertTrue(under.award(OWED, claimOf(List.of(whole)), HALF, "test:graveyard", NOW));
        assertEquals(List.of(whole), under.list(OWED).get(0).rewards(),
                "a draw under the step keeps the line whole: shown at ten and paid at ten");

        ClaimStore over = new ClaimStore("claims", new DiminishLedger("claims-diminish"), () -> 0.75);
        assertFalse(over.award(OWED, claimOf(List.of(whole)), HALF, "test:graveyard", NOW),
                "a draw over the step drops the line, and a Claim left with nothing is not written");
        assertEquals(List.of(), over.list(OWED));
    }

    @Nonnull
    private static Claim claimOf(@Nonnull List<InstanceReward> rewards) {
        return new Claim("test:graveyard:run-1:" + OWED, "test:graveyard", "test.claims.title", List.of(), "",
                rewards, NOW);
    }

    /** A sink recording each command line it is handed, as run. */
    @Nonnull
    private static InstanceRewardGranter.Sink recording(@Nonnull List<String> ran) {
        return new InstanceRewardGranter.Sink() {
            @Override
            public boolean grantCurrency(@Nonnull String currencyId, int amount, @Nonnull Ref<EntityStore> ref,
                    @Nonnull Store<EntityStore> store) {
                return false;
            }

            @Override
            public boolean runCommand(@Nonnull String command, @Nonnull Ref<EntityStore> ref,
                    @Nonnull Store<EntityStore> store) {
                ran.add(command);
                return true;
            }
        };
    }
}
