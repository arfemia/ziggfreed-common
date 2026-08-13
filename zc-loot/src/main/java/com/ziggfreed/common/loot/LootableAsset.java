package com.ziggfreed.common.loot;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.codec.ContainedAssetCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.ziggfreed.common.asset.EditorDataSets;

/**
 * A named, reusable loot table: {@code Server/ZiggfreedCommon/Lootables/<Name>.json}, body
 * {@code { "Rolls": [ <Roll>, ... ], "Pool": { ... } }}. The table's id is the FILENAME,
 * lower-cased; the {@code Name} key inside the file is display only.
 *
 * <pre>{@code
 * // Server/ZiggfreedCommon/Lootables/ForestFinds.json
 * {
 *   "Rolls": [
 *     { "Chance": { "Base": 5 }, "Grants": { "Items": [ { "Item": "Coin_Gold" } ] } }
 *   ],
 *   "Pool": {
 *     "Rolls": { "Base": 1 },
 *     "Entries": [ { "Weight": 3, "Grants": { "Items": [ { "Item": "Gem_Ruby" } ] } } ]
 *   }
 * }
 * }</pre>
 *
 * <p>Anything that pays out references the table by id, so one table serves many sites and retuning
 * it retunes all of them. A table nobody references is simply dormant, not an error.
 *
 * <h2>The two halves, and when to use which</h2>
 *
 * <p>{@code Rolls} is a list of INDEPENDENT payouts: every one is read, and every one whose gates
 * pass hands over what it names. {@code Pool} is a bag of COMPETING outcomes, of which only as many
 * as its own {@code Rolls} formula works out to are drawn. Author the list for what everybody gets,
 * the pool for the part that varies. A table may carry either, both, or neither.
 *
 * <h2>Overriding and inheriting</h2>
 *
 * <p>A file carrying {@code "Parent": "<id>"} starts from that table. Be aware that both
 * {@code Rolls} and the pool's {@code Entries} REPLACE wholesale rather than appending: a child that
 * authors any roll at all discards every roll it inherited, and the same goes for entries. Author
 * {@code ContributesTo} instead when the point is to ADD.
 *
 * <h2>Enriching a table you do not own</h2>
 *
 * <p>{@code ContributesTo} names another table's id, and everything this file authors is folded into
 * that table on top of what it already has: its rolls run after the target's, and its pool entries
 * join the target's bag. Nothing about the target's file changes, so two mods can both enrich one
 * table without either owning the other's content, and removing a contributing pack removes exactly
 * what it added.
 *
 * <p>How many picks the merged pool makes stays the TARGET's decision - a contributor adds outcomes,
 * it does not change the odds of drawing at all. The one exception is a target that declares no pool
 * of its own, where the first contributor's {@code Pool.Rolls} is used, because otherwise a pool that
 * exists only through contributions would have nothing to say how often it is drawn.
 *
 * <p>To retune a table someone else shipped, drop a file with the SAME name into your own pack -
 * later packs win by id. Give your own tables distinctive names, because ids are matched on the
 * filename alone across every installed pack.
 */
public final class LootableAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, LootableAsset>> {

    /** Where these files live, and the id the Asset Editor serves this type's pick list under. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/Lootables";
    public static final String EDITOR_DATASET = "ziggfreedcommon:lootables";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private Roll[] rolls;
    @Nullable private LootPool pool;
    @Nullable private String contributesTo;

    public static final AssetBuilderCodec<String, LootableAsset> CODEC = AssetBuilderCodec.builder(
                    LootableAsset.class,
                    LootableAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id == null ? null : id.toLowerCase(Locale.ROOT),
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .append(new KeyedCodec<>("Name", Codec.STRING, false),
                    (a, name) -> { /* display only - the id comes from the filename */ },
                    a -> a.id)
            .documentation("A human-readable label for editors. The table's id comes from the filename, so "
                    + "changing this changes nothing at runtime.").add()
            .appendInherited(new KeyedCodec<>("Rolls",
                            new ArrayCodec<>(Roll.codec(EditorDataSets.FACTORS), Roll[]::new), false),
                    (a, v) -> a.rolls = v, a -> a.rolls, (a, parent) -> a.rolls = parent.rolls)
            .documentation("The rolls this table contributes. Authoring this in a file with a Parent REPLACES "
                    + "the parent's rolls entirely rather than adding to them.").add()
            .appendInherited(new KeyedCodec<>("Pool", LootPool.codec(EditorDataSets.FACTORS), false),
                    (a, v) -> a.pool = v, a -> a.pool, (a, parent) -> a.pool = parent.pool)
            .documentation("A bag of competing outcomes, of which only as many as the pool's own Rolls formula "
                    + "works out to are drawn. Use it for the part of a payout that varies; use the Rolls list "
                    + "above for the part everybody gets.").add()
            .appendInherited(new KeyedCodec<>("ContributesTo", Codec.STRING, false),
                    (a, v) -> a.contributesTo = v, a -> a.contributesTo,
                    (a, parent) -> a.contributesTo = parent.contributesTo)
            .documentation("Another table's id to fold this file's rolls and pool entries INTO, on top of what "
                    + "that table already has. The way to enrich a table shipped by someone else without "
                    + "owning its file. How many picks the merged pool makes stays the target's decision.").add()
            .build();

    /**
     * The codec for a leaf that accepts EITHER a plain {@code "<lootableId>"} reference OR a whole
     * table written inline (which may carry its own {@code Parent}). Inline is for a table used at
     * exactly one site; name it as a file the moment a second site wants it.
     */
    @Nonnull
    public static final Codec<String> CHILD_ASSET_CODEC =
            new ContainedAssetCodec<>(LootableAsset.class, CODEC);

    public LootableAsset() {
    }

    /** Java-side construction path; sets the same fields the codec fills. */
    @Nonnull
    public static LootableAsset of(@Nonnull String id, @Nullable Roll[] rolls) {
        return of(id, rolls, null, null);
    }

    /** The full Java-side construction path; sets the same fields the codec fills. */
    @Nonnull
    public static LootableAsset of(@Nonnull String id, @Nullable Roll[] rolls, @Nullable LootPool pool,
            @Nullable String contributesTo) {
        LootableAsset a = new LootableAsset();
        a.id = id.toLowerCase(Locale.ROOT);
        a.rolls = rolls;
        a.pool = pool;
        a.contributesTo = contributesTo;
        return a;
    }

    @Override
    public String getId() {
        return id;
    }

    @Nullable
    public Roll[] getRolls() {
        return rolls;
    }

    /** The competing-outcome half of this table, or null when it declares none. */
    @Nullable
    public LootPool getPool() {
        return pool;
    }

    /**
     * The id of the table this file's content is folded INTO, or null when it stands on its own.
     * Resolution applies it after the ordinary id layering, so a contributor enriches whichever
     * version of the target actually won.
     */
    @Nullable
    public String getContributesTo() {
        return contributesTo;
    }

    /** This table's rolls as a list, empty when it authors none. */
    @Nonnull
    public List<Roll> rollsOrEmpty() {
        return rolls == null ? List.of() : Arrays.asList(rolls);
    }

    /** This table's pool as a list of one, empty when it declares none - the shape a pass takes. */
    @Nonnull
    public List<LootPool> poolOrEmpty() {
        return pool == null ? List.of() : List.of(pool);
    }
}
