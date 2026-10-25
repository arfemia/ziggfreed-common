package com.ziggfreed.common.world.gather;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.map.MapCodec;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.match.NamePattern;

/**
 * A named family of blocks (ore, stone, the hardest woods, loose soil, the cheap building blocks thrift may spare):
 * one family per file at {@code Server/ZiggfreedCommon/BlockFamilies/<Id>.json}, the filename its id. What a family
 * is FOR lives elsewhere, by name: the gather step reads the stat {@code GatherSpeed_<Id>} on a block in it, the
 * thrift step {@code BuildThrift_<Id>}, a gather shape names the families its first block must be in. A family no
 * stat names and no shape names is simply never asked.
 *
 * <pre>{@code
 * {
 *   "Match":   { "Tags": { "Type": ["Rock"] } },
 *   "Exclude": { "Tags": { "Family": ["Gem"] }, "Blocks": ["*_Ornate*"] }
 * }
 * }</pre>
 *
 * <p><b>The block names itself first (R751).</b> A block whose own item {@code Tags} carry
 * {@value #OPT_IN_TAG} with a family's id is in that family whatever the file says, so another mod opts its
 * blocks in with one line in its own item file. Otherwise a block is in the family when its item's native tags or
 * its id meet {@code Match} and do not meet {@code Exclude}: a tag key's values are alternatives, the keys are
 * alternatives, and a name pattern is an alternative to every tag (each group reads "any of these"). Tags are read
 * as the engine merges them, a block's {@code Parent} included, so vanilla's {@code Type} and {@code Family}
 * values (a trunk's {@code Type: Trunk}, an ore's {@code Type: Ore}) are the natural keys; a name pattern is for
 * what no native tag tells apart. A group's {@code Families} names other families by id, so a family is written once
 * ({@code Soft_Wood} is the logs that are not {@code Hard_Wood}). A server owner retunes or switches off a family by
 * id in {@code mods/ziggfreedcommon/block-families.json}.
 */
public final class BlockFamilyAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, BlockFamilyAsset>> {

    /** Where the families live under a pack's {@code Server/} folder. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/BlockFamilies";

    /** The item tag through which a block names the families it belongs to itself. */
    public static final String OPT_IN_TAG = "ZigBlockFamily";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private Selector match;
    @Nullable private Selector exclude;
    @Nullable private Boolean enabled;

    /**
     * Which blocks a group names: by their item's native tags, by name pattern, or by the other families they are in.
     * Each is "any of these".
     */
    public static final class Selector {

        @Nullable protected Map<String, String[]> tags;
        @Nullable protected String[] blocks;
        @Nullable protected String[] families;

        /** Tag key to its values, as a block item's own native {@code Tags} map is written. */
        private static final MapCodec<String[], Map<String, String[]>> TAGS_CODEC =
                new MapCodec<>(Codec.STRING_ARRAY, HashMap::new);

        public static final BuilderCodec<Selector> CODEC = BuilderCodec.builder(Selector.class, Selector::new)
                .appendInherited(new KeyedCodec<>("Tags", TAGS_CODEC, false),
                        (o, v) -> o.tags = v, o -> o.tags, (o, p) -> o.tags = p.tags)
                .documentation("Native item tags, written as a block item's own Tags map: a tag key to its values, "
                        + "for example {\"Type\": [\"Rock\"]}. A block carrying any listed value under any listed key "
                        + "is named. Tags are read as the engine merges them, the block's Parent included. Case is "
                        + "ignored.").add()
                .appendInherited(new KeyedCodec<>("Blocks", Codec.STRING_ARRAY, false),
                        (o, v) -> o.blocks = v, o -> o.blocks, (o, p) -> o.blocks = p.blocks)
                .documentation("Block ids as name patterns: \"Rock_Stone\" exactly, \"Rock_*\" starting with, "
                        + "\"*_Cracked\" ending with, \"*Basalt*\" anywhere in the id. For what no native tag tells "
                        + "apart. Case is ignored.").add()
                .appendInherited(new KeyedCodec<>("Families", Codec.STRING_ARRAY, false),
                        (o, v) -> o.families = v, o -> o.families, (o, p) -> o.families = p.families)
                .documentation("Other block families (Server/ZiggfreedCommon/BlockFamilies) by id: a block in any of "
                        + "them is named, so a family is written once and read from many, for example Match "
                        + "{\"Families\": [\"Log\"]} with Exclude {\"Families\": [\"Hard_Wood\"]}. A family that "
                        + "reaches itself through these names nothing that way. Case is ignored.").add()
                .build();

        public Selector() {
        }

        /** Java-side factory; sets the same fields the codec fills. */
        @Nonnull
        public static Selector of(@Nullable Map<String, String[]> tags, @Nullable String... blocks) {
            Selector selector = new Selector();
            selector.tags = tags;
            selector.blocks = blocks;
            return selector;
        }

        /** Java-side factory naming other families; sets the same fields the codec fills. */
        @Nonnull
        public static Selector ofFamilies(@Nullable String... families) {
            Selector selector = new Selector();
            selector.families = families;
            return selector;
        }

        /** Whether the group names nothing at all. */
        public boolean isEmpty() {
            return (tags == null || tags.isEmpty()) && (blocks == null || blocks.length == 0)
                    && (families == null || families.length == 0);
        }

        /** The other families this group names, as written; empty, never null. */
        @Nonnull
        public String[] families() {
            return families == null ? new String[0] : families;
        }

        /**
         * Does this group name the block {@code blockIdLower} whose item reads {@code rawTags}, by tag or name alone?
         * Pure; a null map reads as no tags. A {@code Families} entry names nothing here: {@link #names(String, Map,
         * Predicate)} asks it.
         */
        public boolean names(@Nonnull String blockIdLower, @Nullable Map<String, String[]> rawTags) {
            return names(blockIdLower, rawTags, family -> false);
        }

        /**
         * Does this group name the block {@code blockIdLower} whose item reads {@code rawTags}? {@code inFamily}
         * answers whether the block is in another family by id (any casing), for {@code Families}. Pure over its
         * arguments; a null map reads as no tags.
         */
        public boolean names(@Nonnull String blockIdLower, @Nullable Map<String, String[]> rawTags,
                @Nonnull Predicate<String> inFamily) {
            if (families != null) {
                for (String family : families) {
                    if (family != null && !family.isBlank() && inFamily.test(family.trim())) {
                        return true;
                    }
                }
            }
            if (blocks != null) {
                for (String pattern : blocks) {
                    if (pattern != null && !pattern.isBlank()
                            && NamePattern.parse(pattern.trim()).matches(blockIdLower)) {
                        return true;
                    }
                }
            }
            if (tags == null || tags.isEmpty() || rawTags == null || rawTags.isEmpty()) {
                return false;
            }
            for (Map.Entry<String, String[]> wanted : tags.entrySet()) {
                String[] have = tagValues(rawTags, wanted.getKey());
                if (have != null && wanted.getValue() != null && anyShared(wanted.getValue(), have)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String toString() {
            return "Selector{tags=" + (tags == null ? "-" : tags.keySet()) + ", blocks="
                    + (blocks == null ? "-" : Arrays.toString(blocks)) + ", families="
                    + (families == null ? "-" : Arrays.toString(families)) + "}";
        }
    }

    public static final AssetBuilderCodec<String, BlockFamilyAsset> CODEC = AssetBuilderCodec.builder(
                    BlockFamilyAsset.class,
                    BlockFamilyAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Match", Selector.CODEC, false),
                    (a, v) -> a.match = v, a -> a.match, (a, p) -> a.match = p.match)
            .documentation("The blocks in the family: by native item tags (Tags), by block id (Blocks), or both, "
                    + "each \"any of these\". A family with no Match holds only the blocks that name it themselves "
                    + "through their item's ZigBlockFamily tag.").add()
            .appendInherited(new KeyedCodec<>("Exclude", Selector.CODEC, false),
                    (a, v) -> a.exclude = v, a -> a.exclude, (a, p) -> a.exclude = p.exclude)
            .documentation("Blocks Match names that the family leaves out anyway, written the same way. A block "
                    + "naming the family through its own ZigBlockFamily tag is never left out.").add()
            .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                    (a, v) -> a.enabled = v, a -> a.enabled, (a, p) -> a.enabled = p.enabled)
            .metadata(EditorSchema.defaultValue(true))
            .documentation("Set false to empty the family: no block is in it, a block's own tag included, so every "
                    + "stat and shape naming it stops reading.").add()
            .build();

    public BlockFamilyAsset() {
    }

    /** Java-side factory; sets the same fields the codec fills. */
    @Nonnull
    public static BlockFamilyAsset of(@Nonnull String id, @Nullable Selector match, @Nullable Selector exclude,
            @Nullable Boolean enabled) {
        BlockFamilyAsset asset = new BlockFamilyAsset();
        asset.id = id;
        asset.match = match;
        asset.exclude = exclude;
        asset.enabled = enabled;
        return asset;
    }

    @Override
    public String getId() {
        return id;
    }

    @Nullable
    public Selector getMatch() {
        return match;
    }

    @Nullable
    public Selector getExclude() {
        return exclude;
    }

    /** True unless the family was switched off. */
    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    /**
     * Is the block {@code blockId}, whose item reads {@code rawTags}, in this family? Its own
     * {@value #OPT_IN_TAG} tag naming {@code familyId} puts it in; otherwise {@code Match} must name it and
     * {@code Exclude} must not, by tag or name alone. Pure: a switched-off family holds nothing.
     */
    public boolean holds(@Nonnull String familyId, @Nonnull String blockId, @Nullable Map<String, String[]> rawTags) {
        return holds(familyId, blockId, rawTags, family -> false);
    }

    /**
     * {@link #holds(String, String, Map)}, with {@code inFamily} answering whether the block is in another family by id
     * (any casing), for a selector's {@code Families}. Pure over its arguments: a switched-off family holds nothing.
     */
    public boolean holds(@Nonnull String familyId, @Nonnull String blockId, @Nullable Map<String, String[]> rawTags,
            @Nonnull Predicate<String> inFamily) {
        if (!isEnabled()) {
            return false;
        }
        if (rawTags != null) {
            String[] named = tagValues(rawTags, OPT_IN_TAG);
            if (named != null && anyShared(new String[] {familyId}, named)) {
                return true;
            }
        }
        if (match == null || match.isEmpty()) {
            return false;
        }
        String lower = blockId.toLowerCase(Locale.ROOT);
        return match.names(lower, rawTags, inFamily) && (exclude == null || !exclude.names(lower, rawTags, inFamily));
    }

    /** The values {@code rawTags} holds under {@code key}, matched without regard to case; null for none. */
    @Nullable
    static String[] tagValues(@Nonnull Map<String, String[]> rawTags, @Nullable String key) {
        if (key == null) {
            return null;
        }
        String[] exact = rawTags.get(key);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, String[]> entry : rawTags.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** Does any value of {@code wanted} appear in {@code have}, without regard to case? */
    static boolean anyShared(@Nonnull String[] wanted, @Nonnull String[] have) {
        for (String w : wanted) {
            if (w == null) {
                continue;
            }
            for (String h : have) {
                if (h != null && h.trim().equalsIgnoreCase(w.trim())) {
                    return true;
                }
            }
        }
        return false;
    }
}
