package com.ziggfreed.common.encounter.payout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.loot.diminish.DiminishSpec;

/**
 * A {@code ZigClaim} reward file reads its four leaves as written and writes them back the same, and a file
 * that writes nothing pays share-scaled under the boss default, the first clear each day.
 */
class ZigClaimRewardCodecTest {

    private static ZigClaimReward decode(String json) throws IOException {
        return ZigClaimReward.CODEC.decodeJson(RawJsonReader.fromJsonString(json), new ExtraInfo());
    }

    @Test
    void theRewardRoundTripsThroughTheCodec() throws IOException {
        ZigClaimReward read = decode("{ \"Lootable\": \" Boss_Daily \", \"Diminish\": { \"Steps\": [1, 0.5],"
                + " \"Window\": \"Weekly\" }, \"ShareScaled\": false, \"TitleKey\": \"mymod.boss.claim\" }");
        assertEquals("Boss_Daily", read.lootable());
        assertEquals(DiminishSpec.of(DiminishSpec.Window.WEEKLY, 1.0, 0.5), read.diminish());
        assertEquals(read.diminish(), read.diminishOrDefault(), "written returns are the ones counted");
        assertFalse(read.shareScaled());
        assertEquals("mymod.boss.claim", read.titleKey());

        ExtraInfo info = new ExtraInfo();
        ZigClaimReward again = ZigClaimReward.CODEC.decode(ZigClaimReward.CODEC.encode(read, info), info);
        assertEquals(read.lootable(), again.lootable(), "what is written reads back the same");
        assertEquals(read.diminish(), again.diminish());
        assertEquals(read.shareScaled(), again.shareScaled());
        assertEquals(read.titleKey(), again.titleKey());
    }

    @Test
    void nothingWrittenPaysShareScaledOnceADay() throws IOException {
        ZigClaimReward read = decode("{}");
        assertNull(read.lootable());
        assertNull(read.diminish());
        assertEquals(DiminishSpec.of(DiminishSpec.Window.DAILY, 1.0, 0.0), read.diminishOrDefault(),
                "a boss's Claim with no returns written pays the first clear each day and nothing after");
        assertTrue(read.shareScaled());
        assertNull(read.titleKey());
    }
}
