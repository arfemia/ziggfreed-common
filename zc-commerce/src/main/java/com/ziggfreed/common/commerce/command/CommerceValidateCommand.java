package com.ziggfreed.common.commerce.command;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.ziggfreed.common.commerce.fold.CommerceAudit;
import com.ziggfreed.common.validation.Finding;

/**
 * Audit every piece of authored commerce content and say what is wrong with it.
 *
 * <p>The findings go to whoever asked AND to the server log: chat is where somebody notices, the log
 * is where a long list survives being scrolled past. The chat copy stops after the first twenty and
 * says how many were left.
 */
final class CommerceValidateCommand extends AbstractAsyncCommand {

    CommerceValidateCommand() {
        super(CommerceCommandLine.VALIDATE, CommerceAdminMessages.desc(CommerceCommandLine.VALIDATE));
    }

    @Override
    @Nonnull
    protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext ctx) {
        List<Finding> findings = CommerceAudit.auditAll();
        CommerceAdminMessages.findings(ctx, findings);
        CommerceAudit.log(findings);
        return CompletableFuture.completedFuture(null);
    }
}
