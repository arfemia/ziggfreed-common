package com.ziggfreed.common.ui.toast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.i18n.Msg;

/**
 * A payout toast with no moment behind it carries its own sound; one whose sound is owned elsewhere stays as it
 * was built: a toast silenced on purpose is never given one, and one that already names a sound keeps it.
 */
class ToastSoundsTest {

    @Test
    void aGoldToastWithNoSoundTakesTheDefault() {
        ToastSpec gold = ToastSpec.of(ToastKind.REWARD, Msg.raw("Bought it."));
        assertNull(gold.effectiveSoundId(), "a gold toast is silent by its kind");

        assertEquals(ToastSounds.PURCHASE, ToastSounds.orDefault(gold, ToastSounds.PURCHASE).effectiveSoundId());
    }

    @Test
    void aSilencedToastStaysSilent() {
        ToastSpec silenced = ToastSpec.of(ToastKind.REWARD, Msg.raw("Bought it.")).silent();
        assertNull(ToastSounds.orDefault(silenced, ToastSounds.PURCHASE).effectiveSoundId(),
                "a caller that silenced its toast owns that choice");
    }

    @Test
    void aToastThatNamesASoundKeepsIt() {
        ToastSpec own = ToastSpec.of(ToastKind.REWARD, Msg.raw("Bought it.")).withSound("Test_Own_Sound");
        assertEquals("Test_Own_Sound", ToastSounds.orDefault(own, ToastSounds.PURCHASE).effectiveSoundId());

        ToastSpec success = ToastSpec.of(ToastKind.SUCCESS, Msg.raw("Done."));
        assertEquals(ToastKind.SUCCESS.soundId(),
                ToastSounds.orDefault(success, ToastSounds.PURCHASE).effectiveSoundId(),
                "a kind with a sound of its own keeps it");
    }
}
