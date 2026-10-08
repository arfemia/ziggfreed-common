package com.ziggfreed.common.worldmap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bson.BsonDocument;
import org.bson.BsonValue;

import com.hypixel.hytale.builtin.instances.InstancesPlugin;
import com.hypixel.hytale.builtin.instances.config.InstanceWorldConfig;
import com.hypixel.hytale.builtin.instances.interactions.TeleportInstanceInteraction;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.hypixel.hytale.server.core.universe.world.WorldConfig;
import com.ziggfreed.common.util.SafeLog;

/**
 * The gateways the base game already describes, read off the engine so no pack has to write them:
 * every block that draws a map marker (so the engine records where each copy stands) and teleports
 * whoever touches it into an instance leads into the world that instance spawns. One gateway per
 * block item, under the item's id, into the defaults layer of {@link GatewayConfig}; a pack or owner
 * file of that id replaces it.
 *
 * <p>How it reads: a block's interactions name root interactions, whose interactions include the
 * teleport. The teleport keeps its instance name and key in private fields, so they are read back
 * through its own public codec. The instance's world comes from its {@code instance.bson}, exactly
 * as the engine reads it when it spawns one: the pinned world name (its {@code InstanceKey}) when
 * there is one, and its {@code GameplayConfig}.
 *
 * <p>Read once per boot, at {@code BootEvent} and on first use, whichever comes first. A failure
 * leaves the layer as it was and says so once.
 */
public final class PortalGateways {

    /** One block that draws a map marker and teleports into an instance. */
    public record PortalBlock(@Nonnull String blockId, @Nonnull String instanceName, @Nullable String instanceKey) {
    }

    /** What an instance asset says about the world it spawns: its pinned name, if any, and its GameplayConfig. */
    public record InstanceTarget(@Nullable String worldName, @Nullable String gameplayConfig) {
    }

    private static volatile boolean derived;

    private PortalGateways() {
    }

    /** Read the base game's portals into {@link GatewayConfig} once; later calls return at once. Any thread. */
    public static void ensureDerived() {
        if (derived) {
            return;
        }
        synchronized (PortalGateways.class) {
            if (derived) {
                return;
            }
            derived = true;
            try {
                Map<String, GatewayAsset> found = derive(scanBlocks(), PortalGateways::readInstance);
                GatewayConfig.getInstance().loadDefaults(found);
                if (found.isEmpty()) {
                    SafeLog.info("[gateway] no block both draws a map marker and leads into an instance, so the"
                            + " base game gives no gateway; a pack can describe one at Server/ZiggfreedCommon/"
                            + "Gateways/<Id>.json");
                } else {
                    SafeLog.info("[gateway] the base game's portals give " + found.size() + " gateway(s): "
                            + describe(found.values()));
                }
            } catch (Throwable t) {
                SafeLog.warn("[gateway] the base game's portals could not be read; only gateway files count: "
                        + t.getMessage());
            }
        }
    }

    /**
     * Pure: each portal block's gateway, keyed by block id (first spelling of an id kept, the rest
     * folded into it). The block's own {@code InstanceKey} names the world before the instance's; a
     * portal into an instance nobody ships, or one naming neither a world nor a GameplayConfig, gives none.
     */
    @Nonnull
    public static Map<String, GatewayAsset> derive(@Nonnull Collection<PortalBlock> blocks,
            @Nonnull Function<String, InstanceTarget> instanceOf) {
        Map<String, GatewayAsset> out = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (PortalBlock block : blocks) {
            if (block == null || block.blockId().isBlank() || block.instanceName().isBlank()) {
                continue;
            }
            String id = block.blockId().trim();
            if (!seen.add(id.toLowerCase(Locale.ROOT))) {
                continue;
            }
            InstanceTarget target = instanceOf.apply(block.instanceName().trim());
            if (target == null) {
                continue;
            }
            String key = block.instanceKey();
            String world = key != null && !key.isBlank() ? key : target.worldName();
            GatewayAsset.Into into = GatewayAsset.Into.of(world, target.gameplayConfig());
            if (into.isBlank()) {
                continue;
            }
            out.put(id, GatewayAsset.of(id, null, null, into, new String[]{id}, null));
        }
        return out;
    }

    /** Test seam: forget that the portals were read, so the next {@link #ensureDerived} reads again. */
    static synchronized void resetForTests() {
        derived = false;
    }

    /** Every block type that draws a map marker and has a teleport into an instance among its interactions. */
    @Nonnull
    private static List<PortalBlock> scanBlocks() {
        List<PortalBlock> out = new ArrayList<>();
        for (BlockType type : BlockType.getAssetMap().getAssetMap().values()) {
            try {
                if (type == null || Gateways.markerNameOf(type) == null) {
                    continue;
                }
                Map<InteractionType, String> interactions = type.getInteractions();
                if (interactions == null) {
                    continue;
                }
                for (String rootId : interactions.values()) {
                    TeleportInstanceInteraction teleport = teleportIn(rootId);
                    BsonDocument authored = teleport == null ? null : authoredOf(teleport);
                    String instance = stringOf(authored, "InstanceName");
                    if (instance != null) {
                        out.add(new PortalBlock(baseIdOf(type), instance, stringOf(authored, "InstanceKey")));
                    }
                }
            } catch (Throwable t) {
                SafeLog.fine("[gateway] a block's interactions could not be read: " + t.getMessage());
            }
        }
        return out;
    }

    @Nullable
    private static TeleportInstanceInteraction teleportIn(@Nullable String rootId) {
        RootInteraction root = rootId == null ? null : RootInteraction.getAssetMap().getAsset(rootId);
        String[] ids = root == null ? null : root.getInteractionIds();
        if (ids == null) {
            return null;
        }
        for (String id : ids) {
            Interaction interaction = id == null ? null : Interaction.getAssetMap().getAsset(id);
            if (interaction instanceof TeleportInstanceInteraction teleport) {
                return teleport;
            }
        }
        return null;
    }

    /**
     * The teleport's authored fields, read back through its own public codec (it has no getters). The
     * two-argument encode: the one-argument form is deprecated and only fills in an empty ExtraInfo.
     */
    @Nullable
    private static BsonDocument authoredOf(@Nonnull TeleportInstanceInteraction teleport) {
        return TeleportInstanceInteraction.CODEC.encode(teleport, new ExtraInfo());
    }

    @Nullable
    private static String stringOf(@Nullable BsonDocument document, @Nonnull String key) {
        BsonValue value = document == null ? null : document.get(key);
        String text = value != null && value.isString() ? value.asString().getValue() : null;
        return text == null || text.isBlank() ? null : text.trim();
    }

    /** The block's item id, so every state of one block is one gateway. */
    @Nonnull
    private static String baseIdOf(@Nonnull BlockType type) {
        return type.getItem() != null && type.getItem().getId() != null ? type.getItem().getId() : type.getId();
    }

    /** The world {@code instanceName} spawns, as its {@code instance.bson} says; null when nobody ships it. */
    @Nullable
    private static InstanceTarget readInstance(@Nonnull String instanceName) {
        try {
            if (!InstancesPlugin.doesInstanceAssetExist(instanceName)) {
                return null;
            }
            WorldConfig config = WorldConfig.load(InstancesPlugin.getInstanceAssetPath(instanceName)
                    .resolve(InstancesPlugin.CONFIG_FILENAME)).join();
            if (config == null) {
                return null;
            }
            InstanceWorldConfig instance = InstanceWorldConfig.get(config);
            return new InstanceTarget(instance == null ? null : instance.getInstanceKey(), config.getGameplayConfig());
        } catch (Throwable t) {
            SafeLog.warn("[gateway] instance '" + instanceName + "' could not be read: " + t.getMessage());
            return null;
        }
    }

    @Nonnull
    private static String describe(@Nonnull Collection<GatewayAsset> gateways) {
        List<String> parts = new ArrayList<>();
        for (GatewayAsset gateway : gateways) {
            GatewayAsset.Into into = gateway.getInto();
            parts.add(gateway.getId() + " into " + (into == null ? "?" : into.getGameplayConfig())
                    + (into == null || into.getWorld() == null ? "" : " (world " + into.getWorld() + ")"));
        }
        return String.join(", ", parts);
    }
}
