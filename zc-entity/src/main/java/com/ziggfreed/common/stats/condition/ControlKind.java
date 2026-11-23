package com.ziggfreed.common.stats.condition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.util.SafeLog;

/**
 * A kind of crowd control: the name a foe's rules use ({@link BossRules}) and the row the one landing reads its rule
 * from (zc-effects' {@code ControlLanding}, through {@link ControlKinds}): a freeze counts as a stun, a grab or a drive
 * as a carry, a flinch and a guard break as an interrupt. An effect's kind is its effect family's {@code Control}
 * ({@link EffectFamilyAsset}); a step of a fixed kind (a push, a taunt, a guard break) names its own.
 *
 * <p><b>Kinds are data.</b> Each kind is a file, {@code Server/ZiggfreedCommon/ControlKinds/<Id>.json}
 * ({@link ControlKindAsset}): the library ships the twelve its own code names through the constants below, and a pack
 * adds a kind of its own (a fear, a silence) by shipping a file of a new id, which an effect family's {@code Control}
 * and a rule tag then name like any other. A server holds at most {@value #MAX} kinds; one past that is warned of once
 * and counts as none. Authored by {@link #id()}, matched without regard to case; one instance per id for the life of
 * the process, so a kind compares by identity, and its {@link #bit()} never moves while the server runs (a kind is
 * never saved, so a bit is never read back from disk).
 */
public final class ControlKind {

    /** The most kinds a server holds: a mask of kinds is one {@code int}. */
    public static final int MAX = 32;

    private static final Object LOCK = new Object();

    /** Every kind by lower-cased id. */
    private static final Map<String, ControlKind> BY_ID = new ConcurrentHashMap<>();

    /** Every kind in bit order. */
    private static final List<ControlKind> ALL = new CopyOnWriteArrayList<>();

    private static final AtomicBoolean WARNED_FULL = new AtomicBoolean();

    public static final ControlKind STUN = builtIn("Stun", "A stun, a stagger, a knockdown or a freeze");
    public static final ControlKind HOLD = builtIn("Hold", "A root, a snare or a pin in place");
    public static final ControlKind SLOW = builtIn("Slow", "A slow");
    public static final ControlKind PUSH = builtIn("Push", "A knockback, a launch or a lift");
    public static final ControlKind PULL = builtIn("Pull", "A pull toward whoever cast it");
    public static final ControlKind CARRY = builtIn("Carry", "A grab or a drive that carries the foe");
    public static final ControlKind INTERRUPT = builtIn("Interrupt", "A flinch or a guard break");
    public static final ControlKind TAUNT = builtIn("Taunt", "A taunt that turns the foe onto its caster");
    public static final ControlKind DECOY = builtIn("Decoy", "A decoy that draws the foe away");
    public static final ControlKind WEAKNESS = builtIn("Weakness", "A weakness: the foe takes more damage");
    public static final ControlKind WITHER = builtIn("Wither", "A wither: the foe deals less damage");
    public static final ControlKind MARK = builtIn("Mark", "A mark: a counter's stacks and the look they show");

    /** The library's own kinds, in bit order: the ones its code names. */
    private static final List<ControlKind> BUILT_IN = List.copyOf(ALL);

    @Nonnull
    private final String id;
    @Nonnull
    private final String meaning;
    private final int index;
    private final boolean builtIn;

    private ControlKind(@Nonnull String id, @Nonnull String meaning, int index, boolean builtIn) {
        this.id = id;
        this.meaning = meaning;
        this.index = index;
        this.builtIn = builtIn;
    }

    @Nonnull
    private static ControlKind builtIn(@Nonnull String id, @Nonnull String meaning) {
        ControlKind kind = new ControlKind(id, meaning, ALL.size(), true);
        BY_ID.put(id.toLowerCase(Locale.ROOT), kind);
        ALL.add(kind);
        return kind;
    }

    /** The authored name ({@code Stun}, {@code Taunt}, a pack's own). */
    @Nonnull
    public String id() {
        return id;
    }

    /** A one-line meaning, for the Asset Editor's dropdown of the library's own kinds. */
    @Nonnull
    public String meaning() {
        return meaning;
    }

    /** Is it one of the library's own twelve, which its code names? */
    public boolean isBuiltIn() {
        return builtIn;
    }

    /** This kind's bit in a mask of kinds. */
    public int bit() {
        return 1 << index;
    }

    /** Is this kind in {@code mask}? */
    public boolean in(int mask) {
        return (mask & bit()) != 0;
    }

    @Override
    public String toString() {
        return id;
    }

    /** The kind named {@code raw} (trimmed, any case), or null for a blank name or one no kind file declares. */
    @Nullable
    public static ControlKind parse(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return BY_ID.get(raw.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * The kind {@code id} declares, a pack's own kind taking the next free bit the first time it is seen; the same
     * instance ever after. Null for a blank id, and for a new one once {@value #MAX} kinds are held (warned once).
     * From {@link ControlKinds} as the kind files load.
     */
    @Nullable
    static ControlKind declare(@Nullable String rawId, @Nullable String meaning) {
        if (rawId == null || rawId.isBlank()) {
            return null;
        }
        String id = rawId.trim();
        String key = id.toLowerCase(Locale.ROOT);
        ControlKind known = BY_ID.get(key);
        if (known != null) {
            return known;
        }
        synchronized (LOCK) {
            known = BY_ID.get(key);
            if (known != null) {
                return known;
            }
            if (ALL.size() >= MAX) {
                if (WARNED_FULL.compareAndSet(false, true)) {
                    SafeLog.warn("[control] the kind of control '" + id + "' is past the " + MAX + " a server holds, "
                            + "so it counts as none; merge it into a kind already loaded");
                }
                return null;
            }
            ControlKind kind = new ControlKind(id, meaning == null || meaning.isBlank() ? id : meaning.trim(),
                    ALL.size(), false);
            BY_ID.put(key, kind);
            ALL.add(kind);
            return kind;
        }
    }

    /** Every kind held, the library's twelve first, in bit order. */
    @Nonnull
    public static List<ControlKind> all() {
        return Collections.unmodifiableList(new ArrayList<>(ALL));
    }

    /** Every kind's authored name, in bit order. */
    @Nonnull
    public static String[] ids() {
        List<ControlKind> held = ALL;
        String[] out = new String[held.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = held.get(i).id;
        }
        return out;
    }

    /**
     * Alternating name and meaning of the library's own twelve, in order. A schema leaf naming a kind takes
     * {@code EditorSchema.assetRef(ControlKindAsset.class)}, the editor's picker over every kind file loaded (a pack's
     * own among them); this list is only the library's.
     */
    @Nonnull
    public static String[] editorPairs() {
        String[] out = new String[BUILT_IN.size() * 2];
        for (int i = 0; i < BUILT_IN.size(); i++) {
            out[i * 2] = BUILT_IN.get(i).id;
            out[i * 2 + 1] = BUILT_IN.get(i).meaning;
        }
        return out;
    }

    /** The kinds in {@code mask}, in bit order. */
    @Nonnull
    public static Set<ControlKind> ofMask(int mask) {
        Set<ControlKind> out = new LinkedHashSet<>();
        if (mask == 0) {
            return Collections.unmodifiableSet(out);
        }
        for (ControlKind kind : ALL) {
            if (kind.in(mask)) {
                out.add(kind);
            }
        }
        return Collections.unmodifiableSet(out);
    }
}
