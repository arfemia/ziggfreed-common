package com.ziggfreed.common.dialogue.asset;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.codec.DeferredCodec;
import com.ziggfreed.common.dialogue.schema.DialogueChrome;
import com.ziggfreed.common.dialogue.state.DialogueMemory;
import com.ziggfreed.common.dialogue.schema.DialogueNode;
import com.ziggfreed.common.dialogue.schema.DialogueOption;
import com.ziggfreed.common.dialogue.schema.DialogueStart;
import com.ziggfreed.common.dialogue.schema.DialogueTypeTable;
import com.ziggfreed.common.dialogue.schema.NpcDialogue;

/**
 * One authored conversation, at {@code Server/ZiggfreedCommon/Dialogues/<id>.json}. The FILE NAME is
 * the dialogue id.
 *
 * <pre>{@code
 * {
 *   "Memories": { "greeted": { "World": "*Forgotten_Temple*" } },
 *   "Start": { "Fallback": "greet" },
 *   "Fragments": { "footer": [ { "LabelKey": "...", "Close": true } ] },
 *   "Nodes": {
 *     "greet": { "TextKey": "dialogue.guide.greet.text",
 *                "Options": [ { "LabelKey": "...", "Accept": "getting_started", "Goto": "brief" } ],
 *                "IncludeOptions": [ "footer" ] } }
 * }
 * }</pre>
 *
 * <p>The whole conversation is read by a real codec, shorthand and all, so a mistyped key is a
 * startup error naming the file rather than a line that silently never appears. Every field above is
 * a field of the file itself, which is also what the in-game asset editor offers.
 *
 * <p><b>Reuse is inheritance, not copying.</b> A file may name another with {@code "Parent": "<id>"}
 * at the top level and restate only what differs. Each field decides for itself what that means:
 * {@code Nodes}, {@code Memories} and {@code Fragments} merge by name, so a screen the child does not
 * mention keeps everything the parent gave it, while {@code Start} is one ladder and a child that
 * writes one replaces it. Mark a file that only exists to be inherited from with
 * {@code "Abstract": true} and it is never handed to anybody as a conversation of its own.
 *
 * <p><b>Lines several conversations repeat</b> can live outside this file entirely, as a
 * {@link DialogueFragmentAsset} under {@code DialogueFragments/}; a screen names either kind the same
 * way with {@code IncludeOptions}, and a group declared here wins over a file of the same name.
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

    @Nullable private DialogueStart start;
    @Nullable private Map<String, DialogueNode> nodes;
    @Nullable private Map<String, DialogueMemory> memories;
    @Nullable private Map<String, DialogueOption[]> fragments;
    @Nullable private String[] header;
    @Nullable private DialogueChrome chrome;

    /** The conversation assembled from the fields above, once the whole file has been read. */
    @Nullable private NpcDialogue dialogue;

    // Each conversation field's codec cannot exist when this class loads: their shapes are the
    // action, condition and shorthand vocabulary the installed mods register while they start up.
    // These stand in for them and resolve to the finished codecs at the first read, which the server
    // always performs after every plugin has finished starting. One stand-in per field rather than
    // one for the whole body, so each field keeps its own inheritance behaviour.

    private static final DeferredCodec<DialogueStart> START =
            new DeferredCodec<>(() -> DialogueTypeTable.get().startCodec());
    private static final DeferredCodec<Map<String, DialogueNode>> NODES =
            new DeferredCodec<>(() -> DialogueTypeTable.get().nodesCodec());
    private static final DeferredCodec<Map<String, DialogueMemory>> MEMORIES =
            new DeferredCodec<>(() -> DialogueTypeTable.get().memoriesCodec());
    private static final DeferredCodec<Map<String, DialogueOption[]>> FRAGMENTS =
            new DeferredCodec<>(() -> DialogueTypeTable.get().fragmentsCodec());

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
            .metadata(EditorSchema.defaultValue(true))
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
            .appendInherited(new KeyedCodec<>("Start", START, false),
                    (a, v) -> a.start = v, a -> a.start, (a, p) -> a.start = p.start)
            .documentation(DialogueTypeTable.START_DOC)
            .add()
            .appendInherited(new KeyedCodec<>("Nodes", NODES, false),
                    (a, v) -> a.nodes = v, a -> a.nodes, (a, p) -> a.nodes = p.nodes)
            .documentation(DialogueTypeTable.NODES_DOC)
            .add()
            .appendInherited(new KeyedCodec<>("Memories", MEMORIES, false),
                    (a, v) -> a.memories = v, a -> a.memories, (a, p) -> a.memories = p.memories)
            .documentation(DialogueTypeTable.MEMORIES_DOC)
            .add()
            .appendInherited(new KeyedCodec<>("Fragments", FRAGMENTS, false),
                    (a, v) -> a.fragments = v, a -> a.fragments, (a, p) -> a.fragments = p.fragments)
            .documentation(DialogueTypeTable.FRAGMENTS_DOC)
            .add()
            .appendInherited(new KeyedCodec<>("Header", Codec.STRING_ARRAY, false),
                    (a, v) -> a.header = v, a -> a.header, (a, p) -> a.header = p.header)
            .documentation("Where the one-line note under the speaker's name comes from, in order: the "
                    + "first source with something to say is the line drawn. ActiveObjective reads the "
                    + "player's current step on a quest this character gives. Leave it out and the "
                    + "conversation shows no note at all, which is what a character who never talks "
                    + "about quests wants.")
            .add()
            .appendInherited(new KeyedCodec<>("Chrome", DialogueChrome.CODEC, false),
                    (a, v) -> a.chrome = v, a -> a.chrome, (a, p) -> a.chrome = p.chrome)
            .documentation("This conversation's own wording for the two lines the page supplies rather "
                    + "than the author: the exit row and the nothing-to-say line. Unauthored uses the "
                    + "library's wording, which is what most conversations want.")
            .add()
            .afterDecode((asset, extraInfo) -> asset.assemble())
            .build();

    public ZcDialogueAsset() {
    }

    /**
     * Turn the fields this file authored into the conversation everything else reads, once, after
     * the whole file (its {@code Parent} included) has been read. The conversation carries the
     * file's id so anything holding one knows what it is, and its screens pick up the shared option
     * groups they name here rather than at every render.
     */
    private void assemble() {
        if (start == null && nodes == null && memories == null && fragments == null
                && header == null && chrome == null) {
            dialogue = null;
            return;
        }
        NpcDialogue built = new NpcDialogue();
        if (id != null) {
            built.setId(id);
        }
        built.setTree(start, nodes);
        built.setMemories(memories);
        built.setFragments(fragments);
        built.setHeaderSources(header == null ? null : List.of(header));
        built.setChrome(chrome);
        built.spliceFragments();
        dialogue = built;
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

    /** The decoded conversation, or null when the file carried no conversation fields at all. */
    @Nullable
    public NpcDialogue getDialogue() {
        return dialogue;
    }
}
