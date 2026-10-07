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
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.asset.SeasonLeaf;
import com.ziggfreed.common.codec.DeferredCodec;
import com.ziggfreed.common.dialogue.schema.DialogueExtension;
import com.ziggfreed.common.dialogue.schema.DialogueOption;
import com.ziggfreed.common.dialogue.schema.DialogueSelector;
import com.ziggfreed.common.dialogue.schema.DialogueTypeTable;
import com.ziggfreed.common.dialogue.schema.NodeSelector;
import com.ziggfreed.common.season.SeasonGate;

/**
 * Lines a pack adds to conversations it did not write, at
 * {@code Server/ZiggfreedCommon/DialogueExtensions/<Id>.json}. The FILE NAME is the extension's id,
 * and an id is owner-prefixed ({@code My_Pack_Festival_Greeting}) because a folder does not
 * namespace it.
 *
 * <pre>{@code
 * { "Dialogues": { "Exclude": ["Round_Lobby"] },
 *   "Options": [ { "LabelKey": "my_pack.festival_greeting", "OnceId": "festival_greeting",
 *                  "Once": { "Period": "Daily" },
 *                  "Conditions": [ { "Type": "Factor", "Factor": "my_pack:festival_live", "Min": 1 } ] } ] }
 * }</pre>
 *
 * <p><b>The one store that pushes.</b> A shared {@link DialogueFragmentAsset} is pull-only: it lands
 * only where a screen names it. An extension lands uninvited, by design and nowhere else: every
 * conversation unless {@code Dialogues} narrows it, on the screens a conversation opens on unless
 * {@code On} names screens or tags. Its lines follow the screen's own lines and the groups placed on
 * it, and come before the groups the screen pulls in, so a footer the screen names stays last.
 *
 * <p>A line here is an ordinary option row, read by the same codec, so every shorthand and condition
 * works as on a screen. Its {@code Once} is the line's own: spent with one character, it is spent
 * with every character the line reaches. Give every line a {@code LabelKey}; a {@code Goto} or a
 * memory has no meaning in a conversation the line does not know, and the audit says so.
 *
 * <p>{@code Season} names the calendar event the lines belong to: they stay spliced all year, and are
 * offered only while that event runs, read live at every render and click.
 *
 * <p>To take a shipped extension out, override the file by id with {@code "Enabled": false}.
 */
public final class DialogueExtensionAsset
        implements JsonAssetWithMap<String, DefaultAssetMap<String, DialogueExtensionAsset>> {

    /** The content path this type is authored under. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/DialogueExtensions";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private Boolean enabled;
    @Nullable private DialogueSelector dialogues;
    @Nullable private NodeSelector on;
    @Nullable private DialogueOption[] options;
    @Nullable private String season;

    /**
     * An option row's codec is the vocabulary the installed mods register while they start up, so it
     * cannot exist when this class loads; this resolves to it at the first read, which the server
     * performs after every plugin has started.
     */
    private static final DeferredCodec<DialogueOption[]> OPTIONS =
            new DeferredCodec<>(() -> DialogueTypeTable.get().optionsArray());

    public static final AssetBuilderCodec<String, DialogueExtensionAsset> CODEC = SeasonLeaf.append(AssetBuilderCodec.builder(
                    DialogueExtensionAsset.class,
                    DialogueExtensionAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id == null ? null : id.toLowerCase(Locale.ROOT),
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .append(new KeyedCodec<>("Name", Codec.STRING, false),
                    (a, name) -> { /* no-op: the id comes from the filename */ },
                    a -> a.id)
            .add()
            .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                    (a, v) -> a.enabled = v, a -> a.enabled, (a, p) -> a.enabled = p.enabled)
            .metadata(EditorSchema.defaultValue(true))
            .documentation("Whether these lines go anywhere; unauthored means true. Set false to take them "
                    + "out without deleting the file.")
            .add()
            .appendInherited(new KeyedCodec<>("Dialogues", DialogueSelector.CODEC, false),
                    (a, v) -> a.dialogues = v, a -> a.dialogues, (a, p) -> a.dialogues = p.dialogues)
            .documentation("Which conversations get the lines: Ids names them, Exclude takes some back out. "
                    + "Leave it out for every conversation on the server.")
            .add()
            .appendInherited(new KeyedCodec<>("On", NodeSelector.CODEC, false),
                    (a, v) -> a.on = v, a -> a.on, (a, p) -> a.on = p.on)
            .documentation("Which screens of those conversations get the lines: Nodes by exact id, Tags for "
                    + "every screen carrying one, Exclude to take screens back out. Leave it out for the "
                    + "screens a conversation opens on.")
            .add()
            .appendInherited(new KeyedCodec<>("Options", OPTIONS, false),
                    (a, v) -> a.options = v, a -> a.options, (a, p) -> a.options = p.options)
            .documentation("The lines added, in the order shown, after the screen's own lines and before "
                    + "the shared groups it pulls in. Give each a LabelKey and gate it with Conditions like "
                    + "any line. A Once on one of these lines is the line's own: spent with one character, "
                    + "it is spent with every character the line reaches.")
            .add(),
                    (a, v) -> a.season = v, a -> a.season)
            .build();

    public DialogueExtensionAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /** In circulation? Unauthored means true. */
    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    /** The calendar event these lines belong to, trimmed, or null for all year. */
    @Nullable
    public String getSeason() {
        return SeasonGate.normalize(season);
    }

    /** The lines as decoded (unmarked), or null when the file wrote none. */
    @Nullable
    public DialogueOption[] getOptions() {
        return options;
    }

    /** The file as the engine uses it, under the folded id the fold hands in. */
    @Nonnull
    public DialogueExtension toExtension(@Nonnull String foldedId) {
        return DialogueExtension.of(foldedId, options, dialogues, on, isEnabled(), getSeason());
    }
}
