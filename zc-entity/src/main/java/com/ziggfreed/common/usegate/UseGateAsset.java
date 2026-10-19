package com.ziggfreed.common.usegate;

import java.util.ArrayList;
import java.util.List;
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
import com.ziggfreed.common.factor.FeatureFlags;
import com.ziggfreed.common.progress.gate.FeatureLift;
import com.ziggfreed.common.progress.gate.GateSpec;

/**
 * A USE GATE, written as content: {@code Server/ZiggfreedCommon/UseGates/<Gate_Id>.json}. It says what a player
 * must meet to break a block, hit a creature, or break or hit while holding an item, or to put a piece of armor
 * on; a moment it refuses does not happen, and the player is told why in one line. The gate id is the FILENAME,
 * spelled {@code Is_Like_This}; a later-loaded pack's same-id file replaces it whole, and the server owner's
 * {@code mods/ziggfreedcommon/use-gates.json} merges over it leaf by leaf.
 *
 * <pre>{@code
 * // Server/ZiggfreedCommon/UseGates/Iron_Pickaxe.json
 * {
 *   "Feature": "yourmod:Tool_Levels",
 *   "On": { "Held": ["Tool_Pickaxe_Iron*"] },
 *   "Requires": { "Factors": [ { "Factor": "hytale:stat", "Param": "Mining_Level", "Min": 10 } ] }
 * }
 * }</pre>
 *
 * <p><b>Axes are OR'd</b> ({@link UseGateOn}): the gate applies when any axis it authors matches the moment.
 * <b>The most specific pattern wins</b>, per axis, among the gates of one {@code Group}: of every switched-on gate
 * whose pattern matches, only the one whose pattern names the thing most closely applies (an exact id over a
 * pattern, a longer literal over a shorter, the item's own id over an id it inherits from), the first by id on a
 * tie, so a narrower gate replaces a broader one for what it names. Gates of different groups all apply.
 *
 * <p><b>Whether the gate is there</b> is three orthogonal knobs, all read at the moment: {@code Enabled}, the
 * {@code Feature} it sits behind, and any plain top-level feature or mod-presence condition its {@code Requires}
 * writes ({@link FeatureLift#liftKnown}). A gate that is not there neither refuses nor shadows a broader gate.
 * {@code Feature} sits apart from {@code Requires} so an owner who retunes what a gate asks (a whole new
 * {@code Requires} in the owner file) keeps the switch. Everything else in {@code Requires} is asked of the player
 * at the moment. A gate whose companion mod is not installed is dropped at load.
 *
 * <p>The {@value ItemLevelTags#GROUP} group is the one an item's own {@code ZigLevel} tag competes in
 * ({@link ItemLevelTags}): a gate in that group naming the item outranks its tag, and one switched off there
 * switches the tag off for what it names.
 */
public final class UseGateAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, UseGateAsset>> {

    /** Where these files live. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/UseGates";

    /** The group a gate that names none belongs to. */
    public static final String DEFAULT_GROUP = "";

    /** What separates a {@code Feature}'s namespace from its feature id. */
    private static final char FEATURE_SEPARATOR = ':';

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable protected Boolean enabled;
    @Nullable protected String feature;
    @Nullable protected UseGateOn on;
    @Nullable protected String group;
    @Nullable private GateSpec gate;
    /** The block split into its presence half and its live half, once per instance. */
    @Nullable private volatile FeatureLift.Result lifted;
    /** {@link #feature} read as the switch it names, once per instance; a built gate sets it directly. */
    @Nullable private volatile List<FeatureLift.Lifted> features;

    public static final AssetBuilderCodec<String, UseGateAsset> CODEC = AssetBuilderCodec.builder(
                    UseGateAsset.class,
                    UseGateAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                    (a, v) -> a.enabled = v, a -> a.enabled, (a, p) -> a.enabled = p.enabled)
            .metadata(EditorSchema.defaultValue(true))
            .documentation("false switches the gate off: it refuses nothing and shadows no broader gate. In the "
                    + "ZigLevel group it also switches off the ZigLevel tag of every item it names. Unauthored "
                    + "means true. The owner file is the place to flip it.").add()
            .appendInherited(new KeyedCodec<>("Feature", Codec.STRING, false),
                    (a, v) -> a.feature = v, a -> a.feature, (a, p) -> a.feature = p.feature)
            .documentation("The server switch this gate sits behind, written <namespace>:<Feature_Id> (a feature "
                    + "a mod declares, such as mmoskilltree:Item_Requirements). While that switch is off, or no "
                    + "installed mod declares it, the gate is not there at all: it refuses nobody and shadows no "
                    + "broader gate. It stays apart from Requires, so an owner who rewrites what the gate asks "
                    + "keeps the switch. Unauthored, the gate is there whenever it is switched on.").add()
            .appendInherited(new KeyedCodec<>("On", UseGateOn.CODEC, false),
                    (a, v) -> a.on = v, a -> a.on, (a, p) -> a.on = p.on)
            .documentation("What the gate is about: Held, Worn, Block and Mob name patterns, any one of which "
                    + "matching is enough. A gate naming nothing applies to nothing.").add()
            .appendInherited(new KeyedCodec<>("Group", Codec.STRING, false),
                    (a, v) -> a.group = v, a -> a.group, (a, p) -> a.group = p.group)
            .documentation("Gates in one group compete: on each axis only the most specific matching pattern "
                    + "applies, so a narrower gate replaces a broader one. Gates in different groups all apply. "
                    + "The ZigLevel group is where an item's own ZigLevel tag competes: a gate there naming the "
                    + "item asks instead of the tag. Unauthored is the shared default group. Matched without "
                    + "regard to case.").add()
            .appendInherited(new KeyedCodec<>("Requires", GateSpec.CODEC, false),
                    (a, v) -> a.gate = v, a -> a.gate, (a, p) -> a.gate = p.gate)
            .documentation("What a player must meet, in the Requires shape quests use (Factors, Permission, "
                    + "Quests, Custom, AllOf, AnyOf, Not), every condition read. A plain top-level "
                    + "{ \"Factor\": \"hytale:mod_installed\", \"Param\": \"Group:Name\", \"Min\": 1 } decides "
                    + "whether the gate is there at all; the rest is asked each time. Write a switch as Feature, "
                    + "never here, so a retune keeps it. A refused player reads the one line that says what is "
                    + "missing.").add()
            .build();

    public UseGateAsset() {
    }

    /** The gate id this file describes, exactly as the filename spells it. */
    @Override
    public String getId() {
        return id;
    }

    /** Whether the gate is switched on; unauthored means yes. */
    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    /** The {@code Feature} leaf as authored, or null. */
    @Nullable
    public String feature() {
        return feature;
    }

    /** What the gate is about, never null (an unauthored group reads as naming nothing). */
    @Nonnull
    public UseGateOn on() {
        return on == null ? new UseGateOn() : on;
    }

    /** The competition group, trimmed and lower-cased; {@link #DEFAULT_GROUP} when none is authored. */
    @Nonnull
    public String group() {
        return groupKey(group);
    }

    /** {@code group} as {@link #group()} folds it, for a caller comparing one it was handed. */
    @Nonnull
    public static String groupKey(@Nullable String group) {
        return group == null || group.isBlank() ? DEFAULT_GROUP : group.trim().toLowerCase(Locale.ROOT);
    }

    /** The block as authored, or null when the file authors none. */
    @Nullable
    public GateSpec requires() {
        return gate;
    }

    /** The part of {@link #requires()} asked of the player at the moment; null when nothing is. */
    @Nullable
    public GateSpec liveRequires() {
        return split().requires();
    }

    /** The conditions lifted out of {@code Requires} onto the presence axis (features and mod presence). */
    @Nonnull
    public List<FeatureLift.Lifted> liftedConditions() {
        return split().lifted();
    }

    /** The switch {@code Feature} names, as a lifted feature condition; empty when none or malformed. */
    @Nonnull
    public List<FeatureLift.Lifted> features() {
        List<FeatureLift.Lifted> held = features;
        if (held == null) {
            FeatureLift.Lifted parsed = parseFeature(feature);
            held = parsed == null ? List.of() : List.of(parsed);
            features = held;
        }
        return held;
    }

    /**
     * Every switch the gate's presence reads, in order: its {@code Feature}, then each feature condition lifted
     * out of {@code Requires} (never mod presence). What an admin surface lists as the switches over the gate.
     */
    @Nonnull
    public List<FeatureLift.Lifted> switches() {
        List<FeatureLift.Lifted> out = new ArrayList<>(features());
        for (FeatureLift.Lifted lifted : liftedConditions()) {
            if (!lifted.isModPresence()) {
                out.add(lifted);
            }
        }
        return out;
    }

    /** Is the gate there right now: switched on, its {@code Feature} on, and every lifted condition on? */
    public boolean isPresent() {
        return isEnabled() && FeatureLift.allOn(features()) && FeatureLift.allOn(split().lifted());
    }

    @Nonnull
    private FeatureLift.Result split() {
        FeatureLift.Result held = lifted;
        if (held == null) {
            held = FeatureLift.liftKnown(gate);
            lifted = held;
        }
        return held;
    }

    /**
     * {@code <namespace>:<Feature_Id>} as the feature condition it names (the namespace's {@code <namespace>:feature}
     * factor on that id), or null when it is blank or not that shape. A namespace nothing declares still parses:
     * it reads off, so the gate is not there.
     */
    @Nullable
    public static FeatureLift.Lifted parseFeature(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        int split = trimmed.indexOf(FEATURE_SEPARATOR);
        if (split <= 0 || split >= trimmed.length() - 1) {
            return null;
        }
        String namespace = trimmed.substring(0, split).trim();
        String featureId = trimmed.substring(split + 1).trim();
        if (namespace.isEmpty() || featureId.isEmpty() || featureId.indexOf(FEATURE_SEPARATOR) >= 0) {
            return null;
        }
        return new FeatureLift.Lifted(FeatureFlags.factorId(namespace.toLowerCase(Locale.ROOT)), featureId);
    }

    /** Java-side factory, for a test or a consumer registering a gate without a file. */
    @Nonnull
    public static UseGateAsset of(@Nonnull String id, @Nullable Boolean enabled, @Nullable UseGateOn on,
            @Nullable String group, @Nullable GateSpec requires) {
        return of(id, enabled, null, on, group, requires);
    }

    /** {@link #of(String, Boolean, UseGateOn, String, GateSpec)} with a {@code Feature} switch. */
    @Nonnull
    public static UseGateAsset of(@Nonnull String id, @Nullable Boolean enabled, @Nullable String feature,
            @Nullable UseGateOn on, @Nullable String group, @Nullable GateSpec requires) {
        UseGateAsset a = new UseGateAsset();
        a.id = id;
        a.enabled = enabled;
        a.feature = feature;
        a.on = on;
        a.group = group;
        a.gate = requires;
        return a;
    }

    /**
     * The gate an item's {@code ZigLevel} tag makes ({@link ItemLevelTags}): {@code requires} asked on holding and
     * wearing {@code itemId}, in the {@value ItemLevelTags#GROUP} group, behind every switch in {@code switches}
     * (the item's {@code ZigLevelFeature} tag). Never folded into the store.
     */
    @Nonnull
    static UseGateAsset forTag(@Nonnull String gateId, @Nonnull String itemId, @Nonnull GateSpec requires,
            @Nonnull List<FeatureLift.Lifted> switches) {
        UseGateAsset a = of(gateId, null, null,
                UseGateOn.of(new String[] {itemId}, new String[] {itemId}, null, null), ItemLevelTags.GROUP, requires);
        a.features = List.copyOf(switches);
        return a;
    }
}
