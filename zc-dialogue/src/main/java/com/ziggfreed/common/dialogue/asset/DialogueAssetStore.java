package com.ziggfreed.common.dialogue.asset;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonObject;

import com.ziggfreed.common.dialogue.DialogueEngine;
import com.ziggfreed.common.dialogue.NpcDialogue;
import com.ziggfreed.common.util.SafeLog;

/**
 * Every conversation the server has loaded, ready to open.
 *
 * <p>The reading happens in the asset store itself, so what lands here is already a decoded
 * conversation with its {@code Parent} merged in and its shared option groups spliced - there is no
 * second parse and no per-mod decode step. The layer is rebuilt WHOLESALE from each load event, so a
 * hot re-import is idempotent.
 *
 * <p>{@link #dialogues()} hands back everything in circulation, whoever wrote it, which is what lets
 * several mods author into one folder and any of them open a conversation another shipped. Skeletons
 * marked {@code Abstract} and files switched off with {@code Enabled: false} are never handed out.
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

    /** id -> the loaded asset (the decoded conversation plus its switches). */
    private final Map<String, ZcDialogueAsset> loaded = new ConcurrentHashMap<>();

    /** id -> the owner's own conversation, decoded from {@code mods/ziggfreedcommon/dialogues.json}. */
    private final Map<String, NpcDialogue> owner = new ConcurrentHashMap<>();

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
        applyOwnerLayer();
    }

    /**
     * Re-read the owner's own conversations and decode them through the SAME codec the files use, so
     * there is one schema whichever layer a body was written in. Called after every load, because an
     * override only means something once the engine's vocabulary is in and the layer under it exists.
     *
     * <p><b>An entry folds over the stored conversation of its id, leaf by leaf</b> - the same
     * per-node merge {@code Parent} inheritance uses, so an owner retuning one line writes one node
     * and keeps every screen, memory and shared group the pack authored. An id nothing stores is a
     * new conversation, standing on its own.
     *
     * <p>A body that will not decode is reported by id and skipped, which leaves the stored
     * conversation of that id standing rather than taking the character out of circulation.
     */
    public synchronized void applyOwnerLayer() {
        DialogueOverrides overrides = DialogueOverrides.getInstance();
        overrides.reload();
        owner.clear();
        for (Map.Entry<String, JsonObject> entry : overrides.bodies().entrySet()) {
            ZcDialogueAsset stored = loaded.get(entry.getKey());
            NpcDialogue base = stored == null ? null : stored.getDialogue();
            NpcDialogue decoded = DialogueEngine.shared()
                    .decode(entry.getKey(), entry.getValue().toString(), base);
            if (decoded == null) {
                SafeLog.warn("[dialogue] the owner conversation '" + entry.getKey() + "' could not be read"
                        + " and was skipped; the one already in circulation (if any) stands");
                continue;
            }
            owner.put(entry.getKey(), decoded);
        }
    }

    /**
     * The conversation to open for {@code id}: the owner's own if they wrote one, else the one in
     * circulation. Null when nothing answers, which the page says on its own screen.
     */
    @Nullable
    public NpcDialogue dialogue(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String key = id.toLowerCase(Locale.ROOT);
        NpcDialogue own = owner.get(key);
        if (own != null) {
            return own;
        }
        ZcDialogueAsset asset = loaded.get(key);
        if (asset == null || asset.isAbstract() || !asset.isEnabled()) {
            return null;
        }
        return asset.getDialogue();
    }

    /**
     * Every conversation anybody may open, by id: everything in circulation that is not a skeleton.
     *
     * <p>A file is not addressed to one reader. One folder holds the server's conversations, each
     * reader takes the whole set, and an id written twice is settled where the readers' catalogues
     * meet rather than by hiding one file from one mod.
     *
     * @return id -> conversation, a fresh snapshot
     */
    @Nonnull
    public Map<String, NpcDialogue> dialogues() {
        Map<String, NpcDialogue> out = new LinkedHashMap<>();
        for (Map.Entry<String, ZcDialogueAsset> entry : loaded.entrySet()) {
            ZcDialogueAsset asset = entry.getValue();
            NpcDialogue dialogue = asset.getDialogue();
            if (dialogue == null || asset.isAbstract() || !asset.isEnabled()) {
                continue;
            }
            out.put(entry.getKey(), dialogue);
        }
        // The owner's own conversations sit on top, so a listing shows what would actually open.
        out.putAll(owner);
        return out;
    }

    /** Unmodifiable view of every loaded asset, skeletons and switched-off files included. */
    @Nonnull
    public Map<String, ZcDialogueAsset> assets() {
        return Collections.unmodifiableMap(loaded);
    }
}
