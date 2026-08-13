package com.ziggfreed.common.loot.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.instance.reward.InstanceReward;
import com.ziggfreed.common.instance.reward.LootEntry;
import com.ziggfreed.common.registry.RegistryLedger;

/**
 * One registry now holds both halves of a reward kind: what pays it out, and how a terse authoring
 * format writes it. These cases prove the two halves stay keyed together and that the compact
 * grammars read the SAME table, so a token cannot be writable in one format and unknown in the other.
 */
class RewardKindFoldTest {

    @AfterEach
    void reset() {
        RewardKinds.clear();
    }

    @Nested
    class TheTable {

        @Test
        void theTwoFacetsAreIndependentAndBothKeyedByTheSameId() {
            RewardKindRegistry kinds = new RewardKindRegistry("test");
            kinds.register("mana", "mymod", (spec, subject) -> { });

            assertTrue(kinds.isRegistered("mana"));
            assertNull(kinds.authoring("mana"), "a kind needs no authoring adapter to be payable");

            kinds.registerAuthoring("mana", arg -> RewardSpec.of("mana", "id", arg));
            assertNotNull(kinds.authoring("MANA"), "tokens are matched without regard to case");
        }

        @Test
        void anAuthoringAdapterCanExpandIntoADifferentKindEntirely() {
            RewardKindRegistry kinds = new RewardKindRegistry("test");
            kinds.registerAuthoring("xp", arg -> RewardSpec.of("command",
                    Map.of("id", "/mmoxp give {player} " + arg + " {amount}", "icon", "XpToken")));

            RewardSpec expanded = kinds.expand("xp", "MINING");

            assertNotNull(expanded);
            assertEquals("command", expanded.kind());
            assertEquals("/mmoxp give {player} MINING {amount}", expanded.param("id"));
            assertEquals("XpToken", expanded.param("icon"));
        }

        @Test
        void anUnregisteredTokenExpandsToNothing() {
            assertNull(new RewardKindRegistry("test").expand("nobodyregisteredthis", "arg"));
        }

        @Test
        void anAdapterThatThrowsCostsOnlyItsOwnLine() {
            RewardKindRegistry kinds = new RewardKindRegistry("test");
            kinds.registerAuthoring("bad", arg -> {
                throw new IllegalStateException("adapter blew up");
            });
            assertNull(kinds.expand("bad", "arg"));
        }

        @Test
        void registeringNullDropsTheAdapter() {
            RewardKindRegistry kinds = new RewardKindRegistry("test");
            kinds.registerAuthoring("xp", arg -> RewardSpec.of("command", "id", arg));
            kinds.registerAuthoring("xp", null);
            assertNull(kinds.authoring("xp"));
        }

        @Test
        void clearingDropsBothFacets() {
            RewardKindRegistry kinds = new RewardKindRegistry("test");
            kinds.register("mana", "mymod", (spec, subject) -> { });
            kinds.registerAuthoring("mana", arg -> RewardSpec.of("mana", "id", arg));
            kinds.clear();
            assertFalse(kinds.isRegistered("mana"));
            assertTrue(kinds.authoringTokens().isEmpty());
        }
    }

    @Nested
    class TheCompactGrammars {

        @Test
        void bothGrammarsReadTheSameSharedTable() {
            RewardKinds.shared().registerAuthoring("xp", arg -> RewardSpec.of("command",
                    Map.of("id", "/mmoxp give {player} " + arg + " {amount}", "icon", "XpToken_" + arg)));

            InstanceReward flat = InstanceReward.parse("xp MINING 500 mymod.reward.xp");
            LootEntry pooled = LootEntry.parse("w4 s2000 any xp MINING 500 mymod.reward.xp");

            assertNotNull(flat);
            assertNotNull(pooled);
            assertEquals(InstanceReward.Kind.COMMAND, flat.kind());
            assertEquals(flat.kind(), pooled.kind());
            assertEquals(flat.id(), pooled.id());
            assertEquals("XpToken_MINING", flat.iconItemId());
            assertEquals(flat.iconItemId(), pooled.iconItemId());
            assertEquals(500, flat.quantity());
            assertEquals(4, pooled.weight());
            assertEquals(2000, pooled.minScore());
        }

        @Test
        void aSpecForAnAbsentModDoesNotParseAtAll() {
            assertNull(InstanceReward.parse("xp MINING 500"),
                    "no phantom reward when the mod that would grant it is not installed");
            assertNull(LootEntry.parse("w4 xp MINING 500"));
        }

        @Test
        void theBuiltInTokensNeedNoRegistrationAtAll() {
            InstanceReward item = InstanceReward.parse("item Coin_Gold 3");
            InstanceReward currency = InstanceReward.parse("currency token 25 mymod.currency.token");

            assertNotNull(item);
            assertEquals(InstanceReward.Kind.ITEM, item.kind());
            assertEquals(3, item.quantity());
            assertNotNull(currency);
            assertEquals(InstanceReward.Kind.CURRENCY, currency.kind());
            assertEquals("mymod.currency.token", currency.displayKey());
        }

        @Test
        void anAdapterExpandingToSomethingUnusableIsRefusedRatherThanGuessed() {
            RewardKinds.shared().registerAuthoring("weird", arg -> RewardSpec.of("not_a_kind", "id", arg));
            assertNull(InstanceReward.parse("weird thing 1"));

            RewardKinds.shared().registerAuthoring("idless", arg -> RewardSpec.of("command"));
            assertNull(InstanceReward.parse("idless thing 1"));
        }

        @Test
        void quantityRangesStillWorkOnARegisteredToken() {
            RewardKinds.shared().registerAuthoring("xp",
                    arg -> RewardSpec.of("command", Map.of("id", "/xp " + arg)));
            LootEntry ranged = LootEntry.parse("xp MINING 100-500");
            assertNotNull(ranged);
            assertEquals(100, ranged.qtyMin());
            assertEquals(500, ranged.qtyMax());
        }
    }

    @Nested
    class TheFrameworkKinds {

        @Test
        void everyFrameworkKindRegistersUnderTheFrameworkOwner() {
            RewardKindRegistry kinds = new RewardKindRegistry("test");
            LootRewardKinds.registerInto(kinds);

            assertTrue(kinds.isRegistered(LootRewardKinds.KIND_ITEM));
            assertTrue(kinds.isRegistered(LootRewardKinds.KIND_LOOTABLE));
            assertTrue(kinds.isRegistered(LootRewardKinds.KIND_STAMPED_ITEM));
            assertTrue(kinds.isRegistered(LootRewardKinds.KIND_COMMAND));
            assertEquals(LootRewardKinds.OWNER,
                    kinds.info().get(RegistryLedger.normalize(LootRewardKinds.KIND_ITEM)).owner());
        }

        /**
         * The framework's kind ids are native-asset style and UNPREFIXED, which is what a consumer's
         * prefixed ids (Mmo_Xp, Mmo_Currency) are named against. Pinned as literals rather than read
         * off the constants, so renaming one is a decision taken here and not a silent content break.
         */
        @Test
        void theFrameworkKindsCarryTheCanonicalUnprefixedIds() {
            assertEquals("Item", LootRewardKinds.KIND_ITEM);
            assertEquals("Lootable", LootRewardKinds.KIND_LOOTABLE);
            assertEquals("Stamped_Item", LootRewardKinds.KIND_STAMPED_ITEM);
        }

        @Test
        void aRewardStillNamesAKindHoweverItSpellsIt() {
            RewardKindRegistry kinds = new RewardKindRegistry("test");
            LootRewardKinds.registerInto(kinds);

            assertNotNull(kinds.handler("item"), "an older lower-case spelling still pays out");
            assertNotNull(kinds.handler("STAMPED_ITEM"));
        }

        @Test
        void everyDeclaredKindDocumentsItsParameterKeys() {
            Map<String, List<String>> keys = LootRewardKinds.parameterKeys();
            assertEquals(Set.of(LootRewardKinds.KIND_ITEM, LootRewardKinds.KIND_LOOTABLE,
                            LootRewardKinds.KIND_STAMPED_ITEM, LootRewardKinds.KIND_COMMAND),
                    keys.keySet(), "every framework kind says what it reads");
            keys.values().forEach(list -> assertFalse(list.isEmpty()));
        }
    }
}
