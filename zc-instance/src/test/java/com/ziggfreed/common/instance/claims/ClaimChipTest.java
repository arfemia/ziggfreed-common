package com.ziggfreed.common.instance.claims;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.protocol.ParamValue;
import com.hypixel.hytale.protocol.StringParamValue;
import com.ziggfreed.common.i18n.LangCatalog;
import com.ziggfreed.common.instance.result.RewardChipRenderer;
import com.ziggfreed.common.instance.reward.InstanceReward;
import com.ziggfreed.common.loot.reward.RewardSpec;

/**
 * A Claim's chip never prints the console line that pays it. A command row with no label of its own and no
 * reward kept behind it (a Claim written before rows kept one, a table's raw {@code Commands} leaf) reads as the
 * library's generic line, which this module's English file ships, and a label key nothing ships paints only when
 * the reward wrote it itself. No server: what the chip says is its message.
 */
class ClaimChipTest {

    private static final Path ENGLISH = Path.of("src", "main", "resources", "Server", "Languages", "en-US",
            "ziggfreedcommon.claims.lang");

    @Test
    void aCommandRowNothingNamesNeverShowsItsLine() throws IOException {
        String line = "zigcommerce give --player={player} --currency=Test_Sweets --amount={amount}";

        FormattedMessage label = ClaimsView.chip(InstanceReward.command(line, 10, null, null)).label()
                .getFormattedMessage();

        List<String> texts = new ArrayList<>();
        collect(label, texts);
        assertEquals(List.of(), texts.stream().filter(text -> text.contains("zigcommerce")).toList(),
                "no part of the chip's label is the console line");
        assertEquals(RewardChipRenderer.GENERIC_KEY, label.messageId, "it reads as the library's generic line");
        String key = RewardChipRenderer.GENERIC_KEY.substring(ClaimsText.PREFIX.length());
        assertTrue(Files.readAllLines(ENGLISH, StandardCharsets.UTF_8).stream()
                        .anyMatch(entry -> entry.startsWith(key + " =")),
                "the generic line ships in " + ENGLISH);
    }

    @Test
    void aDisplayKeyNothingShipsFallsThroughUnlessTheRewardWroteIt() {
        String line = "zigcommerce give --player={player} --currency=Test_Sweets --amount={amount}";
        RewardSpec wallet = RewardSpec.of("Currency", Map.of("Currency", "Test_Sweets", "Amount", "10"));
        LangCatalog.overrideForTests(Map.of());
        try {
            FormattedMessage fell = ClaimsView.chip(InstanceReward.command(line, 10, "test.kind.unshipped", null,
                    wallet)).label().getFormattedMessage();
            assertEquals(RewardChipRenderer.GENERIC_KEY, fell.messageId,
                    "a kind file's key nothing ships falls through to the reading below it");

            FormattedMessage kept = ClaimsView.chip(InstanceReward.command(line, 10, "test.own.typo", null,
                    wallet.with("NameKey", "test.own.typo"))).label().getFormattedMessage();
            assertEquals("test.own.typo", kept.messageId, "the reward's own NameKey stays as written");
        } finally {
            LangCatalog.overrideForTests(null);
        }
    }

    /** Every text a message carries: its raw text, its key, its text params, its children and nested params. */
    private static void collect(@Nullable FormattedMessage message, List<String> into) {
        if (message == null) {
            return;
        }
        if (message.rawText != null) {
            into.add(message.rawText);
        }
        if (message.messageId != null) {
            into.add(message.messageId);
        }
        if (message.params != null) {
            for (ParamValue value : message.params.values()) {
                if (value instanceof StringParamValue text && text.value != null) {
                    into.add(text.value);
                }
            }
        }
        if (message.children != null) {
            for (FormattedMessage child : message.children) {
                collect(child, into);
            }
        }
        if (message.messageParams != null) {
            message.messageParams.values().forEach(nested -> collect(nested, into));
        }
    }
}
