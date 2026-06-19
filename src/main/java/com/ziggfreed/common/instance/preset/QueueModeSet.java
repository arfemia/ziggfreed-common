package com.ziggfreed.common.instance.preset;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The resolved set of the three queue modes a preset offers on its {@code PlayModePage}
 * (Public / Party / Solo), each a fully-populated {@link QueueModeEntry} with the
 * documented defaults baked in. Lives entirely inside the winning {@link InstancePreset}
 * (resolution is whole-record per id, never a per-field merge), so a resolved preset
 * always exposes a complete set.
 *
 * <p>The mode CONTENT (which modes are enabled, the item-glyph icon, the order, the label
 * override) is AUTHORED by the consumer in its {@code Server/<Mod>/Instances/*.json}
 * asset - it is never baked into this library. {@link #fallback()} is only a neutral
 * STRUCTURAL safety net (all three enabled, no glyph) for the degenerate case of a preset
 * with no asset at all; a real deployment authors every mode in {@code /Server/}. A preset
 * overrides only what it authors (e.g. a co-op-only difficulty sets
 * {@code "Solo": { "Enabled": false }}).
 */
public record QueueModeSet(@Nonnull QueueModeEntry publicMode, @Nonnull QueueModeEntry partyMode,
                           @Nonnull QueueModeEntry soloMode) {

    /**
     * The NEUTRAL structural fallback (no baked content): all three modes enabled, ordered
     * by ordinal, with NO icon (the page draws no glyph for a blank id). The real icons /
     * gating live in the consumer's {@code /Server/} asset; this only keeps the screen
     * non-broken when a preset ships no {@code InstancePreset} asset whatsoever.
     */
    @Nonnull
    public static QueueModeSet fallback() {
        return new QueueModeSet(
                new QueueModeEntry(QueueModeId.PUBLIC, true, "", 0, null),
                new QueueModeEntry(QueueModeId.PARTY, true, "", 1, null),
                new QueueModeEntry(QueueModeId.SOLO, true, "", 2, null));
    }

    /**
     * Resolve an authored {@link InstancePresetAsset.QueueModes} (nullable) into a complete
     * set: start from the neutral {@link #fallback()} and override only the fields a preset
     * actually authored (absent = the structural fallback), mirroring
     * {@code InstancePresetAsset.toPreset}'s "absent = default" contract. The authored
     * content lives in the consumer's {@code /Server/} asset, never in this library.
     */
    @Nonnull
    static QueueModeSet from(@Nullable InstancePresetAsset.QueueModes raw) {
        QueueModeSet d = fallback();
        if (raw == null) {
            return d;
        }
        return new QueueModeSet(
                overlay(QueueModeId.PUBLIC, d.publicMode, raw.publicMode),
                overlay(QueueModeId.PARTY, d.partyMode, raw.partyMode),
                overlay(QueueModeId.SOLO, d.soloMode, raw.soloMode));
    }

    @Nonnull
    private static QueueModeEntry overlay(@Nonnull QueueModeId mode, @Nonnull QueueModeEntry base,
                                          @Nullable InstancePresetAsset.QueueMode raw) {
        if (raw == null) {
            return base;
        }
        boolean enabled = raw.enabled != null ? raw.enabled : base.enabled();
        String icon = (raw.iconItemId != null && !raw.iconItemId.isBlank()) ? raw.iconItemId : base.iconItemId();
        int order = raw.order != InstancePresetAsset.UNSET_INT ? raw.order : base.order();
        String labelKey = (raw.labelKey != null && !raw.labelKey.isBlank()) ? raw.labelKey : base.labelKey();
        return new QueueModeEntry(mode, enabled, icon, order, labelKey);
    }

    /** The enabled modes only, sorted ascending by their authored {@code order} (then mode ordinal). */
    @Nonnull
    public List<QueueModeEntry> enabledOrdered() {
        List<QueueModeEntry> out = new ArrayList<>(3);
        if (publicMode.enabled()) {
            out.add(publicMode);
        }
        if (partyMode.enabled()) {
            out.add(partyMode);
        }
        if (soloMode.enabled()) {
            out.add(soloMode);
        }
        out.sort(Comparator.comparingInt(QueueModeEntry::order).thenComparingInt(e -> e.mode().ordinal()));
        return out;
    }

    /** The entry for a given mode id (whether enabled or not). */
    @Nonnull
    public QueueModeEntry forMode(@Nonnull QueueModeId mode) {
        return switch (mode) {
            case PUBLIC -> publicMode;
            case PARTY -> partyMode;
            case SOLO -> soloMode;
        };
    }
}
