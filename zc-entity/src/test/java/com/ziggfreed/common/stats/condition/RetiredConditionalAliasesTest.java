package com.ziggfreed.common.stats.condition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.URL;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.stats.StatAliases;

/**
 * A stack saved with one of the library's retired held conditional stats reads as the plain stat a grant now binds
 * {@code While} holding, once setup has registered the aliases (R925). An alias shadows any stat of its id, so a
 * retired id ships no stat or conditional file again, and its successor ships one. The ids are spelt out, so one
 * dropping out of the table fails here.
 */
class RetiredConditionalAliasesTest {

    private static final Map<String, String> RETIRED = Map.of(
            "DamageResist_Physical_WhileHeld_Tool", "DamageResist_Physical",
            "DamageResist_Projectile_WhileHeld_Tool", "DamageResist_Projectile",
            "KnockbackResist_WhileHeld_Tool", "KnockbackResist",
            "KnockbackResist_HoldingBlock", "KnockbackResist");

    @AfterEach
    void reset() {
        StatAliases.resetForTests();
    }

    @Test
    void eachRetiredHeldStatReadsAsItsPlainStat_andShipsNoFileOfItsOwn() {
        ConditionalStats.registerRetiredAliases();
        for (Map.Entry<String, String> entry : RETIRED.entrySet()) {
            String retired = entry.getKey();
            String successor = entry.getValue();
            assertEquals(List.of(successor), StatAliases.resolve(retired),
                    retired + " is not aliased onto " + successor);
            assertEquals(1.0, StatAliases.scale(retired), 0.0, retired + " counts in its successor's unit");
            assertNull(resource("/Server/Entity/Stats/" + retired + ".json"), retired + " still ships a stat file");
            assertNull(resource("/Server/ZiggfreedCommon/ConditionalStats/" + retired + ".json"),
                    retired + " still ships a conditional file");
            assertNotNull(resource("/Server/Entity/Stats/" + successor + ".json"), successor + " ships no stat file");
        }
    }

    private static URL resource(String path) {
        return RetiredConditionalAliasesTest.class.getResource(path);
    }
}
