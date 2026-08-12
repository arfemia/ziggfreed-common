package com.ziggfreed.common.loot;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.ziggfreed.common.asset.EditorDataSets;

/**
 * The ONE way to say "loot happens here": shared tables by id, rolls written inline, or both.
 * Wherever content references loot, it is this group, so the shape is the same everywhere an author
 * meets it.
 *
 * <pre>{@code
 * "Loot": {
 *   "Lootables": [ "forestfinds" ],
 *   "Rolls": [ { "Chance": { "Base": 2 }, "Grants": { "Items": [ { "Item": "Gem_Ruby" } ] } } ]
 * }
 * }</pre>
 *
 * <p>Both leaves resolve together and in that order: every referenced table's rolls first, then the
 * inline ones. Reach for a table when more than one site wants the same outcomes, and for an inline
 * roll when the outcome belongs to this site alone - an inline roll beside a reference is also the
 * way to ADD to a shared table without owning its file.
 *
 * <p>Referenced ids are matched without regard to case. An id nothing answers to is skipped at
 * runtime, and the validator reports it at authoring time rather than leaving a quiet hole.
 */
public final class LootRef {

    @Nullable protected String[] lootables;
    @Nullable protected Roll[] rolls;

    public static final BuilderCodec<LootRef> CODEC = BuilderCodec.builder(LootRef.class, LootRef::new)
            .appendInherited(new KeyedCodec<>("Lootables",
                            new ArrayCodec<>(LootableAsset.CHILD_ASSET_CODEC, String[]::new), false),
                    (o, v) -> o.lootables = v, o -> o.lootables, (o, p) -> o.lootables = p.lootables)
            .documentation("Shared loot table ids; each table's rolls evaluate here. An entry may instead be a "
                    + "whole table written inline, but a table's Rolls replace rather than append on inherit, so "
                    + "use the sibling Rolls leaf below to ADD to a shared table.").add()
            .appendInherited(new KeyedCodec<>("Rolls",
                            new ArrayCodec<>(Roll.codec(EditorDataSets.FACTORS), Roll[]::new), false),
                    (o, v) -> o.rolls = v, o -> o.rolls, (o, p) -> o.rolls = p.rolls)
            .documentation("Rolls written directly here, evaluated after any referenced tables'.").add()
            .build();

    public LootRef() {
    }

    /** Java-side factory; sets the same fields the codec fills. */
    @Nonnull
    public static LootRef of(@Nullable String[] lootables, @Nullable Roll[] rolls) {
        LootRef ref = new LootRef();
        ref.lootables = lootables;
        ref.rolls = rolls;
        return ref;
    }

    @Nullable
    public String[] getLootables() {
        return lootables;
    }

    @Nullable
    public Roll[] getRolls() {
        return rolls;
    }

    /** True when neither leaf is authored - an empty ref rolls nothing, same as an absent one. */
    public boolean isEmpty() {
        return (lootables == null || lootables.length == 0) && (rolls == null || rolls.length == 0);
    }
}
