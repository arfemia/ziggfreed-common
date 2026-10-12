package com.ziggfreed.common.achievement;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.CRC32;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.loot.reward.RewardSpec;

/**
 * Which of an achievement's {@code Claim} rows a player's record already settled, so a row added to the list
 * after they collected (or after it settled on earning with nothing to collect) can be told apart from one
 * they were paid.
 *
 * <p>A row is known by a FINGERPRINT: eight hex digits of a CRC32 over its kind (lower-cased, since kinds
 * match without regard to case) and its parameters in key order.
 *
 * <p>The record's NOTE never shrinks. It holds every fingerprint any settle of the achievement covered (a
 * multiset union: a fingerprint counts as often as the most copies of it one list held), plus the most rows
 * one settle covered ({@code n:<count>}). So a row that leaves the list (its mod missing, an owner override, a
 * reload) and comes back is still covered and never paid twice.
 *
 * <p>A row is owed only when the list holds MORE rows than any settle covered: as many as it grew by, never
 * more, taken from the rows the note does not cover, the last ones first (rows are appended at the end). So
 * retuning a row's numbers (a new fingerprint, no more rows) reopens nothing; a row retuned and another
 * appended in the same change owes the appended one. Two rows sharing one fingerprint count as two, so a third
 * identical row is still owed.
 */
final class ClaimRows {

    /** How the note writes its row count, an entry no fingerprint can be. */
    private static final String ROWS_PREFIX = "n:";

    private ClaimRows() {
    }

    /** One row's fingerprint, stable across restarts and casing. */
    @Nonnull
    static String fingerprint(@Nonnull RewardSpec row) {
        StringBuilder canonical = new StringBuilder(row.kind().toLowerCase(Locale.ROOT));
        for (Map.Entry<String, String> param : new TreeMap<>(row.params()).entrySet()) {
            canonical.append('\n').append(param.getKey()).append('=').append(param.getValue());
        }
        CRC32 crc = new CRC32();
        crc.update(canonical.toString().getBytes(StandardCharsets.UTF_8));
        return String.format(Locale.ROOT, "%08x", crc.getValue());
    }

    /** The note of a settle covering no row at all: what earning with nothing to collect proves. */
    @Nonnull
    static List<String> none() {
        return write(new TreeMap<>(), 0);
    }

    /**
     * {@code note} (null: none yet) after a settle of {@code rows}: every fingerprint it already covered plus
     * those of {@code rows}, and the larger row count. Never fewer entries than it had.
     */
    @Nonnull
    static List<String> settle(@Nullable List<String> note, @Nonnull List<RewardSpec> rows) {
        Map<String, Integer> union = new TreeMap<>();
        int covered = 0;
        if (note != null) {
            union.putAll(counts(note));
            covered = rowCount(note);
        }
        Map<String, Integer> now = new HashMap<>();
        for (RewardSpec row : rows) {
            now.merge(fingerprint(row), Integer.valueOf(1), Integer::sum);
        }
        now.forEach((fingerprint, count) -> union.merge(fingerprint, count, Math::max));
        return write(union, Math.max(covered, rows.size()));
    }

    /**
     * The rows of {@code rows} that {@code note} does not cover, in list order: empty unless the list holds more
     * rows than any settle covered, and then at most that many more, the last of the uncovered rows.
     */
    @Nonnull
    static List<RewardSpec> uncovered(@Nonnull List<RewardSpec> rows, @Nonnull List<String> note) {
        int grew = rows.size() - rowCount(note);
        if (grew <= 0) {
            return List.of();
        }
        Map<String, Integer> left = new HashMap<>(counts(note));
        List<RewardSpec> unmatched = new ArrayList<>();
        for (RewardSpec row : rows) {
            String fingerprint = fingerprint(row);
            Integer count = left.get(fingerprint);
            if (count != null && count.intValue() > 0) {
                left.put(fingerprint, Integer.valueOf(count.intValue() - 1));
            } else {
                unmatched.add(row);
            }
        }
        int owed = Math.min(grew, unmatched.size());
        return List.copyOf(unmatched.subList(unmatched.size() - owed, unmatched.size()));
    }

    /** Every fingerprint in {@code note}, with how many copies it covers. */
    @Nonnull
    private static Map<String, Integer> counts(@Nonnull List<String> note) {
        Map<String, Integer> counts = new TreeMap<>();
        for (String entry : note) {
            if (!entry.startsWith(ROWS_PREFIX)) {
                counts.merge(entry, Integer.valueOf(1), Integer::sum);
            }
        }
        return counts;
    }

    /**
     * The most rows one settle covered, as {@code note} writes it. It can be fewer than the fingerprints, which
     * also keep every retuned version of a row. A note with no readable count (written by hand) reads as one row
     * per fingerprint, which offers less, never more.
     */
    private static int rowCount(@Nonnull List<String> note) {
        int fingerprints = 0;
        int written = -1;
        for (String entry : note) {
            if (!entry.startsWith(ROWS_PREFIX)) {
                fingerprints++;
                continue;
            }
            try {
                written = Math.max(written, Integer.parseInt(entry.substring(ROWS_PREFIX.length()).trim()));
            } catch (NumberFormatException unreadable) {
                // An unreadable count falls back to the fingerprints, below.
            }
        }
        return written >= 0 ? written : fingerprints;
    }

    /** The note as entries: each fingerprint as often as it counts, sorted, then the row count. */
    @Nonnull
    private static List<String> write(@Nonnull Map<String, Integer> union, int rows) {
        List<String> out = new ArrayList<>();
        union.forEach((fingerprint, count) -> {
            for (int i = 0; i < count.intValue(); i++) {
                out.add(fingerprint);
            }
        });
        out.add(ROWS_PREFIX + rows);
        return List.copyOf(out);
    }
}
