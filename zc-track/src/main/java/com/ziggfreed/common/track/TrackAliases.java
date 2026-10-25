package com.ziggfreed.common.track;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.track.asset.TrackCategoryAsset;
import com.ziggfreed.common.track.asset.TrackCategoryConfig;
import com.ziggfreed.common.util.SafeLog;

/**
 * Names that are no track but a gate still asks for: a consumer that retired a track (one skill split into several)
 * answers, for the old id, the category its successors sit in, so content still asking for the old id reads the
 * best of those tracks, "Old 40" passing when any one of them reaches 40. Only the gate readings
 * {@value TrackFactors#LEVEL} and {@value TrackFactors#CLIMB} (an item's {@code ZigLevel} tag among them, which also
 * counts an alias as a track this server runs) read an alias; nothing writes, lists, pays or prestiges one, and a
 * track of the same id, while usable, always wins. A consumer names its aliases with a lookup ({@link #registerSource})
 * asked at every read, so they follow its own data; the library registers none, so a server without such a consumer
 * has no alias at all. The first read of each alias says so once in the log, naming who answered it.
 */
public final class TrackAliases {

    /** Each consumer's lookup: an id to the category its alias reads, or null for an id that is no alias of theirs. */
    private static final Map<String, Function<String, String>> SOURCES = new ConcurrentHashMap<>();

    /** The aliases already said in the log, folded to upper case. */
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private TrackAliases() {
    }

    /** One alias as a source answers it: the category it reads, and who answered. */
    private record Alias(@Nonnull String category, @Nonnull String owner) {
    }

    /**
     * Let {@code owner}'s {@code categoryOf} name aliases: given an id (as content wrote it), the category whose usable
     * tracks it reads, or null when the id is no alias of theirs. Asked at every read, so it may answer from data
     * that loads later; it must not throw. Registering an owner again replaces its lookup. Once, at setup.
     */
    public static void registerSource(@Nonnull String owner, @Nonnull Function<String, String> categoryOf) {
        if (!owner.isBlank()) {
            SOURCES.put(owner.trim(), categoryOf);
        }
    }

    /** Take {@code owner}'s lookup back: for a test, or a consumer unloading. */
    public static void unregisterSource(@Nullable String owner) {
        if (owner != null) {
            SOURCES.remove(owner.trim());
            REPORTED.clear();
        }
    }

    /** Is {@code id} an alias some consumer names, and no usable track shadows? */
    public static boolean isAlias(@Nullable String id) {
        return alias(id) != null;
    }

    /** Does {@code id} read as something here: a usable track, or an alias with one usable track to read? */
    public static boolean readable(@Nullable String id) {
        return TrackService.isUsable(id) || !tracksOf(id).isEmpty();
    }

    /** The usable tracks {@code aliasId} reads, by {@code Order} then id; empty for an id that is no alias. */
    @Nonnull
    public static List<TrackDef> tracksOf(@Nullable String aliasId) {
        Alias alias = alias(aliasId);
        if (alias == null) {
            return List.of();
        }
        List<TrackDef> out = new ArrayList<>();
        for (TrackDef def : TrackService.defs()) {
            if (alias.category().equalsIgnoreCase(def.asset().category()) && !def.asset().isDerived()) {
                out.add(def);
            }
        }
        return out;
    }

    /**
     * {@code playerId}'s best reading among the tracks {@code aliasId} reads, as {@code rank} orders them (the best
     * level for {@value TrackFactors#LEVEL}, the current climb's for {@value TrackFactors#CLIMB}), the first on a
     * tie; null for an id that is no alias, or when none of its tracks reads for the player here. Said once in the
     * log the first time it answers.
     */
    @Nullable
    public static TrackReading best(@Nonnull UUID playerId, @Nullable String aliasId,
            @Nonnull ToIntFunction<TrackReading> rank) {
        Alias alias = alias(aliasId);
        if (alias == null) {
            return null;
        }
        TrackReading best = null;
        for (TrackDef def : tracksOf(aliasId)) {
            TrackReading reading = TrackService.read(TrackKey.player(playerId, def.id()));
            if (reading != null && (best == null || rank.applyAsInt(reading) > rank.applyAsInt(best))) {
                best = reading;
            }
        }
        if (best != null && REPORTED.add(fold(aliasId))) {
            SafeLog.info("[track] '" + aliasId.trim() + "' is no track: it reads as the best of the "
                    + alias.category() + " tracks, as " + alias.owner() + " answers it");
        }
        return best;
    }

    /** What a lock line calls {@code aliasId}: its category's own name, else its id; null for an id that is no alias. */
    @Nullable
    public static Message name(@Nullable String aliasId) {
        Alias alias = alias(aliasId);
        if (alias == null) {
            return null;
        }
        TrackCategoryAsset category = TrackCategoryConfig.getInstance().category(alias.category());
        String key = category == null ? null : category.getTitleKey();
        return key == null || key.isBlank() ? Msg.raw(aliasId.trim()) : ContentKeys.tr(key);
    }

    /** {@code id}'s alias as the first source naming it answers, or null: none names it, or a usable track has it. */
    @Nullable
    private static Alias alias(@Nullable String id) {
        if (id == null || id.isBlank() || SOURCES.isEmpty() || TrackService.isUsable(id)) {
            return null;
        }
        for (Map.Entry<String, Function<String, String>> source : SOURCES.entrySet()) {
            String category;
            try {
                category = source.getValue().apply(id.trim());
            } catch (Throwable t) {
                category = null;
            }
            if (category != null && !category.isBlank()) {
                return new Alias(category.trim(), source.getKey());
            }
        }
        return null;
    }

    @Nonnull
    private static String fold(@Nonnull String id) {
        return id.trim().toUpperCase(Locale.ROOT);
    }
}
