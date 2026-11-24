package com.ziggfreed.common.track.asset;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.track.TrackKey;

/**
 * One slot row, the one shape every list of slots that open on their own condition shares: a stance's
 * {@code Slots} ({@code TrackStances}), a track's saved builds past its {@code Loadouts.Max}
 * ({@code TrackAsset.Loadouts}), and any other carrier of numbered places a player fills. A row is open while
 * its {@code Requires} holds and, when it names a {@code Pick}, while the player owns that pick; a row naming neither
 * is open from the start. How many places a carrier has is how many of its rows are open, so a third place is one
 * more row with its own condition, written by a pack or an owner's file, never a number in code.
 *
 * <pre>{@code
 * "Slots": [
 *   { "Id": "I" },
 *   { "Id": "II", "Pick": "Your_Second_Slot_Key" },
 *   { "Id": "III", "Requires": { "Factors": [ { "Factor": "ziggfreedcommon:track_level", "Param": "Your_Track", "Min": 50 } ] } }
 * ]
 * }</pre>
 *
 * <p>A row is known by its {@code Id} in a player's record, so give each row an id that names a lasting fact (a
 * numeral, never a price or a level) and never rename it on a live server. A row with no id is no row. A carrier
 * whose places are numbered (saved builds) reads the place a row opens off its id ({@link #number}: {@code III} or
 * {@code 3} is the third), and a carrier on a track lets a row's {@code Pick} write {@value #TRACK_TOKEN} for that
 * track's own id ({@link #pick(String)}), so one row on a parent track file serves every track under it.
 */
public final class SlotRow {

    /** What a row's {@code Pick} may write for the id of the track its carrier sits on. */
    public static final String TRACK_TOKEN = "{Track}";

    @Nullable protected String id;
    @Nullable protected GateSpec requires;
    @Nullable protected String pick;

    public static final BuilderCodec<SlotRow> CODEC = BuilderCodec.builder(SlotRow.class, SlotRow::new)
            .appendInherited(new KeyedCodec<>("Id", Codec.STRING, false),
                    (o, v) -> o.id = v, o -> o.id, (o, p) -> o.id = p.id)
            .documentation("What this slot is known by in a player's record (letters, digits and underscores), "
                    + "such as I, II or III. It names a lasting fact, never a price or a level: renaming it on a "
                    + "live server empties every player's slot of that name. A row with no Id is no slot. On a track's "
                    + "Loadouts.Slots it is also the saved build's number, as a numeral (III) or in digits (3).").add()
            .appendInherited(new KeyedCodec<>("Requires", GateSpec.CODEC, false),
                    (o, v) -> o.requires = v, o -> o.requires, (o, p) -> o.requires = p.requires)
            .documentation("What a player must meet for this slot to be open, in the Requires shape quests use, "
                    + "every condition read (a mod condition included). Read when the player fills a slot and at "
                    + "each settle (a login, a world change, a change of picks), so write readings that change "
                    + "through the player's own progress (a level, a quest, a permission). Leave it out for no "
                    + "condition.").add()
            .appendInherited(new KeyedCodec<>("Pick", Codec.STRING, false),
                    (o, v) -> o.pick = v, o -> o.pick, (o, p) -> o.pick = p.pick)
            .metadata(EditorSchema.assetRef("TrackPickAsset"))
            .documentation("A pick (by id, on any track) the player must own for this slot to be open: the key a "
                    + "quest's reward, a shop or an admin teaches. Leave it out for no key. A row with neither "
                    + "Requires nor Pick is open from the start. On a track's Loadouts.Slots, {Track} stands for the "
                    + "track's own id (Your_Build_Key_{Track}), so one row on a parent track serves every track "
                    + "under it.").add()
            .build();

    /** A list of rows, as a carrier writes it. */
    public static final ArrayCodec<SlotRow> ARRAY_CODEC = new ArrayCodec<>(CODEC, SlotRow[]::new);

    public SlotRow() {
    }

    /** Java-side factory (a test); sets the same fields the codec fills. */
    @Nonnull
    public static SlotRow of(@Nullable String id, @Nullable GateSpec requires, @Nullable String pick) {
        SlotRow row = new SlotRow();
        row.id = id;
        row.requires = requires;
        row.pick = pick;
        return row;
    }

    /** The id as written, trimmed, or null for none. */
    @Nullable
    public String id() {
        return id == null || id.isBlank() ? null : id.trim();
    }

    /** The id as a player's record keeps it (lower-cased), or empty for none. */
    @Nonnull
    public String key() {
        return TrackKey.normalize(id);
    }

    /** The {@code Requires} block as written, or null for none. */
    @Nullable
    public GateSpec requires() {
        return requires;
    }

    /** The key pick's id, lower-cased, or null for none. */
    @Nullable
    public String pick() {
        return pick(null);
    }

    /**
     * The key pick's id on the track {@code trackId} (any case), lower-cased: {@value #TRACK_TOKEN} (any case) in it
     * stands for that track's id; with no track the id as written. Null for none.
     */
    @Nullable
    public String pick(@Nullable String trackId) {
        String key = TrackKey.normalize(pick);
        if (key.isEmpty()) {
            return null;
        }
        String token = TRACK_TOKEN.toLowerCase(Locale.ROOT);
        String track = TrackKey.normalize(trackId);
        return track.isEmpty() || !key.contains(token) ? key : key.replace(token, track);
    }

    /** Is this row open from the start: no {@code Requires} and no {@code Pick}? */
    public boolean openFromStart() {
        return requires == null && pick() == null;
    }

    /**
     * Is this row open: its key pick owned ({@code owns}, asked with the lower-cased id) and its {@code Requires}
     * met ({@code passes}, asked only when the key is owned or none is named, and only when a block is written).
     */
    public boolean opens(@Nonnull Predicate<String> owns, @Nonnull Predicate<GateSpec> passes) {
        return opens(null, owns, passes);
    }

    /** {@link #opens(Predicate, Predicate)} for a row on the track {@code trackId}, its key read by {@link #pick(String)}. */
    public boolean opens(@Nullable String trackId, @Nonnull Predicate<String> owns,
            @Nonnull Predicate<GateSpec> passes) {
        String key = pick(trackId);
        if (key != null && !owns.test(key)) {
            return false;
        }
        return requires == null || passes.test(requires);
    }

    /**
     * The numbered place this row opens, read off its id: digits ({@code 3}) or a Roman numeral ({@code III}, any
     * case, written the usual way: {@code IV}, never {@code IIII}); 0 for an id that reads as neither. Pure.
     */
    public int number() {
        return numberOf(id());
    }

    /** {@link #number()} of an id as written; 0 for none or one that reads as no number. Pure. */
    public static int numberOf(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return 0;
        }
        String text = id.trim().toUpperCase(Locale.ROOT);
        if (text.chars().allMatch(Character::isDigit)) {
            try {
                int value = Integer.parseInt(text);
                return Math.max(0, value);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        int value = 0;
        for (int i = 0; i < text.length(); i++) {
            int here = numeral(text.charAt(i));
            if (here == 0) {
                return 0;
            }
            int next = i + 1 < text.length() ? numeral(text.charAt(i + 1)) : 0;
            value += here < next ? -here : here;
        }
        return value > 0 && value < 4000 && roman(value).equals(text) ? value : 0;
    }

    private static int numeral(char c) {
        return switch (c) {
            case 'I' -> 1;
            case 'V' -> 5;
            case 'X' -> 10;
            case 'L' -> 50;
            case 'C' -> 100;
            case 'D' -> 500;
            case 'M' -> 1000;
            default -> 0;
        };
    }

    /** {@code value} (1 to 3999) as the usual Roman numeral, upper case. */
    @Nonnull
    private static String roman(int value) {
        int[] amounts = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] words = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder out = new StringBuilder();
        int left = value;
        for (int i = 0; i < amounts.length; i++) {
            while (left >= amounts[i]) {
                out.append(words[i]);
                left -= amounts[i];
            }
        }
        return out.toString();
    }

    /**
     * The rows {@code raw} writes, in order: a null row, a row with no id and a later row repeating an earlier id
     * (any case) left out, so a player's record names one place by one id.
     */
    @Nonnull
    public static List<SlotRow> rows(@Nullable SlotRow[] raw) {
        if (raw == null || raw.length == 0) {
            return List.of();
        }
        List<SlotRow> out = new ArrayList<>(raw.length);
        Set<String> seen = new HashSet<>();
        for (SlotRow row : raw) {
            if (row != null && !row.key().isEmpty() && seen.add(row.key())) {
                out.add(row);
            }
        }
        return List.copyOf(out);
    }
}
