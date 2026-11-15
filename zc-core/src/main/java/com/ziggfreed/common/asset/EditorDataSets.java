package com.ziggfreed.common.asset;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.builtin.asseteditor.event.AssetEditorRequestDataSetEvent;
import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.ziggfreed.common.util.SafeLog;

/**
 * Serves the value lists behind the {@code UIEditor.Dropdown} dataset ids this library's codecs
 * declare, so the in-game Asset Editor offers a real pick list on those fields instead of a
 * free-text box.
 *
 * <p>The mechanism is the engine's own keyed {@code AssetEditorRequestDataSetEvent}: the editor
 * asks for a dataset by id and a registered handler fills {@code setResults(...)}. One registration
 * per dataset id.
 *
 * <p>Two flavors: {@link #live} answers from a runtime registry at request time, so a mod that
 * registers its factors late simply widens the next answer, and {@link #fixed} serves a closed
 * compile-time set. Both are try-guarded end to end - a server build without the Asset Editor
 * module must degrade to plain free-text fields, never fail plugin startup - and both must stay
 * cheap and side-effect free, because the request arrives as an async event.
 *
 * <p><b>A dropdown is authoring convenience, never validation.</b> Hand-written JSON never passes
 * through the editor at all, so the content validators stay the real backstop; nothing they check
 * is retired because a pick list exists.
 */
public final class EditorDataSets {

    /**
     * The ids an NPC placement's {@code Requires.Conditions} may gate on. Served off the process-wide
     * placement factor vocabulary plus every asset-defined factor.
     */
    public static final String PLACEMENT_FACTORS = "ziggfreedcommon:placement_factors";

    /**
     * The generic factor vocabulary, for any field naming a factor id that is not placement-specific
     * (a dialogue {@code Factor} condition, a {@code FactorFormula} term).
     */
    public static final String FACTORS = "ziggfreedcommon:factors";

    /**
     * The objective kinds content may author, for any field naming one: an objective's {@code Kind}, a
     * moment table's {@code When.Kind}. Served by zc-progression from the one shared objective vocabulary;
     * named here so a field in a module below it can offer the list too.
     */
    public static final String OBJECTIVE_KINDS = "ziggfreedcommon:objective_kinds";

    /**
     * The character ids content names an NPC by (a quest's giver and hand-in, a talk step's target), every id
     * a character file or placement declares. Offered as suggestions, since a role nobody wrote a character
     * file for still answers to its own role name. Served by the wiring root, which sees the dialogue module.
     */
    public static final String NPC_IDS = "ziggfreedcommon:npc_ids";

    /**
     * The prefix of the lists of one asset type's loaded ids: {@code ziggfreedcommon:ids/<Type>}, the type named
     * the way the editor names it (a store's asset class simple name, {@code BlockType}, {@code Item}, a mod's
     * {@code TitleAsset}; {@code NPCRole} for the NPC roles). Offered as suggestions on a leaf the matcher reads
     * as a pattern or a set key, which must stay typeable, where the editor's own picker offers one id alone
     * ({@link #assetIds}).
     */
    public static final String ASSET_IDS = "ziggfreedcommon:ids/";

    /** The asset type the NPC roles are named by, which no asset store holds. */
    private static final String NPC_ROLE_TYPE = "NPCRole";

    /**
     * Every dataset served through this class, by id (lower-cased), so a load check can ask what a dataset
     * holds ({@link #values}) without going through the editor's event.
     */
    private static final Map<String, Supplier<Collection<String>>> SERVED = new ConcurrentHashMap<>();

    /** Whether the {@link #ASSET_IDS} family is served, so {@link #values} answers it. */
    private static volatile boolean assetIdsServed;

    private EditorDataSets() {
    }

    /** The list of {@code assetType}'s loaded ids: {@code ziggfreedcommon:ids/<assetType>}. */
    @Nonnull
    public static String assetIds(@Nonnull String assetType) {
        return ASSET_IDS + assetType.trim();
    }

    /**
     * Serve every {@link #ASSET_IDS} list from one handler, each answered at request time off the loaded assets
     * of its type (a pack loaded later simply widens the next answer). Registered once, at setup; try-guarded
     * like every dataset here.
     */
    public static void serveAssetIds(@Nonnull EventRegistry registry) {
        assetIdsServed = true;
        try {
            registry.registerGlobal(AssetEditorRequestDataSetEvent.class, event -> {
                String dataSet = event.getDataSet();
                if (dataSet == null || !dataSet.startsWith(ASSET_IDS) || event.getResults() != null) {
                    return;
                }
                try {
                    List<String> ids = new ArrayList<>(idsOf(dataSet.substring(ASSET_IDS.length())));
                    Collections.sort(ids);
                    event.setResults(ids.toArray(String[]::new));
                } catch (Throwable t) {
                    SafeLog.fine("[editor] dataset '" + dataSet + "' failed: " + t.getMessage());
                }
            });
        } catch (Throwable t) {
            SafeLog.fine("[editor] the asset id lists could not be registered (no asset editor?): " + t.getMessage());
        }
    }

    /** The loaded ids of the asset type the editor names {@code assetType}; empty for a type nothing holds. */
    @Nonnull
    static Collection<String> idsOf(@Nonnull String assetType) {
        if (NPC_ROLE_TYPE.equals(assetType)) {
            NPCPlugin npcs = NPCPlugin.get();
            return npcs == null ? List.of() : npcs.getRoleTemplateNames(false);
        }
        for (AssetStore<?, ?, ?> store : AssetRegistry.getStoreMap().values()) {
            if (store.getAssetClass().getSimpleName().equals(assetType)) {
                List<String> ids = new ArrayList<>();
                for (Object key : store.getAssetMap().getAssetMap().keySet()) {
                    ids.add(String.valueOf(key));
                }
                return ids;
            }
        }
        return List.of();
    }

    /**
     * What dataset {@code dataSet} holds right now, or null when nothing on this server serves it through
     * this class (an engine picker, or a mod not installed). A source that throws answers null too. For a
     * load check (a factor's {@code ParamDataSet}); never the editor's own path.
     */
    @Nullable
    public static Collection<String> values(@Nullable String dataSet) {
        if (dataSet == null || dataSet.isBlank()) {
            return null;
        }
        if (assetIdsServed && dataSet.trim().startsWith(ASSET_IDS)) {
            try {
                return List.copyOf(idsOf(dataSet.trim().substring(ASSET_IDS.length())));
            } catch (Throwable t) {
                SafeLog.fine("[editor] dataset '" + dataSet + "' failed: " + t.getMessage());
                return null;
            }
        }
        Supplier<Collection<String>> source = SERVED.get(dataSet.trim().toLowerCase(Locale.ROOT));
        if (source == null) {
            return null;
        }
        try {
            Collection<String> values = source.get();
            return values == null ? null : List.copyOf(values);
        } catch (Throwable t) {
            SafeLog.fine("[editor] dataset '" + dataSet + "' failed: " + t.getMessage());
            return null;
        }
    }

    /** Serve {@code dataSet} to {@link #values} only (no editor event); tests, and null forgets it. */
    public static void serveForTests(@Nonnull String dataSet, @Nullable Supplier<Collection<String>> source) {
        String key = dataSet.trim().toLowerCase(Locale.ROOT);
        if (source == null) {
            SERVED.remove(key);
        } else {
            SERVED.put(key, source);
        }
    }

    /**
     * Register a dataset answered from a live registry at request time. Answering an empty list is
     * legitimate (nothing registered yet), never an error; a source that throws is logged at fine
     * and answers nothing rather than propagating into the editor.
     */
    public static void live(@Nonnull EventRegistry registry, @Nonnull String dataSet,
            @Nonnull Supplier<Collection<String>> source) {
        SERVED.put(dataSet.trim().toLowerCase(Locale.ROOT), source);
        try {
            registry.register(AssetEditorRequestDataSetEvent.class, dataSet, event -> {
                try {
                    List<String> ids = new ArrayList<>(source.get());
                    Collections.sort(ids);
                    event.setResults(ids.toArray(String[]::new));
                } catch (Throwable t) {
                    SafeLog.fine("[editor] dataset '" + dataSet + "' failed: " + t.getMessage());
                }
            });
        } catch (Throwable t) {
            SafeLog.fine("[editor] dataset '" + dataSet + "' could not be registered (no asset editor?): "
                    + t.getMessage());
        }
    }

    /** Register a dataset whose values are a closed, compile-time-known set. */
    public static void fixed(@Nonnull EventRegistry registry, @Nonnull String dataSet,
            @Nonnull String... values) {
        String[] snapshot = values.clone();
        SERVED.put(dataSet.trim().toLowerCase(Locale.ROOT), () -> List.of(snapshot));
        try {
            registry.register(AssetEditorRequestDataSetEvent.class, dataSet,
                    event -> event.setResults(snapshot.clone()));
        } catch (Throwable t) {
            SafeLog.fine("[editor] dataset '" + dataSet + "' could not be registered (no asset editor?): "
                    + t.getMessage());
        }
    }
}
