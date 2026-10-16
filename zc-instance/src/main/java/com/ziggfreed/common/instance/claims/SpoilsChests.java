package com.ziggfreed.common.instance.claims;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.asset.type.blockhitbox.BlockBoundingBoxes;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.BlockEntity;
import com.hypixel.hytale.server.core.modules.entity.component.Interactable;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.interaction.Interactions;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.encounter.asset.EncounterBindingAsset;
import com.ziggfreed.common.encounter.asset.EncounterBindingConfig;
import com.ziggfreed.common.encounter.event.EncounterDefeatedEvent;
import com.ziggfreed.common.encounter.ledger.ParticipantShare;
import com.ziggfreed.common.encounter.payout.EncounterClaims;
import com.ziggfreed.common.encounter.run.EncounterRun;
import com.ziggfreed.common.feedback.EventTitles;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.loot.claim.Claim;
import com.ziggfreed.common.loot.claim.ClaimAddedEvent;
import com.ziggfreed.common.loot.claim.ClaimStore;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.world.TickingSections;

/**
 * The spoils chest: one prop a won boss fight leaves where the boss fell, in which every credited player finds only
 * their own unclaimed Claims from that fight (a fight's Claims carry its run id, {@link Claim#fight()}). The chest IS
 * those Claims, so nothing in it is ever lost: whatever a player leaves in it waits under Claims, forever.
 *
 * <p><b>When it stands.</b> A defeat of a fight its binding row names, with {@code Spoils.Enabled} (the default), a
 * place the boss fell and someone credited, is noted here ({@link #onDefeated}); the chest goes up at the first Claim
 * that fight writes ({@link #onClaimAdded}), so a fight whose rolls land nothing shows no chest. Both events come
 * before any Claim of the fight: the defeat is announced before its row's loot pays, and a script's {@code ZigClaim}
 * pays after the defeat. The chest is raised on the fight's world thread, once its chunk section ticks
 * ({@link TickingSections#whenTicking}), never inside the system that wrote the Claim.
 *
 * <p><b>What it is.</b> The engine's own interactable block prop, as vanilla's {@code CarriedBlock.dropAndClear}
 * builds one: a {@code BlockEntity} with the row's {@code Spoils.Prop} look, its {@code Interactable} marker and an
 * {@code Interactions} map whose Use runs {@link #ROOT_INTERACTION} (the {@code ZigOpenSpoils} Type,
 * {@link ZigOpenSpoilsInteraction}), with {@link #HINT_KEY} as the prompt, plus {@link SpoilsChest}, the mark that names
 * its fight and when it goes.
 *
 * <p><b>When it goes</b> ({@link SpoilsChestSystems}): once every credited player holds nothing more from the fight
 * (taken from the chest or from the Claims tab), once its {@code Spoils.Lifetime} runs out, or with its world. A chest
 * this session does not know (one a restart left on disk) is removed the moment it loads. A Claim the fight writes
 * after its emptied chest went raises it again while the lifetime lasts.
 *
 * <p>Thread-safe: the events arrive on whatever thread paid, the upkeep on the world thread, and every entry guards
 * its own chest.
 */
public final class SpoilsChests {

    static final String LOG = "[spoils] ";

    /** The root interaction a chest's Use runs, shipped in this module ({@code Server/Item/RootInteractions/}). */
    public static final String ROOT_INTERACTION = "Zig_Open_Spoils";

    /** The prompt a player looking at a chest reads, in {@code ziggfreedcommon.claims.lang}. */
    public static final String HINT_KEY = ClaimsText.PREFIX + "spoils.hint";

    /** The library's line under the banner {@link #tellInChest} raises, in {@code ziggfreedcommon.claims.lang}. */
    public static final String BANNER_KEY = ClaimsText.PREFIX + "spoils.banner";

    /** How often a standing chest asks whether it should go. */
    static final long CHECK_EVERY_MS = 1_000L;

    /** Why the upkeep takes a chest down, or that it keeps it. */
    enum Verdict {
        KEEP, EMPTIED, EXPIRED, UNKNOWN
    }

    /** One won fight's spoils, by its fight id (lower-cased). */
    private static final Map<String, Spoils> FIGHTS = new ConcurrentHashMap<>();

    /** The props (lower-cased) already warned about as naming no block, one warning each. */
    private static final Set<String> WARNED_PROPS = ConcurrentHashMap.newKeySet();

    private SpoilsChests() {
    }

    /** What one won fight left: where, for whom, how it looks, until when, and the chest standing for it. */
    private static final class Spoils {
        final String fight;
        final String encounterId;
        final UUID world;
        final Vector3d at;
        final Set<UUID> credited;
        final String blockKey;
        final long expiresAt;
        /** The standing chest's entity uuid, or null while none stands. Guarded by this. */
        @Nullable UUID chest;
        /** True while a raise is on its way. Guarded by this. */
        boolean raising;

        Spoils(@Nonnull String fight, @Nonnull String encounterId, @Nonnull UUID world, @Nonnull Vector3d at,
                @Nonnull Set<UUID> credited, @Nonnull String blockKey, long expiresAt) {
            this.fight = fight;
            this.encounterId = encounterId;
            this.world = world;
            this.at = at;
            this.credited = Set.copyOf(credited);
            this.blockKey = blockKey;
            this.expiresAt = expiresAt;
        }

        @Nonnull
        String head() {
            return "run=" + shortId(fight) + " encounter=" + encounterId;
        }
    }

    // ==================== the events ====================

    /**
     * A boss is down: note its spoils when its binding row leaves a chest. Nothing for a fight no row names, a row
     * that switches the chest off, a defeat that names no place or no world, or one that credited nobody. Never throws.
     */
    public static void onDefeated(@Nonnull EncounterDefeatedEvent event) {
        try {
            EncounterBindingAsset row = EncounterBindingConfig.getInstance().forEncounter(event.encounterId());
            if (row == null || !row.spoilsOrDefault().enabled()) {
                return;
            }
            Vector3d at = event.deathPosition();
            UUID world = event.worldUuid();
            Set<UUID> credited = credited(event);
            if (at == null || world == null || credited.isEmpty()) {
                SafeLog.fine(LOG + "no chest for run=" + EncounterRun.shortId(event.runId()) + " encounter="
                        + event.encounterId() + ": " + (credited.isEmpty() ? "nobody was credited"
                        : "the defeat names no place to stand it"));
                return;
            }
            EncounterBindingAsset.Spoils spoils = row.spoilsOrDefault();
            String blockKey = blockKeyOf(spoils.prop(), event.encounterId());
            if (blockKey == null) {
                return;
            }
            long now = System.currentTimeMillis();
            prune(now);
            String fight = EncounterClaims.fightOf(event.runId());
            FIGHTS.put(key(fight), new Spoils(fight, event.encounterId(), world, at, credited, blockKey,
                    now + spoils.lifetimeSeconds() * 1000L));
        } catch (Throwable t) {
            SafeLog.warn(LOG + "the spoils of '" + event.encounterId() + "' could not be noted, so it leaves no chest", t);
        }
    }

    /**
     * A Claim was written: the first one of a noted fight raises its chest, on the fight's world. Dispatched inside
     * the Claims store's lock, so this only queues. Never throws.
     */
    public static void onClaimAdded(@Nonnull ClaimAddedEvent event) {
        try {
            String fight = event.claim().fight();
            Spoils spoils = fight == null ? null : FIGHTS.get(key(fight));
            if (spoils == null) {
                return;
            }
            if (System.currentTimeMillis() >= spoils.expiresAt) {
                FIGHTS.remove(key(fight), spoils);
                return;
            }
            synchronized (spoils) {
                if (spoils.chest != null || spoils.raising) {
                    return;
                }
                spoils.raising = true;
            }
            World world = Universe.get().getWorld(spoils.world);
            if (world == null || !world.isAlive()) {
                settleRaise(spoils, null);
                return;
            }
            world.execute(() -> TickingSections.whenTicking(world, spoils.at.x, spoils.at.y, spoils.at.z,
                    () -> raise(world, spoils),
                    reason -> {
                        settleRaise(spoils, null);
                        SafeLog.info(LOG + "no chest " + spoils.head() + ": " + reason);
                    }));
        } catch (Throwable t) {
            SafeLog.warn(LOG + "a spoils chest could not be raised", t);
        }
    }

    /** A world closing: its fights' spoils go with it (a chest saved with it is removed when it loads again). */
    public static void forgetWorld(@Nonnull World world) {
        try {
            UUID uuid = world.getWorldConfig().getUuid();
            FIGHTS.values().removeIf(spoils -> spoils.world.equals(uuid));
        } catch (Throwable t) {
            SafeLog.fine(LOG + "a closing world's spoils could not be dropped: " + t.getMessage());
        }
    }

    // ==================== the chest ====================

    /** On the fight's world thread, its section ticking: stand the chest at the place the boss fell. */
    private static void raise(@Nonnull World world, @Nonnull Spoils spoils) {
        UUID chest = null;
        try {
            Store<EntityStore> store = world.getEntityStore().getStore();
            UUIDComponent id = UUIDComponent.randomUUID();
            Holder<EntityStore> holder = EntityStore.REGISTRY.newHolder();
            holder.addComponent(BlockEntity.getComponentType(), new BlockEntity(spoils.blockKey));
            Vector3d centre = new Vector3d(spoils.at).add(0.0, halfHeight(spoils.blockKey), 0.0);
            holder.addComponent(TransformComponent.getComponentType(),
                    new TransformComponent(centre, new Rotation3f(0.0F, 0.0F, 0.0F)));
            holder.addComponent(UUIDComponent.getComponentType(), id);
            holder.ensureComponent(Interactable.getComponentType());
            Interactions use = new Interactions(Map.of(InteractionType.Use, ROOT_INTERACTION));
            use.setInteractionHint(HINT_KEY);
            holder.addComponent(Interactions.getComponentType(), use);
            if (SpoilsChest.TYPE == null) {
                SafeLog.warn(LOG + "no chest " + spoils.head() + ": its mark is not registered");
                return;
            }
            holder.addComponent(SpoilsChest.TYPE, SpoilsChest.of(spoils.fight, spoils.expiresAt));
            store.addEntity(holder, AddReason.SPAWN);
            chest = id.getUuid();
            SafeLog.info(LOG + "chest up " + spoils.head() + " prop=" + spoils.blockKey + " at "
                    + Math.round(centre.x) + "," + Math.round(centre.y) + "," + Math.round(centre.z) + " for "
                    + spoils.credited.size() + " credited, " + (spoils.expiresAt - System.currentTimeMillis()) / 1000L
                    + "s");
        } catch (Throwable t) {
            SafeLog.warn(LOG + "the chest " + spoils.head() + " could not be stood up; its Claims wait under Claims", t);
        } finally {
            settleRaise(spoils, chest);
        }
    }

    /** End a raise: the chest that now stands, or none. */
    private static void settleRaise(@Nonnull Spoils spoils, @Nullable UUID chest) {
        synchronized (spoils) {
            spoils.raising = false;
            spoils.chest = chest;
        }
    }

    /** Half the block's hitbox height: a block entity stands on its centre. Half a block when it cannot be read. */
    private static double halfHeight(@Nonnull String blockKey) {
        try {
            BlockType type = BlockType.getAssetMap().getAsset(blockKey);
            BlockBoundingBoxes boxes = type == null ? null : BlockBoundingBoxes.getAssetMap().getAsset(type.getHitboxTypeIndex());
            return boxes == null ? 0.5 : boxes.get(0).getBoundingBox().height() / 2.0;
        } catch (Throwable t) {
            return 0.5;
        }
    }

    // ==================== the upkeep's questions ====================

    /**
     * What the upkeep does with a chest marked {@code mark} on the entity {@code entity} at {@code nowMs}: take it down
     * once its lifetime is over, once this session knows no spoils of its fight standing as this entity (a chest a
     * restart left behind, or a second copy), or once every credited player has taken everything the fight paid them.
     */
    @Nonnull
    static Verdict verdict(@Nonnull SpoilsChest mark, @Nullable UUID entity, long nowMs) {
        if (nowMs >= mark.expiresAt()) {
            return Verdict.EXPIRED;
        }
        String fight = mark.fight();
        Spoils spoils = fight == null ? null : FIGHTS.get(key(fight));
        if (spoils == null || entity == null) {
            return Verdict.UNKNOWN;
        }
        synchronized (spoils) {
            if (!entity.equals(spoils.chest)) {
                return Verdict.UNKNOWN;
            }
        }
        return emptied(spoils) ? Verdict.EMPTIED : Verdict.KEEP;
    }

    /**
     * The upkeep took the chest {@code entity} of {@code fight} down for {@code verdict}: an emptied chest's spoils
     * stay noted until their lifetime runs out, so a Claim the fight writes later raises it again; an expired one's go.
     */
    static void takenDown(@Nullable String fight, @Nullable UUID entity, @Nonnull Verdict verdict) {
        Spoils spoils = fight == null ? null : FIGHTS.get(key(fight));
        SafeLog.info(LOG + "chest down " + (spoils == null ? "run=" + (fight == null ? "?" : shortId(fight))
                + " (not this session's)" : spoils.head()) + " reason=" + verdict.name().toLowerCase(Locale.ROOT));
        if (spoils == null) {
            return;
        }
        synchronized (spoils) {
            if (entity != null && entity.equals(spoils.chest)) {
                spoils.chest = null;
            }
        }
        if (verdict == Verdict.EXPIRED) {
            FIGHTS.remove(key(fight), spoils);
        }
    }

    /** Whether no credited player holds a Claim the fight paid. */
    private static boolean emptied(@Nonnull Spoils spoils) {
        ClaimStore claims = ClaimStore.shared();
        for (UUID player : spoils.credited) {
            for (Claim claim : claims.list(player)) {
                if (claim.fromFight(spoils.fight)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Whether {@code claim} is in a spoils chest: its fight's chest stands, or is on its way up, and has not run
     * out. A chest that could not be stood (no ticking section, a failed spawn) or has been taken down holds nothing,
     * so its Claims read as waiting under Claims alone. What a notice says of where the Claim waits.
     */
    public static boolean inChest(@Nonnull Claim claim) {
        String fight = claim.fight();
        Spoils spoils = fight == null ? null : FIGHTS.get(key(fight));
        if (spoils == null || System.currentTimeMillis() >= spoils.expiresAt) {
            return false;
        }
        synchronized (spoils) {
            return spoils.chest != null || spoils.raising;
        }
    }

    // ==================== a later payout's way into the chest ====================

    /**
     * Whether a reward {@code player} earns after {@code fight} goes into that fight's chest: the fight's spoils are
     * noted (a won fight whose row leaves a chest), have not run out, and credited the player. A source that pays
     * once the fight is over (a dungeon's clear) asks this, and when it answers true files its Claim under the fight
     * ({@link Claim#fight}), so the chest offers it with the fight's own Claims (and stands for it, when the fight
     * itself paid nothing) and nothing else needs to show it. Never throws.
     */
    public static boolean takes(@Nullable String fight, @Nullable UUID player) {
        if (fight == null || fight.isBlank() || player == null) {
            return false;
        }
        Spoils spoils = FIGHTS.get(key(fight));
        return spoils != null && System.currentTimeMillis() < spoils.expiresAt && spoils.credited.contains(player);
    }

    /**
     * Tell {@code playerRef} that their reward went into a chest ({@link #takes}): the game's banner, {@code headline}
     * over the line {@code lineKey} names (the source's own, which a pack authors), else the library's
     * ({@link #BANNER_KEY}). On the player's own world thread; nothing for a player who is gone. Never throws.
     */
    public static void tellInChest(@Nonnull PlayerRef playerRef, @Nonnull Message headline, @Nullable String lineKey) {
        try {
            Ref<EntityStore> ref = playerRef.getReference();
            World world = ref == null || !ref.isValid() ? null : ref.getStore().getExternalData().getWorld();
            if (world == null || !world.isAlive()) {
                return;
            }
            Message line = lineKey == null || lineKey.isBlank() ? Msg.key(BANNER_KEY) : ContentKeys.tr(lineKey.trim());
            Runnable show = () -> EventTitles.show(playerRef, headline, line, true);
            if (world.isInThread()) {
                show.run();
            } else {
                world.execute(show);
            }
        } catch (Throwable t) {
            SafeLog.warn(LOG + "a player could not be told their reward is in the chest", t);
        }
    }

    // ==================== the pure pieces ====================

    /** Every participant the defeat credited. */
    @Nonnull
    private static Set<UUID> credited(@Nonnull EncounterDefeatedEvent event) {
        Set<UUID> out = new HashSet<>();
        for (ParticipantShare share : event.participantShares()) {
            if (share.credited()) {
                out.add(share.playerId());
            }
        }
        return out;
    }

    /**
     * The block the chest draws for {@code prop}: a block item's block, or a block named directly. A prop that names
     * neither falls back to {@link EncounterBindingAsset.Spoils#DEFAULT_PROP}, with one warning per prop; null when
     * even that cannot be drawn on this server.
     */
    @Nullable
    private static String blockKeyOf(@Nonnull String prop, @Nonnull String encounterId) {
        String block = blockOf(prop);
        if (block != null) {
            return block;
        }
        if (WARNED_PROPS.add(prop.toLowerCase(Locale.ROOT))) {
            SafeLog.warn(LOG + "the binding row of '" + encounterId + "' writes Spoils.Prop '" + prop + "', which "
                    + "names no block item on this server, so its chest takes the default look");
        }
        return blockOf(EncounterBindingAsset.Spoils.DEFAULT_PROP);
    }

    @Nullable
    private static String blockOf(@Nonnull String prop) {
        Item item = Item.getAssetMap().getAsset(prop);
        if (item != null && item.hasBlockType() && item.getBlockId() != null) {
            return item.getBlockId();
        }
        return BlockType.getAssetMap().getAsset(prop) != null ? prop : null;
    }

    /** Drop every fight whose chest would have gone by now. */
    private static void prune(long nowMs) {
        FIGHTS.values().removeIf(spoils -> nowMs >= spoils.expiresAt);
    }

    @Nonnull
    private static String key(@Nonnull String fight) {
        return fight.trim().toLowerCase(Locale.ROOT);
    }

    /** A fight id's short form for a log line, the run's own when it is a run id. */
    @Nonnull
    private static String shortId(@Nonnull String fight) {
        try {
            return EncounterRun.shortId(UUID.fromString(fight.trim()));
        } catch (IllegalArgumentException e) {
            return fight;
        }
    }
}
