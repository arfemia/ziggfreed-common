package com.ziggfreed.common.asset;

import java.util.function.BiConsumer;
import java.util.function.Function;

import javax.annotation.Nonnull;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.season.SeasonGate;

/**
 * The ONE codec leaf a season-bearing store declares: {@code "Season": "<calendar event id>"}.
 *
 * <p>A scalar leaf, inherited through native {@code Parent}, so a season's shared base names it once
 * and every child keeps it whatever the child writes in {@code Requires}. That is the whole point: a
 * gate carried in a base's {@code Requires.Factors} is lost the moment a child writes its own array,
 * because a child's array replaces the parent's whole.
 *
 * <p>A store reads the value only through {@link SeasonGate}, in the same check that reads its
 * {@code Enabled}, so a season hides content and never locks it.
 *
 * <p>Wrap a codec chain with it, the way a shared group's {@code appendLeaves} is used:
 * <pre>{@code
 * CODEC = SeasonLeaf.append(AssetBuilderCodec.builder(...)
 *             ...
 *             .add(),
 *         (a, v) -> a.season = v, a -> a.season)
 *         .build();
 * }</pre>
 */
public final class SeasonLeaf {

    /** The authored key. */
    public static final String KEY = "Season";

    /** The sentence the Asset Editor shows beside the leaf. */
    public static final String DOCUMENTATION = "The calendar event this belongs to, by its id, such as "
            + "Harvest_Feast. While that event is not running this is hidden everywhere, as if it were not on "
            + "this server, and nothing about it shows as locked; while it runs, it behaves as if this line "
            + "were not here. Leave it out for something that is on all year. A child keeps its Parent's "
            + "Season whatever it writes in Requires, so a season's shared base names it once. An id no "
            + "calendar event defines is reported, and the content stays hidden.";

    private SeasonLeaf() {
    }

    /**
     * Append the {@code Season} leaf to {@code builder} and hand the builder back for the rest of the
     * chain. The inheritance copies the parent's value through {@code get} and {@code set}, so an asset
     * needs no third lambda.
     */
    @Nonnull
    public static <T, S extends BuilderCodec.BuilderBase<T, S>> S append(@Nonnull S builder,
            @Nonnull BiConsumer<T, String> set, @Nonnull Function<T, String> get) {
        return builder.appendInherited(new KeyedCodec<>(KEY, Codec.STRING, false), set, get,
                        (child, parent) -> set.accept(child, get.apply(parent)))
                .documentation(DOCUMENTATION)
                .add();
    }
}
