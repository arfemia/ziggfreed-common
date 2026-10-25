package com.ziggfreed.common.world.gather;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.asset.EditorSchema;

/**
 * The server's one word on the gather steps' own rules, the numbers no stat carries: when a tree counts as tall (for
 * {@code GatherSpeed_Tall_Tree}), how long the break chain keeps its rhythm and how many steps it counts (for
 * {@code GatherRhythm}), and how long a shaped placement keeps every placement shaped (for {@code BuildThrift_Shaped}).
 * The FILE NAME is the record's id, and the record every step reads is {@value #SHARED_ID}.
 *
 * <p>Authored at {@code Server/ZiggfreedCommon/GatherRules/Default.json}, which this library ships; a pack's same-id
 * file wins by pack order, and the owner layer is {@code mods/ziggfreedcommon/gather-rules.json}:
 * <pre>{@code
 * { "Default": { "Rhythm": { "MaxSteps": 8 } } }
 * }</pre>
 */
public final class GatherRulesAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, GatherRulesAsset>> {

    /** Where these are authored. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/GatherRules";

    /** The id of the record every step reads (the file is {@code Default.json}; ids fold lower-case). */
    public static final String SHARED_ID = "default";

    /** How many logs one column must stand for its tree to be tall, unless the record says otherwise. */
    public static final int DEFAULT_TALL_TREE_LOGS = 10;

    /** How long after one break the next still continues the chain, in seconds, unless the record says otherwise. */
    public static final double DEFAULT_RHYTHM_WINDOW_SECONDS = 2.0;

    /** The most steps the chain counts, unless the record says otherwise. */
    public static final int DEFAULT_RHYTHM_MAX_STEPS = 5;

    /** How long after a shaped placement every placement still counts as shaped, in seconds, unless said otherwise. */
    public static final double DEFAULT_SHAPED_FLOW_SECONDS = 10.0;

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable protected TallTree tallTree;
    @Nullable protected Rhythm rhythm;
    @Nullable protected Shaped shaped;

    public static final AssetBuilderCodec<String, GatherRulesAsset> CODEC = AssetBuilderCodec.builder(
                    GatherRulesAsset.class,
                    GatherRulesAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("TallTree", TallTree.CODEC, false),
                    (a, v) -> a.tallTree = v, a -> a.tallTree, (a, p) -> a.tallTree = p.tallTree)
            .documentation("When a tree counts as tall, for the GatherSpeed_Tall_Tree stat.").add()
            .appendInherited(new KeyedCodec<>("Rhythm", Rhythm.CODEC, false),
                    (a, v) -> a.rhythm = v, a -> a.rhythm, (a, p) -> a.rhythm = p.rhythm)
            .documentation("The break chain the GatherRhythm stat reads: a break soon after the last adds a step.")
            .add()
            .appendInherited(new KeyedCodec<>("Shaped", Shaped.CODEC, false),
                    (a, v) -> a.shaped = v, a -> a.shaped, (a, p) -> a.shaped = p.shaped)
            .documentation("Shaped placement, which the BuildThrift_Shaped stat reads: a block continuing a straight "
                    + "run of three, and every placement soon after one.").add()
            .build();

    public GatherRulesAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /** How many logs one column must stand for its tree to be tall, 1 or more. */
    public int tallTreeLogs() {
        Integer logs = tallTree == null ? null : tallTree.logs;
        return logs == null ? DEFAULT_TALL_TREE_LOGS : Math.max(1, logs);
    }

    /** How long after one break the next still continues the chain, in milliseconds, 0 or more. */
    public long rhythmWindowMs() {
        return millis(rhythm == null ? null : rhythm.windowSeconds, DEFAULT_RHYTHM_WINDOW_SECONDS);
    }

    /** The most steps the chain counts, 0 or more. */
    public int rhythmMaxSteps() {
        Integer steps = rhythm == null ? null : rhythm.maxSteps;
        return steps == null ? DEFAULT_RHYTHM_MAX_STEPS : Math.max(0, steps);
    }

    /** How long after a shaped placement every placement still counts as shaped, in milliseconds, 0 or more. */
    public long shapedFlowMs() {
        return millis(shaped == null ? null : shaped.flowSeconds, DEFAULT_SHAPED_FLOW_SECONDS);
    }

    private static long millis(@Nullable Double seconds, double fallback) {
        double value = seconds == null || !Double.isFinite(seconds) ? fallback : Math.max(0.0, seconds);
        return (long) (value * 1000.0);
    }

    /** The TallTree group. */
    public static final class TallTree {

        @Nullable protected Integer logs;

        public static final BuilderCodec<TallTree> CODEC = BuilderCodec.builder(TallTree.class, TallTree::new)
                .appendInherited(new KeyedCodec<>("Logs", Codec.INTEGER, false),
                        (o, v) -> o.logs = v, o -> o.logs, (o, p) -> o.logs = p.logs)
                .metadata(EditorSchema.defaultValue((long) DEFAULT_TALL_TREE_LOGS))
                .documentation("How many blocks one column of the same log must stand, counting the log hit, for "
                        + "its tree to be tall. 10 when unauthored.").add()
                .build();

        public TallTree() {
        }
    }

    /** The Rhythm group. */
    public static final class Rhythm {

        @Nullable protected Double windowSeconds;
        @Nullable protected Integer maxSteps;

        public static final BuilderCodec<Rhythm> CODEC = BuilderCodec.builder(Rhythm.class, Rhythm::new)
                .appendInherited(new KeyedCodec<>("WindowSeconds", Codec.DOUBLE, false),
                        (o, v) -> o.windowSeconds = v, o -> o.windowSeconds, (o, p) -> o.windowSeconds = p.windowSeconds)
                .metadata(EditorSchema.defaultValue(DEFAULT_RHYTHM_WINDOW_SECONDS))
                .documentation("Seconds after one break within which the next adds a step; a longer pause starts "
                        + "the chain again. 2 when unauthored.").add()
                .appendInherited(new KeyedCodec<>("MaxSteps", Codec.INTEGER, false),
                        (o, v) -> o.maxSteps = v, o -> o.maxSteps, (o, p) -> o.maxSteps = p.maxSteps)
                .metadata(EditorSchema.defaultValue((long) DEFAULT_RHYTHM_MAX_STEPS))
                .documentation("The most steps the chain counts; GatherRhythm pays once a step. 5 when unauthored.")
                .add()
                .build();

        public Rhythm() {
        }
    }

    /** The Shaped group. */
    public static final class Shaped {

        @Nullable protected Double flowSeconds;

        public static final BuilderCodec<Shaped> CODEC = BuilderCodec.builder(Shaped.class, Shaped::new)
                .appendInherited(new KeyedCodec<>("FlowSeconds", Codec.DOUBLE, false),
                        (o, v) -> o.flowSeconds = v, o -> o.flowSeconds, (o, p) -> o.flowSeconds = p.flowSeconds)
                .metadata(EditorSchema.defaultValue(DEFAULT_SHAPED_FLOW_SECONDS))
                .documentation("Seconds after a placement that continued a straight run of three during which every "
                        + "placement still counts as shaped. 10 when unauthored.").add()
                .build();

        public Shaped() {
        }
    }
}
