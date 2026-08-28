package com.ziggfreed.common.commerce.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.protocol.LongParamValue;
import com.hypixel.hytale.server.core.Message;

import com.ziggfreed.common.i18n.Msg;

/**
 * The amount-and-name compositions, pinned at the wire shape: the amount binds as a TYPED numeric
 * param - never a pre-formatted String - so a {@code {0, number}} blank groups its digits in each
 * player's own locale, and the wallet's name nests as a client-translated {@link Message}.
 *
 * <p>The typed-param assertion is the localization invariant itself: a server-grouped
 * "1,234" wrapped as raw text once shipped on reward rows, freezing one grouping into every
 * locale. The price and reward forms are pinned as SEPARATE keys because the two readings must
 * never share wording - a reward row carries the plus, a price never does.
 */
class CommerceChipsTest {

    @Test
    void priceAmountBindsATypedNumberAndANestedName() {
        Message line = CommerceChips.priceAmount(1234L, Msg.raw("Bounty Tokens"));

        FormattedMessage fm = line.getFormattedMessage();
        assertEquals("ziggfreedcommon.commerce.price.amount_and_name", fm.messageId,
                "the PRICE composition key, whose value carries no sign");
        assertNotNull(fm.params, "the amount must bind as a typed numeric param");
        assertEquals(1234L, ((LongParamValue) fm.params.get("0")).value,
                "typed, so the client's own locale decides the digit grouping - never a "
                        + "server-pre-formatted String");
        assertNotNull(fm.messageParams, "the wallet name must nest as a Message");
        assertEquals("Bounty Tokens", fm.messageParams.get("1").rawText);
    }

    @Test
    void rewardAmountIsItsOwnKeyBecauseAGainLineCarriesThePlus() {
        Message line = CommerceChips.rewardAmount(50L, Msg.raw("Bounty Tokens"));

        FormattedMessage fm = line.getFormattedMessage();
        assertEquals("ziggfreedcommon.commerce.reward.amount_and_name", fm.messageId,
                "the REWARD-row composition key, whose value carries the leading plus");
        assertEquals(50L, ((LongParamValue) fm.params.get("0")).value);
        assertNotNull(fm.messageParams, "the name still nests, exactly as on the price form");
        assertEquals("Bounty Tokens", fm.messageParams.get("1").rawText);
    }
}
