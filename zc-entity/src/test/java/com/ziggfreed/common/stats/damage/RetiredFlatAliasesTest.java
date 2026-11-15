package com.ziggfreed.common.stats.damage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.stats.StatAliases;

/**
 * A stack a server saved with the library's retired flat damage stats, {@code DamageBonusFlat_<Type>}, reads as that
 * type's percent stat at {@link TypedDamageBonuses#RETIRED_FLAT_PERCENT} points a flat point once setup has
 * registered the aliases. The types are spelt out, so one dropping out of the table fails here.
 */
class RetiredFlatAliasesTest {

    private static final List<String> RETIRED_TYPES = List.of("Physical", "Projectile", "Fire", "Ice", "Lightning",
            "Water", "Wind", "Earth", "Poison", "Arcane", "Void", "Life");

    @AfterEach
    void reset() {
        StatAliases.resetForTests();
    }

    @Test
    void everyRetiredFlatTypeReadsAsItsPercentAtTheFlatRate() {
        TypedDamageBonuses.registerRetiredFlatAliases();
        for (String type : RETIRED_TYPES) {
            String retired = "DamageBonusFlat_" + type;
            assertTrue(StatAliases.isRetired(retired), retired + " is not aliased");
            assertEquals(List.of("DamageBonus_" + type), StatAliases.resolve(retired));
            assertEquals(TypedDamageBonuses.RETIRED_FLAT_PERCENT, StatAliases.scale(retired), 1e-9);
        }
        Map<String, Double> saved = new LinkedHashMap<>();
        saved.put("DamageBonusFlat_Fire", 3.0);
        assertEquals(3.0 * TypedDamageBonuses.RETIRED_FLAT_PERCENT,
                StatAliases.normalize(saved).get("DamageBonus_Fire"), 1e-9);
    }
}
