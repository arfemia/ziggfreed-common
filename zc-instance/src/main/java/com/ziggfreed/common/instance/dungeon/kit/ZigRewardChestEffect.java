package com.ziggfreed.common.instance.dungeon.kit;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.builtin.triggervolumes.YawRotation;
import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerContext;
import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerEffect;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.math.vector.Vector3dUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.instance.claims.ClaimRolls;
import com.ziggfreed.common.instance.claims.RewardChestAsset;
import com.ziggfreed.common.instance.claims.SpoilsChests;
import com.ziggfreed.common.instance.dungeon.DungeonRuns;
import com.ziggfreed.common.loot.LootableAsset;
import com.ziggfreed.common.loot.LootableConfig;
import com.ziggfreed.common.loot.claim.Claim;
import com.ziggfreed.common.util.SafeLog;

/**
 * A reward chest a room raises for its dungeon run: the trigger-volume effect {@code ZigRewardChest}. The first time
 * it fires in a run (a member walking in, a lock opened, a fight's signal), the reward chest file {@code Chest} names
 * stands at {@code Offset} from the volume's origin through the library's one entry ({@code SpoilsChests.stand}, a
 * fresh {@code chest:<uuid>} key), crediting every member standing in the run's area then, each finding the file's
 * {@code Contents} rolled for them alone.
 *
 * <pre>{@code
 * "Effects": [ { "Type": "ZigRewardChest", "Event": "BLOCK_USED", "Chest": "MyPack_Cache_Chest",
 *                "Offset": { "X": 2.5, "Y": 0, "Z": -3.5 },
 *                "OpenerLootable": "MyPack_Lock_Mining", "OpenerTitleKey": "mypack.lock.mining.title" } ]
 * }</pre>
 *
 * <p>With {@code OpenerLootable}, the player who set the volume off (the one who stepped in or used the block; none
 * for a signal) also rolls that table, as one Claim of their own filed under the same chest
 * ({@code chest:<key>:<player>:opener}, titled {@code OpenerTitleKey}, never diminished), so the chest offers it to
 * them beside their share of its contents, and their toast names it in the chest's own words (the file's
 * {@code Text.NoticeKey} in its {@code .named} form over that title). Whatever is left in the chest waits under
 * Claims (M428).
 *
 * <p>It counts once per run for its placement, the volume's own unless {@code Placement} names one, and keeps working
 * after the run's clear until the party leaves (R1000), so a lock opened after the last boss still raises its cache.
 * Outside a dungeon run it does nothing. Never throws. Its settings are flat leaves the in-game trigger-volume
 * inspector edits with real controls ({@code Offset} a vector row, the rest text), each nullable.
 *
 * <p>Registered into the engine's trigger effect map at setup ({@code InstanceBootstrap.registerRoomKit}), the
 * library's manifest depending on the TriggerVolumes plugin that reads it.
 */
public final class ZigRewardChestEffect extends TriggerEffect {

    /** The authored {@code "Type"} name. */
    public static final String TYPE_NAME = "ZigRewardChest";

    /** What the id of the opener's own Claim ends with, after the chest's key and the player. */
    public static final String OPENER_SUFFIX = ":opener";

    private static final String LOG = "[dungeon] ";

    /** Volumes already logged for naming no {@code Chest}, by volume id. */
    private static final Set<String> WARNED_VOLUMES = ConcurrentHashMap.newKeySet();

    /** Opener tables (lower-cased) no loaded file defines, already logged. */
    private static final Set<String> WARNED_TABLES = ConcurrentHashMap.newKeySet();

    @Nullable
    private String chest;
    @Nullable
    private Vector3d offset;
    @Nullable
    private String openerLootable;
    @Nullable
    private String openerTitleKey;
    @Nullable
    private String placement;

    /** Lazy holder, as an interaction Type's: only registration builds the codec. */
    private static final class Holder {
        static final BuilderCodec<ZigRewardChestEffect> CODEC = BuilderCodec.builder(ZigRewardChestEffect.class,
                        ZigRewardChestEffect::new, TriggerEffect.BASE_CODEC)
                .documentation("Raises a reward chest for a dungeon run, once per run: every member standing in the "
                        + "run's area finds their own share of what it holds, and the player who set it off may find "
                        + "a reward of their own in it too. Outside a dungeon run it does nothing.")
                .append(new KeyedCodec<>("Chest", Codec.STRING, false), (e, v) -> e.chest = v, e -> e.chest)
                .metadata(EditorSchema.assetRef(RewardChestAsset.class))
                .documentation("The reward chest file that stands, by id (a file under "
                        + "Server/ZiggfreedCommon/RewardChests/): its look, what it holds for each member, its words "
                        + "and when it goes. Unauthored, the volume raises nothing.").add()
                .append(new KeyedCodec<>("Offset", Vector3dUtil.CODEC, false), (e, v) -> e.offset = v, e -> e.offset)
                .metadata(EditorSchema.defaultVector(0.0, 0.0, 0.0))
                .documentation("Where the chest's foot stands, in blocks from the volume's Position: X and Z on a "
                        + "cell's middle (2.5, not 2), Y on the floor it stands on. It turns with the room when the "
                        + "room is placed turned. Unauthored means 0 0 0, the Position itself.").add()
                .append(new KeyedCodec<>("OpenerLootable", Codec.STRING, false),
                        (e, v) -> e.openerLootable = v, e -> e.openerLootable)
                .metadata(EditorSchema.assetRef(LootableAsset.class))
                .documentation("A loot table rolled only for the player who set the volume off (who stepped in, or "
                        + "used the block), as a reward of their own in the same chest, never diminished. A signal "
                        + "sets it off for nobody, so it rolls for nobody. Unauthored, none.").add()
                .append(new KeyedCodec<>("OpenerTitleKey", Codec.STRING, false),
                        (e, v) -> e.openerTitleKey = v, e -> e.openerTitleKey)
                .documentation("The localization key of the title the opener's own reward reads, in the chest, "
                        + "under Claims and in its toast. Unauthored, the tab's own word for a reward.").add()
                .append(new KeyedCodec<>("Placement", Codec.STRING, false),
                        (e, v) -> e.placement = v, e -> e.placement)
                .documentation("What the chest stands once per run under. Unauthored means this volume, so every "
                        + "copy of a room raises its own; give several volumes the same Placement and only the first "
                        + "to fire raises one.").add()
                .build();
    }

    /** The codec, built on first call. Only registration and a codec test invoke this. */
    @Nonnull
    public static BuilderCodec<ZigRewardChestEffect> codec() {
        return Holder.CODEC;
    }

    @Override
    public void execute(@Nonnull TriggerContext context) {
        try {
            String chestId = trimmed(chest);
            if (chestId == null) {
                String volume = RoomKitTrigger.placement(context, null);
                if (WARNED_VOLUMES.add(volume)) {
                    SafeLog.warn(LOG + "the trigger volume " + volume + " has a " + TYPE_NAME
                            + " effect naming no Chest, so it raises nothing");
                }
                return;
            }
            UUID player = RoomKitTrigger.playerOf(context);
            DungeonRuns.LiveRun run = RoomKitTrigger.runOf(context, player);
            if (run == null) {
                return;
            }
            String where = RoomKitTrigger.placement(context, placement);
            if (!DungeonRuns.shared().once(run, TYPE_NAME, where)) {
                return;
            }
            World world = context.getStore().getExternalData().getWorld();
            Set<UUID> credited = new LinkedHashSet<>(run.members());
            if (player != null) {
                // The run was found by the area they stand in, so they are a member there even if the 1 Hz roster
                // has not seen them yet.
                credited.add(player);
            }
            Vector3d at = new Vector3d(context.getVolume().getPosition()).add(offset());
            String key = SpoilsChests.newKey();
            SpoilsChests.Stood stood = SpoilsChests.stand(SpoilsChests.Stand.of(chestId, key,
                    world.getWorldConfig().getUuid(), at, credited,
                    DungeonRuns.nameOfSource(DungeonRuns.SOURCE_PREFIX + run.dungeonId()), "room=" + where));
            boolean opener = player != null && payOpener(key, chestId, player);
            SafeLog.info(LOG + "the run " + run.runId() + " raised the chest " + chestId + " at " + where + " for "
                    + credited.size() + " member(s): " + stood.written() + " share(s) written"
                    + (opener ? ", and the opener's own" : "") + (stood.noted() ? "" : ", but no chest was noted"));
        } catch (Throwable t) {
            SafeLog.warn(LOG + "a room's reward chest could not be raised", t);
        }
    }

    /**
     * Roll {@code OpenerLootable} for {@code player} into their own Claim filed under the chest {@code key}; whether a
     * roll was asked for (it may still land nothing, or land later on their world's thread).
     */
    private boolean payOpener(@Nonnull String key, @Nonnull String chestId, @Nonnull UUID player) {
        String tableId = trimmed(openerLootable);
        if (tableId == null) {
            return false;
        }
        LootableAsset table = LootableConfig.getInstance().resolve(tableId);
        if (table == null) {
            if (WARNED_TABLES.add(tableId.toLowerCase(Locale.ROOT))) {
                SafeLog.warn(LOG + "a " + TYPE_NAME + " effect rolls the opener's table '" + tableId
                        + "', which no loaded file defines, so the opener finds nothing of their own");
            }
            return false;
        }
        String source = SpoilsChests.KEY_PREFIX + chestId.toLowerCase(Locale.ROOT);
        Claim draft = new Claim(openerClaimId(key, player), source, trimmed(openerTitleKey), List.of(), null,
                List.of(), System.currentTimeMillis(), key);
        ClaimRolls.award(new ClaimRolls.Payout(player, draft, List.of(ClaimRolls.Draw.once(table)), null, source,
                message -> SafeLog.warn(LOG + message)), claim -> {
                    if (claim == null) {
                        SafeLog.info(LOG + "the opener's table '" + tableId + "' landed nothing for " + player);
                    }
                });
        return true;
    }

    /** The opener's own Claim's id: {@code chest:<key tail>:<player>:opener}, the key's {@code chest:} not doubled. */
    @Nonnull
    static String openerClaimId(@Nonnull String key, @Nonnull UUID player) {
        String tail = key.startsWith(SpoilsChests.KEY_PREFIX) ? key.substring(SpoilsChests.KEY_PREFIX.length()) : key;
        return SpoilsChests.KEY_PREFIX + tail + ":" + player + OPENER_SUFFIX;
    }

    @Nullable
    private static String trimmed(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Nonnull
    private Vector3d offset() {
        return offset == null ? new Vector3d() : new Vector3d(offset);
    }

    /** A room placed turned turns the chest's spot with it, as the engine's own effects do. */
    @Override
    public void rotateInPlace(float yawRadians, @Nonnull Vector3d volumeOrigin) {
        if (Math.abs(yawRadians) < YawRotation.EPSILON || offset == null) {
            return;
        }
        Vector3d turned = new Vector3d(offset);
        YawRotation.rotate(turned, yawRadians);
        offset = turned;
    }
}
