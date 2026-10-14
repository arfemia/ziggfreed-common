package com.ziggfreed.common.commerce.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.ui.toast.ToastSounds;
import com.ziggfreed.common.ui.toast.ToastSpec;

/**
 * The storefront's purchase toast through the commerce deps, pinned with no page or server anywhere,
 * because what the storefront says after a purchase is decided here.
 */
class CommercePageDepsTest {

    /**
     * No moment fires for a purchase, so its toast plays the purchase sound itself: the library's line and a
     * consumer's own alike, unless the consumer's names a sound of its own or silenced it on purpose.
     */
    @Test
    void aPurchaseToastPlaysThePurchaseSound() {
        ToastSpec library = ToastSpec.of(ToastKind.REWARD, Msg.raw("Bought it."));
        assertEquals(ToastSounds.PURCHASE, CommercePageDeps.DEFAULTS.resolvePurchaseToast("offer", () -> library)
                .effectiveSoundId(), "the library's line");

        CommercePageDeps mine = CommercePageDeps.builder()
                .purchaseToast(offerId -> ToastSpec.of(ToastKind.REWARD, Msg.raw("Bought, my way")))
                .build();
        ToastSpec consumer = mine.resolvePurchaseToast("offer", () -> library);
        assertEquals("Bought, my way", consumer.message().getRawText(), "the consumer's line wins");
        assertEquals(ToastSounds.PURCHASE, consumer.effectiveSoundId(), "and still plays the purchase sound");

        CommercePageDeps quiet = CommercePageDeps.builder()
                .purchaseToast(offerId -> ToastSpec.of(ToastKind.REWARD, Msg.raw("Shh")).silent())
                .build();
        assertNull(quiet.resolvePurchaseToast("offer", () -> library).effectiveSoundId(),
                "a consumer that silenced its line keeps it silent");

        CommercePageDeps broken = CommercePageDeps.builder()
                .purchaseToast(offerId -> { throw new IllegalStateException("boom"); })
                .build();
        assertSame(library.message(), broken.resolvePurchaseToast("offer", () -> library).message(),
                "a consumer toast that throws costs its own line only");
    }
}
