package com.ziggfreed.common.content;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.protocol.UpdateType;
import com.hypixel.hytale.protocol.packets.assets.UpdateTranslations;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.modules.i18n.event.MessagesUpdated;
import com.hypixel.hytale.server.core.universe.Universe;
import com.ziggfreed.common.asset.AssetStoreWriter;
import com.ziggfreed.common.i18n.GeneratedLangPack;
import com.ziggfreed.common.i18n.LangCatalog;
import com.ziggfreed.common.util.OwnerFiles;
import com.ziggfreed.common.util.SafeLog;

/**
 * The words typed into the server content pack's editors: one en-US {@code server.lang} kept in a
 * staging folder beside the library's owner files and served to clients as a generated language pack
 * (zc-core's {@link GeneratedLangPack}: an immutable zip, rebuilt and registered again on every change).
 * Never a {@code .lang} file inside the content pack itself: that pack is a watched folder, and a
 * change to a watched {@code .lang} file drops every live key under its prefix, vanilla ones included.
 *
 * <p>The file is {@code server.lang}, so an entry {@code items.My_Sword.name} is the key {@code
 * server.items.My_Sword.name}, the name a native item already looks for, and every other entry sits
 * in the same {@code server.} namespace. Other locales are the wave's or an owner's: a {@code
 * Server/Languages/<locale>/server.lang} they put in the staging folder is zipped with it untouched.
 *
 * <p><b>When a change shows.</b> The engine's catalogue keeps the first definition of a key and never
 * unloads one. A NEW key joins the catalogue as the pack registers again, and is pushed to every
 * player online the way the engine pushes a key an asset load adds ({@code UpdateTranslations}
 * {@code AddOrUpdate}): {@link AppliesWhen#NOW}. A key the server already loaded keeps its first words
 * until a restart: {@link AppliesWhen#RESTART}. A key another pack defines cannot be set here at all.
 *
 * <p>Every rebuild runs on the asset writer thread ({@link AssetStoreWriter#offThread}): registering a
 * pack takes the engine's asset write lock, which a world thread may never ask for.
 */
final class ServerContentText {

    /** The generated pack's identity. */
    static final String GROUP = "Server";
    static final String NAME = "ServerContentText";

    /** The file name, which is the namespace every entry key is registered under. */
    static final String LANG_FILE = "server.lang";

    static final String LOCALE = "en-US";

    private static final String NAMESPACE = GeneratedLangPack.storedKeyPrefix(LANG_FILE) + ".";

    private static final String VERSION = "1.0.0";

    private static final String DESCRIPTION = "The words typed in game for this server's content pack.";

    /** Where the en-US file is kept, never itself a pack (no manifest). */
    static final Path STAGING = OwnerFiles.DIRECTORY.resolve("server-content-text");

    /** The zip registered from {@link #STAGING}. */
    private static final Path ZIP = OwnerFiles.DIRECTORY.resolve("server-content-text.zip");

    /** A lang entry key: dotted words of letters, digits, {@code _} and {@code -}. */
    private static final Pattern ENTRY_KEY = Pattern.compile("[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*");

    private static final Object LOCK = new Object();

    /** Entry key to its English, as the file holds it. Guarded by {@link #LOCK}. */
    private static final Map<String, String> TEXT = new LinkedHashMap<>();

    /**
     * Each full key this pack has given the engine's catalogue this session, with the words it gave:
     * the words players see until a restart. Guarded by {@link #LOCK}.
     */
    private static final Map<String, String> REGISTERED = new HashMap<>();

    private ServerContentText() {
    }

    /** The registered key for an entry key: {@code server.<entry>}. */
    @Nonnull
    static String fullKey(@Nonnull String entryKey) {
        return NAMESPACE + entryKey;
    }

    /** The entry key a caller meant: {@code key} with a leading {@code server.} taken off, or null when it is not a lang key. */
    @Nullable
    static String entryKey(@Nullable String key) {
        if (key == null) {
            return null;
        }
        String k = key.trim();
        if (k.startsWith(NAMESPACE)) {
            k = k.substring(NAMESPACE.length());
        }
        return ENTRY_KEY.matcher(k).matches() ? k : null;
    }

    /**
     * Typed words as the file may hold them (carriage returns dropped), or null when they cannot be:
     * blank, or holding a backslash. The engine's lang grammar has no escape for a backslash: one at
     * the end of a line joins the next entry onto this one, and one before {@code n} or {@code t}
     * reads as a line break or a tab, so no backslash can reach a player as typed. Pure.
     */
    @Nullable
    static String words(@Nullable String typed) {
        if (typed == null) {
            return null;
        }
        String words = typed.replace("\r", "");
        return words.isBlank() || words.indexOf('\\') >= 0 ? null : words;
    }

    /** How many entries the en-US file holds. */
    static int size() {
        synchronized (LOCK) {
            return TEXT.size();
        }
    }

    /**
     * At the library's setup: read the staged file back and, when it holds anything, register the
     * pack, so the start's own catalogue load reads it with every other pack's. Never throws.
     */
    static void load() {
        try {
            Map<String, String> staged = GeneratedLangPack.readLocaleFile(STAGING, LANG_FILE, LOCALE);
            synchronized (LOCK) {
                TEXT.clear();
                TEXT.putAll(staged);
                REGISTERED.clear();
                for (Map.Entry<String, String> e : staged.entrySet()) {
                    REGISTERED.put(fullKey(e.getKey()), e.getValue());
                }
            }
            if (!staged.isEmpty() && !register()) {
                SafeLog.severe("[content] the server content pack's text could not be registered, so " + staged.size()
                        + " text(s) show as keys this session");
            }
        } catch (Throwable t) {
            SafeLog.severe("[content] the server content pack's text in " + STAGING + " could not be read", t);
        }
    }

    /**
     * Save {@code englishByKey} (entry or full key to its words) and register the pack again on the
     * writer thread. Refuses the whole set, with nothing written, when a key is not a lang key, a value
     * is blank, or another pack already defines a key.
     */
    @Nonnull
    static ServerContent.Result set(@Nonnull Map<String, String> englishByKey) {
        String label = englishByKey.size() == 1 ? englishByKey.keySet().iterator().next() : englishByKey.size() + " texts";
        Map<String, String> clean = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : englishByKey.entrySet()) {
            String entry = entryKey(e.getKey());
            String words = words(e.getValue());
            if (entry == null || words == null) {
                return ServerContent.refused(label, ContentProblem.BAD_TEXT,
                        "not a lang key, no words, or a backslash in the words: " + e.getKey());
            }
            clean.put(entry, words);
        }
        AppliesWhen applies = AppliesWhen.NOW;
        synchronized (LOCK) {
            for (Map.Entry<String, String> e : clean.entrySet()) {
                String full = fullKey(e.getKey());
                String given = REGISTERED.get(full);
                if (given == null && LangCatalog.has(full)) {
                    return ServerContent.refused(label, ContentProblem.TEXT_TAKEN, full + " is another pack's");
                }
                applies = applies.orLater(given == null || given.equals(e.getValue()) ? AppliesWhen.NOW : AppliesWhen.RESTART);
            }
            TEXT.putAll(clean);
        }
        String id = clean.size() == 1 ? fullKey(clean.keySet().iterator().next()) : label;
        return new ServerContent.Result(id, applies, rebuildOffThread("server content text " + label));
    }

    /** Take {@code key} out of the file. Its words stay on screen until a restart if the server loaded them. */
    @Nonnull
    static ServerContent.Result remove(@Nonnull String key) {
        String entry = entryKey(key);
        if (entry == null) {
            return ServerContent.refused(key, ContentProblem.BAD_TEXT, "not a lang key: " + key);
        }
        String full = fullKey(entry);
        AppliesWhen applies;
        synchronized (LOCK) {
            if (TEXT.remove(entry) == null) {
                return ServerContent.refused(full, ContentProblem.NOT_FOUND, "no text " + full);
            }
            applies = REGISTERED.containsKey(full) ? AppliesWhen.RESTART : AppliesWhen.NOW;
        }
        return new ServerContent.Result(full, applies, rebuildOffThread("server content text removal " + full));
    }

    @Nonnull
    private static CompletableFuture<Void> rebuildOffThread(@Nonnull String what) {
        return AssetStoreWriter.offThread(what, () -> {
            Map<String, String> pushed = new LinkedHashMap<>();
            Map<String, String> snapshot;
            synchronized (LOCK) {
                snapshot = new LinkedHashMap<>(TEXT);
                for (Map.Entry<String, String> e : snapshot.entrySet()) {
                    String full = fullKey(e.getKey());
                    if (!REGISTERED.containsKey(full)) {
                        REGISTERED.put(full, e.getValue());
                        pushed.put(full, e.getValue());
                    }
                }
            }
            try {
                writeStaged(snapshot);
            } catch (Throwable t) {
                forget(pushed);
                throw new ContentRefusedException(ContentProblem.IO, "the text file in " + STAGING + " could not be written", t);
            }
            if (!register()) {
                forget(pushed);
                throw new ContentRefusedException(ContentProblem.IO, "the server content pack's text could not be registered");
            }
            push(pushed);
        });
    }

    private static void forget(@Nonnull Map<String, String> pushed) {
        synchronized (LOCK) {
            REGISTERED.keySet().removeAll(pushed.keySet());
        }
    }

    private static void writeStaged(@Nonnull Map<String, String> snapshot) throws IOException {
        GeneratedLangPack.writeLocaleFiles(STAGING, LANG_FILE, Map.of(LOCALE, snapshot), header());
    }

    /** Unregister, rebuild the zip from the staging folder, register. Synchronized: the swap is not atomic. */
    private static synchronized boolean register() {
        return GeneratedLangPack.registerZipPack(STAGING, ZIP, GROUP, NAME, VERSION, DESCRIPTION);
    }

    /**
     * Hand the new keys to every player online, as the engine does for a key an asset load adds, and
     * tell the engine's listeners (the Asset Editor's open windows) the same.
     */
    private static void push(@Nonnull Map<String, String> added) {
        if (added.isEmpty()) {
            return;
        }
        try {
            Universe universe = Universe.get();
            if (universe != null) {
                universe.broadcastPacketNoCache(new UpdateTranslations(UpdateType.AddOrUpdate, new HashMap<>(added)));
            }
            var dispatch = HytaleServer.get().getEventBus().dispatchFor(MessagesUpdated.class);
            if (dispatch.hasListener()) {
                Map<String, Map<String, String>> byLanguage = new HashMap<>();
                byLanguage.put(LOCALE, new HashMap<>(added));
                dispatch.dispatch(new MessagesUpdated(byLanguage, new HashMap<>()));
            }
        } catch (Throwable t) {
            SafeLog.warn("[content] " + added.size() + " new text(s) reach players at their next join: the live push failed", t);
        }
    }

    /**
     * At the library's shutdown: let the engine go of the zip on a plugin unload, so the next load can
     * rebuild it; on a server stop the engine closes every pack itself (an unregister then would make its
     * own listeners fail on a closed file system).
     */
    static void release() {
        try {
            if (!HytaleServer.get().isShuttingDown()) {
                GeneratedLangPack.unregisterZipPack(GROUP, NAME);
            }
        } catch (Throwable t) {
            SafeLog.fine("[content] text pack release: " + t.getMessage());
        }
    }

    @Nonnull
    private static String header() {
        return "The words typed in game for this server's content pack (" + LOCALE + ").\n"
                + "The server writes this file whenever an admin saves text in game, so edit the words there.\n"
                + "To translate, put a server.lang with the same keys in a folder beside this one\n"
                + "(Server/Languages/<locale>/server.lang); the server zips it with this one.";
    }
}
