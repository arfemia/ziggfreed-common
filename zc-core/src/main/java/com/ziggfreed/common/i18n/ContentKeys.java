package com.ziggfreed.common.i18n;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

/**
 * The one place an AUTHORED content key becomes the id the client can actually resolve.
 *
 * <p>The engine's {@code I18nModule} derives a key's namespace from the {@code .lang} FILENAME it
 * was defined in, so an entry written {@code shop.general.title} inside {@code mmoskilltree.lang}
 * registers as {@code mmoskilltree.shop.general.title} - while content authors the key WITHOUT that
 * namespace, because that is what its author typed into their own lang file. A surface that hands
 * the client the authored key verbatim hands it an id nothing resolves, and the raw key is what a
 * player reads. Every library surface that paints authored text asks this class rather than
 * prefixing anything itself, because a title on a board, a shelf heading, a category label and a
 * currency name are the same problem, and solving it per page is how three of them end up right and
 * the fourth ships as a raw key.
 *
 * <h2>How a key finds its owner</h2>
 *
 * <p>The server's own loaded catalogue ({@link LangCatalog}) is the ONE authority - nothing is
 * registered, by this library or by any consumer. In order:
 *
 * <ol>
 *   <li>The catalogue carries the key EXACTLY as authored: it is already a full registered id (a
 *       fully-qualified key, a native {@code server.items.*} name) and goes out untouched.</li>
 *   <li>Otherwise, any loaded id that ends in {@code .<authoredKey>} is the key under a namespace
 *       some loaded {@code .lang} file carries, and the LEXICOGRAPHICALLY SMALLEST such id wins. A
 *       mod that shipped a key is the mod that shipped its translation, so presence in the loaded
 *       catalogue IS the attribution - and it is the only one available, since a folded asset
 *       carries no record of which jar or pack wrote it.</li>
 *   <li>Otherwise the key exactly as written: an unfilled server keeps painting a traceable raw
 *       key rather than blanking a name, and behaves precisely as one did before this seam
 *       existed.</li>
 * </ol>
 *
 * <p>Step 2's ordering is deliberate: the candidates are whatever the server loaded, so only a rule
 * that depends on nothing but the ids themselves resolves the same way on every boot. A key TWO
 * catalogues both define therefore goes to the alphabetically first registered id - stable, but
 * arbitrary, which is why content ids are already expected to be owner-prefixed when two mods share
 * a store.
 */
public final class ContentKeys {

    /**
     * The step-2 answers already computed against one catalogue instance. The engine hands back the
     * SAME map instance until a load bumps its message version (see {@link LangCatalog#catalogue}),
     * so instance identity is the invalidation signal: a new catalogue starts a new memo, and a
     * racing thread at worst computes against its own short-lived one - never against a mix.
     */
    private record Memo(@Nonnull Map<String, String> catalogue,
            @Nonnull ConcurrentHashMap<String, String> hits) {

        static final String MISS = "";

        Memo(@Nonnull Map<String, String> catalogue) {
            this(catalogue, new ConcurrentHashMap<>());
        }

        @Nullable
        String hit(@Nonnull String key) {
            String owned = hits.computeIfAbsent(key, this::scan);
            return MISS.equals(owned) ? null : owned;
        }

        /**
         * The loaded id carrying {@code key} under some namespace, when more than one does.
         *
         * <p>A CONSUMER's word outranks this library's own shipped default, which is the rule that
         * lets a mod reword a heading the library also ships without touching the library. Among
         * ids of equal rank the lexicographically smallest wins, so the answer is the same on every
         * boot however the catalogue happened to be assembled. Ranking by prefix rather than by
         * plain alphabetical order is deliberate: alphabetically, a namespace sorting after
         * {@code ziggfreedcommon.} would beat the library and one sorting before it would lose, so
         * the contract would hold or fail purely on a mod's choice of name.
         */
        @Nonnull
        private String scan(@Nonnull String key) {
            String suffix = '.' + key;
            String best = null;
            for (String id : catalogue.keySet()) {
                if (id.length() > suffix.length() && id.endsWith(suffix) && beats(id, best)) {
                    best = id;
                }
            }
            return best == null ? MISS : best;
        }

        /** Whether {@code id} outranks {@code best}: a consumer first, then the smaller id. */
        private static boolean beats(@Nonnull String id, @Nullable String best) {
            if (best == null) {
                return true;
            }
            boolean idIsLibrary = id.startsWith(LIBRARY_PREFIX);
            boolean bestIsLibrary = best.startsWith(LIBRARY_PREFIX);
            if (idIsLibrary != bestIsLibrary) {
                return bestIsLibrary;
            }
            return id.compareTo(best) < 0;
        }
    }

    /** This library's own lang namespace; a consumer's word outranks anything under it. */
    private static final String LIBRARY_PREFIX = "ziggfreedcommon.";

    private static volatile Memo memo = new Memo(Map.of());

    private ContentKeys() {
    }

    /**
     * The registered id for an authored key: the key itself when the loaded catalogue carries it
     * exactly, else the key under whichever loaded namespace ships it, else the key exactly as it
     * was written.
     */
    @Nonnull
    public static String resolved(@Nonnull String authoredKey) {
        String key = authoredKey.trim();
        if (key.isEmpty()) {
            return authoredKey;
        }
        if (LangCatalog.has(key)) {
            return key;
        }
        String owned = namespaceHit(key);
        return owned == null ? key : owned;
    }

    /**
     * An authored key as a client-resolved {@link Message}, its {@code {0},{1},...} slots bound from
     * {@code args} on {@link Msg}'s rules (a localized arg stays a nested {@link Message}).
     */
    @Nonnull
    public static Message tr(@Nonnull String authoredKey, @Nonnull Object... args) {
        return Msg.key(resolved(authoredKey), args);
    }

    /** True when the loaded catalogue ships this authored key, exactly or under a namespace. */
    public static boolean known(@Nonnull String authoredKey) {
        String key = authoredKey.trim();
        return !key.isEmpty() && (LangCatalog.has(key) || namespaceHit(key) != null);
    }

    /**
     * Which key a surface should emit when content offers an explicit one and a naming convention
     * offers another: the explicit key when the loaded catalogue ships it, else the convention key
     * when it does, else null for "neither exists, fall back to whatever raw text you have".
     */
    @Nullable
    public static String pick(@Nullable String explicitKey, @Nullable String conventionKey) {
        if (explicitKey != null && !explicitKey.isEmpty() && known(explicitKey)) {
            return explicitKey;
        }
        if (conventionKey != null && !conventionKey.isEmpty() && known(conventionKey)) {
            return conventionKey;
        }
        return null;
    }

    @Nullable
    private static String namespaceHit(@Nonnull String key) {
        Map<String, String> catalogue = LangCatalog.catalogue();
        Memo current = memo;
        if (current.catalogue() != catalogue) {
            current = new Memo(catalogue);
            memo = current;
        }
        return current.hit(key);
    }
}
