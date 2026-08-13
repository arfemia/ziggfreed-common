package com.ziggfreed.common.dialogue.asset;

import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.ziggfreed.common.dialogue.DeferredCodec;
import com.ziggfreed.common.dialogue.DialogueTypeTable;
import com.ziggfreed.common.dialogue.NpcDialogue;

/**
 * One authored conversation, at {@code Server/ZiggfreedCommon/Dialogues/<id>.json}. The FILE NAME is
 * the dialogue id.
 *
 * <pre>{@code
 * { "Payload": {
 *     "Memories": { "greeted": { "World": "*Forgotten_Temple*" } },
 *     "Start": [ { "Node": "greet" } ],
 *     "Fragments": { "footer": [ { "LabelKey": "...", "Close": true } ] },
 *     "Nodes": {
 *       "greet": { "TextKey": "dialogue.guide.greet.text",
 *                  "Options": [ { "LabelKey": "...", "Accept": "getting_started", "Goto": "brief" } ],
 *                  "IncludeOptions": [ "footer" ] } } } }
 * }</pre>
 *
 * <p>The whole conversation is read by a real codec, shorthand and all, so a mistyped key is a
 * startup error naming the file rather than a line that silently never appears.
 *
 * <p><b>Reuse is inheritance, not copying.</b> A file may name another with {@code "Parent": "<id>"}
 * at the top level and restate only what differs: screens merge by name, and a screen the child does
 * not mention keeps everything the parent gave it. Mark a file that only exists to be inherited from
 * with {@code "Abstract": true} and it is never handed to anybody as a conversation of its own.
 *
 * <p><b>To retune a conversation somebody else shipped</b>, override the file by id (a same-named
 * file in a later pack), or ship your own with {@code Parent} set to theirs. To take one out of
 * circulation, set {@code Enabled} to false.
 */
public final class ZcDialogueAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, ZcDialogueAsset>> {

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private Boolean enabled;
    @Nullable private Boolean isAbstract;
    @Nullable private NpcDialogue payload;

    /**
     * The conversation body's codec cannot exist when this class loads: its shape is the action,
     * condition and shorthand vocabulary the installed mods register while they start up. This
     * stands in for it and resolves to the finished codec at the first read, which the server always
     * performs after every plugin has finished starting.
     */
    private static final DeferredCodec<NpcDialogue> BODY =
            new DeferredCodec<>(() -> DialogueTypeTable.get().dialogueCodec());

    public static final AssetBuilderCodec<String, ZcDialogueAsset> CODEC = AssetBuilderCodec.builder(
                    ZcDialogueAsset.class,
                    ZcDialogueAsset::new,
                    Codec.STRING,
                    // The engine's asset key is the verbatim filename while every consumer addresses
                    // a dialogue lower-cased; canonicalizing at the one decode authority keeps the id
                    // the same string everywhere.
                    (a, id) -> a.id = id == null ? null : id.toLowerCase(Locale.ROOT),
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            // An optional human-readable echo of the asset key (the authoritative key is the
            // filename), consumed by a no-op setter and emitted on encode for round-trip.
            .append(new KeyedCodec<>("Name", Codec.STRING, false),
                    (a, name) -> { /* no-op: the id comes from the filename */ },
                    a -> a.id)
            .add()
            .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                    (a, v) -> a.enabled = v, a -> a.enabled, (a, p) -> a.enabled = p.enabled)
            .documentation("Whether the conversation is in circulation; unauthored means true. Set false to "
                    + "take one out without deleting the file.")
            .add()
            // The ONE field that deliberately does NOT inherit: a child of a skeleton is a real
            // conversation, so inheriting this would hide every child of every base.
            .append(new KeyedCodec<>("Abstract", Codec.BOOLEAN, false),
                    (a, v) -> a.isAbstract = v, a -> a.isAbstract)
            .documentation("Mark a file that exists only to be inherited from. It stays available as a Parent "
                    + "target and is never opened as a conversation of its own, so a shared skeleton needs no "
                    + "greeting. It never carries down to a child.")
            .add()
            .appendInherited(new KeyedCodec<>("Payload", BODY, false),
                    (a, v) -> a.payload = v, a -> a.payload, (a, p) -> a.payload = p.payload)
            .documentation("The conversation itself: its greetings, its screens, what it remembers, and any "
                    + "shared option groups.")
            .add()
            .afterDecode((asset, extraInfo) -> asset.nameBody())
            .build();

    public ZcDialogueAsset() {
    }

    /** The body carries the file's id, so anything holding a decoded conversation knows what it is. */
    private void nameBody() {
        if (payload != null && id != null) {
            payload.setId(id);
        }
    }

    @Override
    public String getId() {
        return id;
    }

    /** In circulation? Unauthored means true. */
    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    /** A skeleton that exists only to be inherited from, never opened on its own. */
    public boolean isAbstract() {
        return isAbstract != null && isAbstract;
    }

    /** The decoded conversation, or null when the file carried no body. */
    @Nullable
    public NpcDialogue getDialogue() {
        return payload;
    }
}
