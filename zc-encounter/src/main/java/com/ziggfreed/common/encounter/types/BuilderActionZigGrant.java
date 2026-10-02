package com.ziggfreed.common.encounter.types;

import java.io.IOException;
import java.util.EnumSet;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.hypixel.hytale.server.npc.asset.builder.Builder;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.InstructionType;
import com.hypixel.hytale.server.npc.asset.builder.holder.BooleanHolder;
import com.hypixel.hytale.server.npc.corecomponents.builders.BuilderActionBase;
import com.hypixel.hytale.server.npc.instructions.Action;
import com.ziggfreed.common.encounter.event.Encounters;
import com.ziggfreed.common.loot.LootRef;
import com.ziggfreed.common.util.SafeLog;

/**
 * Builds {@link ActionZigGrant}: {@code {"Type": "ZigGrant", "Loot": {...}, "ToMembers": true,
 * "ToKiller": false, "QueueIfOffline": true}}.
 *
 * <p>{@code Loot} is the library's loot reference group (shared tables by id, rolls written inline,
 * or both), kept as authored JSON and decoded ONCE, when the script loads, in the asset context the
 * engine hands the script's builders ({@link #decodeLoot}). A table named by id is a contained-asset
 * reference, which decodes only in an asset context, and a whole table written inline loads with the
 * script exactly as one written in a binding row's {@code Loot} loads with the row. A Loot that
 * cannot be read is warned about once at load, naming the script, and the grant pays nothing.
 */
public class BuilderActionZigGrant extends BuilderActionBase {

    /** The key the loot group sits under. */
    private static final String LOOT = "Loot";

    protected final BooleanHolder toMembers = new BooleanHolder();
    protected final BooleanHolder toKiller = new BooleanHolder();
    protected final BooleanHolder queueIfOffline = new BooleanHolder();
    @Nullable protected JsonElement loot;
    @Nullable private LootRef lootRef;
    private boolean lootUnreadable;

    @Nonnull
    @Override
    public String getShortDescription() {
        return "Pay the encounter's credited participants a loot reference, scaled by each one's share";
    }

    @Nonnull
    @Override
    public String getLongDescription() {
        return "Settles the run's participation credit and pays each credited participant the Loot group "
                + "(Lootables by id and/or inline Rolls), keeping each roll with a chance equal to that "
                + "participant's share of the top contributor's. ToKiller adds the last hitter at a full share; "
                + "QueueIfOffline parks an offline participant's payout for their next connect.";
    }

    @Nonnull
    @Override
    public Action build(@Nonnull BuilderSupport builderSupport) {
        return new ActionZigGrant(this, builderSupport);
    }

    @Nonnull
    @Override
    public BuilderDescriptorState getBuilderDescriptorState() {
        return BuilderDescriptorState.Stable;
    }

    @Nonnull
    @Override
    public Builder<Action> readConfig(@Nonnull JsonElement data) {
        getBoolean(data, "ToMembers", toMembers, true, BuilderDescriptorState.Stable,
                "Pay every credited participant", null);
        getBoolean(data, "ToKiller", toKiller, false, BuilderDescriptorState.Stable,
                "Also pay whoever landed the killing blow, at a full share", null);
        getBoolean(data, "QueueIfOffline", queueIfOffline, true, BuilderDescriptorState.Stable,
                "Park an offline participant's payout for their next connect", null);
        // Read through the helper so the key counts as one this builder asked for.
        this.loot = getOptionalJsonElement(data, LOOT);
        readLoot();
        requireInstructionType(EnumSet.of(InstructionType.Encounter, InstructionType.EncounterStateTransitions));
        return this;
    }

    /**
     * Decodes the authored Loot once, under the key it sits at in the builder's own asset context,
     * the way the engine reads a codec field of a builder (so a whole table written inline gets the
     * same generated id, and loads, as one in an engine field would).
     */
    private void readLoot() {
        this.lootRef = null;
        this.lootUnreadable = false;
        if (loot == null || loot.isJsonNull()) {
            return;
        }
        if (extraInfo != null) {
            extraInfo.pushKey(LOOT);
        }
        try {
            this.lootRef = decodeLoot(loot, extraInfo, fileName == null ? EncounterTypes.GRANT : fileName);
        } catch (IllegalArgumentException e) {
            this.lootUnreadable = true;
            SafeLog.warn(Encounters.LOG_PREFIX + " the " + EncounterTypes.GRANT + " Loot in "
                    + (fileName == null ? "an encounter script" : fileName)
                    + " could not be read, so that grant pays nothing: " + e.getMessage());
        } finally {
            if (extraInfo != null) {
                extraInfo.popKey();
            }
        }
    }

    public boolean getToMembers(@Nonnull BuilderSupport support) {
        return toMembers.get(support.getExecutionContext());
    }

    public boolean getToKiller(@Nonnull BuilderSupport support) {
        return toKiller.get(support.getExecutionContext());
    }

    public boolean getQueueIfOffline(@Nonnull BuilderSupport support) {
        return queueIfOffline.get(support.getExecutionContext());
    }

    /** The Loot as authored, or null when none is. */
    @Nullable
    public JsonElement getLoot() {
        return loot;
    }

    /** The Loot as decoded when the script loaded, or null when none is authored or it could not be read. */
    @Nullable
    public LootRef getLootRef() {
        return lootRef;
    }

    /** True when a Loot is authored but could not be read when the script loaded (warned there once). */
    public boolean isLootUnreadable() {
        return lootUnreadable;
    }

    /**
     * Reads a {@code ZigGrant}'s authored Loot through the loot group's own codec: the ONE decode the
     * action and the encounter audit share. A table named by id is a contained-asset reference, which
     * the engine decodes only in an asset context, so a context that is not one (or none) is replaced
     * by a detached asset context keyed on {@code key}. A whole table written inline loads only
     * where the context's load loads what it gathers (a script's builders, whose load does); in a
     * detached context it decodes to a generated id nothing loads.
     *
     * @param raw     the authored Loot, or null
     * @param context the asset context to decode in, or null for a detached one
     * @param key     what a detached context is keyed on (the script), seeding any generated id
     * @return the loot, or null when none is authored
     * @throws IllegalArgumentException when the Loot cannot be read, saying why
     */
    @Nullable
    public static LootRef decodeLoot(@Nullable JsonElement raw, @Nullable ExtraInfo context, @Nonnull String key) {
        if (raw == null || raw.isJsonNull()) {
            return null;
        }
        ExtraInfo info = context instanceof AssetExtraInfo ? context
                : new AssetExtraInfo<>(new AssetExtraInfo.Data(null, key, null));
        try {
            return LootRef.CODEC.decodeJson(RawJsonReader.fromJsonString(raw.toString()), info);
        } catch (IOException | RuntimeException e) {
            String why = e.getMessage();
            throw new IllegalArgumentException(why == null || why.isBlank() ? e.getClass().getSimpleName() : why, e);
        }
    }
}
