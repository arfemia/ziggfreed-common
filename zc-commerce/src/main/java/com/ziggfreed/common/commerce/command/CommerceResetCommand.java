package com.ziggfreed.common.commerce.command;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.ziggfreed.common.commerce.CommerceStore;
import com.ziggfreed.common.commerce.CommerceStores;
import com.ziggfreed.common.subject.Subject;

/**
 * The two things an admin can put back: a player's purchase counts, and a player's rerolls.
 *
 * <p>Two verbs from one class, for the same reason the wallet verbs are: each names exactly one
 * thing, and naming two would need a mode argument nobody could guess.
 *
 * <p><b>There is deliberately no "rotate this board now".</b> What a rotating pool is showing is a
 * pure function of its id, its cadence and the clock, with no stored schedule anywhere - which is
 * what makes every player see the same shelf and a restart show what was there before. Nothing can
 * be forced without inventing the stored state that design exists to avoid. Clearing a player's
 * rerolls is the real admin move underneath the wish: their shelf goes back to the shared draw and
 * their allowance for the period comes back.
 */
final class CommerceResetCommand extends TargetPlayerSubCommand {

    /** What this instance clears. */
    enum Scope {
        LIMITS(CommerceCommandLine.RESET_LIMITS),
        REROLLS(CommerceCommandLine.RESET_REROLLS);

        private final String commandName;

        Scope(@Nonnull String commandName) {
            this.commandName = commandName;
        }

        @Nonnull
        String commandName() {
            return commandName;
        }
    }

    private final Scope scope;

    CommerceResetCommand(@Nonnull Scope scope) {
        super(scope.commandName());
        this.scope = scope;
    }

    @Override
    protected void execute(@Nonnull CommandContext ctx, @Nonnull Subject subject) {
        CommerceStore store = CommerceStores.get();
        if (scope == Scope.LIMITS) {
            if (!store.recordsPurchases()) {
                CommerceAdminMessages.refused(ctx, "show.store.no_purchases");
                return;
            }
            store.clearPurchases(subject);
            CommerceAdminMessages.done(ctx, "reset.limits", subject.name());
            return;
        }
        if (!store.recordsRerolls()) {
            CommerceAdminMessages.refused(ctx, "show.store.no_rerolls");
            return;
        }
        store.clearRerolls(subject);
        CommerceAdminMessages.done(ctx, "reset.rerolls", subject.name());
    }
}
