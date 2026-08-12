package com.ziggfreed.common.instance.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.loot.reward.RewardKinds;
import com.ziggfreed.common.loot.reward.RewardSpec;

/**
 * The compact reward-spec grammar for {@link InstanceReward#parse} (the {@code Rewards: String[]} flavour):
 * the built-in item/currency tokens, a consumer-registered token (an authoring adapter on the shared
 * reward vocabulary) expanding into its id + carrying an icon, and the command overload's display
 * quantity.
 */
class InstanceRewardParseTest {

    @AfterEach
    void reset() {
        RewardKinds.clear();
    }

    @Test
    void parsesBuiltinKinds() {
        assertEquals(InstanceReward.Kind.ITEM, InstanceReward.parse("item Foo 3").kind());
        assertEquals(InstanceReward.Kind.CURRENCY, InstanceReward.parse("currency token 5").kind());
        assertNull(InstanceReward.parse("xp MINING 500"), "an unregistered token drops");
    }

    @Test
    void registeredTokenRewritesIdCarriesIconKeepsQtyForAmount() {
        RewardKinds.shared().registerAuthoring("xp", skill -> RewardSpec.of("command",
                Map.of("id", "mmoawardxp {player} " + skill + " {amount}",
                        "icon", "Weapon_Sword_Crude")));
        InstanceReward r = InstanceReward.parse("xp SWORDS 750 mmo.reward.xp.swords");
        assertNotNull(r);
        assertEquals(InstanceReward.Kind.COMMAND, r.kind());
        assertEquals("mmoawardxp {player} SWORDS {amount}", r.id());
        assertEquals(750, r.quantity());   // the {amount} the granter substitutes + the chip label number
        assertEquals("Weapon_Sword_Crude", r.iconItemId());
        assertEquals("mmo.reward.xp.swords", r.displayKey());
    }

    @Test
    void commandOverloadCarriesQuantityAndIcon() {
        InstanceReward r = InstanceReward.command("do {amount}", 42, "k", "Icon");
        assertEquals(InstanceReward.Kind.COMMAND, r.kind());
        assertEquals(42, r.quantity());
        assertEquals("Icon", r.iconItemId());
    }
}
