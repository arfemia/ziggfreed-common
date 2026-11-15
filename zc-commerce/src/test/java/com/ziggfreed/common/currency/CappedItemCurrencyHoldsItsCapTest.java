package com.ziggfreed.common.currency;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.commerce.InMemoryCommerceStore;
import com.ziggfreed.common.subject.Subject;

/**
 * A capped item currency holds its cap when its bag is full. Its balance is the bag's count, so a part of a credit
 * dropped at the player's feet is invisible to the next credit's cap check: a run of credits into a full bag each
 * passed it, and the piles, picked up, carried the wallet past its cap. A capped one lands in the bag alone and the
 * rest is cut; an uncapped one still drops the rest at the feet.
 */
class CappedItemCurrencyHoldsItsCapTest {

    private static final Subject SUBJECT = Subject.of(UUID.randomUUID(), "Tester");
    private static final String ITEM = "Ingredient_Life_Essence";

    private BagAndFeet wallet;
    private CurrencyEngine engine;

    @BeforeEach
    void setUp() {
        wallet = new BagAndFeet();
        engine = CurrencyEngine.builder()
                .catalog(CurrencyCatalog.of(List.of(
                        CurrencyDef.builder("Capped_Essence").backingItem(ITEM).cap(100L).build(),
                        CurrencyDef.builder("Life_Essence").backingItem(ITEM).build())))
                .items(wallet)
                .store(new InMemoryCommerceStore())
                .warn(message -> { })
                .build();
    }

    @Test
    @DisplayName("credits into a full bag never carry a capped item currency past its cap")
    void aCappedItemCurrencyNeverDropsPastItsCap() {
        wallet.bag = 80L;
        wallet.room = 0L;

        assertEquals(0L, engine.creditLanded(SUBJECT, "Capped_Essence", 50L));
        assertEquals(0L, engine.creditLanded(SUBJECT, "Capped_Essence", 50L));
        assertEquals(80L, engine.set(SUBJECT, "Capped_Essence", 100L));
        assertEquals(0L, wallet.feet, "a capped item currency drops nothing at the feet, where its cap cannot see it");

        wallet.room = 10L;
        assertEquals(10L, engine.creditLanded(SUBJECT, "Capped_Essence", 50L), "only what the bag takes lands");
        wallet.room = 1_000L;
        assertEquals(10L, engine.creditLanded(SUBJECT, "Capped_Essence", 50L), "and never past the cap");
        assertEquals(0L, engine.creditLanded(SUBJECT, "Capped_Essence", 50L));

        wallet.pickUpTheFeet();
        assertEquals(100L, engine.balance(SUBJECT, "Capped_Essence"), "the cap held exactly");
    }

    @Test
    @DisplayName("an uncapped item currency still drops what a full bag cannot take at the player's feet")
    void anUncappedItemCurrencyStillDropsAtTheFeet() {
        wallet.bag = 80L;
        wallet.room = 0L;

        assertEquals(50L, engine.creditLanded(SUBJECT, "Life_Essence", 50L));
        assertEquals(50L, wallet.feet);

        wallet.pickUpTheFeet();
        assertEquals(130L, engine.balance(SUBJECT, "Life_Essence"));
    }

    /**
     * One item's bag with {@code room} left in it, and the ground at the player's feet: a give lands what the bag
     * takes and drops the rest at the feet, counting both, as the real wallet's pickup does; a bag-only give lands
     * what the bag takes and hands over nothing else.
     */
    private static final class BagAndFeet implements ItemWallet {

        long bag;
        long room;
        long feet;

        /** The player makes room and picks up everything at their feet. */
        void pickUpTheFeet() {
            bag += feet;
            feet = 0L;
        }

        @Override
        public long count(@Nonnull Subject subject, @Nonnull String itemId) {
            return bag;
        }

        @Override
        public boolean take(@Nonnull Subject subject, @Nonnull String itemId, long amount) {
            if (bag < amount) {
                return false;
            }
            bag -= amount;
            room += amount;
            return true;
        }

        @Override
        public long give(@Nonnull Subject subject, @Nonnull String itemId, long amount) {
            long into = giveIntoBag(subject, itemId, amount);
            feet += amount - into;
            return amount;
        }

        @Override
        public long giveIntoBag(@Nonnull Subject subject, @Nonnull String itemId, long amount) {
            long into = Math.min(amount, room);
            bag += into;
            room -= into;
            return into;
        }
    }
}
