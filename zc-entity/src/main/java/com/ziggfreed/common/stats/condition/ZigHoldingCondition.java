package com.ziggfreed.common.stats.condition;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.condition.Condition;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.entity.HeldItemUtil;
import com.ziggfreed.common.inventory.ItemLineage;
import com.ziggfreed.common.match.MatchKey;
import com.ziggfreed.common.match.MatchSets;

/**
 * An entity condition about what the entity holds in its active hotbar slot: with {@code Block}, a block it could
 * place; with {@code Tool}, a gathering tool (an item with a tool spec for any gather type: a pickaxe, a hatchet, a
 * shovel); with {@code Items}, an item one of the listed match keys names (zc-core's {@link MatchKey}: the game's own
 * {@code ItemTag:Family=Sword}, any item tag, or a name pattern read over the item's id and each {@code Parent}'s, so
 * a copy of a vanilla sword holds as the sword does). Each knob unauthored asks nothing; with none, it holds while
 * anything is held. Registered into the engine's entity condition map as {@value #TYPE_NAME} (R700), it is the one
 * "while holding" every bound grant, conditional stat and effect writes (R933, R934).
 *
 * <pre>{@code
 * { "Id": "ZigHolding", "Block": true }
 * { "Id": "ZigHolding", "Tool": true }
 * { "Id": "ZigHolding", "Items": [ "ItemTag:Family=Sword", "ItemTag:Family=Longsword" ] }
 * }</pre>
 */
public final class ZigHoldingCondition extends Condition {

    /** The authored {@code "Id"} name. */
    public static final String TYPE_NAME = "ZigHolding";

    @Nullable
    private Boolean block;
    @Nullable
    private Boolean tool;
    @Nullable
    private String[] items;

    /** {@link #items} parsed once, on the first read. */
    @Nullable
    private volatile List<MatchKey> keys;

    /**
     * Each held item id's answer, so a holder pass asking a few times a second for every bound grant costs a map
     * read: dropped whenever the match sets move ({@link MatchSets#generation}: an item or tag reload). Shared by
     * every entity the condition is asked about; the item ids a server holds are few.
     */
    private final Map<String, Boolean> byItem = new ConcurrentHashMap<>();
    private volatile long byItemGeneration = Long.MIN_VALUE;

    private static final class Holder {
        static final BuilderCodec<ZigHoldingCondition> CODEC = BuilderCodec.builder(
                        ZigHoldingCondition.class, ZigHoldingCondition::new, Condition.BASE_CODEC)
                .documentation("Holds while what the entity holds in its active hotbar slot is as written.")
                .append(new KeyedCodec<>("Block", Codec.BOOLEAN, false), (c, v) -> c.block = v, c -> c.block)
                .documentation("True: only while the held item is a block it could place. False: only while it is "
                        + "not. Either when unauthored.")
                .add()
                .append(new KeyedCodec<>("Tool", Codec.BOOLEAN, false), (c, v) -> c.tool = v, c -> c.tool)
                .documentation("True: only while the held item is a gathering tool (a pickaxe, a hatchet, a shovel). "
                        + "False: only while it is not. Either when unauthored.")
                .add()
                .append(new KeyedCodec<>("Items", Codec.STRING_ARRAY, false), (c, v) -> c.items = v, c -> c.items)
                .documentation("Only while the held item matches one of these keys: the game's own item Family "
                        + "(ItemTag:Family=Sword), any item tag (ItemTag:<Tag>), or a name pattern over the item's id "
                        + "and each Parent's (Weapon_Sword_*). Any item when unauthored.")
                .add()
                .build();
    }

    /** The codec, built on first call. Only registration and a codec test invoke this. */
    @Nonnull
    public static BuilderCodec<ZigHoldingCondition> codec() {
        return Holder.CODEC;
    }

    /** A condition holding while the held item matches one of {@code items} (a test, a consumer's own default). */
    @Nonnull
    public static ZigHoldingCondition holdingAny(@Nonnull String... items) {
        ZigHoldingCondition condition = new ZigHoldingCondition();
        condition.items = items.clone();
        return condition;
    }

    @Override
    public boolean eval0(@Nonnull ComponentAccessor<EntityStore> componentAccessor, @Nonnull Ref<EntityStore> ref,
            @Nonnull Instant currentTime) {
        try {
            InventoryComponent.Hotbar hotbar = componentAccessor.getComponent(ref,
                    InventoryComponent.Hotbar.getComponentType());
            ItemStack held = hotbar == null ? null : hotbar.getActiveItem();
            if (held == null || held.isEmpty()) {
                return false;
            }
            Item item = held.getItem();
            if (item == null) {
                return false;
            }
            if (block != null && block != item.hasBlockType()) {
                return false;
            }
            if (tool != null && tool == HeldItemUtil.toolPowersOf(item).isEmpty()) {
                return false;
            }
            return items == null || items.length == 0 || remembered(held.getItemId());
        } catch (Throwable t) {
            return false;
        }
    }

    /** {@link #matchesItem} answered once per item id until the match sets move. */
    private boolean remembered(@Nullable String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return false;
        }
        long generation = MatchSets.generation();
        if (generation != byItemGeneration) {
            byItem.clear();
            byItemGeneration = generation;
        }
        return byItem.computeIfAbsent(itemId, this::matchesItem);
    }

    /**
     * Does the item {@code itemId} match one of the keys: a set key against the item itself (its tags carry its
     * parents'), a name pattern against its id and each {@code Parent}'s. Pure over the item and lineage reads.
     */
    boolean matchesItem(@Nullable String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return false;
        }
        List<MatchKey> parsed = parsed();
        List<String> lineage = null;
        for (MatchKey key : parsed) {
            if (key.isSet()) {
                if (key.matches(itemId, itemId.toLowerCase(Locale.ROOT))) {
                    return true;
                }
                continue;
            }
            if (lineage == null) {
                lineage = ItemLineage.of(itemId);
            }
            for (String id : lineage) {
                if (key.matches(id, id.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    @Nonnull
    private List<MatchKey> parsed() {
        List<MatchKey> cached = keys;
        if (cached != null) {
            return cached;
        }
        List<MatchKey> out = new ArrayList<>();
        if (items != null) {
            for (String authored : items) {
                MatchKey key = MatchKey.parse(authored);
                if (key != null) {
                    out.add(key);
                }
            }
        }
        keys = List.copyOf(out);
        return keys;
    }
}
