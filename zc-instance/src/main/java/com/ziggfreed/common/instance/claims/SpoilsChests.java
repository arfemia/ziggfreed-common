package com.ziggfreed.common.instance.claims;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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
import com.ziggfreed.common.i18n.NativeNames;
import com.ziggfreed.common.loot.LootableAsset;
import com.ziggfreed.common.loot.LootableConfig;
import com.ziggfreed.common.loot.claim.Claim;
import com.ziggfreed.common.loot.claim.ClaimAddedEvent;
import com.ziggfreed.common.loot.claim.ClaimStore;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.world.TickingSections;

/**
 * The reward chest: one prop a reward source leaves where its moment happened (where a boss fell, where a mob died,
 * where a block broke), in which every player the source credited finds only their own unclaimed Claims from it (the
 * Claims filed under its key, {@link Claim#fight()}). The chest IS those Claims, so nothing in it is ever lost:
 * whatever a player leaves in it waits under Claims, forever.
 *
 * <p><b>The one entry.</b> Every source calls {@link #stand}: a won boss fight ({@link #onDefeated}, its binding row's
 * {@code Spoils}, keyed by the fight's run id), a rare kill or break from a library bonus row (zc-objectives'
 * {@code BonusRowReactions}, a {@code Reward_Chest} reward, keyed {@code chest:<uuid>}), and the admin's
 * {@code /zigclaims chest}. What the chest is comes from the reward chest file the source names
 * ({@link RewardChestAsset}, {@code Zc_Spoils_Chest} for a boss unless its row names another): its look and prompt,
 * who opens it, what it holds of its own ({@code Contents}, rolled once for each credited player here, into one Claim
 * each filed under the chest's key), how opening it shows what it holds ({@link SpoilsView}), its banner, its words
 * and when it goes. A source may still override the look and the lifetime (a binding row's own {@code Spoils.Prop}
 * and {@code Spoils.Lifetime}).
 *
 * <p><b>When it stands.</b> A stand notes the chest; it goes up at the first Claim filed under its key
 * ({@link #onClaimAdded}), so a source that pays nothing shows no chest. A boss's defeat is noted before any Claim of
 * the fight: the defeat is announced before its row's loot pays, and a script's {@code ZigClaim} pays after the
 * defeat. The chest is raised on its world's thread, once its chunk section ticks ({@link TickingSections#whenTicking}),
 * never inside the system that wrote the Claim.
 *
 * <p><b>What it is.</b> The engine's own interactable block prop, as vanilla's {@code CarriedBlock.dropAndClear}
 * builds one: a {@code BlockEntity} with the file's {@code Look.Prop}, its {@code Interactable} marker and an
 * {@code Interactions} map whose Use runs {@link #ROOT_INTERACTION} (the {@code ZigOpenSpoils} Type,
 * {@link ZigOpenSpoilsInteraction}), with the file's {@code Look.HintKey} as the prompt, plus {@link SpoilsChest}, the
 * mark that names its key and when it goes.
 *
 * <p><b>Its banner.</b> A file with a {@code Banner} shows it once to each credited player with a share, as the chest
 * goes up (or as their share lands in a chest already standing), through the game's event title. A source that shows
 * its own banner for the chest ({@link #tellInChest} naming the key: a dungeon's clear that joins a boss's chest)
 * takes that player's place: the chest's own never follows it, and one already shown is replaced by it.
 *
 * <p><b>When it goes</b> ({@link SpoilsChestSystems}): once every credited player holds nothing more from it (taken
 * from the chest or from the Claims tab), unless its file's {@code Despawn.WhenEmptied} is false; once its lifetime
 * runs out; or with its world. A chest this session does not know (one a restart left on disk) is removed the moment
 * it loads. A Claim filed under it after its emptied chest went raises it again while the lifetime lasts.
 *
 * <p>Thread-safe: the events arrive on whatever thread paid, the upkeep on the world thread, and every entry guards
 * its own chest.
 */
public final class SpoilsChests {

    static final String LOG = "[spoils] ";

    /** The root interaction a chest's Use runs, shipped in this module ({@code Server/Item/RootInteractions/}). */
    public static final String ROOT_INTERACTION = "Zig_Open_Spoils";

    /** The prompt a player looking at a chest reads, in {@code ziggfreedcommon.claims.lang}, when its file names none. */
    public static final String HINT_KEY = RewardChestAsset.DEFAULT_HINT_KEY;

    /** The library's line under the banner {@link #tellInChest} raises, in {@code ziggfreedcommon.claims.lang}. */
    public static final String BANNER_KEY = ClaimsText.PREFIX + "spoils.banner";

    /** What the key of a chest no boss fight stands starts with ({@link #newKey}), and a chest's own Claims' source. */
    public static final String KEY_PREFIX = "chest:";

    /** How often a standing chest asks whether it should go. */
    static final long CHECK_EVERY_MS = 1_000L;

    /** Why the upkeep takes a chest down, or that it keeps it. */
    enum Verdict {
        KEEP, EMPTIED, EXPIRED, UNKNOWN
    }

    /** Each noted chest, by its key (lower-cased). */
    private static final Map<String, Spoils> FIGHTS = new ConcurrentHashMap<>();

    /** The props (lower-cased) already warned about as naming no block, one warning each. */
    private static final Set<String> WARNED_PROPS = ConcurrentHashMap.newKeySet();

    /** The chest files (lower-cased, with what was wrong) already warned about, one warning each. */
    private static final Set<String> WARNED_CHESTS = ConcurrentHashMap.newKeySet();

    /** The loot tables (lower-cased) a chest's Contents names that no loaded file defines, one warning each. */
    private static final Set<String> WARNED_TABLES = ConcurrentHashMap.newKeySet();

    private SpoilsChests() {
    }

    /**
     * What a source asks of a chest.
     *
     * @param chest           the reward chest file, by id; null or blank for the library's boss chest
     *                        ({@link RewardChestAsset#SPOILS_CHEST})
     * @param key             what its Claims are filed under ({@link Claim#fight()}): a boss fight's run id, or
     *                        {@link #newKey} for a source with no key of its own
     * @param world           the world it stands in
     * @param at              where it stands, its foot (copied)
     * @param credited        the players it serves, each finding only their own share (copied)
     * @param sourceName      what left it, the banner's {@code {0}}; null for nothing
     * @param source          what left it, for the log ({@code encounter=<id>}, {@code mob=<id>})
     * @param prop            a look that wins over the file's {@code Look.Prop}, or null
     * @param lifetimeSeconds a lifetime that wins over the file's {@code Despawn.Lifetime} when positive, or null
     * @param lootable        a loot table rolled in place of the file's {@code Contents.Lootable}, or null
     */
    public record Stand(@Nullable String chest, @Nonnull String key, @Nonnull UUID world, @Nonnull Vector3d at,
                        @Nonnull Set<UUID> credited, @Nullable Message sourceName, @Nonnull String source,
                        @Nullable String prop, @Nullable Integer lifetimeSeconds, @Nullable String lootable) {

        public Stand {
            at = new Vector3d(at);
            credited = Set.copyOf(credited);
        }

        /** A stand of {@code chest} with nothing overridden: its look, lifetime and contents all the file's. */
        @Nonnull
        public static Stand of(@Nullable String chest, @Nonnull String key, @Nonnull UUID world, @Nonnull Vector3d at,
                @Nonnull Set<UUID> credited, @Nullable Message sourceName, @Nonnull String source) {
            return new Stand(chest, key, world, at, credited, sourceName, source, null, null, null);
        }

        /** This stand rolling {@code table} in place of the file's {@code Contents.Lootable}. */
        @Nonnull
        public Stand withLootable(@Nullable String table) {
            return new Stand(chest, key, world, at, credited, sourceName, source, prop, lifetimeSeconds, table);
        }
    }

    /**
     * What a stand did.
     *
     * @param noted   whether the chest was noted, to go up at its first Claim
     * @param written how many Claims its own {@code Contents} wrote by the time it answered, one per credited player
     *                at most; a credited player standing in another world rolls on that world's thread, after it
     */
    public record Stood(boolean noted, int written) {

        /** A stand that noted nothing. */
        public static final Stood NOTHING = new Stood(false, 0);
    }

    /** One noted chest: where, for whom, what its file says, until when, and the prop standing for it. */
    private static final class Spoils {
        final String fight;
        final String chestId;
        final String source;
        final UUID world;
        final Vector3d at;
        final Set<UUID> credited;
        final String blockKey;
        final String hintKey;
        final long expiresAt;
        final boolean goesWhenEmptied;
        final String leadKey;
        final String noticeKey;
        @Nullable final String bannerTitleKey;
        @Nullable final String bannerSubtitleKey;
        final boolean bannerMajor;
        @Nullable final Message sourceName;
        /** The players a banner for this chest already went to, its own or a joining source's. */
        final Set<UUID> told = ConcurrentHashMap.newKeySet();
        /** The standing chest's entity uuid, or null while none stands. Guarded by this. */
        @Nullable UUID chest;
        /** True while a raise is on its way. Guarded by this. */
        boolean raising;

        Spoils(@Nonnull Stand stand, @Nonnull String chestId, @Nonnull RewardChestAsset file, @Nonnull String blockKey,
                long expiresAt) {
            this.fight = stand.key().trim();
            this.chestId = chestId;
            this.source = stand.source();
            this.world = stand.world();
            this.at = stand.at();
            this.credited = stand.credited();
            this.blockKey = blockKey;
            this.hintKey = file.hintKey();
            this.expiresAt = expiresAt;
            this.goesWhenEmptied = file.goesWhenEmptied();
            this.leadKey = file.leadKey();
            this.noticeKey = file.noticeKey();
            this.bannerTitleKey = file.bannerTitleKey();
            this.bannerSubtitleKey = file.bannerSubtitleKey();
            this.bannerMajor = file.bannerMajor();
            this.sourceName = stand.sourceName();
        }

        @Nonnull
        String head() {
            return "run=" + shortId(fight) + " " + source + " chest=" + chestId;
        }
    }

    // ==================== the one entry ====================

    /** A fresh key for a chest no boss fight stands: {@code chest:<uuid>}. */
    @Nonnull
    public static String newKey() {
        return KEY_PREFIX + UUID.randomUUID();
    }

    /**
     * Note the chest {@code stand} asks for, to go up at its first Claim, and roll its file's {@code Contents} (or the
     * stand's own table) once for each credited player, into one Claim each filed under its key, counted against
     * {@code Contents.Diminish} per player for this chest. Nothing for a stand that credits nobody, a file that lets
     * nobody open it, or a look no block on this server can draw. A file no loaded chest defines takes the library's
     * boss chest in its place, with one warning. Never throws.
     */
    @Nonnull
    public static Stood stand(@Nonnull Stand stand) {
        try {
            if (stand.credited().isEmpty() || stand.key().isBlank()) {
                SafeLog.fine(LOG + "no chest for " + stand.source() + ": nobody was credited");
                return Stood.NOTHING;
            }
            String chestId = stand.chest() == null || stand.chest().isBlank() ? RewardChestAsset.SPOILS_CHEST
                    : stand.chest().trim();
            RewardChestAsset file = chestFile(chestId, stand.source());
            if (!file.openCredited()) {
                if (WARNED_CHESTS.add("closed:" + chestId.toLowerCase(Locale.ROOT))) {
                    SafeLog.warn(LOG + "the reward chest '" + chestId + "' writes Open.Credited false, so nobody may "
                            + "open it and it never goes up; what its source pays waits under Claims");
                }
                return Stood.NOTHING;
            }
            String blockKey = blockKeyOf(stand.prop() != null && !stand.prop().isBlank() ? stand.prop().trim()
                    : file.prop(), chestId);
            if (blockKey == null) {
                return Stood.NOTHING;
            }
            long now = System.currentTimeMillis();
            prune(now);
            Integer authored = stand.lifetimeSeconds();
            int lifetime = authored != null && authored > 0 ? authored : file.lifetimeSeconds();
            Spoils spoils = new Spoils(stand, chestId, file, blockKey, now + lifetime * 1000L);
            FIGHTS.put(key(spoils.fight), spoils);
            return new Stood(true, fill(spoils, file, stand.lootable(), now));
        } catch (Throwable t) {
            SafeLog.warn(LOG + "the chest for " + stand.source() + " could not be noted; what it holds waits under "
                    + "Claims", t);
            return Stood.NOTHING;
        }
    }

    /**
     * Roll the chest's own table ({@code override}, else the file's {@code Contents.Lootable}) once for each credited
     * player and award what it lands as one Claim filed under the chest's key; how many were written.
     */
    private static int fill(@Nonnull Spoils spoils, @Nonnull RewardChestAsset file, @Nullable String override,
            long now) {
        String tableId = override != null && !override.isBlank() ? override.trim() : file.lootable();
        if (tableId == null) {
            // A boss chest fills from its fight's own Claims; a chest a kill, a break or the admin verb stands has
            // only its Contents, so one with none never goes up: say so once rather than leave an author guessing.
            if (spoils.fight.regionMatches(true, 0, "chest:", 0, 6)
                    && WARNED_CHESTS.add("empty:" + spoils.chestId.toLowerCase(Locale.ROOT))) {
                SafeLog.warn(LOG + "the reward chest '" + spoils.chestId + "' names no Contents.Lootable, so a chest "
                        + "stood for " + spoils.chestId + " by a kill, a break or /zigclaims chest holds nothing and "
                        + "never goes up; give it a Contents.Lootable, or pass --lootable");
            }
            return 0;
        }
        LootableAsset table = LootableConfig.getInstance().resolve(tableId);
        if (table == null) {
            if (WARNED_TABLES.add(tableId.toLowerCase(Locale.ROOT))) {
                SafeLog.warn(LOG + "the reward chest '" + spoils.chestId + "' rolls the loot table '" + tableId
                        + "', which no loaded file defines, so it holds nothing of its own");
            }
            return 0;
        }
        String source = KEY_PREFIX + spoils.chestId.toLowerCase(Locale.ROOT);
        AtomicInteger written = new AtomicInteger();
        int away = 0;
        for (UUID player : spoils.credited) {
            Claim draft = new Claim(contentsClaimId(spoils.fight, player), source, file.contentsTitleKey(),
                    List.of(), null, List.of(), now, spoils.fight);
            ClaimRolls.Payout payout = new ClaimRolls.Payout(player, draft, List.of(ClaimRolls.Draw.once(table)),
                    file.diminish(), source, message -> SafeLog.warn(LOG + message));
            if (!ClaimRolls.award(payout, claim -> {
                if (claim != null) {
                    written.incrementAndGet();
                }
            })) {
                away++;
            }
        }
        SafeLog.info(LOG + "chest " + spoils.head() + " rolled " + tableId + " for " + spoils.credited.size()
                + " credited: " + written.get() + " Claim(s) written"
                + (away > 0 ? ", " + away + " more rolling on their own world" : ""));
        return written.get();
    }

    // ==================== the events ====================

    /**
     * A boss is down: stand its chest when its binding row leaves one ({@link #stand}, the row's {@code Spoils.Chest}
     * under the fight's run id). Nothing for a fight no row names, a row that switches the chest off, a defeat that
     * names no place or no world, or one that credited nobody. Never throws.
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
            stand(new Stand(spoils.chest(), EncounterClaims.fightOf(event.runId()), world, at, credited,
                    bossName(row, event), "encounter=" + event.encounterId(), spoils.getProp(), spoils.getLifetime(),
                    null));
        } catch (Throwable t) {
            SafeLog.warn(LOG + "the spoils of '" + event.encounterId() + "' could not be noted, so it leaves no chest", t);
        }
    }

    /**
     * A Claim was written: the first one filed under a noted chest raises it, on its world; one landing in a chest
     * already standing shows its holder the chest's banner. Dispatched inside the Claims store's lock, so this only
     * queues. Never throws.
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
            boolean standing;
            synchronized (spoils) {
                standing = spoils.chest != null;
                if (!standing && spoils.raising) {
                    // The raise on its way shows every share its banner when it lands.
                    return;
                }
                if (!standing) {
                    spoils.raising = true;
                }
            }
            World world = Universe.get().getWorld(spoils.world);
            if (standing) {
                UUID player = event.player();
                if (world != null && world.isAlive() && spoils.bannerTitleKey != null
                        && spoils.credited.contains(player) && !spoils.told.contains(player)) {
                    world.execute(() -> banner(spoils, player));
                }
                return;
            }
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

    /** A world closing: its chests go with it (a chest saved with it is removed when it loads again). */
    public static void forgetWorld(@Nonnull World world) {
        try {
            UUID uuid = world.getWorldConfig().getUuid();
            FIGHTS.values().removeIf(spoils -> spoils.world.equals(uuid));
        } catch (Throwable t) {
            SafeLog.fine(LOG + "a closing world's spoils could not be dropped: " + t.getMessage());
        }
    }

    // ==================== the chest ====================

    /** On the chest's world thread, its section ticking: stand the chest where its source left it. */
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
            use.setInteractionHint(spoils.hintKey);
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
        if (chest != null) {
            bannerShares(spoils);
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

    // ==================== the banner ====================

    /** The chest just went up: its banner to each credited player holding a share of it, once each. */
    private static void bannerShares(@Nonnull Spoils spoils) {
        if (spoils.bannerTitleKey == null) {
            return;
        }
        try {
            ClaimStore claims = ClaimStore.shared();
            for (UUID player : spoils.credited) {
                if (!spoils.told.contains(player) && holdsAny(claims, player, spoils.fight)) {
                    banner(spoils, player);
                }
            }
        } catch (Throwable t) {
            SafeLog.fine(LOG + "the chest " + spoils.head() + " could not show its banner: " + t.getMessage());
        }
    }

    /** The chest's own banner to {@code player}, once: its title over what left it, its line under. */
    private static void banner(@Nonnull Spoils spoils, @Nonnull UUID player) {
        String titleKey = spoils.bannerTitleKey;
        if (titleKey == null || !spoils.told.add(player)) {
            return;
        }
        Universe universe = Universe.get();
        PlayerRef playerRef = universe == null ? null : universe.getPlayer(player);
        if (playerRef == null) {
            return;
        }
        Message title = ContentKeys.tr(titleKey, spoils.sourceName == null ? Msg.raw("") : spoils.sourceName);
        Message line = spoils.bannerSubtitleKey == null ? Msg.raw("") : ContentKeys.tr(spoils.bannerSubtitleKey);
        showBanner(playerRef, title, line, spoils.bannerMajor);
    }

    /** The game's event title for {@code playerRef}, on their own world thread; nothing for a player who is gone. */
    private static void showBanner(@Nonnull PlayerRef playerRef, @Nonnull Message title, @Nonnull Message line,
            boolean major) {
        try {
            Ref<EntityStore> ref = playerRef.getReference();
            World world = ref == null || !ref.isValid() ? null : ref.getStore().getExternalData().getWorld();
            if (world == null || !world.isAlive()) {
                return;
            }
            Runnable show = () -> EventTitles.show(playerRef, title, line, major);
            if (world.isInThread()) {
                show.run();
            } else {
                world.execute(show);
            }
        } catch (Throwable t) {
            SafeLog.warn(LOG + "a player could not be shown a chest's banner", t);
        }
    }

    // ==================== the upkeep's questions ====================

    /**
     * What the upkeep does with a chest marked {@code mark} on the entity {@code entity} at {@code nowMs}: take it down
     * once its lifetime is over, once this session knows no chest of its key standing as this entity (a chest a
     * restart left behind, or a second copy), or once every credited player has taken everything filed under it
     * (unless its file keeps it standing then).
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
        return spoils.goesWhenEmptied && emptied(spoils) ? Verdict.EMPTIED : Verdict.KEEP;
    }

    /**
     * The upkeep took the chest {@code entity} of {@code fight} down for {@code verdict}: an emptied chest stays
     * noted until its lifetime runs out, so a Claim filed under it later raises it again; an expired one goes.
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

    /** Whether no credited player holds a Claim filed under the chest. */
    private static boolean emptied(@Nonnull Spoils spoils) {
        ClaimStore claims = ClaimStore.shared();
        for (UUID player : spoils.credited) {
            if (holdsAny(claims, player, spoils.fight)) {
                return false;
            }
        }
        return true;
    }

    /** Whether {@code player} holds a Claim filed under {@code fight}. */
    private static boolean holdsAny(@Nonnull ClaimStore claims, @Nonnull UUID player, @Nonnull String fight) {
        for (Claim claim : claims.list(player)) {
            if (claim.fromFight(fight)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code claim} is in a chest: the chest of its key stands, or is on its way up, and has not run out. A
     * chest that could not be stood (no ticking section, a failed spawn) or has been taken down holds nothing, so its
     * Claims read as waiting under Claims alone. What a notice says of where the Claim waits.
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

    // ==================== what the chest's file says, for its view and its notices ====================

    /**
     * The toast's line for {@code claim} waiting in its chest, in that chest's words ({@code Text.NoticeKey}, its
     * {@code .named} form over {@code title} when the Claim has one); null when the Claim is in no chest
     * ({@link #inChest}).
     */
    @Nullable
    static Message notice(@Nonnull Claim claim, @Nullable Message title) {
        if (!inChest(claim)) {
            return null;
        }
        Spoils spoils = FIGHTS.get(key(claim.fight()));
        String base = spoils == null ? RewardChestAsset.DEFAULT_NOTICE_KEY : spoils.noticeKey;
        return title == null ? ContentKeys.tr(base) : ContentKeys.tr(base + RewardChestAsset.NAMED_SUFFIX, title);
    }

    /** The chest page's line for the chest of {@code fight}, its file's {@code Text.LeadKey}; the boss chest's when unknown. */
    @Nonnull
    static Message leadOf(@Nullable String fight) {
        Spoils spoils = fight == null ? null : FIGHTS.get(key(fight));
        return ContentKeys.tr(spoils == null ? RewardChestAsset.DEFAULT_LEAD_KEY : spoils.leadKey);
    }

    // ==================== a later payout's way into the chest ====================

    /**
     * Whether a reward {@code player} earns after {@code fight} goes into that fight's chest: the chest is noted (a
     * won fight whose row leaves one), has not run out, and credited the player. A source that pays once the fight is
     * over (a dungeon's clear) asks this, and when it answers true files its Claim under the fight
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
        tellInChest(playerRef, null, headline, lineKey);
    }

    /**
     * {@link #tellInChest(PlayerRef, Message, String)} as the banner of the chest of {@code fight}: the chest's own
     * banner never follows it for this player, and one already shown is replaced by it.
     */
    public static void tellInChest(@Nonnull PlayerRef playerRef, @Nullable String fight, @Nonnull Message headline,
            @Nullable String lineKey) {
        try {
            Spoils spoils = fight == null || fight.isBlank() ? null : FIGHTS.get(key(fight));
            UUID player = playerRef.getUuid();
            if (spoils != null && player != null) {
                spoils.told.add(player);
            }
            Message line = lineKey == null || lineKey.isBlank() ? Msg.key(BANNER_KEY) : ContentKeys.tr(lineKey.trim());
            showBanner(playerRef, headline, line, true);
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

    /** The boss's name as each reader's client resolves it: the row's {@code NameKey}, else the subject's name. */
    @Nonnull
    private static Message bossName(@Nonnull EncounterBindingAsset row, @Nonnull EncounterDefeatedEvent event) {
        String key = row.getNameKey();
        if (key != null) {
            return ContentKeys.tr(key);
        }
        String mob = event.subjectMobId();
        return NativeNames.entityNameMsg(mob == null || mob.isBlank() ? event.encounterId() : mob);
    }

    /** The reward chest file {@code chestId} names, else the library's boss chest with one warning. */
    @Nonnull
    private static RewardChestAsset chestFile(@Nonnull String chestId, @Nonnull String source) {
        RewardChestConfig config = RewardChestConfig.getInstance();
        RewardChestAsset file = config.resolve(chestId);
        if (file != null) {
            return file;
        }
        if (WARNED_CHESTS.add(chestId.toLowerCase(Locale.ROOT))) {
            SafeLog.warn(LOG + source + " names the reward chest '" + chestId + "', which no loaded file defines, so "
                    + "it takes the library's boss chest (" + RewardChestAsset.SPOILS_CHEST + ") in its place");
        }
        RewardChestAsset boss = config.resolve(RewardChestAsset.SPOILS_CHEST);
        return boss != null ? boss : RewardChestAsset.BARE;
    }

    /**
     * The block the chest draws for {@code prop}: a block item's block, or a block named directly. A prop that names
     * neither falls back to {@link RewardChestAsset#DEFAULT_PROP}, with one warning per prop; null when even that
     * cannot be drawn on this server.
     */
    @Nullable
    private static String blockKeyOf(@Nonnull String prop, @Nonnull String chestId) {
        String block = blockOf(prop);
        if (block != null) {
            return block;
        }
        if (WARNED_PROPS.add(prop.toLowerCase(Locale.ROOT))) {
            SafeLog.warn(LOG + "the chest '" + chestId + "' takes the look '" + prop + "', which names no block item "
                    + "on this server, so it takes the default look");
        }
        return blockOf(RewardChestAsset.DEFAULT_PROP);
    }

    @Nullable
    private static String blockOf(@Nonnull String prop) {
        Item item = Item.getAssetMap().getAsset(prop);
        if (item != null && item.hasBlockType() && item.getBlockId() != null) {
            return item.getBlockId();
        }
        return BlockType.getAssetMap().getAsset(prop) != null ? prop : null;
    }

    /**
     * The id of the Claim a chest's own roll writes {@code player}: {@code chest:<key>:<player>}, the key's own
     * {@code chest:} not doubled. One per chest, per player.
     */
    @Nonnull
    static String contentsClaimId(@Nonnull String fight, @Nonnull UUID player) {
        String tail = fight.startsWith(KEY_PREFIX) ? fight.substring(KEY_PREFIX.length()) : fight;
        return KEY_PREFIX + tail + ":" + player;
    }

    /** Drop every chest whose lifetime is over. */
    private static void prune(long nowMs) {
        FIGHTS.values().removeIf(spoils -> nowMs >= spoils.expiresAt);
    }

    @Nonnull
    private static String key(@Nonnull String fight) {
        return fight.trim().toLowerCase(Locale.ROOT);
    }

    /** A key's short form for a log line, the run's own when it is a run id. */
    @Nonnull
    private static String shortId(@Nonnull String fight) {
        try {
            return EncounterRun.shortId(UUID.fromString(fight.trim()));
        } catch (IllegalArgumentException e) {
            return fight;
        }
    }
}
