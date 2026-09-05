package com.ziggfreed.common.encounter.validate;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.ziggfreed.common.validation.Finding;

/**
 * A pack prefab read the way the builder's prefab page and {@code /paste} set it down: each block
 * entry is placed with ONLY the state the file carries. A block whose type keeps a spawn marker
 * alive (a {@code BlockEntity.Components.SpawnMarkerBlock} on the block type) therefore needs its
 * marker configuration written on the entry itself, under {@code components}; a bare entry gives
 * a decorative block with no block entity and no marker, and a script's {@code TriggerSpawners}
 * then finds nothing and says nothing. A hand-placed block, and a plugin paste through
 * {@code PrefabUtil}, clone the type's own template and never show the gap, which is why a fight
 * can work in one world and stand silent in another.
 *
 * <p>Pure: it walks a prefab's JSON and asks, per block name, whether the type is a spawner. The
 * audit supplies that answer off the loaded block types; a test supplies its own.
 */
public final class EncounterPrefabAudit {

    static final String BLOCKS = "blocks";
    static final String NAME = "name";
    static final String COMPONENTS = "components";

    private EncounterPrefabAudit() {
    }

    /**
     * Audit one prefab.
     *
     * @param prefabId     the prefab's id as a pack names it (its path under {@code Server/Prefabs},
     *                     without the suffix), the finding's source
     * @param root         the prefab file's root object
     * @param spawnerBlock answers whether a block type keeps a spawn marker alive
     */
    @Nonnull
    public static List<Finding> audit(@Nonnull String prefabId, @Nonnull JsonObject root,
            @Nonnull Predicate<String> spawnerBlock) {
        List<Finding> findings = new ArrayList<>();
        JsonElement blocks = root.get(BLOCKS);
        if (blocks == null || !blocks.isJsonArray()) {
            return findings;
        }
        for (JsonElement element : blocks.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject block = element.getAsJsonObject();
            String name = string(block.get(NAME));
            if (name == null || !spawnerBlock.test(name)) {
                continue;
            }
            JsonElement components = block.get(COMPONENTS);
            if (components != null && components.isJsonObject()) {
                continue;
            }
            findings.add(Finding.warning(EncounterValidator.DOMAIN, EncounterValidator.PREFAB_SPAWNER_WITHOUT_STATE,
                    "The block '" + name + "' at " + position(block) + " keeps a spawn marker alive, but this "
                    + "entry carries no components state, so a paste from the builder's prefab page (or /paste) "
                    + "sets down a bare block with no block entity and no marker; a script's TriggerSpawners then "
                    + "finds nothing and says nothing. Save the prefab from a world where the block was placed "
                    + "by hand, or write components.Components.SpawnMarkerBlock.Config (SpawnMarker and "
                    + "MarkerOffset) on the entry.", prefabId));
        }
        return findings;
    }

    @Nullable
    private static String string(@Nullable JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                ? element.getAsString() : null;
    }

    @Nonnull
    private static String position(@Nonnull JsonObject block) {
        return "(" + number(block.get("x")) + ", " + number(block.get("y")) + ", " + number(block.get("z")) + ")";
    }

    @Nonnull
    private static String number(@Nullable JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()
                ? element.getAsString() : "?";
    }
}
