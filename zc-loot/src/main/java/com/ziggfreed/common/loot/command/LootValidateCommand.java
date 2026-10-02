package com.ziggfreed.common.loot.command;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.ziggfreed.common.command.FindingsReply;
import com.ziggfreed.common.loot.LootAudit;
import com.ziggfreed.common.validation.Finding;

/**
 * Audit every loaded loot table and say what is wrong with it. The findings go to whoever asked AND
 * to the server log; chat stops after the first twenty.
 */
final class LootValidateCommand extends AbstractAsyncCommand {

    LootValidateCommand() {
        super(LootCommandLine.VALIDATE, LootAdminMessages.desc(LootCommandLine.VALIDATE));
    }

    @Override
    @Nonnull
    protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext ctx) {
        List<Finding> findings = LootAudit.auditAll();
        FindingsReply.send(ctx, LootAdminMessages.PREFIX, findings);
        LootAudit.log(findings);
        return CompletableFuture.completedFuture(null);
    }
}
