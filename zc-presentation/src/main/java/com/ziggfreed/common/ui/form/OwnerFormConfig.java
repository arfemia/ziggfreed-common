package com.ziggfreed.common.ui.form;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The {@code defaults < pack < owner} fold of every {@link OwnerFormAsset}, keyed by form id. A form may gate on a
 * mod through its top-level {@code Requires} (the mod gate): the store's fold and its owner reader
 * ({@link OwnerFormOwnerLayers}) count what they leave out under {@link #MOD_GATE_STORE}. Every merge rebuilds the
 * sorted list the owner menu reads.
 */
public final class OwnerFormConfig extends AbstractKeyedAssetConfig<OwnerFormAsset> {

    /** The store's mod-gate label, which its drop lines carry. */
    public static final String MOD_GATE_STORE = "OwnerForms";

    private static final OwnerFormConfig INSTANCE = new OwnerFormConfig();

    /** Every form in force, by section then order then id; rebuilt on every fold, read lock-free. */
    @Nonnull
    private volatile List<OwnerFormAsset> sorted = List.of();

    private OwnerFormConfig() {
        super(MOD_GATE_STORE);
    }

    @Nonnull
    public static OwnerFormConfig getInstance() {
        return INSTANCE;
    }

    @Override
    public synchronized void mergePackLayer(@Nonnull Map<String, OwnerFormAsset> layer) {
        super.mergePackLayer(layer);
        folded();
    }

    @Override
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, OwnerFormAsset> layer) {
        super.mergeOwnerLayer(layer);
        folded();
    }

    /** Every form in force, by order then id. */
    @Nonnull
    public List<OwnerFormAsset> forms() {
        return sorted;
    }

    /** The form {@code id} names (any case), or null. */
    @Nullable
    public OwnerFormAsset form(@Nullable String id) {
        return id == null || id.isBlank() ? null : resolve(id.trim());
    }

    /**
     * The sorted list again. No widened-gate line ({@code StoreGates.reportWidened}): the store has read its whole
     * {@code Requires} since it first shipped.
     */
    private void folded() {
        List<OwnerFormAsset> forms = new ArrayList<>();
        for (OwnerFormAsset form : all().values()) {
            if (form != null) {
                forms.add(form);
            }
        }
        forms.sort(Comparator.comparingInt(OwnerFormAsset::order)
                .thenComparing(OwnerFormAsset::getId, String.CASE_INSENSITIVE_ORDER));
        sorted = List.copyOf(forms);
    }
}
