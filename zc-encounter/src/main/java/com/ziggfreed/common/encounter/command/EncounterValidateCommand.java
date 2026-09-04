package com.ziggfreed.common.encounter.command;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.ziggfreed.common.encounter.validate.EncounterAudit;
import com.ziggfreed.common.validation.Finding;

/**
 * Audit every loaded encounter script, binding row and participation rule and say what is wrong.
 * The findings go to whoever asked AND to the server log; chat stops after the first twenty.
 */
final class EncounterValidateCommand extends AbstractAsyncCommand {

    EncounterValidateCommand() {
        super(EncounterCommandLine.VALIDATE, EncounterAdminMessages.desc(EncounterCommandLine.VALIDATE));
    }

    @Override
    @Nonnull
    protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext ctx) {
        List<Finding> findings = EncounterAudit.auditAll();
        EncounterAdminMessages.findings(ctx, findings);
        EncounterAudit.log(findings);
        return CompletableFuture.completedFuture(null);
    }
}
