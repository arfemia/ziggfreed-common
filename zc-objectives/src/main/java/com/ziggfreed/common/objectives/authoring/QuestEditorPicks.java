package com.ziggfreed.common.objectives.authoring;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3i;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.asset.EditorDataSets;
import com.ziggfreed.common.asset.EditorPick;
import com.ziggfreed.common.authoring.Pickers;
import com.ziggfreed.common.authoring.WorldPick;
import com.ziggfreed.common.npc.NpcIdentities;
import com.ziggfreed.common.progress.LocationTarget;
import com.ziggfreed.common.progress.ObjectiveKindRegistry;
import com.ziggfreed.common.progress.asset.ObjectiveKindPicks;
import com.ziggfreed.common.progress.asset.ProgressEditorDataSets;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.util.SafeLog;

/**
 * What the quest editor's lists offer and what its "Pick in the world" buttons read, each from the live registries
 * at the moment the step paints: the objective and reward kinds from the shared vocabularies, a step's targets from
 * its kind file's {@code Pick} (the one the Asset Editor offers), a reward parameter's from its kind's description,
 * the characters, the quests and the factors.
 */
final class QuestEditorPicks {

    /** The asset type the NPC roles are named by. */
    private static final String NPC_ROLE = "NPCRole";
    private static final String BLOCK_TYPE = "BlockType";
    private static final String ITEM = "Item";

    private QuestEditorPicks() {
    }

    /** Every objective kind content may author on this server. */
    @Nonnull
    static Pickers.Source objectiveKinds() {
        return Pickers.of(ProgressEditorDataSets::objectiveKindIds);
    }

    /** Every reward kind on this server, as its owner spells it. */
    @Nonnull
    static Pickers.Source rewardKinds() {
        return Pickers.of(ProgressEditorDataSets::rewardKindIds);
    }

    /** Every character id a character file or placement declares. */
    @Nonnull
    static Pickers.Source characters() {
        return Pickers.of(NpcIdentities::allDeclaredNpcIds);
    }

    /** Every quest on this server, by id. */
    @Nonnull
    static Pickers.Source quests() {
        return () -> {
            List<String> ids = new ArrayList<>();
            for (Quest quest : ProgressionRuntime.quests().quests()) {
                ids.add(quest.id());
            }
            return ids;
        };
    }

    /** Every factor a requirement may read. */
    @Nonnull
    static Pickers.Source factors() {
        return () -> {
            Collection<String> ids = EditorDataSets.values(EditorDataSets.FACTORS);
            return ids == null ? List.of() : ids;
        };
    }

    /**
     * What a step of {@code kind} may count, from its kind file's {@code Pick}: the ids it lists, and before them each
     * id's first word ({@code Skeleton} for {@code Skeleton_Fighter}), which a step's default match counts every one
     * of. Null when the kind names no pick, so the step takes a typed target.
     */
    @Nullable
    static Pickers.Source targets(@Nonnull String kind) {
        Pickers.Source listed = of(targetPick(kind));
        if (listed == null) {
            return null;
        }
        return () -> {
            Collection<String> ids = listed.ids();
            Set<String> out = new LinkedHashSet<>();
            for (String id : ids) {
                int cut = id == null ? -1 : id.indexOf('_');
                if (cut > 0) {
                    out.add(id.substring(0, cut));
                }
            }
            out.addAll(ids);
            return out;
        };
    }

    /** What {@code pick} offers, read fresh each paint, or null when it offers nothing. */
    @Nullable
    static Pickers.Source of(@Nullable EditorPick pick) {
        if (pick == null || pick.isEmpty()) {
            return null;
        }
        List<String> closed = pick.getValues();
        if (closed != null && !closed.isEmpty()) {
            return Pickers.of(closed.toArray(String[]::new));
        }
        String asset = pick.getAsset();
        if (asset != null) {
            return () -> assetIds(asset);
        }
        String list = pick.getList();
        return list == null ? null : () -> listed(list);
    }

    /** The loaded ids of an asset type, as the editor names it. */
    @Nonnull
    private static Collection<String> assetIds(@Nonnull String type) {
        Collection<String> ids = EditorDataSets.values(EditorDataSets.assetIds(type));
        if (ids != null) {
            return ids;
        }
        return ITEM.equals(type) ? Pickers.items().ids() : List.of();
    }

    @Nonnull
    private static Collection<String> listed(@Nonnull String dataSet) {
        Collection<String> ids = EditorDataSets.values(dataSet);
        return ids == null ? List.of() : ids;
    }

    @Nullable
    private static EditorPick targetPick(@Nonnull String kind) {
        try {
            return ObjectiveKindPicks.targetPick(kind);
        } catch (Throwable t) {
            SafeLog.fine("[quest-editor] the target pick of " + kind + " could not be read: " + t.getMessage());
            return null;
        }
    }

    /** The asset type a pick names, by its {@code Asset} or its {@code ziggfreedcommon:ids/<Type>} list; else null. */
    @Nullable
    private static String assetType(@Nullable EditorPick pick) {
        if (pick == null) {
            return null;
        }
        if (pick.getAsset() != null) {
            return pick.getAsset();
        }
        String list = pick.getList();
        return list != null && list.startsWith(EditorDataSets.ASSET_IDS) ? list.substring(EditorDataSets.ASSET_IDS.length())
                : null;
    }

    // ---------------------------------------------------------------- what the world fills

    /**
     * What "Pick in the world" reads for a step of {@code kind}: the character for a step naming characters, the spot
     * for a place step with no list, the creature, the block or the item for a kind whose pick lists those; null when
     * the world has nothing to give it.
     */
    @Nullable
    static Function<WorldPick, String> reading(@Nonnull String kind) {
        EditorPick pick = targetPick(kind);
        if (pick != null && EditorDataSets.NPC_IDS.equalsIgnoreCase(String.valueOf(pick.getList()))) {
            return QuestEditorPicks::characterOf;
        }
        ObjectiveKindRegistry registry = ProgressionRuntime.objectiveKinds();
        if (pick == null && registry.isPlaceTargeted(kind)) {
            return QuestEditorPicks::spotOf;
        }
        String type = assetType(pick);
        if (NPC_ROLE.equals(type)) {
            return WorldPick::npcRole;
        }
        if (BLOCK_TYPE.equals(type)) {
            return WorldPick::blockId;
        }
        return ITEM.equals(type) ? WorldPick::itemId : null;
    }

    /** True for a step that names a place, so it needs a target. */
    static boolean namesPlace(@Nonnull String kind) {
        return ProgressionRuntime.objectiveKinds().isPlaceTargeted(kind);
    }

    /** True for a place step whose target is a spot, {@code x,y,z} (no list of places to pick from). */
    static boolean namesSpot(@Nonnull String kind) {
        return namesPlace(kind) && targetPick(kind) == null;
    }

    /** Who the picked entity is, the id a quest names a character by; null for no character. World thread. */
    @Nullable
    static String characterOf(@Nonnull WorldPick pick) {
        Ref<EntityStore> entity = pick.entity();
        if (entity == null || !entity.isValid()) {
            return null;
        }
        try {
            return NpcIdentities.npcIdOfEntity(entity.getStore(), entity);
        } catch (Throwable t) {
            SafeLog.fine("[quest-editor] the picked character could not be read: " + t.getMessage());
            return null;
        }
    }

    /** The spot above the picked block, {@code x,y,z}, where a player stands to reach it; null for no block. */
    @Nullable
    static String spotOf(@Nonnull WorldPick pick) {
        Vector3i block = pick.block();
        return block == null ? null : String.format(Locale.ROOT, "%d,%d,%d", block.x, block.y + 1, block.z);
    }

    /**
     * True when {@code spot} reads as {@code x,y,z} with an optional radius (a world name before a colon allowed):
     * the one parse the library's reach-location producer and validators read ({@link LocationTarget#parse}).
     */
    static boolean isSpot(@Nullable String spot) {
        return LocationTarget.parse(spot) != null;
    }
}
