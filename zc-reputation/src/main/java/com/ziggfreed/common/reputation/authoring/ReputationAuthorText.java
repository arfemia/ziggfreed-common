package com.ziggfreed.common.reputation.authoring;

import java.util.List;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.reputation.ReputationText;

/**
 * The reputation editor's words: {@code ziggfreedcommon.reputation.lang}'s {@code author.*} lines. A field label, a
 * hint or a step name is a full key ({@link #key}), which the wizard frame resolves on the client.
 */
public final class ReputationAuthorText {

    /** Every key the editor speaks, without the file's prefix, for the test that holds the English file to it. */
    public static final List<String> SPOKEN = List.of(
            "author.title",
            "author.step.which", "author.step.name", "author.step.earn", "author.step.ranks", "author.step.beyond",
            "author.step.members",
            "author.note.which", "author.field.edit",
            "author.field.name", "author.hint.name", "author.field.description", "author.field.icon",
            "author.hint.icon",
            "author.note.earn", "author.field.kill.1", "author.field.kill.2", "author.field.kill.3",
            "author.field.amount", "author.header.lines", "author.note.lines", "author.field.earn.1",
            "author.field.earn.2", "author.field.earn.3",
            "author.note.ranks", "author.field.rank.1", "author.field.rank.2", "author.field.rank.3",
            "author.field.from",
            "author.note.beyond", "author.field.every", "author.field.item.1", "author.field.item.2",
            "author.field.count", "author.field.loot",
            "author.note.members", "author.field.add", "author.field.remove",
            "author.preview.name", "author.preview.unnamed", "author.preview.new", "author.preview.id",
            "author.preview.members", "author.preview.members.none", "author.preview.kill.gain",
            "author.preview.kill.loss", "author.preview.rank", "author.preview.beyond", "author.preview.kept",
            "author.check.edit", "author.check.name", "author.check.backslash", "author.check.whole",
            "author.check.item", "author.check.kill.group", "author.check.kill.amount", "author.check.kill.unknown",
            "author.check.rank.from", "author.check.rank.name", "author.check.rank.range", "author.check.rank.same",
            "author.check.every", "author.check.count", "author.check.loot", "author.check.role",
            "author.check.role.taken");

    private static final String PREFIX = ReputationText.PREFIX + "author.";

    private ReputationAuthorText() {
    }

    /** One of the editor's lines, {@code {0}, {1}...} bound from {@code args}. */
    @Nonnull
    static Message line(@Nonnull String key, @Nonnull Object... args) {
        return Msg.key(PREFIX + key, args);
    }

    /** The full key of one of the editor's lines, for a field label, a hint or a step name. */
    @Nonnull
    static String key(@Nonnull String key) {
        return PREFIX + key;
    }
}
