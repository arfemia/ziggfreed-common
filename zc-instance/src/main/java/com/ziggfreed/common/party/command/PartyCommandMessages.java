package com.ziggfreed.common.party.command;

import java.awt.Color;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.ziggfreed.common.party.ZcPartyText;

/**
 * Every line {@code /zigparty} says: a sentence is a key in {@code ziggfreedcommon.party.lang} (under
 * {@code command.}), resolved on the reader's own client, and a player's name is an argument. A command's and an
 * argument's description are keys too, since the engine resolves a description through its own localization.
 */
final class PartyCommandMessages {

    private static final String COMMAND = "command.";

    private static final Color DETAIL = new Color(0xAAAAAA);
    private static final Color BAD = new Color(0xFF5555);

    private PartyCommandMessages() {
    }

    /** What a command or one of its arguments is FOR, resolved by the engine's own help. */
    @Nonnull
    static String desc(@Nonnull String what) {
        return ZcPartyText.PREFIX + COMMAND + "desc." + what;
    }

    /** An answer that is not a refusal. */
    static void said(@Nonnull CommandContext ctx, @Nonnull String key, @Nonnull Object... args) {
        ctx.sendMessage(ZcPartyText.line(COMMAND + key, args).color(DETAIL));
    }

    /** Something was refused, and this is why. */
    static void refused(@Nonnull CommandContext ctx, @Nonnull String key, @Nonnull Object... args) {
        ctx.sendMessage(ZcPartyText.line(COMMAND + key, args).color(BAD));
    }
}
