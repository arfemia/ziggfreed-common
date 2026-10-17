package com.ziggfreed.common.instance.claims;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.protocol.IntParamValue;
import com.ziggfreed.common.instance.result.RewardChipRenderer;
import com.ziggfreed.common.instance.reward.InstanceReward;
import com.ziggfreed.common.instance.reward.InstanceRewardGranter;
import com.ziggfreed.common.loot.claim.Claim;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.ui.toast.ToastLine;

/**
 * A press's toast names what really landed (R556): a row its kind paid reads the kind's receipt (a reputation's
 * gain after the worn gear and the Cap), never the row's own amount, which is what it showed while the receipt was
 * thrown away; a receipt listing nothing adds no row; a row that paid by itself reads at its own quantity. No
 * server: nothing here names the test kind, so each row reads as the generic line, its count the amount.
 */
class ClaimToastLinesTest {

    @Test
    void aPressToastReadsWhatLandedNotWhatTheRowSaid() {
        String line = "favor add Old_Jack {amount} --player={player}";
        RewardSpec favor = RewardSpec.of("Test_Favor", Map.of("Reputation", "Old_Jack", "Amount", "50"));
        InstanceReward worn = InstanceReward.command(line, 50, null, null, favor);
        InstanceReward capped = InstanceReward.command(line, 25, null, null, favor.with("Amount", "25"));
        InstanceReward byLine = InstanceReward.command(
                "zigcommerce give --player={player} --currency=Test_Sweets --amount={amount}", 10, null, null);
        Claim claim = new Claim("test:claim", "test:graveyard", null, List.of(), null,
                List.of(worn, capped, byLine), 0L);

        List<ToastLine> lines = ClaimsView.landedLines(List.of(new ClaimGrants.Collected(claim, List.of(
                new InstanceRewardGranter.Landed(worn, List.of(favor.with("Amount", "60"))),
                new InstanceRewardGranter.Landed(capped, List.of()),
                new InstanceRewardGranter.Landed(byLine, null)))));

        assertEquals(2, lines.size(), "a gain the Cap cut to nothing adds no row");
        assertEquals(60, count(lines.get(0)), "the kind's receipt, the worn gain in it, not the row's 50");
        assertEquals(10, count(lines.get(1)), "a row paid by its line reads at its own quantity");
    }

    /** The count a generic line binds as its typed {@code {0}}. */
    private static int count(ToastLine line) {
        FormattedMessage message = line.text().getFormattedMessage();
        assertEquals(RewardChipRenderer.GENERIC_KEY, message.messageId);
        return assertInstanceOf(IntParamValue.class, message.params.get("0")).value;
    }
}
