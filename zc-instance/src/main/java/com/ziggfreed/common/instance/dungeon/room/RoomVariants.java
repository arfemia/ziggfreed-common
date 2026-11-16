package com.ziggfreed.common.instance.dungeon.room;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bson.BsonDocument;
import org.bson.BsonValue;

import com.ziggfreed.common.instance.dungeon.layout.SplitMix64;

/**
 * A run's variant picks, pure: a volume or point tagged {@code ZigVariant: "<Group>:<n>"} ({@code n} from 1) is placed
 * only when its run's seed picked {@code n} for {@code <Group>}, one pick per group for the whole run, so a puzzle and
 * its clue in a room further back agree. A group has as many variants as the highest {@code n} any room of the run
 * tags it with; the group's name is matched without regard to case. Logic carrying no {@code ZigVariant} tag, or one
 * that does not read as {@code <Group>:<n>}, is always placed.
 */
public final class RoomVariants {

    /** The tag key a variant is named under. */
    public static final String KEY = "ZigVariant";

    private RoomVariants() {
    }

    /**
     * One variant tag, read.
     *
     * @param group its group, trimmed and folded to lower case
     * @param n     which variant of the group it is, from 1
     */
    public record Variant(@Nonnull String group, int n) {
    }

    /** {@code value} read as {@code <Group>:<n>}; null when it does not read so (no colon, no group, n below 1). */
    @Nullable
    public static Variant parse(@Nullable String value) {
        if (value == null) {
            return null;
        }
        int colon = value.lastIndexOf(':');
        if (colon <= 0) {
            return null;
        }
        String group = value.substring(0, colon).trim().toLowerCase(Locale.ROOT);
        try {
            int n = Integer.parseInt(value.substring(colon + 1).trim());
            return group.isEmpty() || n < 1 ? null : new Variant(group, n);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * The variant a holder's encoded JSON names in its {@code Tags} ({@link #KEY}, matched without regard to case; a
     * value written as a list reads its first entry, as the engine reads it); null for none.
     */
    @Nullable
    public static Variant of(@Nonnull BsonDocument holder) {
        BsonDocument tags = RoomTurns.doc(holder, "Tags");
        if (tags == null) {
            return null;
        }
        for (Map.Entry<String, BsonValue> tag : tags.entrySet()) {
            if (!KEY.equalsIgnoreCase(tag.getKey().trim())) {
                continue;
            }
            BsonValue value = tag.getValue();
            if (value.isArray() && !value.asArray().isEmpty()) {
                value = value.asArray().get(0);
            }
            return value.isString() ? parse(value.asString().getValue()) : null;
        }
        return null;
    }

    /** Count {@code variant} into {@code counts}: its group's count is the highest {@code n} seen. */
    public static void count(@Nullable Variant variant, @Nonnull Map<String, Integer> counts) {
        if (variant != null) {
            counts.merge(variant.group(), variant.n(), Math::max);
        }
    }

    /** The variant {@code seed} picks for {@code group} of {@code count} variants, from 1; 1 for a count below 2. */
    public static int pick(long seed, @Nonnull String group, int count) {
        if (count < 2) {
            return 1;
        }
        String folded = group.trim().toLowerCase(Locale.ROOT);
        return SplitMix64.derive(seed, folded.hashCode()).nextInt(count) + 1;
    }

    /** Each group of {@code counts} with the variant {@code seed} picks for it. */
    @Nonnull
    public static Map<String, Integer> picks(long seed, @Nonnull Map<String, Integer> counts) {
        Map<String, Integer> out = new HashMap<>();
        for (Map.Entry<String, Integer> group : counts.entrySet()) {
            out.put(group.getKey(), pick(seed, group.getKey(), group.getValue()));
        }
        return out;
    }

    /** Whether logic tagged {@code variant} is placed under {@code picks}: untagged always, else only the pick. */
    public static boolean keeps(@Nullable Variant variant, @Nonnull Map<String, Integer> picks) {
        if (variant == null) {
            return true;
        }
        Integer picked = picks.get(variant.group());
        return picked == null || picked == variant.n();
    }
}
