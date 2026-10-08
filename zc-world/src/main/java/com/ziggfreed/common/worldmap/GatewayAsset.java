package com.ziggfreed.common.worldmap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.codec.Vec3;
import com.ziggfreed.common.world.WorldSelector;

/**
 * Where the way into another world stands, as content:
 * {@code Server/ZiggfreedCommon/Gateways/<Id>.json}. A pointer at a character standing only in
 * another world points at the nearest of these instead. A gateway only says where the way is: it
 * never opens, places, gates or teleports anything.
 *
 * <pre>{@code
 * { "Into": { "GameplayConfig": "ForgottenTemple" },
 *   "Blocks": [ "Forgotten_Temple_Portal_Enter" ] }
 * }</pre>
 *
 * <p>The base game's own portals need no file: {@link PortalGateways} reads them off the engine
 * under their block's id, and a file with that id replaces the one read. {@code Blocks} finds every
 * copy the engine has recorded in the viewer's world (the engine keeps the cell of each block that
 * draws a map marker, once its chunk has generated); {@code Positions} adds fixed ones. The two unite.
 */
public final class GatewayAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, GatewayAsset>> {

    /** Where these files live. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/Gateways";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private Boolean enabled;
    @Nullable private WorldSelector where;
    @Nullable private Into into;
    @Nullable private String[] blocks;
    @Nullable private Vec3[] positions;

    public static final AssetBuilderCodec<String, GatewayAsset> CODEC = AssetBuilderCodec.builder(
                    GatewayAsset.class,
                    GatewayAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                    (a, v) -> a.enabled = v, a -> a.enabled, (a, p) -> a.enabled = p.enabled)
            .metadata(EditorSchema.defaultValue(true))
            .documentation("Whether a pointer ever leads here. Unauthored means true. To switch off one "
                    + "of the base game's portals, ship a file with its block's id and false.").add()
            .appendInherited(new KeyedCodec<>("Where", WorldSelector.CODEC, false),
                    (a, v) -> a.where = v, a -> a.where, (a, p) -> a.where = p.where)
            .documentation("The worlds the gateway stands in. Unauthored means every world, which suits "
                    + "a block worldgen places anywhere.").add()
            .appendInherited(new KeyedCodec<>("Into", Into.CODEC, false),
                    (a, v) -> a.into = v, a -> a.into, (a, p) -> a.into = p.into)
            .documentation("The world it leads into. A character whose own Where matches that world is "
                    + "reached through this gateway.").add()
            .appendInherited(new KeyedCodec<>("Blocks", Codec.STRING_ARRAY, false),
                    (a, v) -> a.blocks = v, a -> a.blocks, (a, p) -> a.blocks = p.blocks)
            .metadata(EditorSchema.assetRef(BlockType.class))
            .documentation("Blocks that are the way in. Every copy the engine has recorded in the "
                    + "player's world counts, which only a block drawing a map marker gets, and only "
                    + "once its chunk has generated.").add()
            .appendInherited(new KeyedCodec<>("Positions", new ArrayCodec<>(Vec3.CODEC, Vec3[]::new), false),
                    (a, v) -> a.positions = v, a -> a.positions, (a, p) -> a.positions = p.positions)
            .documentation("Fixed spots that are the way in, in every world Where matches: a hand-built "
                    + "portal, or one at a fixed place inside an instance.").add()
            .build();

    public GatewayAsset() {
    }

    /** Java-side factory, for a test or the engine layer. */
    @Nonnull
    public static GatewayAsset of(@Nonnull String id, @Nullable Boolean enabled, @Nullable WorldSelector where,
            @Nullable Into into, @Nullable String[] blocks, @Nullable Vec3[] positions) {
        GatewayAsset a = new GatewayAsset();
        a.id = id;
        a.enabled = enabled;
        a.where = where;
        a.into = into;
        a.blocks = blocks;
        a.positions = positions;
        return a;
    }

    @Override
    public String getId() {
        return id;
    }

    @Nullable
    public WorldSelector getWhere() {
        return where;
    }

    @Nullable
    public Into getInto() {
        return into;
    }

    /** {@code Enabled}, unauthored true. */
    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    /** Does it stand in the world called {@code worldName} running {@code worldGameplayConfig}? */
    public boolean standsIn(@Nullable String worldName, @Nullable String worldGameplayConfig) {
        return where == null || where.isBlank() || where.match(worldName, worldGameplayConfig) != null;
    }

    /** Does it lead into a world one of {@code targetWheres} matches? An empty {@code Into} leads nowhere. */
    public boolean leadsInto(@Nonnull Collection<WorldSelector> targetWheres) {
        if (into == null || into.isBlank()) {
            return false;
        }
        for (WorldSelector targetWhere : targetWheres) {
            if (targetWhere != null && targetWhere.match(into.getWorld(), into.getGameplayConfig()) != null) {
                return true;
            }
        }
        return false;
    }

    /** The authored block ids, trimmed, blanks dropped. */
    @Nonnull
    public List<String> blockIds() {
        List<String> out = new ArrayList<>();
        if (blocks != null) {
            for (String block : blocks) {
                if (block != null && !block.isBlank()) {
                    out.add(block.trim());
                }
            }
        }
        return out;
    }

    /** The authored fixed positions, nulls dropped. */
    @Nonnull
    public List<Vec3> fixedPositions() {
        List<Vec3> out = new ArrayList<>();
        if (positions != null) {
            for (Vec3 position : positions) {
                if (position != null) {
                    out.add(position);
                }
            }
        }
        return out;
    }

    /** The world a gateway leads into, named in the two terms a {@code Where} scores. */
    public static final class Into {

        @Nullable protected String world;
        @Nullable protected String gameplayConfig;

        public static final BuilderCodec<Into> CODEC = BuilderCodec.builder(Into.class, Into::new)
                .appendInherited(new KeyedCodec<>("World", Codec.STRING, false),
                        (o, v) -> o.world = v, o -> o.world, (o, p) -> o.world = p.world)
                .documentation("The exact name of the world it leads into: a persistent world such as "
                        + "default, or an instance whose name is pinned. An unpinned instance's name "
                        + "changes on every visit, so name its GameplayConfig instead.").add()
                .appendInherited(new KeyedCodec<>("GameplayConfig", Codec.STRING, false),
                        (o, v) -> o.gameplayConfig = v, o -> o.gameplayConfig,
                        (o, p) -> o.gameplayConfig = p.gameplayConfig)
                .documentation("The GameplayConfig id the world it leads into runs, the same on every visit "
                        + "to an instance (the Forgotten Temple runs ForgottenTemple).").add()
                .build();

        public Into() {
        }

        @Nonnull
        public static Into of(@Nullable String world, @Nullable String gameplayConfig) {
            Into i = new Into();
            i.world = world;
            i.gameplayConfig = gameplayConfig;
            return i;
        }

        @Nullable
        public String getWorld() {
            return world == null || world.isBlank() ? null : world.trim();
        }

        @Nullable
        public String getGameplayConfig() {
            return gameplayConfig == null || gameplayConfig.isBlank() ? null : gameplayConfig.trim();
        }

        /** True when it names no world at all. */
        public boolean isBlank() {
            return getWorld() == null && getGameplayConfig() == null;
        }
    }
}
