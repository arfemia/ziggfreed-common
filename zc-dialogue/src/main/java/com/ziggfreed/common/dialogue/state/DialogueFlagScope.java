package com.ziggfreed.common.dialogue.state;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bson.BsonNull;
import org.bson.BsonValue;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.CommonLog;
import com.ziggfreed.common.dialogue.DialogueContext;
import com.ziggfreed.common.world.WhereValidator;
import com.ziggfreed.common.world.WorldNameMatcher.Pattern;
import com.ziggfreed.common.world.WorldSelector;

/**
 * INTERNAL PLUMBING: how a per-world {@code Where} is folded into a dialogue state key. Nothing here
 * is authored directly - a {@code Once} knob or a {@code Memories} declaration writes a {@code Where},
 * and this class turns that plus a raw key from {@link DialogueStateKeys} into the key the consumer's
 * {@link DialogueFlagStore} actually sees.
 *
 * <h2>The scope is the ONE world grammar, not a grammar of its own</h2>
 *
 * <p>{@code Where} is the shared {@link WorldSelector} group - {@code Match} / {@code GameplayConfig}
 * / {@code ExcludeMatch} - the same field an NPC placement, a world rule and a dialogue {@code World}
 * condition all carry. An author who has written one has already learned this one, and a new axis
 * reaches every surface at the same moment.
 *
 * <h2>What the state is filed under</h2>
 *
 * <p>State cannot be filed under the world's NAME: a dynamically created instance world is called
 * {@code instance-<Name>-<random uuid>} and is destroyed outright when it empties, so a first-visit
 * beat would come back on every fresh instance. So the key carries a stable CORE, resolved from
 * whichever axis actually matched the world the player is standing in:
 *
 * <ul>
 *   <li>a <b>{@code GameplayConfig}</b> hit files under that config id, which is authored, carries no
 *       uuid, and survives an instance being rebuilt - the sturdiest form, and the one to reach for
 *       when the state belongs to an instance;</li>
 *   <li>a <b>{@code Match}</b> pattern files under the pattern's literal core ({@code KweebecNightmare}
 *       for {@code *KweebecNightmare*}), and an exact name is its own core;</li>
 *   <li>with several matching, the MOST SPECIFIC wins deterministically: a config hit first, else the
 *       longest core, so which key is written never depends on the order the axes were read.</li>
 * </ul>
 *
 * <h2>The key format, and why the scope goes INSIDE any prefix</h2>
 *
 * <b>The scope segment wraps only the FINAL segment of the key, preserving any leading
 * prefix.</b> A quest-owned key {@code q:<questId>:greeted} scoped to {@code forgottentemple}
 * becomes:
 *
 * <pre>{@code q:<questId>:w:forgottentemple:greeted}</pre>
 *
 * and a bare {@code greeted} becomes {@code w:forgottentemple:greeted}.
 *
 * <p>This is NOT cosmetic. A consumer's store may clear a namespace by a leading-PREFIX match -
 * the hyMMO MMO clears a quest's dialogue state with {@code startsWith("q:" + questId + ":")} when
 * that quest is reset. Prepending the scope instead ({@code w:<core>:q:<id>:greeted}) would move
 * the key out of that prefix, the reset would silently miss it, and the dialogue would stay
 * soft-locked forever with no error anywhere. Inserting the scope before the last segment keeps
 * every leading namespace intact, so any prefix-based clear keeps working unchanged, and it is why
 * a memory's {@code ResetWithQuest} prefix and its {@code Where} scope compose.
 *
 * <p><b>Semantics in a world the selector does not match:</b> a WRITE is a no-op and a READ is
 * "unset". That is safe when the scoped state sits beside a {@code World} condition on the same
 * beat (it cannot be reached elsewhere anyway), but a TYPO would silently re-show a first-visit beat
 * forever - so a selector that matches no world the server has loaded emits a warn-once at runtime
 * and is a {@code dialogue/validate/DialogueStructureValidator} finding. An absent {@code Where}
 * narrows nothing: the state is kept once per character. So does one whose only axis is a bare
 * {@code *} - "every world" and "not per world at all" are the same state, and writing {@code *} to
 * mean the former is a validator finding rather than a key with an empty core in it.
 */
public final class DialogueFlagScope {

    /** The segment prefix a world scope contributes: {@code w:<core>}. */
    public static final String WORLD_SEGMENT_PREFIX = "w";

    /** The segment separator inside a flag id. */
    public static final char SEPARATOR = ':';

    /** What the {@code Where} leaf is for, written once because two types carry the same leaf. */
    public static final String WHERE_DOC =
            "Keep this per world, using the same Where group an NPC placement uses: GameplayConfig for "
                    + "an instance world (it survives the instance being rebuilt, so state is not "
                    + "forgotten each visit), Match for a world name or a name pattern. Leave it out to "
                    + "keep the state once per character.";

    /**
     * The retired single-pattern {@code World} leaf, kept only so a file still authoring it is told
     * what to write instead. The engine would otherwise refuse the file over an unknown key, which
     * names the mistake but not the fix.
     */
    public static final Codec<String> RETIRED_WORLD_LEAF = new Codec<>() {

        private static final String MESSAGE =
                "World is now the shared Where group: {\"Where\": {\"GameplayConfig\": [\"<config>\"]}} "
                        + "for an instance world, or {\"Where\": {\"Match\": [\"<name or pattern>\"]}} for "
                        + "one named world. It is the same Where an NPC placement carries.";

        @Override
        @Nullable
        public String decode(BsonValue value, ExtraInfo extraInfo) {
            throw new IllegalArgumentException(MESSAGE);
        }

        @Nonnull
        @Override
        public BsonValue encode(String value, ExtraInfo extraInfo) {
            return BsonNull.VALUE;
        }

        @Override
        @Nullable
        public String decodeJson(RawJsonReader reader, ExtraInfo extraInfo) throws IOException {
            throw new IllegalArgumentException(MESSAGE);
        }

        @Nonnull
        @Override
        public Schema toSchema(@Nonnull SchemaContext context) {
            return Codec.STRING.toSchema(context);
        }
    };

    /** One warn per selector per process; see {@link #keyFor}. */
    private static final Set<String> WARNED_UNMATCHED = ConcurrentHashMap.newKeySet();

    /** Selectors already confirmed against a loaded world, so the scan happens once per selector. */
    private static final Set<String> VERIFIED_MATCHED = ConcurrentHashMap.newKeySet();

    @Nullable protected WorldSelector where;

    public static final BuilderCodec<DialogueFlagScope> CODEC =
            BuilderCodec.builder(DialogueFlagScope.class, DialogueFlagScope::new)
                    .append(new KeyedCodec<>("Where", WorldSelector.CODEC, false),
                            (s, v) -> s.where = v, s -> s.where)
                    .documentation(WHERE_DOC)
                    .add()
                    .append(new KeyedCodec<>("World", RETIRED_WORLD_LEAF, false),
                            (s, v) -> { /* never decoded: the leaf refuses and says what to write */ },
                            s -> null)
                    .documentation("Retired. Write Where instead.")
                    .add()
                    .build();

    public DialogueFlagScope() {
    }

    /** Java-side construction (tests, a consumer building a scope in code). */
    @Nonnull
    public static DialogueFlagScope ofWhere(@Nullable WorldSelector where) {
        DialogueFlagScope scope = new DialogueFlagScope();
        scope.where = where;
        return scope;
    }

    /** The worlds this scope narrows to, or null when unauthored. */
    @Nullable
    public WorldSelector getWhere() {
        return where;
    }

    /**
     * True when this scope narrows nothing, so the flag stays global. That covers the unauthored
     * case AND a selector whose only axis is a bare {@code *}: matching every world and not scoping
     * at all are the same state, and treating them the same is what keeps a {@code *} out of the
     * stored key.
     */
    public boolean isBlank() {
        return where == null || where.isBlank() || matchesEveryWorld(where);
    }

    /** True when the selector's only statement is "every world", which is no narrowing at all. */
    public static boolean matchesEveryWorld(@Nullable WorldSelector selector) {
        if (selector == null || isEmpty(selector.getMatch())) {
            return false;
        }
        if (!isEmpty(selector.getGameplayConfig()) || !isEmpty(selector.getExcludeMatch())) {
            return false;
        }
        for (String raw : selector.getMatch()) {
            if (raw != null && !raw.isBlank() && !Pattern.parse(raw).isDefaultRule()) {
                return false;
            }
        }
        return true;
    }

    /** True when two declarations narrow to the same worlds, compared as the runtime compares them. */
    public static boolean sameSelector(@Nullable WorldSelector a, @Nullable WorldSelector b) {
        return describe(a).equals(describe(b));
    }

    // ==================== Key construction (pure) ====================

    /**
     * The stable core a scope files its state under IN THIS WORLD, or null when the selector does not
     * apply here at all. See the class javadoc for which axis wins; an empty string is the honest
     * answer for a selector that matched without narrowing which world it is in (a bare {@code *}
     * with an exclusion), which files one shared key.
     */
    @Nullable
    public static String coreFor(@Nullable WorldSelector selector, @Nullable String worldName,
            @Nullable String worldGameplayConfig) {
        if (selector == null || selector.match(worldName, worldGameplayConfig) == null) {
            return null;
        }
        // A GameplayConfig hit is the top of the shared ladder AND the sturdiest key: it is authored,
        // carries no uuid, and survives the instance world being torn down and rebuilt.
        String config = normalize(worldGameplayConfig);
        if (!config.isEmpty() && matchesExactly(selector.getGameplayConfig(), config)) {
            return config;
        }
        String best = null;
        String worldLower = normalize(worldName);
        String[] patterns = selector.getMatch();
        if (patterns != null && !worldLower.isEmpty()) {
            for (String raw : patterns) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                Pattern pattern = Pattern.parse(raw);
                if (pattern.matches(worldLower)
                        && (best == null || pattern.core().length() > best.length())) {
                    best = pattern.core();
                }
            }
        }
        return best == null ? "" : best;
    }

    /**
     * Insert a world scope segment into {@code flag}, <b>immediately before the flag's LAST
     * segment</b>, preserving every leading segment:
     *
     * <pre>{@code
     * scopedKey("greeted",          "forgotten_temple") -> "w:forgotten_temple:greeted"
     * scopedKey("q:temple:greeted", "forgotten_temple") -> "q:temple:w:forgotten_temple:greeted"
     * }</pre>
     *
     * <p>{@code stateKey} is a stable CORE (see {@link #coreFor}), never a raw world name, so a
     * rebuilt instance world files its state in the same place as the one before it.
     *
     * <p>The preserved leading prefix is load-bearing: see the class javadoc - a consumer clears a
     * namespace by prefix, and a scope in FRONT of that prefix would escape the clear and
     * soft-lock the dialogue permanently.
     */
    @Nonnull
    public static String scopedKey(@Nonnull String flag, @Nonnull String stateKey) {
        String segment = WORLD_SEGMENT_PREFIX + SEPARATOR + normalize(stateKey);
        int lastSeparator = flag.lastIndexOf(SEPARATOR);
        if (lastSeparator < 0) {
            return segment + SEPARATOR + flag;
        }
        return flag.substring(0, lastSeparator + 1) + segment + SEPARATOR + flag.substring(lastSeparator + 1);
    }

    /**
     * The PURE resolver: the storage key {@code flag} should be read from / written to under
     * {@code scope}, given the world the player is standing in.
     *
     * <ul>
     *   <li>no scope (unauthored, or every-world) - the bare {@code flag}, kept once per character;</li>
     *   <li>a scope that matches this world - the {@link #scopedKey} under the matching axis's
     *       core;</li>
     *   <li>a scope that does NOT match - {@code null}, meaning "this flag does not exist here": the
     *       caller no-ops a write and reads an unset value.</li>
     * </ul>
     */
    @Nullable
    public static String resolve(@Nullable DialogueFlagScope scope, @Nonnull String flag,
            @Nullable String worldName) {
        return resolve(scope, flag, worldName, null);
    }

    /** {@link #resolve(DialogueFlagScope, String, String)} with the world's gameplay config too. */
    @Nullable
    public static String resolve(@Nullable DialogueFlagScope scope, @Nonnull String flag,
            @Nullable String worldName, @Nullable String worldGameplayConfig) {
        if (scope == null || scope.isBlank()) {
            return flag;
        }
        String core = coreFor(scope.where, worldName, worldGameplayConfig);
        return core == null ? null : scopedKey(flag, core);
    }

    // ==================== Engine-facing ====================

    /**
     * {@link #resolve} against the player's current world, read through {@link DialogueWorlds}.
     * Returns null when the scope does not match that world (write no-op / read unset).
     *
     * <p><b>Warn-once:</b> when the scope resolves to nothing AND it matches no world the server
     * currently has loaded, one warning is logged per selector per process (a
     * {@link ConcurrentHashMap} key set, so the warning cannot spam a dialogue that re-renders
     * every click). A selector that DOES describe a loaded world is never warned about - that is
     * the ordinary "scoped to somewhere else" case, not a bug - and an unreadable world list is
     * never warned about either, so a pre-boot evaluation cannot produce a false alarm.
     */
    @Nullable
    static String keyFor(@Nullable DialogueFlagScope scope, @Nonnull String flag,
            @Nonnull DialogueContext ctx) {
        if (scope == null || scope.isBlank()) {
            return flag;
        }
        String key = resolve(scope, flag, DialogueWorlds.currentWorldName(ctx),
                DialogueWorlds.currentGameplayConfig(ctx));
        if (key == null) {
            warnUnmatchedOnce(scope.where, flag);
        }
        return key;
    }

    private static void warnUnmatchedOnce(@Nullable WorldSelector selector, @Nonnull String flag) {
        // The ordinary case is a VALID selector for a world the player is simply not in right now,
        // and that case is re-evaluated on every render - so confirm a selector against the loaded
        // worlds at most once rather than walking them on each miss.
        String described = describe(selector);
        if (VERIFIED_MATCHED.contains(described) || WARNED_UNMATCHED.contains(described)) {
            return;
        }
        Iterable<WhereValidator.LoadedWorld> loaded = DialogueWorlds.loadedWorlds();
        boolean any = false;
        for (WhereValidator.LoadedWorld world : loaded) {
            any = true;
            if (selector != null && selector.match(world.name(), world.gameplayConfig()) != null) {
                VERIFIED_MATCHED.add(described);
                return;
            }
        }
        if (!any) {
            // Cannot tell yet (no world readable); never latch a verdict from that.
            return;
        }
        if (!WARNED_UNMATCHED.add(described)) {
            return;
        }
        try {
            CommonLog.LOGGER.atWarning().log(
                    "[Dialogue] state '%s' is kept per world %s, which matches no world this server"
                            + " has loaded - it will never be written or read. Check it against your real"
                            + " worlds (GameplayConfig for an instance world, *Name* for a family of"
                            + " them).",
                    flag, described);
        } catch (Throwable ignored) {
            // log-manager-less unit JVM: the flogger LOGGER can throw; swallow it.
        }
    }

    /**
     * Forget every warn-once record AND every confirmed-matched selector. Call from a consumer's
     * config-reload command: worlds come and go, so both verdicts go stale.
     */
    public static void resetWarnings() {
        WARNED_UNMATCHED.clear();
        VERIFIED_MATCHED.clear();
    }

    // ==================== helpers ====================

    /** A selector as one stable, comparable line: what a warn-once key and an equality test need. */
    @Nonnull
    private static String describe(@Nullable WorldSelector selector) {
        if (selector == null) {
            return "";
        }
        return join(selector.getGameplayConfig()) + "|" + join(selector.getMatch())
                + "|" + join(selector.getExcludeMatch());
    }

    @Nonnull
    private static String join(@Nullable String[] values) {
        if (values == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(normalize(value));
        }
        return sb.toString();
    }

    private static boolean matchesExactly(@Nullable String[] values, @Nonnull String wanted) {
        if (values == null) {
            return false;
        }
        for (String value : values) {
            if (value != null && normalize(value).equals(wanted)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isEmpty(@Nullable String[] values) {
        if (values == null || values.length == 0) {
            return true;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return false;
            }
        }
        return true;
    }

    @Nonnull
    private static String normalize(@Nullable String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
}
