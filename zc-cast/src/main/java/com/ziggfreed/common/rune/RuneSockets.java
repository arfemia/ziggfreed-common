package com.ziggfreed.common.rune;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.map.MapCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.asset.type.item.config.CoreItemAbility;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.codec.DeferredCodec;
import com.ziggfreed.common.playerdata.PlayerRecords;
import com.ziggfreed.common.util.SafeLog;

/**
 * What each of a player's rune lines holds, saved with the player ({@link #REGISTRY_ID}): per line id a consumer
 * names ({@code "mymod:slot_1"}), the support runes socketed behind that line's core, in their sockets. Only
 * supports: a line's core is the consumer's to mint each time the line opens ({@link RuneBench}), so a core is
 * never kept here.
 *
 * <p><b>Saved.</b> Each line is its sockets by number ({@code 1} and {@code 2}, the slots after the core), each a
 * whole {@link ItemStack} through the engine's own item codec, so a rune's metadata rides along. A line with
 * nothing socketed is not written. Registered at library setup, before any world loads, and attached to every
 * player on connect, so a save carrying it always reads back.
 *
 * <p><b>One write path.</b> {@link #write} and {@link #takeAll}, on the player's world thread, are the only ways
 * the record changes, and each announces a write that changed it through {@link PlayerRecords#changed}, so a
 * consumer keeping player data outside the engine's save hears every one. {@link RuneBench} writes a line on
 * every change in its open window and again when it closes, so the window is the truth while it is open.
 *
 * <p>Inert on a server where no mod opens a line: every player carries an empty record and nothing is written.
 */
public final class RuneSockets implements Component<EntityStore> {

    /** The engine registry id; the persisted save key. */
    public static final String REGISTRY_ID = "ZiggfreedCommon:RuneSockets";

    /** The save's {@code Version} leaf (zc-core's {@code codec/} router). */
    public static final int VERSION = 1;

    /**
     * One line's sockets: the socket's number (1 for the slot after the core) to the support there. The engine's
     * own item codec, resolved only when a stack actually flows, so this type loads without the item classes.
     */
    private static final Codec<Map<String, ItemStack>> LINE =
            new MapCodec<>(new DeferredCodec<>(() -> ItemStack.CODEC), LinkedHashMap::new, false);

    /** Every line by its id. */
    private static final Codec<Map<String, Map<String, ItemStack>>> LINES =
            new MapCodec<>(LINE, LinkedHashMap::new, false);

    @Nonnull
    public static final BuilderCodec<RuneSockets> CODEC = BuilderCodec
            .builder(RuneSockets.class, RuneSockets::new)
            .versioned()
            .codecVersion(VERSION)
            .append(new KeyedCodec<>("Lines", LINES),
                    (c, v) -> c.load(v),
                    c -> c.save()).add()
            .build();

    /** The registered type, or null until {@link #register} runs (or when it failed). */
    @Nullable
    private static volatile ComponentType<EntityStore, RuneSockets> type;

    /** Line id to its {@link RuneBench#SUPPORTS_PER_LINE} sockets, an empty one null; an array is never changed once in. */
    private final Map<String, ItemStack[]> lines = new ConcurrentHashMap<>();

    public RuneSockets() {
    }

    /**
     * Register the component type. Once, at library setup, BEFORE any world loads. Never throws: a failure logs and
     * leaves the type unset, and then no line keeps what it holds past its window.
     */
    @Nullable
    public static ComponentType<EntityStore, RuneSockets> register(@Nonnull ComponentRegistryProxy<EntityStore> registry) {
        try {
            type = registry.registerComponent(RuneSockets.class, REGISTRY_ID, CODEC);
            return type;
        } catch (Throwable t) {
            SafeLog.warn("[runes] could not register the rune socket record", t);
            return null;
        }
    }

    /** Hang the connect hook that attaches one of these to every player's holder. */
    public static void install(@Nonnull PluginBase plugin) {
        plugin.getEventRegistry().register(PlayerConnectEvent.class, RuneSockets::onPlayerConnect);
    }

    /** The registered type, or null when registration has not run or failed. */
    @Nullable
    public static ComponentType<EntityStore, RuneSockets> getComponentType() {
        return type;
    }

    private static void onPlayerConnect(@Nonnull PlayerConnectEvent event) {
        try {
            ComponentType<EntityStore, RuneSockets> registered = type;
            if (registered != null) {
                event.getHolder().ensureAndGetComponent(registered);
            }
        } catch (Throwable t) {
            SafeLog.warn("[runes] could not attach the rune socket record", t);
        }
    }

    // ==================== Reads ====================

    /**
     * The supports line {@code lineId} holds, in socket order, {@link RuneBench#SUPPORTS_PER_LINE} of them with an
     * empty socket as null; all null for a line that holds nothing.
     */
    @Nonnull
    public List<ItemStack> supports(@Nonnull String lineId) {
        ItemStack[] held = lines.get(lineId);
        return held == null ? emptyLine() : Collections.unmodifiableList(Arrays.asList(held.clone()));
    }

    /** A line with every socket empty. */
    @Nonnull
    private static List<ItemStack> emptyLine() {
        return Collections.unmodifiableList(Arrays.asList(new ItemStack[RuneBench.SUPPORTS_PER_LINE]));
    }

    /** Every line holding at least one support, sorted. */
    @Nonnull
    public Set<String> lineIds() {
        return Collections.unmodifiableSet(new TreeSet<>(lines.keySet()));
    }

    /** True when no line holds a support. */
    public boolean isEmpty() {
        return lines.isEmpty();
    }

    /** The record on the player at {@code ref}, or null (no player, not registered); on that entity's world thread. */
    @Nullable
    public static RuneSockets of(@Nonnull ComponentAccessor<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        ComponentType<EntityStore, RuneSockets> registered = type;
        if (registered == null || !ref.isValid()) {
            return null;
        }
        try {
            return store.getComponent(ref, registered);
        } catch (Throwable t) {
            SafeLog.fine("[runes] the rune socket record could not be read: " + t.getMessage());
            return null;
        }
    }

    /** {@link #supports(String)} for the player at {@code ref}; all null when they carry no record. */
    @Nonnull
    public static List<ItemStack> supports(@Nonnull ComponentAccessor<EntityStore> store,
            @Nonnull Ref<EntityStore> ref, @Nonnull String lineId) {
        RuneSockets record = of(store, ref);
        return record == null ? emptyLine() : record.supports(lineId);
    }

    // ==================== The one write path ====================

    /**
     * Record {@code supports} (socket order, an empty socket as null or an empty stack) as what line {@code lineId}
     * holds for the player at {@code ref}; nothing socketed drops the line. A core is never kept: one in a socket is
     * left out. On that entity's world thread. True when the record changed, which is then announced.
     */
    public static boolean write(@Nonnull ComponentAccessor<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull String lineId, @Nonnull List<ItemStack> supports) {
        RuneSockets record = of(store, ref);
        if (record == null) {
            SafeLog.fine("[runes] no rune socket record to write line '" + lineId + "' to");
            return false;
        }
        boolean changed = record.put(lineId, supports);
        if (changed) {
            PlayerRecords.changed(store, ref, REGISTRY_ID);
        }
        return changed;
    }

    /**
     * Empty every line of the player at {@code ref}, answering what each held (line id to its supports in socket
     * order, an empty socket as null), so the caller hands them on. On that entity's world thread; empty when they
     * held nothing. The emptying is announced.
     */
    @Nonnull
    public static Map<String, List<ItemStack>> takeAll(@Nonnull ComponentAccessor<EntityStore> store,
            @Nonnull Ref<EntityStore> ref) {
        RuneSockets record = of(store, ref);
        if (record == null || record.isEmpty()) {
            return Map.of();
        }
        Map<String, List<ItemStack>> taken = new TreeMap<>();
        for (String lineId : record.lineIds()) {
            taken.put(lineId, record.supports(lineId));
        }
        record.lines.clear();
        PlayerRecords.changed(store, ref, REGISTRY_ID);
        return Collections.unmodifiableMap(taken);
    }

    /**
     * The in-place step under {@link #write}: line {@code lineId} becomes {@code supports} (a core left out, the rest
     * past {@link RuneBench#SUPPORTS_PER_LINE} ignored), answering whether that changed what it held.
     */
    boolean put(@Nonnull String lineId, @Nonnull List<ItemStack> supports) {
        ItemStack[] next = new ItemStack[RuneBench.SUPPORTS_PER_LINE];
        boolean any = false;
        for (int i = 0; i < next.length && i < supports.size(); i++) {
            ItemStack stack = supports.get(i);
            if (!ItemStack.isEmpty(stack) && !isCore(stack)) {
                next[i] = stack;
                any = true;
            }
        }
        ItemStack[] before = any ? lines.put(lineId, next) : lines.remove(lineId);
        return !Arrays.equals(before == null ? new ItemStack[RuneBench.SUPPORTS_PER_LINE] : before, next);
    }

    /** True when {@code stack}'s item is a core rune, which a line never keeps. */
    private static boolean isCore(@Nonnull ItemStack stack) {
        try {
            return stack.getItem().getAbility() instanceof CoreItemAbility;
        } catch (Throwable t) {
            return false;
        }
    }

    // ==================== Save ====================

    /** Each line holding a support, sorted, as its sockets by number; null when none does, so nothing is written. */
    @Nullable
    private Map<String, Map<String, ItemStack>> save() {
        if (lines.isEmpty()) {
            return null;
        }
        Map<String, Map<String, ItemStack>> out = new TreeMap<>();
        for (Map.Entry<String, ItemStack[]> line : lines.entrySet()) {
            Map<String, ItemStack> sockets = new LinkedHashMap<>();
            ItemStack[] held = line.getValue();
            for (int i = 0; i < held.length; i++) {
                if (!ItemStack.isEmpty(held[i])) {
                    sockets.put(Integer.toString(i + 1), held[i]);
                }
            }
            if (!sockets.isEmpty()) {
                out.put(line.getKey(), sockets);
            }
        }
        return out.isEmpty() ? null : out;
    }

    private void load(@Nullable Map<String, Map<String, ItemStack>> saved) {
        lines.clear();
        if (saved == null) {
            return;
        }
        for (Map.Entry<String, Map<String, ItemStack>> line : saved.entrySet()) {
            if (line.getKey() == null || line.getKey().isBlank() || line.getValue() == null) {
                continue;
            }
            ItemStack[] held = new ItemStack[RuneBench.SUPPORTS_PER_LINE];
            boolean any = false;
            for (Map.Entry<String, ItemStack> socket : line.getValue().entrySet()) {
                int at = socketIndex(socket.getKey());
                if (at < 0) {
                    SafeLog.warn("[runes] line '" + line.getKey() + "' saved a support in socket '" + socket.getKey()
                            + "', which a line does not have; it was not read: " + socket.getValue());
                    continue;
                }
                if (!ItemStack.isEmpty(socket.getValue())) {
                    held[at] = socket.getValue();
                    any = true;
                }
            }
            if (any) {
                lines.put(line.getKey(), held);
            }
        }
    }

    /** The 0-based socket a saved key names ({@code "1"} is the first), or -1 for one a line does not have. */
    private static int socketIndex(@Nullable String key) {
        try {
            int number = key == null ? 0 : Integer.parseInt(key.trim());
            return number >= 1 && number <= RuneBench.SUPPORTS_PER_LINE ? number - 1 : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Override
    public RuneSockets clone() {
        RuneSockets copy = new RuneSockets();
        for (Map.Entry<String, ItemStack[]> line : lines.entrySet()) {
            copy.lines.put(line.getKey(), line.getValue().clone());
        }
        return copy;
    }

    /** What each line holds, for a log line. */
    @Override
    public String toString() {
        List<String> out = new ArrayList<>();
        for (String lineId : lineIds()) {
            out.add(lineId + "=" + supports(lineId));
        }
        return "RuneSockets" + out;
    }
}
