package com.ziggfreed.common.objectives.title.page;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.objectives.title.TitleConfig;
import com.ziggfreed.common.util.SafeLog;

/**
 * What the title picker shows, worked out with nothing but the player's titles, the fold and a source lookup in
 * hand: the earned titles on offer as tiles in picker order, at most one of them shown; every other title on offer as
 * a line, with the achievement that gives it and the player's progress when one they can see does; and which empty
 * state reads, if any. A plan, because a page cannot be stood up in a unit JVM.
 */
final class TitlePickerRows {

    /** One earned title: its tile, shown or not. */
    record Tile(@Nonnull String titleId, boolean shown) {

        Tile {
            Objects.requireNonNull(titleId, "titleId");
        }
    }

    /** One title still to earn, with the achievement that gives it; no source when none the player can see does. */
    record Line(@Nonnull String titleId, @Nullable TitleSources.Source source) {

        Line {
            Objects.requireNonNull(titleId, "titleId");
        }
    }

    /** The whole picker. */
    record Plan(@Nonnull List<Tile> earned, @Nonnull List<Line> notEarned) {

        Plan {
            earned = List.copyOf(earned);
            notEarned = List.copyOf(notEarned);
        }

        /** Nothing earned, something to earn: "No titles yet" over the lines. */
        boolean noneYet() {
            return earned.isEmpty() && !notEarned.isEmpty();
        }

        /** The server offers no title at all: the empty state alone. */
        boolean noneOnServer() {
            return earned.isEmpty() && notEarned.isEmpty();
        }
    }

    private TitlePickerRows() {
    }

    /**
     * The picker for a player holding {@code unlocked} and showing {@code active}: every title {@code titles} offers,
     * in picker order, the earned ones as tiles and the rest as lines, each line's source from {@code sources} (a
     * lookup that throws, or answers null, leaves that line without one).
     */
    @Nonnull
    static Plan plan(@Nonnull Collection<String> unlocked, @Nullable String active, @Nonnull TitleConfig titles,
            @Nonnull Function<String, TitleSources.Source> sources) {
        List<String> earnedIds = titles.listing(unlocked);
        String shown = key(active);
        List<Tile> earned = new ArrayList<>(earnedIds.size());
        for (String id : earnedIds) {
            earned.add(new Tile(id, id.equals(shown)));
        }
        List<Line> notEarned = new ArrayList<>();
        for (String id : titles.listing(titles.ids())) {
            if (!earnedIds.contains(id)) {
                notEarned.add(new Line(id, sourceOf(id, sources)));
            }
        }
        return new Plan(earned, notEarned);
    }

    /** The single-choice rule: {@code tiles} with only {@code shownId} shown, or none when it names no tile. */
    @Nonnull
    static List<Tile> showing(@Nonnull List<Tile> tiles, @Nullable String shownId) {
        String shown = key(shownId);
        List<Tile> out = new ArrayList<>(tiles.size());
        for (Tile tile : tiles) {
            out.add(new Tile(tile.titleId(), tile.titleId().equals(shown)));
        }
        return out;
    }

    @Nullable
    private static TitleSources.Source sourceOf(@Nonnull String titleId,
            @Nonnull Function<String, TitleSources.Source> sources) {
        try {
            return sources.apply(titleId);
        } catch (Throwable t) {
            SafeLog.warn("[title] what gives the title '" + titleId + "' could not be read: " + t.getMessage());
            return null;
        }
    }

    @Nullable
    private static String key(@Nullable String titleId) {
        return titleId == null || titleId.isBlank() ? null : titleId.trim().toLowerCase(Locale.ROOT);
    }
}
