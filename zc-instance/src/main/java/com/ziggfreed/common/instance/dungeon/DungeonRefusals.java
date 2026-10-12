package com.ziggfreed.common.instance.dungeon;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.occurrence.Occurrences;
import com.ziggfreed.common.ui.toast.ToastDelivery;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.ui.toast.ToastSpec;
import com.ziggfreed.common.util.SafeLog;

/**
 * What a player is told when a way into a dungeon cannot send them in: the one map from a {@link DungeonEntry}
 * answer to a line in {@code ziggfreedcommon.dungeon.lang} ({@code entry.*}), shared by every way in a player
 * takes (a block's {@code ZigEnterDungeon}, a placement's or a conversation's {@code Dungeon} destination). The
 * admin's {@code /zigdungeon enter} keeps its own, fuller lines.
 *
 * <p>A player never reads a pack author's mistake: a dungeon no loaded file defines, a missing or absent prefab,
 * an audit ERROR and a copy that failed to open all read "cannot be entered right now", and the log says why (the
 * entry and the audit log their own; an unknown id is logged here, once per id). A dungeon that is closed says
 * whether it is switched off or waiting on its season, naming the season when its calendar event has a name.
 */
public final class DungeonRefusals {

    /** The key family the lines resolve under: the shipped file's name, minus {@code .lang}. */
    static final String PREFIX = "ziggfreedcommon.dungeon.";

    private static final String LOG = "[dungeon] ";

    /** Dungeon ids a way in named that no loaded file defines, already logged. */
    private static final Set<String> WARNED_UNKNOWN = ConcurrentHashMap.newKeySet();

    private DungeonRefusals() {
    }

    /**
     * Tell {@code player} why {@code outcome} did not send them in, where they are looking (drawn into the page
     * they have open, else the corner feed), once what raised it has settled. Nothing when they were sent.
     * {@code dungeonId} is the id the way in named, for the log when no dungeon answers to it.
     */
    public static void tell(@Nonnull PlayerRef player, @Nullable String dungeonId,
            @Nonnull DungeonEntry.Outcome outcome) {
        Message line = line(dungeonId, outcome);
        if (line == null) {
            return;
        }
        ToastDelivery.deliverWhenSettled(player, ToastSpec.of(ToastKind.ERROR, line), null);
    }

    /** The line for {@code outcome}, or null when the player was sent. */
    @Nullable
    public static Message line(@Nullable String dungeonId, @Nonnull DungeonEntry.Outcome outcome) {
        DungeonAsset dungeon = outcome.dungeon();
        return switch (outcome.result()) {
            case SENT -> null;
            case UNKNOWN -> unknown(dungeonId);
            case CLOSED -> dungeon == null ? key("entry.unavailable.unnamed") : closed(dungeon);
            case ON_THE_WAY -> key("entry.on_the_way");
            case INSIDE -> key("entry.inside");
            case FULL -> named("entry.full", dungeon);
            case COPY_GONE -> named("entry.copy_gone", dungeon);
            case NO_PREFAB, MISSING_PREFAB, AUDIT_ERROR, FAILED -> named("entry.unavailable", dungeon);
        };
    }

    /** A dungeon's name as its players read it: its {@code Text.NameKey}, or its id when it names none. */
    @Nonnull
    public static Message dungeonName(@Nonnull DungeonAsset dungeon) {
        String key = dungeon.nameKey();
        return key == null ? Msg.raw(dungeon.getId()) : ContentKeys.tr(key);
    }

    /** Switched off, or waiting on its season (named when its calendar event has a name). */
    @Nonnull
    private static Message closed(@Nonnull DungeonAsset dungeon) {
        Message name = dungeonName(dungeon);
        if (!dungeon.isEnabled()) {
            return key("entry.off", name);
        }
        String season = dungeon.getSeason();
        String seasonKey = season == null ? null : Occurrences.source().titleKey(season);
        return seasonKey == null
                ? key("entry.out_of_season.unnamed", name)
                : key("entry.out_of_season", name, ContentKeys.tr(seasonKey));
    }

    /** No dungeon of that id is loaded: the player hears it cannot be entered, the log names the id once. */
    @Nonnull
    private static Message unknown(@Nullable String dungeonId) {
        String id = dungeonId == null ? "" : dungeonId.trim();
        if (WARNED_UNKNOWN.add(id.toLowerCase(Locale.ROOT))) {
            SafeLog.warn(LOG + "a way in names the dungeon '" + id + "', which no loaded file defines (or a mod"
                    + " gate dropped it), so it tells every player the dungeon cannot be entered right now");
        }
        return key("entry.unavailable.unnamed");
    }

    @Nonnull
    private static Message named(@Nonnull String key, @Nullable DungeonAsset dungeon) {
        return dungeon == null ? key("entry.unavailable.unnamed") : key(key, dungeonName(dungeon));
    }

    @Nonnull
    private static Message key(@Nonnull String key, @Nonnull Object... args) {
        return Msg.key(PREFIX + key, args);
    }
}
