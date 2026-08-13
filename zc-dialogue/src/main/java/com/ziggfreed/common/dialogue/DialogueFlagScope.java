package com.ziggfreed.common.dialogue;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.CommonLog;
import com.ziggfreed.common.world.WorldNameMatcher.Pattern;

/**
 * INTERNAL PLUMBING: how a per-world {@code World} leaf is folded into a dialogue state key.
 * Nothing here is authored directly - a {@code Once} knob or a {@code Memories} declaration writes
 * a {@code World}, and this class turns that plus a raw key from {@link DialogueStateKeys} into the
 * key the consumer's {@link DialogueFlagStore} actually sees.
 *
 * <h2>The knob is a world name or a pattern, and the pattern is the fuzziness dial</h2>
 *
 * <p>{@code World} takes the ordinary world-name grammar every other world-targeting field uses:
 * an exact name ({@code default}), a prefix ({@code Arena_*}), a suffix ({@code *_Boss}) or a
 * CONTAINS form ({@code *KweebecNightmare*}). There is no second exact-versus-fuzzy switch beside
 * it, because choosing the pattern IS that choice.
 *
 * <p>The contains form is the one that matters for instances. A dynamically created instance world
 * is named {@code instance-KweebecNightmare_Barn-<random uuid>} and is destroyed outright when it
 * empties, so state keyed by the world's literal name would come back on every fresh instance.
 * State is therefore keyed by the pattern's literal CORE - {@code KweebecNightmare} for
 * {@code *KweebecNightmare*} - which is stable across every re-instantiation, and narrowing to one
 * arena is simply a longer core ({@code *KweebecNightmare_Barn*}). An exact name is its own core,
 * so a per-world scope on a persistent world reads exactly as written.
 *
 * <h2>The key format, and why the scope goes INSIDE any prefix</h2>
 *
 * <b>The scope segment wraps only the FINAL segment of the key, preserving any leading
 * prefix.</b> A quest-owned key {@code q:<questId>:greeted} scoped to {@code forgotten_temple}
 * becomes:
 *
 * <pre>{@code q:<questId>:w:forgotten_temple:greeted}</pre>
 *
 * and a bare {@code greeted} becomes {@code w:forgotten_temple:greeted}.
 *
 * <p>This is NOT cosmetic. A consumer's store may clear a namespace by a leading-PREFIX match -
 * the hyMMO MMO clears a quest's dialogue state with {@code startsWith("q:" + questId + ":")} when
 * that quest is reset. Prepending the scope instead ({@code w:<core>:q:<id>:greeted}) would move
 * the key out of that prefix, the reset would silently miss it, and the dialogue would stay
 * soft-locked forever with no error anywhere. Inserting the scope before the last segment keeps
 * every leading namespace intact, so any prefix-based clear keeps working unchanged, and it is why
 * a memory's {@code ResetWithQuest} prefix and its {@code World} scope compose.
 *
 * <p><b>Semantics in a world the pattern does not match:</b> a WRITE is a no-op and a READ is
 * "unset". That is safe when the scoped state sits beside a {@code World} condition on the same
 * entry (the beat cannot be reached elsewhere anyway), but a TYPO in the pattern would silently
 * re-show a first-visit beat forever - so a pattern that matches no world the server has loaded
 * emits a warn-once at runtime and is a {@code dialogue/validate/DialogueStructureValidator}
 * finding. An absent or blank {@code World} narrows nothing: the state is kept once per character.
 * So does a bare {@code *}, which is worth saying out loud - "every world" and "not per world at
 * all" are the same state, and writing {@code *} to mean the former is a validator finding rather
 * than a key with an empty core in it.
 */
public final class DialogueFlagScope {

    /** The segment prefix a world scope contributes: {@code w:<patternCore>}. */
    public static final String WORLD_SEGMENT_PREFIX = "w";

    /** The segment separator inside a flag id. */
    public static final char SEPARATOR = ':';

    public static final BuilderCodec<DialogueFlagScope> CODEC =
            BuilderCodec.builder(DialogueFlagScope.class, DialogueFlagScope::new)
                    .append(new KeyedCodec<>("World", Codec.STRING, false),
                            (s, v) -> s.world = v, s -> s.world)
                    .documentation("Keep this per world: an exact world name, or a pattern - Foo* "
                            + "(prefix), *Foo (suffix), *Foo* (contains). Use the contains form for a "
                            + "family of instance worlds, whose names carry a random uuid. Leave it "
                            + "out to keep the state once per character.")
                    .add()
                    .build();

    /** One warn per pattern per process; see {@link #keyFor}. */
    private static final Set<String> WARNED_UNMATCHED = ConcurrentHashMap.newKeySet();

    /** Patterns already confirmed against a loaded world, so the scan happens once per pattern. */
    private static final Set<String> VERIFIED_MATCHED = ConcurrentHashMap.newKeySet();

    @Nullable protected String world;

    public DialogueFlagScope() {
    }

    /** Java-side construction (tests, a consumer building a scope in code). */
    @Nonnull
    public static DialogueFlagScope ofWorld(@Nullable String worldPattern) {
        DialogueFlagScope scope = new DialogueFlagScope();
        scope.world = worldPattern;
        return scope;
    }

    /** The world name or pattern this scope narrows to, or null when unauthored. */
    @Nullable
    public String getWorld() {
        return world;
    }

    /**
     * True when this scope narrows nothing, so the flag stays global. That covers the unauthored
     * case AND a bare {@code *}: matching every world and not scoping at all are the same state,
     * and treating them the same is what keeps a {@code *} out of the stored key.
     */
    public boolean isBlank() {
        return world == null || world.isBlank() || Pattern.parse(world).isDefaultRule();
    }

    // ==================== Key construction (pure) ====================

    /**
     * The stable state key a pattern files its state under: the pattern's literal core, which for
     * an exact name is the name itself. Empty for a bare {@code *} (which {@link #isBlank} already
     * treats as no scope at all).
     */
    @Nonnull
    public static String stateKeyOf(@Nullable String worldPattern) {
        return worldPattern == null ? "" : Pattern.parse(worldPattern).core();
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
     * <p>{@code stateKey} is a pattern CORE (see {@link #stateKeyOf}), never a raw world name, so a
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
     * {@code scope}, given the name of the world the player is standing in.
     *
     * <ul>
     *   <li>no scope (null, blank, or a bare {@code *}) - the bare {@code flag}, kept once per
     *       character;</li>
     *   <li>a scope whose pattern matches this world - the {@link #scopedKey} under the pattern's
     *       core;</li>
     *   <li>a scope whose pattern does NOT match - {@code null}, meaning "this flag does not exist
     *       here": the caller no-ops a write and reads an unset value.</li>
     * </ul>
     */
    @Nullable
    public static String resolve(@Nullable DialogueFlagScope scope, @Nonnull String flag,
            @Nullable String worldName) {
        if (scope == null || scope.isBlank()) {
            return flag;
        }
        if (worldName == null || worldName.isEmpty()) {
            return null;
        }
        Pattern pattern = Pattern.parse(scope.world);
        return pattern.matches(worldName.toLowerCase(Locale.ROOT))
                ? scopedKey(flag, pattern.core())
                : null;
    }

    // ==================== Engine-facing ====================

    /**
     * {@link #resolve} against the player's current world, read through
     * {@link DialogueWorlds#currentWorld}. Returns null when the scope's pattern does not match
     * that world (write no-op / read unset).
     *
     * <p><b>Warn-once:</b> when the scope resolves to nothing AND the pattern matches no world the
     * server currently has loaded, one warning is logged per pattern per process (a
     * {@link ConcurrentHashMap} key set, so the warning cannot spam a dialogue that re-renders
     * every click). A pattern that DOES describe a loaded world is never warned about - that is
     * the ordinary "scoped to somewhere else" case, not a bug - and an unreadable world list is
     * never warned about either, so a pre-boot evaluation cannot produce a false alarm.
     */
    @Nullable
    static String keyFor(@Nullable DialogueFlagScope scope, @Nonnull String flag,
            @Nonnull DialogueContext ctx) {
        if (scope == null || scope.isBlank()) {
            return flag;
        }
        String key = resolve(scope, flag, DialogueWorlds.currentWorldName(ctx));
        if (key == null) {
            warnUnmatchedOnce(normalize(scope.world), flag);
        }
        return key;
    }

    private static void warnUnmatchedOnce(@Nonnull String worldPattern, @Nonnull String flag) {
        // The ordinary case is a VALID pattern for a world the player is simply not in right now,
        // and that case is re-evaluated on every render - so confirm a pattern against the loaded
        // worlds at most once rather than walking them on each miss.
        if (VERIFIED_MATCHED.contains(worldPattern) || WARNED_UNMATCHED.contains(worldPattern)) {
            return;
        }
        Set<String> loaded = DialogueWorlds.loadedWorldNames();
        if (loaded.isEmpty()) {
            // Cannot tell yet (no world readable); never latch a verdict from that.
            return;
        }
        Pattern pattern = Pattern.parse(worldPattern);
        for (String name : loaded) {
            if (pattern.matches(name)) {
                VERIFIED_MATCHED.add(worldPattern);
                return;
            }
        }
        if (!WARNED_UNMATCHED.add(worldPattern)) {
            return;
        }
        try {
            CommonLog.LOGGER.atWarning().log(
                    "[Dialogue] state '%s' is kept per world '%s', which matches no world this server"
                            + " has loaded - it will never be written or read. Check the pattern against"
                            + " your real world names (use *Name* for a family of instance worlds).",
                    flag, worldPattern);
        } catch (Throwable ignored) {
            // log-manager-less unit JVM: the flogger LOGGER can throw; swallow it.
        }
    }

    /**
     * Forget every warn-once record AND every confirmed-matched pattern. Call from a consumer's
     * config-reload command: worlds come and go, so both verdicts go stale.
     */
    public static void resetWarnings() {
        WARNED_UNMATCHED.clear();
        VERIFIED_MATCHED.clear();
    }

    @Nonnull
    private static String normalize(@Nullable String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
}
