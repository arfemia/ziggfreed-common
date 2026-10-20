package com.ziggfreed.common.track.world;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.asset.OwnerLayerReader;
import com.ziggfreed.common.util.JsonOverrideWriter;
import com.ziggfreed.common.util.OwnerFiles;

/**
 * The {@code defaults < pack < owner} fold of every {@link TrackWorldRuleAsset}, and its owner file
 * {@code mods/ziggfreedcommon/track-world-rules.json} (an id to the leaves that file should read differently,
 * decoded against the packs' answer through the same codec). Every merge moves {@link TrackWorldRules}' generation,
 * so each world reads its rules again and every compiled gain plan is built again.
 *
 * <p><b>The owner's switch.</b> {@value #ENABLED_KEY} {@code false} at the top of the owner file switches every
 * track world rule off, the files and a world's own gameplay config alike: every world gains and keeps its grants
 * as anywhere else, until the key goes or reads true.
 *
 * <p><b>A consumer's older rules.</b> A consumer that kept world rules in a shape of its own before this store
 * existed folds them in through {@link #contribute}, already decoded into this type: they sit in the pack layer, a
 * contributed id replacing a file of the same id (the consumer's older file is a pack's word too, written in the old
 * spelling), so the owner file still has the last word over both, and every owner entry is read again against them.
 *
 * <p><b>Editing the owner file.</b> {@link #putOwnerLeaves}, {@link #removeOwnerEntry} and {@link #setEnabled}
 * write the owner file (through {@link JsonOverrideWriter}, which never overwrites a file it cannot read) and read it
 * straight back, for an admin page.
 */
public final class TrackWorldRuleConfig extends AbstractKeyedAssetConfig<TrackWorldRuleAsset> {

    /** The owner file. */
    public static final String OWNER_FILE = "track-world-rules.json";

    /** The owner file's switch for every track world rule. */
    public static final String ENABLED_KEY = "$Enabled";

    private static final String LOG_TAG = "track";

    private static final TrackWorldRuleConfig INSTANCE = new TrackWorldRuleConfig();

    @Nonnull
    private static volatile Path ownerDirectory = OwnerFiles.DIRECTORY;

    /** The owner file's {@value #ENABLED_KEY}, as last read. */
    private volatile boolean enabled = true;

    /** The ids the owner file writes, lower-cased, to their spelling there, as last read. */
    @Nonnull
    private volatile Map<String, String> ownerKeys = Map.of();

    /** The rule files' own layer, as the store last folded it. */
    @Nonnull
    private Map<String, TrackWorldRuleAsset> filesLayer = Map.of();

    /** Each consumer's contributed rules, by its owner id lower-cased. */
    private final Map<String, Map<String, TrackWorldRuleAsset>> contributed = new ConcurrentHashMap<>();

    /**
     * Whether the owner file was read since the rule files last folded. A contribution before that needs no read of
     * its own: the fold's read, which follows it, decodes every owner entry against the contributed rules too.
     */
    private volatile boolean ownerReadSinceFold;

    private TrackWorldRuleConfig() {
    }

    @Nonnull
    public static TrackWorldRuleConfig getInstance() {
        return INSTANCE;
    }

    /** Point the owner file at another directory (a test, or a consumer with its own data dir). */
    public static void setOwnerDirectory(@Nonnull Path dir) {
        ownerDirectory = dir;
    }

    /** The owner file's path. */
    @Nonnull
    public static Path ownerFile() {
        return ownerDirectory.resolve(OWNER_FILE);
    }

    /** (Re)read {@code track-world-rules.json}: its switch, its ids, and the fold's owner layer. */
    public static void reloadOwnerLayer() {
        Path file = ownerFile();
        JsonObject root = quietRoot(file);
        INSTANCE.enabled = OwnerFiles.flag(root, ENABLED_KEY, file, LOG_TAG,
                "must be true or false, so the track world rules stay on",
                "switches every track world rule off");
        Map<String, String> keys = new LinkedHashMap<>();
        if (root != null) {
            for (String key : root.keySet()) {
                if (!OwnerFiles.isReservedKey(key)) {
                    keys.putIfAbsent(key.trim().toLowerCase(Locale.ROOT), key);
                }
            }
        }
        INSTANCE.ownerKeys = Collections.unmodifiableMap(keys);
        OwnerLayerReader.apply(LOG_TAG, file, TrackWorldRuleAsset.class, TrackWorldRuleAsset.CODEC, INSTANCE,
                "track world rule");
        INSTANCE.ownerReadSinceFold = true;
    }

    /** Are the track world rules on? False only while the owner file writes {@value #ENABLED_KEY} false. */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Fold {@code owner}'s older rules into the pack layer, replacing what it contributed before (an empty map takes
     * its rules out), and read the owner file again against them once the rule files have folded (before that, the
     * fold's own read covers them). Each value is the rule as this type, its id the map's key; a contributed id
     * replaces a rule file of the same id. A consumer with no older rules, and none contributed before, need not
     * call it.
     */
    public synchronized void contribute(@Nonnull String owner, @Nonnull Map<String, TrackWorldRuleAsset> rules) {
        String key = owner.trim().toLowerCase(Locale.ROOT);
        if (rules.isEmpty()) {
            contributed.remove(key);
        } else {
            contributed.put(key, Collections.unmodifiableMap(new LinkedHashMap<>(rules)));
        }
        super.mergePackLayer(composed());
        if (ownerReadSinceFold) {
            // Read again so each owner entry decodes against the new pack layer; the owner merge moves the rules'
            // generation, so every world reads its rules again.
            reloadOwnerLayer();
        } else {
            TrackWorldRules.invalidate();
        }
    }

    /** The ids {@code owner} contributed, as given, in its order; empty when none. */
    @Nonnull
    public List<String> contributedIds(@Nonnull String owner) {
        Map<String, TrackWorldRuleAsset> rules = contributed.get(owner.trim().toLowerCase(Locale.ROOT));
        return rules == null ? List.of() : List.copyOf(rules.keySet());
    }

    /** Does the owner file write an entry for {@code id} (any casing)? */
    public boolean hasOwnerEntry(@Nonnull String id) {
        return ownerKeys.containsKey(id.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Write {@code leaves} (dotted PascalCase paths inside one rule, such as {@code Rules.Gain.Multiplier}; a null
     * value removes that leaf) into the owner file's entry for {@code id}, under the spelling the file already gives
     * it, and read the file back. False when the write failed, the file left as it was.
     */
    public boolean putOwnerLeaves(@Nonnull String id, @Nonnull Map<String, Object> leaves) {
        String entry = ownerKeys.getOrDefault(id.trim().toLowerCase(Locale.ROOT), id.trim());
        Map<String, Object> dotted = new LinkedHashMap<>();
        for (Map.Entry<String, Object> leaf : leaves.entrySet()) {
            dotted.put(entry + "." + leaf.getKey(), leaf.getValue());
        }
        boolean written = JsonOverrideWriter.setLeaves(ownerFile(), dotted);
        reloadOwnerLayer();
        return written;
    }

    /**
     * Take the owner file's entry for {@code id} out, so the rule reads as its files say, and read the file back.
     * False when the file writes no entry for it, or the write failed.
     */
    public boolean removeOwnerEntry(@Nonnull String id) {
        String entry = ownerKeys.get(id.trim().toLowerCase(Locale.ROOT));
        if (entry == null) {
            return false;
        }
        boolean written = JsonOverrideWriter.setLeaf(ownerFile(), entry, null);
        reloadOwnerLayer();
        return written;
    }

    /**
     * Switch every track world rule on or off in the owner file ({@value #ENABLED_KEY}: written false, or taken
     * out for on) and read the file back. False when the write failed.
     */
    public boolean setEnabled(boolean on) {
        boolean written = JsonOverrideWriter.setLeaf(ownerFile(), ENABLED_KEY, on ? null : Boolean.FALSE);
        reloadOwnerLayer();
        return written;
    }

    @Override
    public synchronized void loadDefaults(@Nonnull Map<String, TrackWorldRuleAsset> jarDefaults) {
        super.loadDefaults(jarDefaults);
        TrackWorldRules.invalidate();
    }

    /** The rule files' layer from the store's fold, with every consumer's contributed rules over it. */
    @Override
    public synchronized void mergePackLayer(@Nonnull Map<String, TrackWorldRuleAsset> layer) {
        filesLayer = Collections.unmodifiableMap(new LinkedHashMap<>(layer));
        super.mergePackLayer(composed());
        // The store's own load reads the owner file right after this fold (FrameworkAssetRegistrar).
        ownerReadSinceFold = false;
        TrackWorldRules.invalidate();
    }

    @Override
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, TrackWorldRuleAsset> layer) {
        super.mergeOwnerLayer(layer);
        TrackWorldRules.invalidate();
    }

    /** The files, then each consumer's rules in owner id order, a contributed id replacing a file's. */
    @Nonnull
    private Map<String, TrackWorldRuleAsset> composed() {
        Map<String, TrackWorldRuleAsset> out = new LinkedHashMap<>();
        for (Map.Entry<String, TrackWorldRuleAsset> e : filesLayer.entrySet()) {
            out.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
        }
        List<String> owners = new ArrayList<>(contributed.keySet());
        Collections.sort(owners);
        for (String owner : owners) {
            for (Map.Entry<String, TrackWorldRuleAsset> e : contributed.get(owner).entrySet()) {
                if (e.getKey() != null && !e.getKey().isBlank() && e.getValue() != null) {
                    out.put(e.getKey().trim().toLowerCase(Locale.ROOT), e.getValue());
                }
            }
        }
        return out;
    }

    /** The owner file as an object, or null when missing, blank or unreadable (the layer reader says why). */
    @Nullable
    private static JsonObject quietRoot(@Nonnull Path file) {
        try {
            if (!Files.exists(file)) {
                return null;
            }
            String body = Files.readString(file, StandardCharsets.UTF_8);
            if (body.isBlank()) {
                return null;
            }
            JsonElement parsed = JsonParser.parseString(body);
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
