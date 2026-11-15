package com.ziggfreed.common.proc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.hypixel.hytale.server.core.modules.entity.condition.Condition;
import com.ziggfreed.common.stats.condition.BoundGrants;
import com.ziggfreed.common.stats.condition.ZigHoldingCondition;

/**
 * A counter's {@code PerStack} row decodes its {@code While} (entity conditions from the engine's own
 * {@code Condition.CODEC}) and is then a bound row, handed to {@code BoundGrants} rather than written under the
 * counter's own key; a row without one stays a plain row. Its bound amount follows the stacks, and its id keeps its
 * place among the bound rows. No amount is pinned beyond the file's.
 */
class CounterPerStackWhileTest {

    @BeforeAll
    static void registerTheCondition() {
        // The library registers its conditions at setup; a unit JVM registers the one this file names.
        Condition.CODEC.register(ZigHoldingCondition.TYPE_NAME, ZigHoldingCondition.class, ZigHoldingCondition.codec());
    }

    @Test
    void aPerStackRowWithWhile_decodesBound_andARowWithoutStaysPlain() throws IOException {
        CounterAsset counter = counter("{ \"Size\": { \"Base\": 5 }, \"PerStack\": ["
                + " { \"Stat\": \"Plain_Stat\", \"Amount\": 1 },"
                + " { \"Stat\": \"CritChance\", \"Amount\": 2,"
                + "   \"While\": [ { \"Id\": \"ZigHolding\", \"Items\": [ \"ItemTag:Family=Sword\" ] } ] } ] }");
        List<CounterAsset.PerStack> rows = counter.perStack();
        assertEquals(2, rows.size());
        assertFalse(rows.get(0).bound(), "a row with no While always counts");
        assertEquals(0, rows.get(0).whileConditions().length);
        assertTrue(rows.get(1).bound());
        assertEquals(1, rows.get(1).whileConditions().length);
        assertInstanceOf(ZigHoldingCondition.class, rows.get(1).whileConditions()[0]);

        List<BoundGrants.Bound> three = Counters.bound(counter, 3.0);
        assertEquals(1, three.size(), "only the While row is handed to the bound grants");
        assertEquals("CritChance", three.get(0).statId());
        assertEquals("CritChance:1", three.get(0).id(), "the id is the stat and the row's place among bound rows");
        assertEquals(6.0f, three.get(0).modifier().getAmount(), 1e-6f, "the row's amount times the stacks");
        assertTrue(Counters.bound(counter, 0.0).isEmpty(), "no stacks: nothing bound, so the owner empties");
    }

    private static CounterAsset counter(String json) throws IOException {
        CounterAsset noParent = null;
        return CounterAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), noParent,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(CounterAsset.class, "Your_Counter", null)));
    }
}
