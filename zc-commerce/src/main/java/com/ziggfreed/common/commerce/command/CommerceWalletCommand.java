package com.ziggfreed.common.commerce.command;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.ziggfreed.common.commerce.fold.CommerceDefaults;
import com.ziggfreed.common.currency.CurrencyEngine;
import com.ziggfreed.common.subject.Subject;

/**
 * The three wallet verbs, which are one implementation: {@code give}, {@code take} and {@code set}.
 *
 * <p>Three registered commands rather than one with a mode argument - each is its own name, its own
 * help line and its own permission node, which is how the engine's own families read - built from
 * one class because the difference between them is a single call. The class stays a private detail:
 * what a server sees is three verbs.
 *
 * <p>Every write goes through the {@link CurrencyEngine}, never the state store, so an item-backed
 * wallet moves real items and a counter-backed one moves a counter without this command ever asking
 * which it is. That dispatch is the engine's whole job and nothing above it may duplicate it.
 */
final class CommerceWalletCommand extends TargetPlayerSubCommand {

    /** What this instance does to a balance. */
    enum Op {
        GIVE(CommerceCommandLine.GIVE),
        TAKE(CommerceCommandLine.TAKE),
        SET(CommerceCommandLine.SET);

        private final String commandName;

        Op(@Nonnull String commandName) {
            this.commandName = commandName;
        }

        @Nonnull
        String commandName() {
            return commandName;
        }
    }

    private final Op op;
    private final OptionalArg<String> currencyArg;
    private final OptionalArg<String> amountArg;

    CommerceWalletCommand(@Nonnull Op op) {
        super(op.commandName());
        this.op = op;
        this.currencyArg = withOptionalArg("currency", CommerceAdminMessages.desc("arg.currency"),
                ArgTypes.STRING);
        this.amountArg = withOptionalArg("amount", CommerceAdminMessages.desc("arg.amount"),
                ArgTypes.STRING);
    }

    @Override
    protected void execute(@Nonnull CommandContext ctx, @Nonnull Subject subject) {
        String currencyId = currencyArg.provided(ctx) ? currencyArg.get(ctx) : null;
        if (currencyId == null || currencyId.isBlank()) {
            CommerceAdminMessages.refused(ctx, "arg.currency.needed");
            return;
        }
        String rawAmount = amountArg.provided(ctx) ? amountArg.get(ctx) : null;
        if (rawAmount == null || rawAmount.isBlank()) {
            CommerceAdminMessages.refused(ctx, "arg.amount.needed");
            return;
        }
        long amount;
        try {
            amount = Long.parseLong(rawAmount.trim());
        } catch (NumberFormatException notANumber) {
            CommerceAdminMessages.refused(ctx, "arg.amount.number", rawAmount);
            return;
        }
        if (amount < 0L) {
            CommerceAdminMessages.refused(ctx, "arg.amount.negative");
            return;
        }
        CurrencyEngine engine = CommerceDefaults.currencyEngine();
        if (engine.definition(currencyId) == null) {
            CommerceAdminMessages.unknownCurrency(ctx, currencyId);
            return;
        }
        apply(ctx, engine, subject, currencyId.trim(), amount);
    }

    private void apply(@Nonnull CommandContext ctx, @Nonnull CurrencyEngine engine,
            @Nonnull Subject subject, @Nonnull String currencyId, long amount) {
        switch (op) {
            case GIVE -> CommerceAdminMessages.done(ctx, "wallet.gave", amount, currencyId,
                    subject.name(), engine.credit(subject, currencyId, amount));
            case TAKE -> take(ctx, engine, subject, currencyId, amount);
            case SET -> CommerceAdminMessages.done(ctx, "wallet.set", subject.name(),
                    engine.set(subject, currencyId, amount), currencyId);
        }
    }

    /**
     * A take is all or nothing, so a short balance is reported as what they actually hold rather
     * than as a partial success.
     */
    private static void take(@Nonnull CommandContext ctx, @Nonnull CurrencyEngine engine,
            @Nonnull Subject subject, @Nonnull String currencyId, long amount) {
        if (!engine.debit(subject, currencyId, amount)) {
            CommerceAdminMessages.refused(ctx, "wallet.short", subject.name(),
                    engine.balance(subject, currencyId), currencyId);
            return;
        }
        CommerceAdminMessages.done(ctx, "wallet.took", amount, currencyId, subject.name(),
                engine.balance(subject, currencyId));
    }
}
