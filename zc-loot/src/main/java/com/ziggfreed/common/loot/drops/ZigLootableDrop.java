package com.ziggfreed.common.loot.drops;

import java.util.List;
import java.util.Set;
import java.util.function.DoubleSupplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemDrop;
import com.hypixel.hytale.server.core.asset.type.item.config.container.ItemDropContainer;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.loot.LootableAsset;

/**
 * One of our loot tables inside a native drop list: a container {@code Type} registered into the engine's own
 * {@link ItemDropContainer#CODEC}, so a block's, an NPC's or a chest's drop list ({@code Server/Drops/}) rolls a
 * {@code Lootable} inline, beside its own containers, and the engine spawns what it rolled as it spawns its own drops.
 *
 * <pre>{@code
 * // Server/Drops/NPCs/Intelligent/Trork/Drop_Trork_Warrior.json, one container more in its Multiple
 * {
 *   "Container": {
 *     "Type": "Multiple",
 *     "Containers": [
 *       { "Type": "Choice", "Weight": 100, "Containers": [ ... ] },
 *       { "Type": "ZigLootable", "Weight": 100, "Lootable": "Trork_Spoils" }
 *     ]
 *   }
 * }
 * }</pre>
 *
 * <p>The table rolls as it rolls anywhere ({@link DropRoll}): its {@code Requires}, each roll's {@code Conditions},
 * {@code Chance} and {@code Ladder}, then its {@code Pool}, against the player the drop is for ({@link DropContext}:
 * the killer, or the breaker, asked through the table's gate query), with the engine's own random source. What it
 * drops: its items, its native drop lists rolled into the same drops, and a nested {@code Lootable} reward's table;
 * every other row pays nothing here ({@code reward/DropRows}), which {@link DropAudit} warns of once the stores load.
 * With nobody known, a gated row refuses and the rest pays. {@code Weight} is the engine's own, read as every
 * container's is.
 *
 * <p>The engine's own check that a drop list can drop something asks {@link #getAllDrops}, which answers the table's
 * items, and one entry naming no item when it can name none (a table not loaded yet, or one that drops no item), so
 * the engine never refuses a whole drop list over our table: the audit says why instead.
 *
 * <p>Registered at setup, unguarded, from {@code LootDropsBootstrap}: a registration that fails throws there, never
 * as an unknown {@code Type} inside a pack's drop list later. A pack whose drop list names this Type depends on
 * {@code Ziggfreed:ZiggfreedCommon}.
 */
public final class ZigLootableDrop extends ItemDropContainer {

    /** The {@code Type} a drop list's container writes to be one of these. */
    public static final String TYPE = "ZigLootable";

    @Nullable private String lootable;

    public static final BuilderCodec<ZigLootableDrop> CODEC =
            BuilderCodec.builder(ZigLootableDrop.class, ZigLootableDrop::new, ItemDropContainer.DEFAULT_CODEC)
                    .documentation("One of the loot tables under Server/ZiggfreedCommon/Lootables, rolled inside this "
                            + "drop list. The table rolls for the player who killed or broke what dropped, so its "
                            + "Requires and each roll's Conditions ask about them; with nobody to ask (a drop no "
                            + "player caused), a roll that asks about a player pays nothing and the rest still pays. "
                            + "It drops the table's items, its native drop lists and any table a Lootable reward "
                            + "names. A command or any other reward in the table pays nothing in a drop list, and "
                            + "the server log says so when it starts.")
                    .append(new KeyedCodec<>("Lootable", Codec.STRING, false),
                            (o, v) -> o.lootable = v, o -> o.lootable)
                    .metadata(EditorSchema.assetRef(LootableAsset.class))
                    .documentation("The loot table to roll, by id (a file under Server/ZiggfreedCommon/Lootables, "
                            + "any case). Unauthored, or naming no table, it drops nothing.")
                    .add()
                    .afterDecode(ZigLootableDrop::noteNamed)
                    .build();

    public ZigLootableDrop() {
    }

    /** The table this container rolls, trimmed, or null when it names none. */
    @Nullable
    public String tableId() {
        return lootable == null || lootable.isBlank() ? null : lootable.trim();
    }

    @Override
    protected void populateDrops(@Nonnull List<ItemDrop> drops, @Nonnull DoubleSupplier chanceProvider,
            @Nonnull Set<String> droplistReferences) {
        String table = tableId();
        if (table == null) {
            return;
        }
        DropRoll.roll(drops, chanceProvider, table, null, DropContext.whoFor(droplistReferences));
    }

    @Nonnull
    @Override
    public List<ItemDrop> getAllDrops(@Nonnull List<ItemDrop> list) {
        DropRoll.everyDrop(list, tableId());
        return list;
    }

    /**
     * Every container decoded wakes the drop credit ({@link DropContext#live}), and one decoded inside an asset (a drop
     * list file) is noted with that file's id, for the audit.
     */
    private static void noteNamed(@Nonnull ZigLootableDrop container, @Nullable ExtraInfo extraInfo) {
        DropContext.markLive();
        if (!(extraInfo instanceof AssetExtraInfo<?> asset)) {
            return;
        }
        Object key = asset.getKey();
        DropAudit.noteNamed(container.tableId(), key == null ? null : key.toString());
    }

    @Nonnull
    @Override
    public String toString() {
        return "ZigLootableDrop{lootable='" + lootable + "', weight=" + weight + '}';
    }
}
