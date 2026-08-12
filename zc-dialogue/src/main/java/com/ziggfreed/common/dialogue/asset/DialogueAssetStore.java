package com.ziggfreed.common.dialogue.asset;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.dialogue.NpcDialogue;

/**
 * Every conversation the server has loaded, ready to open.
 *
 * <p>The reading happens in the asset store itself, so what lands here is already a decoded
 * conversation with its {@code Parent} merged in and its shared option groups spliced - there is no
 * second parse and no per-mod decode step. The layer is rebuilt WHOLESALE from each load event, so a
 * hot re-import is idempotent.
 *
 * <p>A mod asks for {@linkplain #dialogues(String) its own} conversations by owner, which is what
 * lets several of them author into one folder without seeing each other's content. Skeletons marked
 * {@code Abstract} and files switched off with {@code Enabled: false} are never handed out.
 *
 * <p>Ids are lower-cased so author casing never splits an entry. Writes are synchronized; the map is
 * concurrent for lock-free reads.
 */
public final class DialogueAssetStore {

    private static final DialogueAssetStore INSTANCE = new DialogueAssetStore();

    @Nonnull
    public static DialogueAssetStore getInstance() {
        return INSTANCE;
    }

    /** id -> the loaded asset (the decoded conversation plus its owner and switches). */
    private final Map<String, ZcDialogueAsset> loaded = new ConcurrentHashMap<>();

    private DialogueAssetStore() {
    }

    /**
     * Rebuild the layer from a load event's decoded assets. Idempotent on hot re-import (the whole
     * layer is rebuilt), and an entry whose file carried no body is skipped.
     */
    public synchronized void merge(@Nonnull Map<String, ZcDialogueAsset> assetLayer) {
        loaded.clear();
        for (Map.Entry<String, ZcDialogueAsset> e : assetLayer.entrySet()) {
            ZcDialogueAsset asset = e.getValue();
            if (asset == null || asset.getDialogue() == null) {
                continue;
            }
            loaded.put(e.getKey().toLowerCase(Locale.ROOT), asset);
        }
    }

    /**
     * The conversations {@code ownerFilter} may open, by id: everything in circulation that is not a
     * skeleton and that either belongs to that owner or belongs to nobody.
     *
     * @param ownerFilter the asking mod's owner name, or null for every owner
     * @return id -> conversation, a fresh snapshot
     */
    @Nonnull
    public Map<String, NpcDialogue> dialogues(@Nullable String ownerFilter) {
        String filter = ownerFilter == null ? null : ownerFilter.toLowerCase(Locale.ROOT);
        Map<String, NpcDialogue> out = new LinkedHashMap<>();
        for (Map.Entry<String, ZcDialogueAsset> entry : loaded.entrySet()) {
            ZcDialogueAsset asset = entry.getValue();
            NpcDialogue dialogue = asset.getDialogue();
            if (dialogue == null || asset.isAbstract() || !asset.isEnabled()) {
                continue;
            }
            String owner = asset.getOwner();
            if (filter == null || owner == null || filter.equals(owner)) {
                out.put(entry.getKey(), dialogue);
            }
        }
        return out;
    }

    /** Unmodifiable view of every loaded asset, skeletons and switched-off files included. */
    @Nonnull
    public Map<String, ZcDialogueAsset> assets() {
        return Collections.unmodifiableMap(loaded);
    }
}
