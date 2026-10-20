package com.ziggfreed.common.track.tree;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.track.asset.TrackAsset;
import com.ziggfreed.common.ui.route.Destination;
import com.ziggfreed.common.ui.route.DestinationContext;
import com.ziggfreed.common.ui.route.DestinationType;
import com.ziggfreed.common.ui.route.Destinations;

/**
 * The {@code Track_Tree} destination, the library's because the tree page behind it is:
 *
 * <pre>{@code
 * "Open": { "Type": "Track_Tree", "Track": "Zc_Track_Example" }
 * }</pre>
 *
 * <p>A conversation's answer, a placement's {@code Interact}, a page's link or a contributed row's button (the Claims
 * tab's choices waiting) opens a track's tree this way, on the player. Registered at setup by
 * {@code TrackBootstrap}, before any asset decodes.
 */
public final class TreeDestinations {

    /** The owner the registration is attributed to. */
    public static final String OWNER = "ziggfreedcommon";

    /** The {@code Type} id content writes. */
    public static final String TYPE = "Track_Tree";

    private TreeDestinations() {
    }

    /** Seed the type into the shared vocabulary, at setup, before any asset decodes. */
    public static void register() {
        Destinations.register(OWNER, DestinationType.of(TYPE, Tree.class, Tree.CODEC, TreeDestinations::open));
    }

    /** The destination that opens {@code trackId}'s tree. */
    @Nonnull
    public static Tree of(@Nonnull String trackId) {
        Tree tree = new Tree();
        tree.track = trackId;
        return tree;
    }

    private static boolean open(@Nonnull Tree destination, @Nonnull DestinationContext ctx) {
        return TreePages.open(destination.track(), ctx.store(), ctx.playerReference(), ctx.player());
    }

    /** Open a track's tree. */
    public static final class Tree extends Destination {

        @Nullable protected String track;

        public static final BuilderCodec<Tree> CODEC = BuilderCodec.builder(Tree.class, Tree::new)
                .append(new KeyedCodec<>("Track", Codec.STRING, false), (d, v) -> d.track = v, d -> d.track)
                .metadata(EditorSchema.assetRef(TrackAsset.class))
                .documentation("The track whose tree to open, by id. A track with no tree opens nothing and says "
                        + "so.").add()
                .build();

        public Tree() {
        }

        /** The track named, or null. */
        @Nullable
        public String track() {
            return track == null || track.isBlank() ? null : track.trim();
        }
    }
}
