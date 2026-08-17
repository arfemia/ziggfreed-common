package com.ziggfreed.common.objectives.command;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.quest.Quest;

/**
 * {@code quest list}: the MERGED quest catalogue - every quest any mod on this server published,
 * whoever authored it - one row each, with the flags an administrator is usually looking for.
 *
 * <p>Naming a {@code --tag} narrows the list to quests carrying it, which is the one classification
 * the runtime object carries; a consumer's own grouping (a category, a chapter) is that consumer's
 * vocabulary and reads through its own listing.
 *
 * <p>Two things about a quest are said as their own fragment rather than as a word substituted into
 * a sentence: that it is switched off, and that it is hidden from open listings. A shipped "false"
 * is a token nobody translated, and those two are exactly what somebody is scanning the list for.
 */
final class QuestListCommand extends AbstractAsyncCommand {

    private final OptionalArg<String> tagArg;

    QuestListCommand() {
        super(ProgressCommandLine.Quest.LIST,
                ProgressAdminMessages.desc(ProgressCommandLine.Quest.GROUP + "."
                        + ProgressCommandLine.Quest.LIST));
        this.tagArg = withOptionalArg("tag", ProgressAdminMessages.desc("arg.tag"), ArgTypes.STRING);
    }

    @Override
    @Nonnull
    protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext ctx) {
        String tag = tagArg.provided(ctx) ? tagArg.get(ctx) : null;
        List<Quest> listed = new ArrayList<>();
        for (Quest quest : ProgressionRuntime.quests().quests()) {
            if (tag == null || quest.hasTag(tag)) {
                listed.add(quest);
            }
        }
        listed.sort(Comparator.comparingInt(Quest::listOrder).thenComparing(Quest::id));
        ProgressAdminMessages.heading(ctx, "quest.list.header", listed.size());
        if (listed.isEmpty()) {
            ProgressAdminMessages.detail(ctx, "quest.list.none");
            return CompletableFuture.completedFuture(null);
        }
        for (Quest quest : listed) {
            row(ctx, quest);
        }
        return CompletableFuture.completedFuture(null);
    }

    private static void row(@Nonnull CommandContext ctx, @Nonnull Quest quest) {
        List<String> flags = new ArrayList<>();
        if (!quest.available()) {
            flags.add("quest.list.off");
        }
        if (quest.visibility().hidden()) {
            flags.add("quest.list.hidden");
        }
        if (quest.repeatable()) {
            flags.add("quest.list.repeatable");
        }
        ProgressAdminMessages.detail(ctx, "quest.list.row", quest.id(),
                ProgressAdminMessages.questName(quest), quest.objectives().size(),
                ProgressAdminMessages.flags(flags));
        String tags = tagsOf(quest);
        if (tags != null) {
            ProgressAdminMessages.detail(ctx, "quest.list.tags", tags);
        }
    }

    /** The tags as one comma-separated raw value, or null when there are none to say. */
    @Nullable
    private static String tagsOf(@Nonnull Quest quest) {
        return quest.tags().isEmpty() ? null : String.join(", ", quest.tags());
    }
}
