package com.ziggfreed.common.instance.leaderboard.statboard;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.i18n.LangCatalog;
import com.ziggfreed.common.instance.dungeon.DungeonAsset;
import com.ziggfreed.common.instance.dungeon.DungeonConfig;
import com.ziggfreed.common.instance.dungeon.DungeonRuns;
import com.ziggfreed.common.instance.leaderboard.LeaderboardSplit;
import com.ziggfreed.common.instance.portal.PortalDungeonConfig;

/**
 * Records' Dungeons tab: the {@value #SECTION} section, split ({@link StatSplit}) into its dungeons the way the Bosses
 * tab splits into bosses, through the same builder ({@link RecordedThings}). Its first choice, All dungeons, shows the
 * section's plain stats (every run a player began, cleared and failed); a picked dungeon shows the section's
 * {@code Stat/*} stats over its own lines (its runs begun, cleared and failed, and its fastest clear), and a dungeon
 * run at more than one difficulty has each as a variant.
 *
 * <p>A dungeon is a preset some player holds a line of in All Time: a dungeon stat files each run under
 * {@code dungeon:<difficulty>@<dungeon id>} (the round contract's target and qualifier), so a line's dungeon is its
 * id after the {@code @} and its variant the difficulty before it, empty for normal. A walk-in dungeon is named by its
 * file's {@code NameKey}, a portal dungeon by its {@code PortalDungeons} file's (else the game's own name for the
 * portal), anything else by its id. Dungeons sit in no group: every one shows under All dungeons.
 */
public final class StatBoardDungeons {

    /** The section the Dungeons tab is: its plain stats the totals, its {@code Stat/*} stats one dungeon's columns. */
    public static final String SECTION = "dungeons";

    /** What every dungeon line starts with: the dungeon rounds' mod id and the target's colon. */
    private static final String PREFIX = DungeonRuns.MOD_ID + ":";

    /** What separates a dungeon line's difficulty from its dungeon id. */
    private static final char AT = '@';

    /** The variant a run with no difficulty label (a normal run) files under. */
    private static final String NORMAL = "normal";

    private StatBoardDungeons() {
    }

    /** The Dungeons tab's split, registered once at setup for {@value #SECTION}; its columns are the section's. */
    @Nonnull
    public static StatSplit split() {
        Message all = StatBoardMessages.line("dungeons.all");
        return new StatSplit(List.of(), all, all, () -> RecordedThings.groups(SECTION, Dungeons.READING),
                LeaderboardSplit.EVERYONE).withVariantAllLabel(StatBoardMessages.line("dungeons.difficulties.all"));
    }

    /** How the Dungeons tab reads a recorded dungeon line. */
    private enum Dungeons implements RecordedThings.Reading {
        READING;

        /**
         * {@code dungeon:<difficulty>@<dungeon id>}: the dungeon id the thing, the difficulty (lower-cased, normal for
         * none) its variant.
         */
        @Override
        @Nullable
        public RecordedThings.Place place(@Nonnull String line) {
            if (!line.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
                return null;
            }
            int at = line.indexOf(AT, PREFIX.length());
            if (at < 0 || at == line.length() - 1) {
                return null;
            }
            String difficulty = line.substring(PREFIX.length(), at).trim().toLowerCase(Locale.ROOT);
            return new RecordedThings.Place(line.substring(at + 1).trim(), difficulty.isEmpty() ? NORMAL : difficulty);
        }

        /**
         * A portal dungeon by its file's (or the game's) name, a walk-in dungeon by its file's {@code NameKey}, as the
         * client resolves it; else its id.
         */
        @Override
        @Nonnull
        public Message name(@Nonnull String thing) {
            String key = PortalDungeonConfig.getInstance().nameKeyOf(thing);
            if (key == null) {
                DungeonAsset dungeon = DungeonConfig.getInstance().resolve(thing);
                key = dungeon == null ? null : dungeon.nameKey();
            }
            return key == null || key.isBlank() ? Message.raw(thing) : Message.translation(ContentKeys.resolved(key.trim()));
        }

        /** A label the board's words name ({@code difficulty.<label>}, Normal and Hard among them) by them, else as written. */
        @Override
        @Nonnull
        public Message variantName(@Nonnull String variant) {
            String key = StatBoardMessages.PREFIX + "difficulty." + variant;
            return NORMAL.equals(variant) || LangCatalog.has(key) ? Message.translation(key) : Message.raw(variant);
        }

        /** Normal first, then the labels A to Z. */
        @Override
        @Nonnull
        public Comparator<String> variantOrder() {
            return Comparator.comparing((String variant) -> !NORMAL.equals(variant))
                    .thenComparing(Comparator.naturalOrder());
        }

        /** A dungeon run at one difficulty alone shows no row of them. */
        @Override
        public int fewestVariants() {
            return 2;
        }
    }
}
