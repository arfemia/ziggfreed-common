package com.ziggfreed.common.loot.stamp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.inventory.ItemStack;

/**
 * Naming a stamped stat is OPTIONAL, and both answers have to be usable.
 *
 * <p>A stamper that says nothing must still ANSWER - null, not an exception - because the callers
 * that report an enhancement ask every stamper and fall back to their own plain report. If the
 * default ever became a throw, or the method were dropped from the interface, a bare-stamper server
 * would lose its whole summary instead of losing the wording; and if a describing stamper's answer
 * stopped coming through, every stat would read as a raw id at players in every locale.
 *
 * <p>No item is built here on purpose: {@code apply}/{@code inspect} need a real {@code ItemStack}
 * (whose class initializer wants an asset store a unit JVM has none of), while naming a stat is pure
 * and is exactly the half worth pinning without a server.
 */
class StamperDescribeTest {

    /** A stamper that implements only what the contract requires. */
    private static class SilentStamper implements Stamper {

        @Override
        @Nonnull
        public StampInspection inspect(@Nonnull ItemStack stack) {
            return StampInspection.empty();
        }

        @Override
        @Nonnull
        public ItemStack apply(@Nonnull ItemStack stack, @Nonnull List<StatRoll> entries) {
            return stack;
        }
    }

    /** A stamper that owns the vocabulary and says so. */
    private static final class NamingStamper extends SilentStamper {

        @Override
        @Nullable
        public Message describe(@Nonnull StatRoll entry) {
            return Message.raw(entry.statId() + " +" + entry.points());
        }
    }

    @Test
    void aStamperThatNamesNothingAnswersNullRatherThanThrowing() {
        assertNull(new SilentStamper().describe(new StatRoll("Swing_Speed", 3)),
                "the default is an answer, and its answer is 'I have no wording for this'");
    }

    @Test
    void aStamperThatOwnsTheVocabularyIsAskedInsteadOfGuessedAt() {
        Message line = new NamingStamper().describe(new StatRoll("Swing_Speed", 3));

        assertNotNull(line);
        assertEquals("Swing_Speed +3", line.getRawText());
    }
}
