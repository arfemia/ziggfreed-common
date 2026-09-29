package com.ziggfreed.common.stats.gearset;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.codec.InheritMapCodec;
import com.ziggfreed.common.text.ContentTextAsset;

/**
 * A GEAR SET, written as content: {@code Server/ZiggfreedCommon/GearSets/<Set_Id>.json}. The set id
 * is the FILENAME, spelled {@code Is_Like_This}; a later-loaded pack's same-id file replaces it whole,
 * and the server owner's {@code mods/ziggfreedcommon/gear-sets.json} merges over it leaf by leaf.
 *
 * <pre>{@code
 * // Server/ZiggfreedCommon/GearSets/Ranger_Kit.json
 * {
 *   "Text": { "TitleKey": "gearset.ranger_kit.title" },
 *   "Members": ["Ranger_Hood", "Ranger_Coat", "Ranger_Gloves", "Ranger_Boots", "Ranger_Bow"],
 *   "Bonuses": [
 *     { "Pieces": 2, "Text": { "TitleKey": "gearset.ranger_kit.tier_two" },
 *       "StatModifiers": { "Stamina": [ { "Amount": 10, "CalculationType": "Additive" } ] } },
 *     { "Armor": 4, "Text": { "TitleKey": "gearset.ranger_kit.tier_armor" }, "Effect": "Ranger_Kit_Set",
 *       "StatModifiers": { "Health": [ { "Amount": 20, "CalculationType": "Additive" } ] } },
 *     { "Armor": 4, "Held": 1, "Text": { "TitleKey": "gearset.ranger_kit.tier_bow" },
 *       "StatModifiers": { "Mana": [ { "Amount": 15, "CalculationType": "Additive" } ] } }
 *   ]
 * }
 * }</pre>
 *
 * <p><b>Members</b> are item ids, matched without regard to case; a duplicate counts once, and an item
 * may belong to several sets. <b>A tier's condition is a conjunction of independent minimums</b>,
 * never a bare count: {@code Pieces} (distinct members on anywhere: worn, held or in the utility
 * slot), {@code Armor} (distinct members in armor slots), {@code Held} (the active-hand item is a
 * member that is not also worn in an armor slot) and {@code Utility} (the utility-slot item is a
 * member). {@code Held} and {@code Utility} are authored as 1 to require them and left out
 * otherwise; 0 is an error, never "must not be". Each minimum is nullable and independent; a tier
 * authoring none is an error the validator names. Every tier whose
 * condition holds applies, so tiers are cumulative by construction, and two tiers may share a count:
 * the armor tier carries the set's look ({@code Effect}, a native {@code EntityEffect} held while
 * the tier is active), the weapon tier beside it adds stats only. A tier's {@code Effect} id must be
 * DEDICATED to the set: the engine takes it off whenever no active tier wants it, whoever put it on.
 *
 * <p>A set with no weapon authors {@code {Pieces 2}}, {@code {Pieces 3}}, {@code {Armor 4, Effect}};
 * a set with one authors {@code {Pieces 2}}, {@code {Armor 4, Effect}}, {@code {Armor 4, Held 1}}.
 *
 * <p>{@code StatModifiers} is the native item block, decoded through {@link StatModifierSpec}; the
 * map may carry a {@code $Comment}. Under {@code Parent}, {@code Bonuses} REPLACES wholesale (a
 * child restating the ladder restates all of it) while {@code Text}, {@code Enabled} and
 * {@code Members} merge as every other leaf in this library does.
 *
 * <p>An item names its set in its own {@code items.<Id>.description}; no per-instance display
 * metadata is ever written, since that would stop the item stacking and lie to its next holder. The
 * live count is the {@code Gear_Set_Tier} notice's job.
 */
public final class GearSetAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, GearSetAsset>> {

    /** Where these files live. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/GearSets";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable protected ContentTextAsset text;
    @Nullable protected Boolean enabled;
    @Nullable protected String[] members;
    @Nullable protected Tier[] bonuses;

    public static final AssetBuilderCodec<String, GearSetAsset> CODEC = AssetBuilderCodec.builder(
                    GearSetAsset.class,
                    GearSetAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Text", ContentTextAsset.CODEC, false),
                    (a, v) -> a.text = v, a -> a.text, (a, p) -> a.text = p.text)
            .documentation("What the set is called: TitleKey is the localization key the notice names it "
                    + "by. The same Text group every content type in this library carries.").add()
            .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                    (a, v) -> a.enabled = v, a -> a.enabled, (a, p) -> a.enabled = p.enabled)
            .metadata(EditorSchema.defaultValue(true))
            .documentation("false switches the set off: its bonuses apply to nobody and the validator "
                    + "skips it. Unauthored means true. The owner file is the place to flip it.").add()
            .appendInherited(new KeyedCodec<>("Members", Codec.STRING_ARRAY, false),
                    (a, v) -> a.members = v, a -> a.members, (a, p) -> a.members = p.members)
            .documentation("The item ids that make up the set, matched without regard to case. A "
                    + "duplicate counts once; an item may belong to several sets.").add()
            .appendInherited(new KeyedCodec<>("Bonuses", new ArrayCodec<>(Tier.CODEC, Tier[]::new), false),
                    (a, v) -> a.bonuses = v, a -> a.bonuses, (a, p) -> a.bonuses = p.bonuses)
            .documentation("The tiers, in order. Every tier whose minimums all hold applies at once, so "
                    + "they stack by construction. Under Parent this list replaces the parent's whole; "
                    + "restate every tier you keep.").add()
            .build();

    public GearSetAsset() {
    }

    /** The set id this file describes, exactly as the filename spells it. */
    @Override
    public String getId() {
        return id;
    }

    @Nullable
    public ContentTextAsset getText() {
        return text;
    }

    /** The localization key the set is named by, or null when the file names none. */
    @Nullable
    public String titleKey() {
        return text == null ? null : blankToNull(text.getTitleKey());
    }

    /** Whether the set applies at all; unauthored means yes. */
    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    /** The members exactly as authored, or null when none were written. */
    @Nullable
    public String[] getMembers() {
        return members == null ? null : members.clone();
    }

    /** The distinct member ids, lower-cased and blanks dropped, in authored order. */
    @Nonnull
    public Set<String> memberIds() {
        Set<String> out = new LinkedHashSet<>();
        if (members != null) {
            for (String member : members) {
                if (member != null && !member.isBlank()) {
                    out.add(member.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }

    /** The tiers in authored order, a null entry dropped; empty when none were written. */
    @Nonnull
    public List<Tier> tiers() {
        if (bonuses == null) {
            return List.of();
        }
        List<Tier> out = new ArrayList<>(bonuses.length);
        for (Tier tier : bonuses) {
            if (tier != null) {
                out.add(tier);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** Java-side factory, for a test or a consumer registering a set without a file. */
    @Nonnull
    public static GearSetAsset of(@Nonnull String id, @Nullable ContentTextAsset text, @Nullable Boolean enabled,
            @Nullable String[] members, @Nullable Tier... bonuses) {
        GearSetAsset a = new GearSetAsset();
        a.id = id;
        a.text = text;
        a.enabled = enabled;
        a.members = members == null ? null : members.clone();
        a.bonuses = bonuses == null ? null : bonuses.clone();
        return a;
    }

    @Nullable
    private static String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    // ==================== Tier ====================

    /**
     * One rung of the ladder: the minimums that have to hold, what it adds while they do, and how
     * it reads. The four minimums are independent; an unauthored one is not a condition. A tier
     * decodes fresh every time (the list replaces wholesale under Parent), so nothing in it inherits.
     */
    public static final class Tier {

        @Nullable protected Integer pieces;
        @Nullable protected Integer armor;
        @Nullable protected Integer held;
        @Nullable protected Integer utility;
        @Nullable protected ContentTextAsset text;
        @Nullable protected String effect;
        @Nullable protected Map<String, StatModifierSpec[]> statModifiers;

        public static final BuilderCodec<Tier> CODEC = BuilderCodec.builder(Tier.class, Tier::new)
                .append(new KeyedCodec<>("Pieces", Codec.INTEGER, false),
                        (o, v) -> o.pieces = v, o -> o.pieces)
                .documentation("At least this many DISTINCT members on anywhere: worn, held or in the "
                        + "utility slot. A copy held while the same piece is worn counts once.").add()
                .append(new KeyedCodec<>("Armor", Codec.INTEGER, false),
                        (o, v) -> o.armor = v, o -> o.armor)
                .documentation("At least this many distinct members in ARMOR slots. Neither the held item "
                        + "nor the utility slot counts here.").add()
                .append(new KeyedCodec<>("Held", Codec.INTEGER, false),
                        (o, v) -> o.held = v, o -> o.held)
                .documentation("Author 1 to require the active-hand item to be a member that is not also "
                        + "worn in an armor slot, so a spare copy of a worn piece never counts as the blade; "
                        + "leave it out otherwise. 0 is an error. The way a weapon tier asks for its blade.").add()
                .append(new KeyedCodec<>("Utility", Codec.INTEGER, false),
                        (o, v) -> o.utility = v, o -> o.utility)
                .documentation("Author 1 to require the utility-slot item to be a member; leave it out "
                        + "otherwise. 0 is an error.").add()
                .append(new KeyedCodec<>("Text", ContentTextAsset.CODEC, false),
                        (o, v) -> o.text = v, o -> o.text)
                .documentation("How the tier reads: TitleKey is the one line the notice shows under the "
                        + "set's name when this tier comes on or goes off.").add()
                .append(new KeyedCodec<>("Effect", Codec.STRING, false),
                        (o, v) -> o.effect = v, o -> o.effect)
                .documentation("A native EntityEffect id held on the player while this tier is active and "
                        + "taken off when it stops: the set's look, usually on the full-armor tier. Author "
                        + "it Infinite with OverlapBehavior Ignore, since it goes on when the tier comes on "
                        + "and again at every login. The id must be dedicated to the set: when no active "
                        + "tier wants it, it comes off, even if something else put it on.").add()
                .append(new KeyedCodec<>("StatModifiers",
                                new InheritMapCodec<>(new ArrayCodec<>(StatModifierSpec.CODEC, StatModifierSpec[]::new)),
                                false),
                        (o, v) -> o.statModifiers = v, o -> o.statModifiers)
                .documentation("The stats this tier adds, the SAME block an item's Armor or Weapon carries: "
                        + "a stat channel id to a list of {Amount, CalculationType, Target}. A $Comment may "
                        + "sit inside it. An unregistered channel is named in the log once and skipped.").add()
                .build();

        public Tier() {
        }

        /** Java-side factory; sets the same fields the codec fills. */
        @Nonnull
        public static Tier of(@Nullable Integer pieces, @Nullable Integer armor, @Nullable Integer held,
                @Nullable Integer utility, @Nullable ContentTextAsset text, @Nullable String effect,
                @Nullable Map<String, StatModifierSpec[]> statModifiers) {
            Tier t = new Tier();
            t.pieces = pieces;
            t.armor = armor;
            t.held = held;
            t.utility = utility;
            t.text = text;
            t.effect = effect;
            t.statModifiers = statModifiers;
            return t;
        }

        @Nullable
        public Integer getPieces() {
            return pieces;
        }

        @Nullable
        public Integer getArmor() {
            return armor;
        }

        @Nullable
        public Integer getHeld() {
            return held;
        }

        @Nullable
        public Integer getUtility() {
            return utility;
        }

        @Nullable
        public ContentTextAsset getText() {
            return text;
        }

        /** The localization key of the tier's one line, or null when the file names none. */
        @Nullable
        public String titleKey() {
            return text == null ? null : blankToNull(text.getTitleKey());
        }

        /** The effect held while the tier is active, or null when it carries none. */
        @Nullable
        public String effectId() {
            return blankToNull(effect);
        }

        /** The authored stat block, empty when none was written; never null. */
        @Nonnull
        public Map<String, StatModifierSpec[]> statModifiers() {
            return statModifiers == null ? Map.of() : Collections.unmodifiableMap(statModifiers);
        }

        /** True when at least one minimum is authored; a tier with none can never hold. */
        public boolean hasCondition() {
            return pieces != null || armor != null || held != null || utility != null;
        }

        /** The four minimums as one value, so two tiers asking the same thing compare equal. */
        @Nonnull
        public Condition condition() {
            return new Condition(pieces, armor, held, utility);
        }
    }

    /** A tier's authored minimums, each null when not a condition. */
    public record Condition(@Nullable Integer pieces, @Nullable Integer armor, @Nullable Integer held,
            @Nullable Integer utility) {
    }
}
