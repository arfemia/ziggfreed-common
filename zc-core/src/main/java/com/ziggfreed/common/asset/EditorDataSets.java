package com.ziggfreed.common.asset;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

import javax.annotation.Nonnull;

import com.hypixel.hytale.builtin.asseteditor.event.AssetEditorRequestDataSetEvent;
import com.hypixel.hytale.event.EventRegistry;
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

    private EditorDataSets() {
    }

    /**
     * Register a dataset answered from a live registry at request time. Answering an empty list is
     * legitimate (nothing registered yet), never an error; a source that throws is logged at fine
     * and answers nothing rather than propagating into the editor.
     */
    public static void live(@Nonnull EventRegistry registry, @Nonnull String dataSet,
            @Nonnull Supplier<Collection<String>> source) {
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
        try {
            registry.register(AssetEditorRequestDataSetEvent.class, dataSet,
                    event -> event.setResults(snapshot.clone()));
        } catch (Throwable t) {
            SafeLog.fine("[editor] dataset '" + dataSet + "' could not be registered (no asset editor?): "
                    + t.getMessage());
        }
    }
}
