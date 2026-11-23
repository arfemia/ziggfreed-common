package com.ziggfreed.common.party.board;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.instance.dungeon.DungeonAsset;
import com.ziggfreed.common.instance.dungeon.DungeonConfig;
import com.ziggfreed.common.instance.dungeon.DungeonRefusals;
import com.ziggfreed.common.instance.dungeon.tier.TierNames;

/**
 * The party board's words: every line a key under {@code ziggfreedcommon.party.board.} in
 * {@code ziggfreedcommon.party.lang}, resolved on the reader's own client, and the board's readings of a notice: what
 * it is for, its notes, its title. A name is the caller's (a page's display name, a chat line's plain one).
 */
public final class PartyBoardText {

    /** The key family every line here resolves under. */
    public static final String PREFIX = "ziggfreedcommon.party.board.";

    private PartyBoardText() {
    }

    /** The line {@code key} with {@code args} bound as {@code {0}}, {@code {1}}, ... */
    @Nonnull
    public static Message line(@Nonnull String key, @Nonnull Object... args) {
        return Msg.key(PREFIX + key, args);
    }

    /**
     * What a notice is for, as its title reads it: a depth's word ({@link TierNames#word}, the one reading of it),
     * today's daily, or anything.
     */
    @Nonnull
    public static Message aim(@Nullable String aim) {
        DungeonAsset dungeon = aim == null ? null : DungeonConfig.getInstance().resolve(aim);
        if (dungeon == null) {
            return line("aim.any");
        }
        return dungeon.seedDaily() ? line("aim.daily") : TierNames.word(dungeon);
    }

    /** A notice's notes as one line ("New players welcome. Quick run."), or null for none. */
    @Nullable
    public static Message notes(@Nonnull List<String> noteIds) {
        List<Message> words = new ArrayList<>();
        for (String id : noteIds) {
            PartyNoteAsset note = PartyNoteConfig.getInstance().offered(id);
            if (note != null && note.textKey() != null) {
                words.add(ContentKeys.tr(note.textKey()));
            }
        }
        return switch (words.size()) {
            case 0 -> null;
            case 1 -> line("notes.one", words.get(0));
            default -> line("notes.two", words.get(0), words.get(1));
        };
    }

    /** A notice's title: "{name}: {what for}, wants {n} more". */
    @Nonnull
    public static Message title(@Nonnull Message name, @Nullable String aim, int wants) {
        return line("notice.title", name, aim(aim), wants);
    }

    /** A dungeon's name by id, or null when no loaded dungeon answers. */
    @Nullable
    public static Message dungeonName(@Nullable String dungeonId) {
        DungeonAsset dungeon = dungeonId == null ? null : DungeonConfig.getInstance().resolve(dungeonId);
        return dungeon == null ? null : DungeonRefusals.dungeonName(dungeon);
    }

    /** Where a board stands (its authored place key), or null for none. */
    @Nullable
    public static Message place(@Nullable String placeKey) {
        return placeKey == null || placeKey.isBlank() ? null : ContentKeys.tr(placeKey.trim());
    }
}
