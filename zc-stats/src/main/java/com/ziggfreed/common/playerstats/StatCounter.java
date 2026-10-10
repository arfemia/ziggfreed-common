package com.ziggfreed.common.playerstats;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.progress.ZoneRef;

/**
 * What one produced moment writes to one player's stats. Pure: it reads the index and the moment's
 * own tuple and touches nothing else.
 *
 * <ul>
 *   <li>A stat counts a moment once, at its first {@code From} entry that matches (kind, target,
 *       qualifier and zone), however many of its entries would.</li>
 *   <li>The value is the moment's amount, or one for an entry written {@code "Count": "One"}; a value of
 *       zero or less writes nothing.</li>
 *   <li>The breakdown line is the one the stat's {@code Breakdown} names from the moment.</li>
 * </ul>
 */
public final class StatCounter {

    /** One write: move {@code statId} (and its {@code thing} line, when named) by {@code value}, the {@code kind}'s way. */
    public record Write(@Nonnull String statId, @Nonnull StatKind kind, @Nullable String thing, long value) {
    }

    private StatCounter() {
    }

    @Nonnull
    public static List<Write> writes(@Nonnull StatIndex index, @Nonnull String kindId, @Nonnull String target,
            @Nullable String qualifier, long amount, @Nullable ZoneRef zone) {
        List<StatIndex.Line> lines = index.forKind(kindId);
        if (lines.isEmpty()) {
            return List.of();
        }
        List<Write> out = new ArrayList<>();
        Set<String> counted = new HashSet<>();
        for (StatIndex.Line line : lines) {
            String folded = line.statId().toLowerCase(Locale.ROOT);
            if (counted.contains(folded) || !line.def().matches(target, qualifier) || !line.def().matchesZone(zone)) {
                continue;
            }
            long value = line.countsOne() ? 1L : amount;
            if (value <= 0L) {
                continue;
            }
            counted.add(folded);
            out.add(new Write(line.statId(), line.kind(), line.breakdown().thing(target, qualifier), value));
        }
        return out;
    }
}
