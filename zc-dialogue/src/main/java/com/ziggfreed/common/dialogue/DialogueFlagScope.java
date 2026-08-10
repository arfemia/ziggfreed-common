package com.ziggfreed.common.dialogue;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.server.core.universe.world.World;
import com.ziggfreed.common.CommonLog;

/**
 * The optional {@code Scope} leaf on the generic {@code SetFlag} action and the {@code Flag} /
 * {@code NotFlag} conditions: the namespace a dialogue flag is written to and read from.
 *
 * <p>Authored shape (a nested group, so a future scope axis is a new leaf rather than a new
 * prefixed key):
 * <pre>{@code
 * { "Type": "NotFlag", "Flag": "greeted",
 *   "Scope": { "WorldSelector": "forgotten_temple" } }
 * }</pre>
 *
 * <p>{@code WorldSelector} is a selector NAME from the world-identity vocabulary
 * ({@code Server/ZiggfreedCommon/WorldSelectors/*.json}), never a raw world name. That is the
 * whole point: a dynamically-created instance world is named
 * {@code instance-Forgotten_Temple-<random-uuid>} and is destroyed outright when it empties, so a
 * flag keyed by the world's NAME would re-fire on every fresh instance. Keyed by the selector
 * name, "I already greeted you in the Forgotten Temple" survives the instance being torn down and
 * re-created.
 *
 * <h2>The key format, and why the scope goes INSIDE any prefix</h2>
 *
 * <b>The scope segment wraps only the FINAL segment of the flag, preserving any leading
 * prefix.</b> A quest-scoped flag {@code q:<questId>:greeted} scoped to {@code forgotten_temple}
 * becomes:
 *
 * <pre>{@code q:<questId>:w:forgotten_temple:greeted}</pre>
 *
 * and a bare flag {@code greeted} becomes {@code w:forgotten_temple:greeted}.
 *
 * <p>This is NOT cosmetic. A consumer's flag store may clear a namespace by a leading-PREFIX
 * match - the hyMMO MMO clears a quest's flags with {@code startsWith("q:" + questId + ":")} when
 * that quest is reset. Prepending the scope instead ({@code w:<name>:q:<id>:greeted}) would move
 * the flag out of that prefix, the reset would silently miss it, and the dialogue would stay
 * soft-locked forever with no error anywhere - exactly the failure a consumer's self-heal
 * convention exists to prevent. Inserting the scope before the last segment keeps every leading
 * namespace intact, so any prefix-based clear keeps working unchanged.
 *
 * <p><b>Semantics of a scope the current world does not carry:</b> a WRITE is a no-op and a READ
 * is "unset". That is safe when the scoped flag sits beside a {@code World} condition on the same
 * node (the beat cannot be reached elsewhere anyway), but a TYPO in the selector name would
 * silently re-fire a first-visit beat forever - so an unknown selector name emits a warn-once at
 * runtime and is a {@code dialogue/validate/DialogueStructureValidator} finding. A {@code Scope}
 * with a blank/absent {@code WorldSelector} is treated as no scope at all (the flag stays global),
 * and is also a validator finding, because authoring an empty group reads as an intent that did
 * not survive.
 */
public final class DialogueFlagScope {

    /** The segment prefix a world-selector scope contributes: {@code w:<selectorName>}. */
    public static final String WORLD_SEGMENT_PREFIX = "w";

    /** The segment separator inside a flag id. */
    public static final char SEPARATOR = ':';

    public static final BuilderCodec<DialogueFlagScope> CODEC =
            BuilderCodec.builder(DialogueFlagScope.class, DialogueFlagScope::new)
                    .append(new KeyedCodec<>("WorldSelector", Codec.STRING, false),
                            (s, v) -> s.worldSelector = v, s -> s.worldSelector).add()
                    .build();

    /** One warn per selector name per process; see {@link #keyFor}. */
    private static final Set<String> WARNED_UNKNOWN = ConcurrentHashMap.newKeySet();

    /** Names already confirmed against the loaded pool, so the scan happens once per name. */
    private static final Set<String> VERIFIED_KNOWN = ConcurrentHashMap.newKeySet();

    @Nullable protected String worldSelector;

    public DialogueFlagScope() {
    }

    /** Java-side construction (tests, a consumer building a scope in code). */
    @Nonnull
    public static DialogueFlagScope ofWorldSelector(@Nullable String selectorName) {
        DialogueFlagScope scope = new DialogueFlagScope();
        scope.worldSelector = selectorName;
        return scope;
    }

    /** The selector NAME this scope narrows to, or null when unauthored. */
    @Nullable
    public String getWorldSelector() {
        return worldSelector;
    }

    /** True when nothing is authored, so this scope narrows nothing (the flag stays global). */
    public boolean isBlank() {
        return worldSelector == null || worldSelector.isBlank();
    }

    // ==================== Key construction (pure) ====================

    /**
     * Insert a world-selector scope segment into {@code flag}, <b>immediately before the flag's
     * LAST segment</b>, preserving every leading segment:
     *
     * <pre>{@code
     * scopedKey("greeted",          "forgotten_temple") -> "w:forgotten_temple:greeted"
     * scopedKey("q:temple:greeted", "forgotten_temple") -> "q:temple:w:forgotten_temple:greeted"
     * }</pre>
     *
     * <p>The preserved leading prefix is load-bearing: see the class javadoc - a consumer clears a
     * namespace by prefix, and a scope in FRONT of that prefix would escape the clear and
     * soft-lock the dialogue permanently.
     */
    @Nonnull
    public static String scopedKey(@Nonnull String flag, @Nonnull String selectorName) {
        String segment = WORLD_SEGMENT_PREFIX + SEPARATOR + normalize(selectorName);
        int lastSeparator = flag.lastIndexOf(SEPARATOR);
        if (lastSeparator < 0) {
            return segment + SEPARATOR + flag;
        }
        return flag.substring(0, lastSeparator + 1) + segment + SEPARATOR + flag.substring(lastSeparator + 1);
    }

    /**
     * The PURE resolver: the storage key {@code flag} should be read from / written to under
     * {@code scope}, given the selector names the player's current world carries.
     *
     * <ul>
     *   <li>no scope (null or blank) - the bare {@code flag} (unchanged behaviour, the
     *       no-regression case);</li>
     *   <li>a scope whose name the world carries - the {@link #scopedKey};</li>
     *   <li>a scope whose name the world does NOT carry - {@code null}, meaning "this flag does
     *       not exist here": the caller no-ops a write and reads an unset value.</li>
     * </ul>
     */
    @Nullable
    public static String resolve(@Nullable DialogueFlagScope scope, @Nonnull String flag,
            @Nonnull Set<String> worldSelectorNames) {
        if (scope == null || scope.isBlank()) {
            return flag;
        }
        String wanted = normalize(scope.worldSelector);
        return worldSelectorNames.contains(wanted) ? scopedKey(flag, wanted) : null;
    }

    // ==================== Engine-facing ====================

    /**
     * {@link #resolve} against the player's current world, read through
     * {@link DialogueWorlds#currentWorld}. Returns null when the scope names a selector this world
     * does not carry (write no-op / read unset).
     *
     * <p><b>Warn-once:</b> when the scope resolves to nothing AND the selector name is unknown to
     * the whole loaded selector pool, one warning is logged per selector name per process (a
     * {@link ConcurrentHashMap} key set, so the warning cannot spam a dialogue that re-renders
     * every click). A name the pool DOES know is never warned about - that is the ordinary
     * "scoped to another world" case, not a bug. An empty pool is never warned about either, so a
     * pre-asset-load evaluation cannot produce a false alarm.
     */
    @Nullable
    static String keyFor(@Nullable DialogueFlagScope scope, @Nonnull String flag,
            @Nonnull DialogueContext ctx) {
        if (scope == null || scope.isBlank()) {
            return flag;
        }
        World world = DialogueWorlds.currentWorld(ctx);
        String key = resolve(scope, flag, DialogueWorlds.selectorNamesOf(world));
        if (key == null) {
            warnUnknownOnce(normalize(scope.worldSelector), flag);
        }
        return key;
    }

    private static void warnUnknownOnce(@Nonnull String selectorName, @Nonnull String flag) {
        // The ordinary case is a VALID name whose selector this world simply does not carry, and
        // that case is re-evaluated on every render - so confirm a name against the pool at most
        // once rather than folding the pool on each miss.
        if (VERIFIED_KNOWN.contains(selectorName) || WARNED_UNKNOWN.contains(selectorName)) {
            return;
        }
        Set<String> known = DialogueWorlds.knownSelectorNames();
        if (known.isEmpty()) {
            // Cannot tell yet (assets may not have folded); never latch a verdict from that.
            return;
        }
        if (known.contains(selectorName)) {
            VERIFIED_KNOWN.add(selectorName);
            return;
        }
        if (!WARNED_UNKNOWN.add(selectorName)) {
            return;
        }
        try {
            CommonLog.LOGGER.atWarning().log(
                    "[Dialogue] flag '%s' is scoped to world selector '%s', which no loaded"
                            + " WorldSelector contributes - the flag will never be written or read."
                            + " Check Server/ZiggfreedCommon/WorldSelectors for the intended name.",
                    flag, selectorName);
        } catch (Throwable ignored) {
            // log-manager-less unit JVM: the flogger LOGGER can throw; swallow it.
        }
    }

    /**
     * Forget every warn-once record AND every confirmed-known name. Call from a consumer's
     * config-reload command: a reload can add or remove selectors, so both verdicts go stale.
     */
    public static void resetWarnings() {
        WARNED_UNKNOWN.clear();
        VERIFIED_KNOWN.clear();
    }

    @Nonnull
    private static String normalize(@Nullable String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
}
