package com.ziggfreed.common.encounter.asset;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The folded binding rows ({@code defaults < pack < owner}), keyed by the row's own id, plus the one
 * lookup the runtime actually makes: the row bound to a given native encounter script.
 *
 * <p>A row names its script through {@code EncounterAsset} (defaulting to its own id), so the
 * by-script index is derived from the fold and dropped on every merge; it is rebuilt lazily on the
 * next ask. Two rows binding one script is an authoring mistake the audit reports; the runtime takes
 * the first by id order.
 */
public final class EncounterBindingConfig extends AbstractKeyedAssetConfig<EncounterBindingAsset> {

    private static final EncounterBindingConfig INSTANCE = new EncounterBindingConfig();

    @Nullable private volatile Map<String, EncounterBindingAsset> byScript;

    @Nonnull
    public static EncounterBindingConfig getInstance() {
        return INSTANCE;
    }

    private EncounterBindingConfig() {
    }

    @Override
    public synchronized void loadDefaults(@Nonnull Map<String, EncounterBindingAsset> jarDefaults) {
        super.loadDefaults(jarDefaults);
        byScript = null;
    }

    @Override
    public synchronized void mergePackLayer(@Nonnull Map<String, EncounterBindingAsset> layer) {
        super.mergePackLayer(layer);
        byScript = null;
    }

    @Override
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, EncounterBindingAsset> layer) {
        super.mergeOwnerLayer(layer);
        byScript = null;
    }

    /** The row bound to {@code encounterId} (a native script id, case-insensitive), or null. */
    @Nullable
    public EncounterBindingAsset forEncounter(@Nullable String encounterId) {
        if (encounterId == null || encounterId.isBlank()) {
            return null;
        }
        return index().get(encounterId.trim().toLowerCase(Locale.ROOT));
    }

    /** The bound, ENABLED row for {@code encounterId}, or null when there is none or it is off. */
    @Nullable
    public EncounterBindingAsset enabledFor(@Nullable String encounterId) {
        EncounterBindingAsset row = forEncounter(encounterId);
        return row == null || !row.isEnabled() ? null : row;
    }

    @Nonnull
    private Map<String, EncounterBindingAsset> index() {
        Map<String, EncounterBindingAsset> current = byScript;
        if (current != null) {
            return current;
        }
        Map<String, EncounterBindingAsset> built = new HashMap<>();
        for (EncounterBindingAsset row : all().values()) {
            String script = row.encounterAsset();
            if (script == null || script.isBlank()) {
                continue;
            }
            built.putIfAbsent(script.trim().toLowerCase(Locale.ROOT), row);
        }
        byScript = built;
        return built;
    }
}
