package com.ziggfreed.common.encounter.asset;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The folded encounter rules ({@code defaults < pack < owner}), keyed by the row's own id, plus the
 * one lookup a kill makes: the enabled row naming a given role.
 *
 * <p>The by-role index is derived from the fold and dropped on every merge; it is rebuilt lazily on
 * the next ask. Two enabled rows naming one role is an authoring mistake the audit reports; the
 * lookup takes the first by id.
 */
public final class EncounterRuleConfig extends AbstractKeyedAssetConfig<EncounterRuleAsset> {

    private static final EncounterRuleConfig INSTANCE = new EncounterRuleConfig();

    @Nullable private volatile RoleIndex byRole;

    @Nonnull
    public static EncounterRuleConfig getInstance() {
        return INSTANCE;
    }

    private EncounterRuleConfig() {
    }

    @Override
    public synchronized void loadDefaults(@Nonnull Map<String, EncounterRuleAsset> jarDefaults) {
        super.loadDefaults(jarDefaults);
        byRole = null;
    }

    @Override
    public synchronized void mergePackLayer(@Nonnull Map<String, EncounterRuleAsset> layer) {
        super.mergePackLayer(layer);
        byRole = null;
    }

    @Override
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, EncounterRuleAsset> layer) {
        super.mergeOwnerLayer(layer);
        byRole = null;
    }

    /** The enabled row naming {@code roleId} (case ignored), or null. */
    @Nullable
    public EncounterRuleAsset forRole(@Nullable String roleId) {
        RoleIndex current = byRole;
        if (current == null) {
            current = RoleIndex.of(all());
            byRole = current;
        }
        return current.forRole(roleId);
    }

    /**
     * The enabled rows that name a role, by that role's key ({@link EncounterRuleAsset#roleKey}), the
     * first by id winning a role two rows name. Pure, so a test builds one from decoded rows.
     */
    public record RoleIndex(@Nonnull Map<String, EncounterRuleAsset> byRole) {

        /** Index {@code rowsById} (a fold's {@link #all()}), read in id order. */
        @Nonnull
        public static RoleIndex of(@Nonnull Map<String, EncounterRuleAsset> rowsById) {
            Map<String, EncounterRuleAsset> out = new HashMap<>();
            for (EncounterRuleAsset row : new TreeMap<>(rowsById).values()) {
                String key = row == null || !row.isEnabled() ? null : EncounterRuleAsset.roleKey(row.getRole());
                if (key != null) {
                    out.putIfAbsent(key, row);
                }
            }
            return new RoleIndex(Map.copyOf(out));
        }

        /** The enabled row naming {@code roleId} (case ignored), or null. */
        @Nullable
        public EncounterRuleAsset forRole(@Nullable String roleId) {
            String key = EncounterRuleAsset.roleKey(roleId);
            return key == null ? null : byRole.get(key);
        }
    }
}
