package com.ziggfreed.common.track.pick;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

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
import com.ziggfreed.common.codec.InheritMapCodec;
import com.ziggfreed.common.track.TrackKey;

/**
 * How a choice group's offers are drawn, at {@code Server/ZiggfreedCommon/TrackPickDecks/<Id>.json}, shared by every
 * group naming it (one per category, say). The FILE NAME is the id.
 *
 * <pre>{@code
 * { "Sizes": { "2": 2, "10": 3, "30": 4 },
 *   "Reserved": { "Knack": 1 },
 *   "Extra": { "Knack": 3 },
 *   "Bag": { "Often": 2, "Sometimes": 4 },
 *   "NoDrought": { "Often": 3, "Sometimes": 6 },
 *   "Foes": { "At": [ 35, 55, 75 ], "Offer": 3, "Draw": "Foe" } }
 * }</pre>
 *
 * <p>Every offer holds the group's {@code Core} and is a function of the files alone (R724): the same files offer
 * the same options at the same level to every player on every server, the next levels can be read ahead, and a
 * respec reopens the same offers. There is no seed and nothing is left to luck at the moment a player levels (R653):
 * the draw is a bag, not a die. An option whose {@code Bag} word reads n comes round about one level in n, at the
 * turn its {@code Turn} names, never more than {@code NoDrought} levels apart; options otherwise alike rotate,
 * the one offered longest ago first, then the group's order; and no level offers exactly the set the level before
 * it did. A pick's {@code Offers} write out the levels a pack or an owner wants exact, in each group that lists at
 * least one of their options. Every map merges key by key under {@code Parent}.
 */
public final class PickDeckAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, PickDeckAsset>> {

    /** The store's content path. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/TrackPickDecks";

    /** How many options an offer holds when no Sizes entry covers a level. */
    public static final int DEFAULT_SIZE = 3;

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable protected Map<String, Integer> sizes;
    @Nullable protected Map<String, Integer> reserved;
    @Nullable protected Map<String, Integer> extra;
    @Nullable protected Map<String, Integer> bag;
    @Nullable protected Map<String, Integer> noDrought;
    @Nullable protected Foes foes;

    public static final AssetBuilderCodec<String, PickDeckAsset> CODEC = AssetBuilderCodec.builder(
                    PickDeckAsset.class,
                    PickDeckAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Sizes", new InheritMapCodec<>(Codec.INTEGER), false),
                    (a, v) -> a.sizes = v, a -> a.sizes, (a, p) -> a.sizes = p.sizes)
            .documentation("How many options an offer holds, the core line included, by the level it starts at: "
                    + "{ \"2\": 2, \"10\": 3, \"30\": 4 } offers two from level 2, three from 10, four from 30. A "
                    + "level below every entry takes the first; with no entry at all an offer holds 3.").add()
            .appendInherited(new KeyedCodec<>("Reserved", new InheritMapCodec<>(Codec.INTEGER), false),
                    (a, v) -> a.reserved = v, a -> a.reserved, (a, p) -> a.reserved = p.reserved)
            .documentation("Room kept in every offer for options of one Draw word: { \"Knack\": 1 } puts one knack "
                    + "in each offer while any is open.").add()
            .appendInherited(new KeyedCodec<>("Extra", new InheritMapCodec<>(Codec.INTEGER), false),
                    (a, v) -> a.extra = v, a -> a.extra, (a, p) -> a.extra = p.extra)
            .documentation("One more option of a Draw word, in place of an ordinary one, at every so many levels: "
                    + "{ \"Knack\": 3 } offers a second knack at every third level.").add()
            .appendInherited(new KeyedCodec<>("Bag", new InheritMapCodec<>(Codec.INTEGER), false),
                    (a, v) -> a.bag = v, a -> a.bag, (a, p) -> a.bag = p.bag)
            .documentation("How often an option comes round, by the word its Bag names: { \"Often\": 2 } offers an "
                    + "Often option about one level in two, at the turn the option's Turn names. An option naming no "
                    + "word (or one not here) may come every level.").add()
            .appendInherited(new KeyedCodec<>("NoDrought", new InheritMapCodec<>(Codec.INTEGER), false),
                    (a, v) -> a.noDrought = v, a -> a.noDrought, (a, p) -> a.noDrought = p.noDrought)
            .documentation("The most levels an open option of a Bag word may go unoffered: { \"Often\": 3 } brings "
                    + "every Often option back within three levels.").add()
            .appendInherited(new KeyedCodec<>("Foes", Foes.CODEC, false),
                    (a, v) -> a.foes = v, a -> a.foes, (a, p) -> a.foes = p.foes)
            .documentation("The foe levels: at each, the offer is the core line and options of one Draw word (the "
                    + "foe families) alone. Those options are offered at no other level.").add()
            .build();

    public PickDeckAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /** The draw rules this deck writes, as {@link PickRoad} reads them. */
    @Nonnull
    public PickRoad.Deck rules() {
        TreeMap<Integer, Integer> bySize = new TreeMap<>();
        if (sizes != null) {
            for (Map.Entry<String, Integer> e : sizes.entrySet()) {
                Integer level = level(e.getKey());
                if (level != null && e.getValue() != null && e.getValue() >= 1) {
                    bySize.put(level, e.getValue());
                }
            }
        }
        Set<Integer> foeLevels = new TreeSet<>();
        int offer = Foes.DEFAULT_OFFER;
        String foeDraw = TrackKey.normalize(Foes.DEFAULT_DRAW);
        if (foes != null) {
            if (foes.at != null) {
                for (int level : foes.at) {
                    if (level >= 1) {
                        foeLevels.add(level);
                    }
                }
            }
            if (foes.offer != null) {
                offer = Math.max(1, foes.offer);
            }
            String own = TrackKey.normalize(foes.draw);
            if (!own.isEmpty()) {
                foeDraw = own;
            }
        }
        return new PickRoad.Deck(bySize, words(reserved), words(extra), words(bag), words(noDrought), foeLevels,
                offer, foeLevels.isEmpty() ? null : foeDraw);
    }

    @Nullable
    private static Integer level(@Nullable String key) {
        if (key == null) {
            return null;
        }
        try {
            int level = Integer.parseInt(key.trim());
            return level >= 1 ? level : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A word-keyed map lower-cased, counts below 1 left out. */
    @Nonnull
    private static Map<String, Integer> words(@Nullable Map<String, Integer> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> out = new HashMap<>();
        for (Map.Entry<String, Integer> e : raw.entrySet()) {
            String word = TrackKey.normalize(e.getKey());
            if (!word.isEmpty() && e.getValue() != null && e.getValue() >= 1) {
                out.put(word, e.getValue());
            }
        }
        return Map.copyOf(out);
    }

    /** The Foes group: the levels whose offer is foe families alone. */
    public static final class Foes {

        /** How many foe options a foe level offers beside the core, unauthored. */
        public static final int DEFAULT_OFFER = 3;

        /** The Draw word of the foe options, unauthored. */
        public static final String DEFAULT_DRAW = "Foe";

        @Nullable protected int[] at;
        @Nullable protected Integer offer;
        @Nullable protected String draw;

        public static final BuilderCodec<Foes> CODEC = BuilderCodec.builder(Foes.class, Foes::new)
                .appendInherited(new KeyedCodec<>("At", Codec.INT_ARRAY, false),
                        (o, v) -> o.at = v, o -> o.at, (o, p) -> o.at = p.at)
                .documentation("The foe levels.").add()
                .appendInherited(new KeyedCodec<>("Offer", Codec.INTEGER, false),
                        (o, v) -> o.offer = v, o -> o.offer, (o, p) -> o.offer = p.offer)
                .metadata(EditorSchema.defaultValue((long) DEFAULT_OFFER))
                .documentation("How many foe options a foe level offers beside the core line. 3 when unauthored.")
                .add()
                .appendInherited(new KeyedCodec<>("Draw", Codec.STRING, false),
                        (o, v) -> o.draw = v, o -> o.draw, (o, p) -> o.draw = p.draw)
                .metadata(EditorSchema.defaultValue(DEFAULT_DRAW))
                .documentation("The Draw word of the foe options. Foe when unauthored.").add()
                .build();

        public Foes() {
        }
    }
}
