package com.ziggfreed.common.stats.condition;

import java.time.Instant;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.map.AssetMapWithIndexes;
import com.hypixel.hytale.builtin.tagset.TagSetPlugin;
import com.hypixel.hytale.builtin.tagset.config.NPCGroup;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.modules.entity.condition.Condition;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.ziggfreed.common.asset.EditorSchema;

/**
 * An entity condition: is the entity a creature of a foe family, a member of any of the game's own NPC groups
 * ({@code Server/NPC/Groups/}) named under {@code Groups}? Read as the engine's own kill objectives read it: the
 * creature's role index in the group's flattened member set ({@code TagSetPlugin}, two hash lookups, no allocation).
 * Registered into the engine's entity condition map as {@value #TYPE_NAME} (R700).
 *
 * <p>A family is a native NPC group: the library ships one per foe family ({@code Zig_Foe_Undead} includes vanilla's
 * {@code Undead} and the undead roles outside it), so a pack retunes or extends a family by overriding that group's
 * file. A role has no {@code Tags} of its own to opt in with, so a group is the one handle. A family never holds a
 * boss or an elite (R672): every shipped one excludes vanilla's boss, elite and encounter-target groups
 * ({@code ExcludeGroups}) and their role names ({@code ExcludeRoles}, applied last, so a family's own
 * {@code IncludeRoles} cannot bring one back), and an override keeps both lists.
 *
 * <pre>{@code
 * { "Id": "ZigFoeFamily", "Groups": [ "Zig_Foe_Undead" ] }
 * }</pre>
 */
public final class ZigFoeFamilyCondition extends Condition {

    /** The authored {@code "Id"} name. */
    public static final String TYPE_NAME = "ZigFoeFamily";

    @Nullable
    private String[] groups;

    /** Each named group's index once found ({@link AssetMapWithIndexes#NOT_FOUND} until then). */
    @Nullable
    private volatile int[] resolved;

    private static final class Holder {
        static final BuilderCodec<ZigFoeFamilyCondition> CODEC = BuilderCodec.builder(
                        ZigFoeFamilyCondition.class, ZigFoeFamilyCondition::new, Condition.BASE_CODEC)
                .documentation("Holds while the entity is a creature in any of the NPC groups named.")
                .append(new KeyedCodec<>("Groups", Codec.STRING_ARRAY, false), (c, v) -> c.groups = v, c -> c.groups)
                .metadata(EditorSchema.assetRef(NPCGroup.class))
                .documentation("NPC group ids (Server/NPC/Groups file names): the library ships one per foe family, "
                        + "Zig_Foe_<Family>. A group this server does not have counts nothing.")
                .add()
                .build();
    }

    /** The codec, built on first call. Only registration and a codec test invoke this. */
    @Nonnull
    public static BuilderCodec<ZigFoeFamilyCondition> codec() {
        return Holder.CODEC;
    }

    @Override
    public boolean eval0(@Nonnull ComponentAccessor<EntityStore> componentAccessor, @Nonnull Ref<EntityStore> ref,
            @Nonnull Instant currentTime) {
        if (groups == null || groups.length == 0) {
            return false;
        }
        try {
            NPCEntity npc = componentAccessor.getComponent(ref, NPCEntity.getComponentType());
            if (npc == null) {
                return false;
            }
            int role = npc.getNPCTypeIndex();
            TagSetPlugin.TagSetLookup lookup = TagSetPlugin.get(NPCGroup.class);
            int[] indexes = indexes();
            for (int index : indexes) {
                if (index != AssetMapWithIndexes.NOT_FOUND && lookup.tagInSet(index, role)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** The groups' indexes, looked up again while any is still missing (a group a later pack ships). */
    @Nonnull
    private int[] indexes() {
        int[] current = resolved;
        if (current != null) {
            return current;
        }
        String[] named = groups == null ? new String[0] : groups;
        int[] out = new int[named.length];
        boolean all = true;
        for (int i = 0; i < named.length; i++) {
            out[i] = named[i] == null || named[i].isBlank() ? AssetMapWithIndexes.NOT_FOUND
                    : NPCGroup.getAssetMap().getIndex(named[i].trim());
            all &= out[i] != AssetMapWithIndexes.NOT_FOUND;
        }
        if (all) {
            resolved = out;
        }
        return out;
    }
}
