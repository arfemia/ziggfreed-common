package com.ziggfreed.common.factor;

import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.asset.EditorDataSets;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.codec.InheritMapCodec;
import com.ziggfreed.common.progress.asset.ContentTextAsset;
import com.ziggfreed.common.registry.RegistryLedger;

/**
 * One {@code Server/ZiggfreedCommon/Factors/<file>.json} file, doing either or both of two jobs
 * with no Java: DEFINING a new factor id out of the ones that already exist, and NAMING a factor so
 * every surface that has to explain a requirement on it reads a real name instead of a generic
 * line.
 *
 * <h2>Defining a factor</h2>
 *
 * <p>The file's NAME is the factor id, and its {@code Formula} is what that id resolves to
 * (Pattern A - this codec IS the schema):
 * <pre>{@code
 * // Server/ZiggfreedCommon/Factors/yourmod_gear_score.json
 * { "Formula": {
 *     "Base": 1.0,
 *     "Factors": [ {"Factor": "hytale:tool_quality",    "Weight": 0.5},
 *                  {"Factor": "hytale:tool_item_level", "Weight": 0.1} ],
 *     "Clamp": {"Min": 1.0, "Max": 5.0} } }
 * }</pre>
 *
 * <p>Anything that reads the vocabulary can then address {@code yourmod_gear_score} exactly like a
 * mod-registered id - a placement's {@code Requires}, a dialogue {@code Factor} condition, another
 * formula's term. Nothing tells them apart, which is the whole point: a server owner retunes a
 * number by editing one small file instead of asking a mod author for a new reading. Override a
 * definition by dropping a same-named file in a later pack or in the owner layer; reuse one with a
 * top-level {@code "Parent": "<id>"}, which inherits leaf by leaf.
 *
 * <p><b>A derived factor answers whenever its file exists.</b> An input nobody can read contributes
 * 0 rather than voiding the result ({@link FactorFormula} explains why the value side degrades
 * where a gate fails closed), so a bounds-less condition on a derived id passes as soon as the
 * definition is installed. Author {@code Base} for the value it must have when everything optional
 * is missing, and gate on a {@code Min} when an input is what really matters. A definition that
 * reaches itself, directly or through another one, cannot resolve and fails closed;
 * {@link DerivedFactorValidator} reports the cycle at load.
 *
 * <h2>Naming a factor - any factor, including one a mod registered in code</h2>
 *
 * <p>A file may carry the naming half ALONE, with no {@code Formula} at all: that file is a NAMING
 * OVERLAY on a factor whose value comes from somewhere else, and it registers no value of its own,
 * so it can never shadow the real reading. The overlay's target is the explicit {@code Factor}
 * leaf - explicit because factor ids carry colons a filename cannot - so the file itself may be
 * named anything:
 * <pre>{@code
 * // names the pair yourmod:rank@veteran with one bespoke key
 * { "Factor": "yourmod:rank", "Param": "veteran",
 *   "Text": { "TitleKey": "yourmod.rank.veteran.name" } }
 *
 * // names a whole param family through one key pattern, with one bespoke override beside it
 * { "Factor": "hytale:stat",
 *   "ParamNames": { "KeyPattern": "yourmod.stats.{param}.name",
 *                   "Keys": { "Special_Channel": "yourmod.special.name" } } }
 * }</pre>
 *
 * <p>A file that DEFINES a factor (its filename is already the id) needs no {@code Factor} leaf to
 * name itself - it carries {@code Text} beside its {@code Formula}. {@code Factor} (an overlay
 * pointing elsewhere) and {@code Formula} (a definition of this file's own id) are therefore
 * mutually exclusive, and the validator reports a file carrying both.
 *
 * <p><b>Keys are written IN FULL, exactly as registered, and nothing ever prepends a namespace to
 * them.</b> That is what lets one mod name something with another mod's shipped key, and it is why
 * several mods can each ship an overlay on the SAME shared factor: overlays COMPOSE, tried most
 * specific first (an exact {@code Param} match or a {@code Keys} entry, then a {@code KeyPattern}
 * whose resolved key actually exists, then a bare {@code Text}), and a pattern whose resolved key
 * is not shipped is skipped so another mod's overlay still gets its turn. See {@link FactorNames}.
 */
public final class DerivedFactorAsset
        implements JsonAssetWithMap<String, DefaultAssetMap<String, DerivedFactorAsset>> {

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private FactorFormula formula;
    @Nullable private String factor;
    @Nullable private String param;
    @Nullable private ContentTextAsset text;
    @Nullable private ParamNames paramNames;

    public static final AssetBuilderCodec<String, DerivedFactorAsset> CODEC = AssetBuilderCodec.builder(
                    DerivedFactorAsset.class,
                    DerivedFactorAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Formula", FactorFormula.codec(EditorDataSets.FACTORS), false),
                    (a, v) -> a.formula = v, a -> a.formula, (a, p) -> a.formula = p.formula)
            .documentation("What this file's own factor id (the file name) resolves to: a Base plus weighted "
                    + "readings of other factors, optionally clamped. Omit it for a file that only NAMES a "
                    + "factor whose value a mod already provides. Mutually exclusive with Factor.")
            .add()
            .appendInherited(new KeyedCodec<>("Factor", Codec.STRING, false),
                    (a, v) -> a.factor = v, a -> a.factor, (a, p) -> a.factor = p.factor)
            .documentation("The factor id this file NAMES, when that id is defined elsewhere (a mod-registered "
                    + "reading, or another Factors file) - explicit because ids carry colons a filename cannot, "
                    + "so the file itself may be named anything. A file defining its own id (a Formula file) "
                    + "omits this: its filename is already the id it names. Mutually exclusive with Formula.")
            .add()
            .appendInherited(new KeyedCodec<>("Param", Codec.STRING, false),
                    (a, v) -> a.param = v, a -> a.param, (a, p) -> a.param = p.param)
            .documentation("Narrows this file's Text to ONE factor+param pair (a single channel, a single "
                    + "node), so the name applies only when a requirement authored exactly that Param is "
                    + "explained. Omit it to name the factor itself, or use ParamNames for a whole family.")
            .add()
            .appendInherited(new KeyedCodec<>("Text", ContentTextAsset.CODEC, false),
                    (a, v) -> a.text = v, a -> a.text, (a, p) -> a.text = p.text)
            .documentation("What the factor is CALLED, as the shared Text group: TitleKey is the localization "
                    + "key surfaces resolve when they explain a requirement on this factor. Write the key IN "
                    + "FULL, exactly as it is registered - it is never namespaced for you, so it may point at "
                    + "any mod's shipped key.")
            .add()
            .appendInherited(new KeyedCodec<>("ParamNames", ParamNames.CODEC, false),
                    (a, v) -> a.paramNames = v, a -> a.paramNames, (a, p) -> a.paramNames = p.paramNames)
            .documentation("Names for a whole PARAM family of the target factor (per-channel stats, per-node "
                    + "permissions): a KeyPattern filled with each requirement's own Param, plus bespoke "
                    + "per-param Keys beside it. Several mods may each ship one of these for the same shared "
                    + "factor - a pattern whose resolved key is not shipped is skipped, so the overlays "
                    + "compose instead of colliding.")
            .add()
            .build();

    public DerivedFactorAsset() {
    }

    /** Java-side factory; sets the same fields the codec fills, every leaf optional. */
    @Nonnull
    public static DerivedFactorAsset of(@Nullable String id, @Nullable FactorFormula formula,
            @Nullable String factor, @Nullable String param, @Nullable ContentTextAsset text,
            @Nullable ParamNames paramNames) {
        DerivedFactorAsset a = new DerivedFactorAsset();
        a.id = id;
        a.formula = formula;
        a.factor = factor;
        a.param = param;
        a.text = text;
        a.paramNames = paramNames;
        return a;
    }

    @Override
    public String getId() {
        return id;
    }

    /** The authored formula, or null when the file defines no value of its own. */
    @Nullable
    public FactorFormula getFormula() {
        return formula;
    }

    /** The factor id this file's naming half targets when it is an overlay, or null. */
    @Nullable
    public String getFactor() {
        return factor;
    }

    /** The one param this file's {@code Text} is narrowed to, or null for the bare factor. */
    @Nullable
    public String getParam() {
        return param;
    }

    /** What the factor is called, or null when this file names nothing directly. */
    @Nullable
    public ContentTextAsset getText() {
        return text;
    }

    /** The param-family naming block, or null. */
    @Nullable
    public ParamNames getParamNames() {
        return paramNames;
    }

    /** True when this file carries a usable value definition of its own id. */
    public boolean definesValue() {
        return !isOverlay() && formula != null && !formula.isEmpty();
    }

    /** True when the file targets another id ({@code Factor} authored non-blank). */
    public boolean isOverlay() {
        return factor != null && !factor.isBlank();
    }

    /** True when the file carries any naming content at all ({@code Text} or {@code ParamNames}). */
    public boolean carriesNaming() {
        return text != null || paramNames != null;
    }

    /**
     * The factor id this file's NAMING half addresses, normalized: the explicit {@code Factor}
     * target for an overlay, else the file's own id. Null when neither exists (an id-less decode).
     */
    @Nullable
    public String namedFactorId() {
        if (isOverlay()) {
            return RegistryLedger.normalize(factor);
        }
        return id == null || id.isBlank() ? null : RegistryLedger.normalize(id);
    }

    // ==================== ParamNames ====================

    /**
     * How a factor's PARAM FAMILY reads: one key pattern covering every param, and bespoke
     * per-param keys for the few that need their own wording. Independent optional leaves - a
     * file may carry any of them.
     *
     * <p>{@code StripPrefix} and {@code Case} are the bridge from a CHANNEL spelling to an
     * existing key family: many factors address a param by a technical id ({@code MMO_Level_MINING})
     * while the names for that family already ship under friendlier keys ({@code ...skill.mining}).
     * Both transforms apply to the param BEFORE it drops into {@code KeyPattern} - the prefix is
     * stripped first, then the case fold runs - and neither touches {@code Keys}: a bespoke entry
     * is matched against the requirement's param exactly as authored, because its author writes
     * the whole key by hand anyway.
     */
    public static final class ParamNames {

        /** The literal a {@code KeyPattern} carries where the requirement's own param drops in. */
        public static final String PARAM_SLOT = "{param}";

        /** {@code Case} value: fold the (stripped) param to lower case before substitution. */
        public static final String CASE_LOWER = "Lower";

        /** {@code Case} value: fold the (stripped) param to upper case before substitution. */
        public static final String CASE_UPPER = "Upper";

        @Nullable protected String keyPattern;
        @Nullable protected String stripPrefix;
        @Nullable protected String caseFold;
        @Nullable protected Map<String, String> keys;

        public static final BuilderCodec<ParamNames> CODEC =
                BuilderCodec.builder(ParamNames.class, ParamNames::new)
                        .appendInherited(new KeyedCodec<>("KeyPattern", Codec.STRING, false),
                                (o, v) -> o.keyPattern = v, o -> o.keyPattern,
                                (o, p) -> o.keyPattern = p.keyPattern)
                        .documentation("A full localization key with {param} where the requirement's own Param "
                                + "drops in, verbatim (e.g. \"yourmod.stats.{param}.name\"). It answers only "
                                + "for a param whose resolved key is actually shipped; one that is not is "
                                + "skipped, so another overlay on the same factor still gets its turn.")
                        .add()
                        .appendInherited(new KeyedCodec<>("StripPrefix", Codec.STRING, false),
                                (o, v) -> o.stripPrefix = v, o -> o.stripPrefix,
                                (o, p) -> o.stripPrefix = p.stripPrefix)
                        .documentation("A prefix removed from the requirement's Param before it drops into "
                                + "KeyPattern (e.g. \"MMO_Level_\" turns the channel MMO_Level_MINING into "
                                + "MINING), so one pattern can point a whole channel family at name keys that "
                                + "never carried the technical prefix. A param that does not start with it is "
                                + "left alone. Compared case-insensitively; omit for no stripping.")
                        .add()
                        .appendInherited(new KeyedCodec<>("Case", Codec.STRING, false),
                                (o, v) -> o.caseFold = v, o -> o.caseFold,
                                (o, p) -> o.caseFold = p.caseFold)
                        .documentation("Folds the (stripped) param's case before it drops into KeyPattern, "
                                + "bridging a channel spelling to a key family registered in the other case "
                                + "(MINING to ...skill.mining). Omit to substitute the param exactly as the "
                                + "requirement authored it.")
                        .metadata(EditorSchema.oneOfDocumented(
                                CASE_LOWER, "lower-case the param before substitution",
                                CASE_UPPER, "upper-case the param before substitution"))
                        .add()
                        .appendInherited(new KeyedCodec<>("Keys", new InheritMapCodec<>(Codec.STRING), false),
                                (o, v) -> o.keys = v, o -> o.keys, (o, p) -> o.keys = p.keys)
                        .documentation("Bespoke per-param keys, each written IN FULL: a param listed here reads "
                                + "with its own key instead of the pattern (StripPrefix and Case never apply "
                                + "here - entries match the requirement's Param as authored). Under a Parent "
                                + "the map merges per key, so a child file adds or replaces single entries.")
                        .add()
                        .build();

        public ParamNames() {
        }

        /** Java-side factory; sets the same fields the codec fills. */
        @Nonnull
        public static ParamNames of(@Nullable String keyPattern, @Nullable Map<String, String> keys) {
            return of(keyPattern, null, null, keys);
        }

        /** The full factory, transform knobs included. */
        @Nonnull
        public static ParamNames of(@Nullable String keyPattern, @Nullable String stripPrefix,
                @Nullable String caseFold, @Nullable Map<String, String> keys) {
            ParamNames p = new ParamNames();
            p.keyPattern = keyPattern;
            p.stripPrefix = stripPrefix;
            p.caseFold = caseFold;
            p.keys = keys;
            return p;
        }

        @Nullable
        public String getKeyPattern() {
            return keyPattern;
        }

        /** The prefix stripped from a param before pattern substitution, or null for none. */
        @Nullable
        public String getStripPrefix() {
            return stripPrefix;
        }

        /** The case fold applied before pattern substitution, or null for as-authored. */
        @Nullable
        public String getCase() {
            return caseFold;
        }

        /** The bespoke per-param keys, never null. */
        @Nonnull
        public Map<String, String> keysOrEmpty() {
            return keys == null ? Map.of() : keys;
        }

        /**
         * The bespoke key for {@code param}, matched exactly first and then case-insensitively (a
         * requirement's Param and an override entry are typed by two different authors), or null.
         */
        @Nullable
        public String keyFor(@Nonnull String param) {
            Map<String, String> map = keysOrEmpty();
            String exact = map.get(param);
            if (exact != null && !exact.isBlank()) {
                return exact;
            }
            for (Map.Entry<String, String> entry : map.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(param)
                        && entry.getValue() != null && !entry.getValue().isBlank()) {
                    return entry.getValue();
                }
            }
            return null;
        }

        /**
         * {@code KeyPattern} with the slot filled for {@code param} - transformed first by
         * {@code StripPrefix} and then by {@code Case} - or null when no pattern is authored.
         */
        @Nullable
        public String patternKeyFor(@Nonnull String param) {
            if (keyPattern == null || keyPattern.isBlank()) {
                return null;
            }
            return keyPattern.replace(PARAM_SLOT, transform(param));
        }

        /** The param as the pattern substitutes it: prefix stripped, then case folded. */
        @Nonnull
        private String transform(@Nonnull String param) {
            String value = param;
            if (stripPrefix != null && !stripPrefix.isBlank()
                    && value.length() > stripPrefix.length()
                    && value.regionMatches(true, 0, stripPrefix, 0, stripPrefix.length())) {
                value = value.substring(stripPrefix.length());
            }
            if (CASE_LOWER.equalsIgnoreCase(caseFold)) {
                return value.toLowerCase(Locale.ROOT);
            }
            if (CASE_UPPER.equalsIgnoreCase(caseFold)) {
                return value.toUpperCase(Locale.ROOT);
            }
            return value;
        }
    }
}
