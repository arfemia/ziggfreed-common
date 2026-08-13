package com.ziggfreed.common.loot.reward;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.codec.InheritMapCodec;

/**
 * A reward KIND written as content instead of code: {@code Server/ZiggfreedCommon/RewardKinds/<Id>.json},
 * a declared parameter schema plus one command line to run. The kind's id is the FILENAME.
 *
 * <pre>{@code
 * // Server/ZiggfreedCommon/RewardKinds/Mmo_Xp.json
 * {
 *   "$Comment": "Awards skill experience. Skill is a skill id as it appears in /mmoskills.",
 *   "Name": "Skill experience",
 *   "Params": {
 *     "Skill":  { "Required": true },
 *     "Amount": { "Required": true },
 *     "Silent": { "Default": "false" }
 *   },
 *   "Command": "mmoawardxp {player} {Skill} {Amount} --silent={Silent}"
 * }
 * }</pre>
 *
 * <p>Content then authors it like any other reward: {@code {"Kind": "Mmo_Xp", "Params": {"Skill":
 * "MINING", "Amount": "500"}}}. Nothing about that line tells a reader whether a mod wrote the kind
 * in Java or an owner wrote it in a file, which is the point - a server with an admin command it
 * wants paid out as a reward needs no plugin to say so.
 *
 * <h2>The command template</h2>
 *
 * <p>{@code Command} is one console command line. Three substitutions are made, all case-sensitive:
 *
 * <table>
 *   <caption>What a template may write</caption>
 *   <tr><th>Written</th><th>Becomes</th></tr>
 *   <tr><td>{@code {player}}</td><td>the receiving player's name</td></tr>
 *   <tr><td>{@code {uuid}}</td><td>the receiving player's UUID</td></tr>
 *   <tr><td>{@code {<ParamName>}}</td><td>that parameter, spelled exactly as {@code Params} declares it</td></tr>
 * </table>
 *
 * <p>A placeholder naming nothing is LEFT STANDING rather than blanked, so a typo turns up in the
 * command that ran instead of quietly becoming an empty argument. The content validator reports one
 * before a player ever earns the reward.
 *
 * <p>The line runs as the server console, so it is not limited to what the player who earned the
 * reward may do, and it goes through the shared command primitive - which means a {@code give} with a
 * trailing count is corrected to the {@code --quantity=N} form the engine actually reads.
 *
 * <h2>Parameters</h2>
 *
 * <p>Each entry under {@code Params} is a nested group of two independent knobs. {@code Required}
 * means a reward that does not name this parameter REFUSES to pay out, loudly, rather than running a
 * half-written command; {@code Default} is what stands in when a reward leaves it out. Declaring both
 * makes {@code Required} inert, because the default always satisfies it.
 *
 * <p>An optional parameter with no default substitutes as EMPTY. Author a {@code Default} for every
 * optional parameter the command names unless an empty argument is genuinely what you want there.
 *
 * <h2>Naming a kind</h2>
 *
 * <p>An id is native-asset style: PascalCase with underscores, and the file name IS the id. The
 * framework's own kinds are unprefixed ({@code Item}, {@code Lootable}, {@code Stamped_Item},
 * {@code Effect}, {@code Droplist}); a kind belonging to one mod carries that mod's prefix
 * ({@code Mmo_Xp}, {@code Mmo_Currency}), so two mods installed side by side cannot collide by
 * accident. Ids are matched case-insensitively wherever a reward names one.
 *
 * <h2>Shadowing a kind a mod already provides</h2>
 *
 * <p>Authoring a file whose id matches a kind some mod registered in Java REPLACES it. That is
 * allowed on purpose - an owner who wants a different payout for {@code Item} may have it - and it is
 * loud: the server logs one warning at boot naming the file, and the content audit reports it.
 *
 * <p><b>Read that warning before keeping such a file.</b> A command-backed kind is a command line and
 * nothing more, so it gives up the services the Java kind had: the ask-first inventory fit check that
 * turns "your bag is full" into a message before a price is charged, and the replayable retry that
 * parks a failed payout for the player's next connect. A command line can still be retried, but only
 * as the same line - nothing knows whether running it twice pays twice.
 *
 * <h2>Overriding and inheriting</h2>
 *
 * <p>A file carrying {@code "Parent": "<id>"} starts from that kind. {@code Params} merges per
 * PARAMETER, so a child redeclares one parameter and keeps the rest; {@code Command} replaces.
 * To retune a kind someone else shipped, drop a file with the same name into your own pack.
 *
 * <p>Any key starting with {@code $} is authoring metadata the codec ignores, both on the file and
 * inside {@code Params}. Write a {@code $Comment} saying what the reward does, what each parameter
 * means in game, and anything an author would get wrong - it ships with the file and is read by
 * whoever opens it next.
 */
public final class RewardKindAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, RewardKindAsset>> {

    /** Where these files live. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/RewardKinds";

    /** The placeholder standing for the receiving player's name. */
    public static final String PLACEHOLDER_PLAYER = "player";

    /** The placeholder standing for the receiving player's UUID. */
    public static final String PLACEHOLDER_UUID = "uuid";

    /** Every placeholder the engine fills in itself, so a parameter never has to be declared for one. */
    public static final Set<String> RESERVED_PLACEHOLDERS = Set.of(PLACEHOLDER_PLAYER, PLACEHOLDER_UUID);

    /** {@code {Name}}, the one spelling a template writes a substitution in. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_]+)}");

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private String authoredId;
    @Nullable private Map<String, Param> params;
    @Nullable private String command;

    public static final AssetBuilderCodec<String, RewardKindAsset> CODEC = AssetBuilderCodec.builder(
                    RewardKindAsset.class,
                    RewardKindAsset::new,
                    Codec.STRING,
                    (a, id) -> {
                        a.authoredId = id;
                        a.id = id == null ? null : id.toLowerCase(Locale.ROOT);
                    },
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .append(new KeyedCodec<>("Name", Codec.STRING, false),
                    (a, name) -> { /* display only - the id comes from the filename */ },
                    a -> a.id)
            .documentation("A human-readable label for editors. The kind's id comes from the filename, so "
                    + "changing this changes nothing at runtime.").add()
            .appendInherited(new KeyedCodec<>("Params",
                            new InheritMapCodec<>(Param.CODEC, LinkedHashMap::new), false),
                    (a, v) -> a.params = v, a -> a.params, (a, parent) -> a.params = parent.params)
            .documentation("The parameters a reward of this kind may name, keyed by the exact spelling the "
                    + "Command writes them in. Merged per parameter under Parent inheritance, so a child "
                    + "redeclares one and keeps the rest.").add()
            .appendInherited(new KeyedCodec<>("Command", Codec.STRING, false),
                    (a, v) -> a.command = v, a -> a.command, (a, parent) -> a.command = parent.command)
            .documentation("The console command line this reward runs, with {player}, {uuid} and each "
                    + "declared parameter substituted in. A kind with no command pays out nothing.").add()
            .build();

    public RewardKindAsset() {
    }

    /** Java-side construction path; sets the same fields the codec fills. */
    @Nonnull
    public static RewardKindAsset of(@Nonnull String id, @Nullable Map<String, Param> params,
            @Nullable String command) {
        RewardKindAsset a = new RewardKindAsset();
        a.authoredId = id;
        a.id = id.toLowerCase(Locale.ROOT);
        a.params = params;
        a.command = command;
        return a;
    }

    @Override
    public String getId() {
        return id;
    }

    /**
     * The id spelled the way the file spells it, for a log line or a finding an owner has to find the
     * file from. {@link #getId()} is the lower-cased key everything else matches on.
     */
    @Nonnull
    public String authoredId() {
        return authoredId != null && !authoredId.isBlank() ? authoredId : (id == null ? "" : id);
    }

    @Nullable
    public Map<String, Param> getParams() {
        return params;
    }

    /** The declared parameters, with a null read as none so a caller never branches on it. */
    @Nonnull
    public Map<String, Param> paramsOrEmpty() {
        return params == null ? Map.of() : params;
    }

    @Nullable
    public String getCommand() {
        return command;
    }

    /** True when this kind names no command, so registering it would pay out nothing. */
    public boolean isBlank() {
        return command == null || command.isBlank();
    }

    /**
     * The declaration for {@code name}, matched case-insensitively so an author's casing in a reward's
     * {@code Params} never has to agree with the declaration's before the reward will pay out.
     */
    @Nullable
    public Param param(@Nullable String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Map<String, Param> declared = paramsOrEmpty();
        Param exact = declared.get(name);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, Param> entry : declared.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** Is {@code name} declared (case-insensitively)? */
    public boolean declares(@Nullable String name) {
        return param(name) != null;
    }

    /**
     * Every placeholder the command line writes, in the order written and without duplicates,
     * including the reserved ones. Empty when there is no command.
     */
    @Nonnull
    public List<String> commandPlaceholders() {
        if (isBlank()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(command);
        while (matcher.find()) {
            seen.add(matcher.group(1));
        }
        return List.copyOf(seen);
    }

    /**
     * The first word of the command line with any leading slash stripped, or an empty string when
     * there is no command - the command HEAD, which is what a dry check asks the server about.
     */
    @Nonnull
    public String commandHead() {
        if (isBlank()) {
            return "";
        }
        String trimmed = command.trim();
        String bare = trimmed.startsWith("/") ? trimmed.substring(1) : trimmed;
        int space = bare.indexOf(' ');
        String head = space < 0 ? bare : bare.substring(0, space);
        return head.trim();
    }

    /** Every declared parameter the command never names, so a knob nobody reads is visible. */
    @Nonnull
    public List<String> unusedParams() {
        List<String> written = commandPlaceholders();
        List<String> out = new ArrayList<>();
        for (String declared : paramsOrEmpty().keySet()) {
            if (declared == null || declared.isBlank()) {
                continue;
            }
            boolean used = false;
            for (String placeholder : written) {
                if (placeholder.equalsIgnoreCase(declared)) {
                    used = true;
                    break;
                }
            }
            if (!used) {
                out.add(declared);
            }
        }
        return out;
    }

    // ==================== Param ====================

    /**
     * ONE declared parameter: whether a reward has to name it, and what stands in when it does not.
     *
     * <p>The two are independent, and between them cover every parameter anyone authors. Neither knob
     * makes a parameter exist - declaring it does; these say what happens when a reward leaves it out.
     * Required alone refuses the payout and says which parameter was missing. A default alone quietly
     * fills in. Both together means the default always applies, so the requirement never fires.
     */
    public static final class Param {

        @Nullable protected Boolean required;
        @Nullable protected String defaultValue;

        public static final BuilderCodec<Param> CODEC = BuilderCodec.builder(Param.class, Param::new)
                .appendInherited(new KeyedCodec<>("Required", Codec.BOOLEAN, false),
                        (o, v) -> o.required = v, o -> o.required, (o, p) -> o.required = p.required)
                .documentation("When true, a reward that does not name this parameter refuses to pay out and "
                        + "says so, instead of running a half-written command. Omit for false.").add()
                .appendInherited(new KeyedCodec<>("Default", Codec.STRING, false),
                        (o, v) -> o.defaultValue = v, o -> o.defaultValue, (o, p) -> o.defaultValue = p.defaultValue)
                .documentation("What stands in when a reward leaves this parameter out. Author one for every "
                        + "optional parameter the command names, or it substitutes as empty.").add()
                .build();

        public Param() {
        }

        /** Java-side factory; sets the same fields the codec fills. */
        @Nonnull
        public static Param of(@Nullable Boolean required, @Nullable String defaultValue) {
            Param p = new Param();
            p.required = required;
            p.defaultValue = defaultValue;
            return p;
        }

        @Nullable
        public Boolean getRequired() {
            return required;
        }

        @Nullable
        public String getDefault() {
            return defaultValue;
        }

        /** {@link #required}, reader-defaulted to false. */
        public boolean isRequired() {
            return required != null && required;
        }

        /** True when a value stands in for an omitted parameter, so requiring it can never fire. */
        public boolean hasDefault() {
            return defaultValue != null;
        }
    }
}
