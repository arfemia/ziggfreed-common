package com.ziggfreed.common.entity.flair;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The unlocked-flair set's save-format discipline. The set persists as one {@code '|'}-joined
 * string with no escaping, so an id carrying {@code '|'} or {@code ':'} would corrupt every entry
 * packed after it; such an id is refused at grant time and again at the write path - the same rule
 * custom skill ids live under.
 */
class ZigFlairComponentTest {

    @Test
    void aGrantAndALookupMeetWhateverTheCasing() {
        ZigFlairComponent component = new ZigFlairComponent();

        assertTrue(component.unlock("Golden_Saw"));
        assertTrue(component.hasFlair("golden_saw"));
        assertTrue(component.hasFlair("GOLDEN_SAW"));
        assertFalse(component.unlock("golden_saw"), "a second grant of the same flair adds nothing");
    }

    @Test
    void anIdCarryingAReservedDelimiterIsRefusedAtGrant() {
        ZigFlairComponent component = new ZigFlairComponent();

        assertFalse(component.unlock("bad|flair"), "'|' is the join character of the save format");
        assertFalse(component.unlock("ns:flair"), "':' is reserved with it");
        assertTrue(component.unlockedFlairs.isEmpty(), "a refused grant leaves the set untouched");
    }

    @Test
    void theWritePathDropsAReservedIdLoudlyInsteadOfCorruptingItsNeighbours() {
        // A grant is already refused, so only a direct write to the public set can smuggle one in.
        Set<String> unlocked = new LinkedHashSet<>();
        unlocked.add("golden_saw");
        unlocked.add("bad|flair");
        unlocked.add("silver_axe");

        String packed = ZigFlairComponent.serializeStringSet(unlocked);

        assertEquals(Set.of("golden_saw", "silver_axe"),
                ZigFlairComponent.deserializeStringSet(packed),
                "the reserved id is dropped; every other entry survives the round trip intact");
    }

    @Test
    void theRoundTripHoldsForOrdinaryIds() {
        ZigFlairComponent component = new ZigFlairComponent();
        component.unlock("golden_saw");
        component.unlock("silver_axe");

        String packed = ZigFlairComponent.serializeStringSet(component.unlockedFlairs);

        assertEquals(component.unlockedFlairs, ZigFlairComponent.deserializeStringSet(packed));
    }
}
