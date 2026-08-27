package com.ziggfreed.common.factor;

import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.i18n.LangCatalog;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.progress.asset.ContentTextAsset;
import com.ziggfreed.common.registry.RegistryLedger;

/**
 * What a factor is CALLED, answered entirely from the {@code Server/ZiggfreedCommon/Factors/}
 * assets - the naming half of {@link DerivedFactorAsset}. There is no Java naming seam beside
 * this on purpose: a factor names itself in an asset, and every surface that has to explain a
 * requirement on it then reads the same name, whichever mod shipped the file.
 *
 * <p><b>Overlays COMPOSE, most specific first.</b> Several files may target one factor id, and a
 * question about {@code (factor, param)} walks them in three passes: an EXACT param claim first (a
 * file whose own {@code Param} matches, or a {@code ParamNames.Keys} entry), then every
 * {@code ParamNames.KeyPattern} filled with the param (wrapped in the file's {@code WrapKey}
 * phrase when one is authored - the pattern arm alone, never a bespoke key), then a bare
 * {@code Text} on the factor itself. A pattern whose resolved key is not actually shipped is
 * SKIPPED and the walk continues, which is what lets two mods each ship a pattern on the same
 * shared factor and each name only its own params. Files are walked in id order, so the answer is
 * stable across restarts.
 *
 * <p><b>Keys are used exactly as authored, in full - never namespaced here</b> - so an overlay may
 * point at any mod's shipped key. An explicit key nothing ships still answers as that key (a raw
 * key on screen is traceable to the file that named it, where a blank is not); a PATTERN never
 * does, because a pattern speaks for a whole family and most of a family is usually somebody
 * else's to name.
 *
 * <p>A factor no file names answers {@code null}, and the asking surface falls back to its own
 * generic requirements line - which is the visible cue to author an overlay.
 */
public final class FactorNames {

    private FactorNames() {
    }

    /**
     * The name for {@code factorId} (narrowed by {@code param} when the requirement authored one),
     * or {@code null} when no folded file names it.
     */
    @Nullable
    public static Message name(@Nullable String factorId, @Nullable String param) {
        if (factorId == null || factorId.isBlank()) {
            return null;
        }
        return name(factorId, param, DerivedFactorConfig.getInstance().all(), LangCatalog::has);
    }

    /**
     * The decision core over an explicit file set and key-existence probe - what the unit tests
     * drive, and exactly what {@link #name(String, String)} runs against the folded store.
     */
    @Nullable
    static Message name(@Nonnull String factorId, @Nullable String param,
            @Nonnull Map<String, DerivedFactorAsset> files, @Nonnull Predicate<String> keyExists) {
        String target = RegistryLedger.normalize(factorId);
        Map<String, DerivedFactorAsset> overlays = new TreeMap<>();
        for (Map.Entry<String, DerivedFactorAsset> entry : files.entrySet()) {
            DerivedFactorAsset asset = entry.getValue();
            if (entry.getKey() != null && asset != null && asset.carriesNaming()
                    && target.equals(asset.namedFactorId())) {
                overlays.put(RegistryLedger.normalize(entry.getKey()), asset);
            }
        }
        if (overlays.isEmpty()) {
            return null;
        }

        String p = param == null || param.isBlank() ? null : param.trim();
        String firstAuthoredKey = null;

        // Pass 1: an exact claim on this very param - the file's own Param leaf, or a Keys entry.
        if (p != null) {
            for (DerivedFactorAsset asset : overlays.values()) {
                String authoredParam = asset.getParam();
                if (authoredParam != null && authoredParam.trim().equalsIgnoreCase(p)) {
                    Message named = fromText(asset.getText(), keyExists);
                    if (named != null) {
                        return named;
                    }
                    firstAuthoredKey = remember(firstAuthoredKey, titleKeyOf(asset.getText()));
                }
                DerivedFactorAsset.ParamNames family = asset.getParamNames();
                String bespoke = family == null ? null : family.keyFor(p);
                if (bespoke != null) {
                    if (keyExists.test(bespoke)) {
                        return Msg.key(bespoke);
                    }
                    firstAuthoredKey = remember(firstAuthoredKey, bespoke);
                }
            }

            // Pass 2: a pattern filled with the param - answering ONLY where the key is shipped.
            // An authored WrapKey folds the resolved name into its phrase as {0}; it rides this
            // arm alone (a Keys entry or an exact Param claim writes the whole name by hand).
            for (DerivedFactorAsset asset : overlays.values()) {
                DerivedFactorAsset.ParamNames family = asset.getParamNames();
                String patterned = family == null ? null : family.patternKeyFor(p);
                if (patterned != null && keyExists.test(patterned)) {
                    String wrap = family.getWrapKey();
                    return wrap == null ? Msg.key(patterned) : Msg.key(wrap, Msg.key(patterned));
                }
            }
        }

        // Pass 3: the factor's own name, from a file that narrowed itself to no param.
        for (DerivedFactorAsset asset : overlays.values()) {
            if (asset.getParam() != null && !asset.getParam().isBlank()) {
                continue;
            }
            Message named = fromText(asset.getText(), keyExists);
            if (named != null) {
                return named;
            }
            firstAuthoredKey = remember(firstAuthoredKey, titleKeyOf(asset.getText()));
        }

        // Last rung: an explicitly authored key nobody ships yet still answers as itself, so the
        // screen carries something traceable to the file that named it. Patterns never land here.
        return firstAuthoredKey == null ? null : Msg.key(firstAuthoredKey);
    }

    /** The Text group's answer: its key when shipped, else its plain fallback name, else null. */
    @Nullable
    private static Message fromText(@Nullable ContentTextAsset text,
            @Nonnull Predicate<String> keyExists) {
        if (text == null) {
            return null;
        }
        String key = titleKeyOf(text);
        if (key != null && keyExists.test(key)) {
            return Msg.key(key);
        }
        String plain = text.getDisplayName();
        return plain == null || plain.isBlank() ? null : Msg.raw(plain.trim());
    }

    @Nullable
    private static String titleKeyOf(@Nullable ContentTextAsset text) {
        if (text == null) {
            return null;
        }
        String key = text.getTitleKey();
        return key == null || key.isBlank() ? null : key.trim();
    }

    @Nullable
    private static String remember(@Nullable String kept, @Nullable String candidate) {
        return kept != null ? kept : candidate;
    }
}
