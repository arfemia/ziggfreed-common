package com.ziggfreed.common.reputation.authoring;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.builtin.adventure.reputation.assets.ReputationGroup;
import com.hypixel.hytale.builtin.tagset.config.NPCGroup;
import com.ziggfreed.common.content.ContentProblem;
import com.ziggfreed.common.content.ContentRefusedException;
import com.ziggfreed.common.content.ServerContent;
import com.ziggfreed.common.reputation.ReputationNative;
import com.ziggfreed.common.reputation.ReputationRuntime;
import com.ziggfreed.common.reputation.asset.ReputationAsset;

/**
 * The reputation editor's reads and writes of the server content pack, every one through {@link ServerContent}:
 * which reputations the pack holds, the three files of one ({@link ReputationDraft.Saved}), and a save's writes in
 * the one order the engine can take them. Any thread for {@link #write}; the world thread for the rest.
 */
final class ReputationFiles {

    private ReputationFiles() {
    }

    /** The companion store ({@code ZiggfreedCommon/Reputations}). */
    @Nonnull
    static AssetStore<String, ReputationAsset, DefaultAssetMap<String, ReputationAsset>> companions() {
        return AssetRegistry.getAssetStore(ReputationAsset.class);
    }

    /** The id of every reputation whose native group the server content pack holds: what the editor can open. */
    @Nonnull
    static List<String> held() {
        try {
            return ServerContent.ids(ReputationGroup.getAssetStore());
        } catch (Throwable t) {
            return List.of();
        }
    }

    /** The three files of reputation {@code id} as the pack holds them, or null when it holds no group of that id. */
    @Nullable
    static ReputationDraft.Saved read(@Nonnull String id) {
        JsonObject group = parse(ServerContent.read(ReputationGroup.getAssetStore(), id));
        if (group == null) {
            return null;
        }
        JsonObject companion = parse(ServerContent.read(companions(), id));
        String memberGroupId = null;
        JsonObject memberGroup = null;
        for (String entry : ReputationDraft.npcGroupsOf(group)) {
            if (ReputationDraft.isMemberGroupOf(id, entry)) {
                JsonObject doc = parse(ServerContent.read(NPCGroup.getAssetStore(), entry));
                if (doc != null) {
                    memberGroupId = entry;
                    memberGroup = doc;
                    break;
                }
            }
        }
        return new ReputationDraft.Saved(id, group, companion == null ? new JsonObject() : companion, memberGroupId,
                memberGroup);
    }

    /**
     * Write {@code plan}'s files in the one order the engine can take them: the member group first; the native
     * group only once the member group has landed and every NPC group it names is loaded (the engine throws on every
     * NPC added while a group names one it lacks, so a group that would is refused, never written); the companion
     * last, so its fold builds the reputation's track over a group that is there. A refusal anywhere stops the rest
     * and completes the future with it.
     */
    @Nonnull
    static CompletableFuture<Void> write(@Nonnull ReputationDraft.Plan plan) {
        CompletableFuture<Void> first = plan.memberGroup() == null || plan.memberGroupId() == null
                ? CompletableFuture.completedFuture(null)
                : ServerContent.write(NPCGroup.getAssetStore(), plan.memberGroupId(), plan.memberGroup().toString())
                        .completion();
        return first.thenCompose(done -> groupThenCompanion(plan));
    }

    @Nonnull
    private static CompletableFuture<Void> groupThenCompanion(@Nonnull ReputationDraft.Plan plan) {
        ReputationNative engine = ReputationRuntime.service().engine();
        List<String> missing = new ArrayList<>();
        for (String npcGroup : ReputationDraft.npcGroupsOf(plan.group())) {
            if (!engine.npcGroupExists(npcGroup)) {
                missing.add(npcGroup);
            }
        }
        if (!missing.isEmpty()) {
            return CompletableFuture.failedFuture(new ContentRefusedException(ContentProblem.REJECTED, "the reputation "
                    + "group " + plan.id() + " would name NPC groups the server has not loaded: " + missing));
        }
        return ServerContent.write(ReputationGroup.getAssetStore(), plan.id(), plan.group().toString()).completion()
                .thenCompose(done -> ServerContent.write(companions(), plan.id(), plan.companion().toString())
                        .completion());
    }

    @Nullable
    private static JsonObject parse(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonElement doc = JsonParser.parseString(json);
            return doc != null && doc.isJsonObject() ? doc.getAsJsonObject() : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
