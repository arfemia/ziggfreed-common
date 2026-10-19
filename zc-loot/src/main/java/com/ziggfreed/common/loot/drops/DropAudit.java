package com.ziggfreed.common.loot.drops;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.asset.type.item.config.ItemDropList;
import com.ziggfreed.common.loot.LootableAsset;
import com.ziggfreed.common.loot.LootableConfig;
import com.ziggfreed.common.loot.LootableValidator;
import com.ziggfreed.common.loot.reward.DropRows;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.validation.BootAudit;
import com.ziggfreed.common.validation.Finding;

/**
 * What a {@link ZigLootableDrop} cannot drop, said once the stores have loaded: a container naming no table, a table
 * no file answers to, and every row a named table pays that drops nothing in a drop list ({@link DropRows}: a command,
 * or a reward of any kind but {@code Item}, {@code Droplist} and {@code Lootable}). Each is a WARNING, never an error,
 * and the drop list still loads and rolls: what can drop, drops, and the rest pays nothing. A table its mod gate
 * refused reads as refused, so it is not warned of.
 *
 * <p>The containers are noted as the drop lists decode ({@link #noteNamed}), each with the drop list it sits in, and
 * a list no longer loaded is skipped at audit time; a list still loaded but edited to drop its container is still
 * named until the server restarts, since the engine's containers do not show their children.
 * {@link #logAtBoot} warns once at boot, standing down where zc-core's boot audit runs, since that pass prints these
 * findings through {@code LootAudit}; {@code /zigloot validate} reads them on demand the same way.
 */
public final class DropAudit {

    /** The domain every finding here carries. */
    public static final String DOMAIN = "lootable_drops";

    /** A ZigLootable container names no table. */
    public static final String BLANK_LOOTABLE = "DROPS_BLANK_LOOTABLE";

    /** A ZigLootable container names a table no file answers to. */
    public static final String UNKNOWN_LOOTABLE = "DROPS_UNKNOWN_LOOTABLE";

    /** A table a ZigLootable container rolls pays a row that drops nothing in a drop list. */
    public static final String UNPAID_ROW = "DROPS_UNPAID_ROW";

    /** The key a container naming no table is noted under. */
    private static final String BLANK = "";

    /** The drop list a container is noted in when its file's id is unknown. */
    private static final String UNKNOWN_LIST = "?";

    /** Table id (lower-cased; blank for none) to the drop lists naming it, as decoded. */
    private static final Map<String, Set<String>> NAMED = new ConcurrentHashMap<>();

    private static final AtomicBoolean LOGGED = new AtomicBoolean();

    private DropAudit() {
    }

    /** One decoded container: the table it names (null for none) and the drop list it sits in (null if unknown). */
    static void noteNamed(@Nullable String tableId, @Nullable String dropListId) {
        String key = tableId == null ? BLANK : tableId.toLowerCase(Locale.ROOT);
        NAMED.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet())
                .add(dropListId == null || dropListId.isBlank() ? UNKNOWN_LIST : dropListId);
    }

    /**
     * Every finding over the containers decoded so far whose drop list is still loaded, by table id, then the drop
     * lists in id order: a drop list removed since it decoded is no longer warned of.
     */
    @Nonnull
    public static List<Finding> auditAll() {
        List<Finding> out = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : new TreeMap<>(NAMED).entrySet()) {
            String tableId = entry.getKey();
            Set<String> loaded = new TreeSet<>();
            for (String dropListId : entry.getValue()) {
                if (stillLoaded(dropListId)) {
                    loaded.add(dropListId);
                }
            }
            if (loaded.isEmpty()) {
                continue;
            }
            String lists = String.join(", ", loaded);
            if (tableId.isEmpty()) {
                out.add(Finding.warning(DOMAIN, BLANK_LOOTABLE, "A " + ZigLootableDrop.TYPE + " container names no "
                        + "loot table, so it drops nothing. Write its Lootable.", lists));
                continue;
            }
            LootableAsset table = LootableConfig.getInstance().resolve(tableId);
            if (table == null) {
                if (!LootableValidator.modGateRefused(tableId)) {
                    out.add(Finding.warning(DOMAIN, UNKNOWN_LOOTABLE, "No loot table named '" + tableId + "' is "
                            + "loaded, so the " + ZigLootableDrop.TYPE + " container naming it drops nothing. Check "
                            + "the spelling, or the pack that ships it.", lists));
                }
                continue;
            }
            Set<String> unpaid = unpaidRows(table);
            if (!unpaid.isEmpty()) {
                out.add(Finding.warning(DOMAIN, UNPAID_ROW, "The loot table '" + tableId + "' pays "
                        + String.join(", ", unpaid) + ", which a drop list cannot hand to anyone, so those rows pay "
                        + "nothing when a " + ZigLootableDrop.TYPE + " container rolls it; its items and drop lists "
                        + "still drop.", lists));
            }
        }
        return out;
    }

    /**
     * Whether the drop list a container was noted in is still loaded: a list of unknown id, or a store that cannot be
     * read, answers yes, so a finding is never lost to a question the audit cannot ask.
     */
    private static boolean stillLoaded(@Nonnull String dropListId) {
        if (UNKNOWN_LIST.equals(dropListId)) {
            return true;
        }
        try {
            return ItemDropList.getAssetMap().getAsset(dropListId) != null;
        } catch (Throwable t) {
            return true;
        }
    }

    /** What {@code table} pays that drops nothing, by kind as written, in name order. */
    @Nonnull
    static Set<String> unpaidRows(@Nonnull LootableAsset table) {
        Set<String> unpaid = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        DropRows.Sink sink = new DropRows.Sink() {
            @Override
            public void item(@Nonnull String itemId, int min, int max) {
            }

            @Override
            public void dropList(@Nonnull String dropListId, int rolls) {
            }

            @Override
            public void table(@Nonnull String tableId, @Nullable String trigger) {
            }

            @Override
            public void unpaid(@Nonnull String what) {
                unpaid.add(what);
            }
        };
        DropRoll.forEachGrants(table, grants -> DropRows.walk(grants, sink));
        return unpaid;
    }

    /**
     * The findings, once per boot, each as a WARNING line; nothing where zc-core's boot audit runs, which prints them
     * through {@code LootAudit}.
     */
    public static void logAtBoot() {
        if (BootAudit.asked() || !LOGGED.compareAndSet(false, true)) {
            return;
        }
        try {
            for (Finding finding : auditAll()) {
                SafeLog.warn("[loot] drops: " + finding.message() + " (" + finding.sourceId() + ")");
            }
        } catch (Throwable t) {
            SafeLog.warn("[loot] drops: the drop-list audit could not run", t);
        }
    }
}
