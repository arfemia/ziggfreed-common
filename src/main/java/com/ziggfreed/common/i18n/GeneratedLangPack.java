package com.ziggfreed.common.i18n;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetPack;
import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.common.plugin.PluginManifest;
import com.hypixel.hytale.common.semver.Semver;
import com.hypixel.hytale.common.semver.SemverRange;
import com.hypixel.hytale.server.core.asset.AssetModule;
import com.ziggfreed.common.ZiggfreedCommonPlugin;

/**
 * A mod-agnostic primitive for shipping a RUNTIME-GENERATED localization overlay to clients: write
 * per-locale {@code .lang} files from live server state, then register them as an asset pack so
 * {@code I18nModule} loads the keys and every joining client receives them in its normal
 * translation init. The sanctioned indirect route for keys a plugin computes at boot (a
 * generated-from-a-roster label set, a per-item description twin) that cannot be hand-authored
 * into the jar's static {@code .lang} ahead of time.
 *
 * <p><b>The catalog-sync chain</b> (source-proven, {@code hytale-source-search} LEDGER's
 * runtime-i18n + tooltip entries): {@code AssetModule.registerPack(id, path, manifest,
 * PackSource.RUNTIME)} -> the {@code AssetPackRegisterEvent} listener {@code
 * I18nModule.loadMessagesFromPack} walks the pack's {@code Server/Languages/<locale>/*.lang} into
 * the live {@code languages} map -> {@code I18nModule.sendTranslations} pushes a fresh
 * {@code UpdateTranslations(Init,...)} to each client at join ({@code RequestAssets}) and on
 * {@code UpdateLanguage}. {@code I18nModule} derives each key's namespace PREFIX from the FILENAME
 * (minus {@code .lang}); a {@code server.lang} file's bare {@code items.X.description.zplain} line
 * therefore lands the stored/synced key {@code server.items.X.description.zplain}. Register during
 * plugin {@code setup()}/{@code start()} (BEFORE any player connects) and every joining client is
 * covered - no per-client work, no restart.
 *
 * <p><b>Why an IMMUTABLE ZIP, never a watched directory</b> (the landmine this primitive exists to
 * make un-steppable-on): a MUTABLE directory pack gets {@code I18nModule}'s per-file
 * {@code AssetMonitor}, and a post-registration MODIFY of a {@code server.lang} inside it makes
 * {@code I18nAssetMonitorHandler} PRUNE the ENTIRE {@code server.*} namespace (all vanilla item /
 * hint / quality names) from the live catalog and reload only that one file, wiping vanilla names
 * for every later-joining client. {@code AssetModule} marks any {@code .zip}/{@code .jar} path
 * immutable, so {@code loadMessagesFromPack} never attaches the monitor to it - a rewrite reaches
 * clients only through an explicit re-register, never a destructive hot file-watch. The generated
 * overlay regenerates each boot, so joining clients (the requirement) are always covered; the
 * already-connected-client live push (the only thing a mutable dir would add) is not worth the
 * catalog-prune hazard. {@link #registerZipPack} therefore ALWAYS builds a fresh immutable zip and
 * registers that. A consumer that must refresh mid-session re-invokes {@link #registerZipPack}
 * (unregister-then-register), accepting {@code I18nModule}'s first-writer-wins merge (new keys land;
 * an already-loaded key's value cannot change until restart).
 *
 * <p>Guarded, world-thread-agnostic file IO meant for setup time: {@link #writeLocaleFiles} throws
 * only genuine {@link IOException} (the caller logs + degrades), and {@link #registerZipPack}
 * swallows every failure into a {@code false} return, leaving any prior registration untouched.
 */
public final class GeneratedLangPack {

    private GeneratedLangPack() {
    }

    /**
     * The I18nModule key-PREFIX for a {@code .lang} filename: the filename with its {@code .lang}
     * extension stripped (the engine's {@code getPrefix} rule). A {@code "server.lang"} file
     * prefixes {@code "server"}; a {@code "client.lang"} file prefixes {@code "client"}. Pure.
     */
    @Nonnull
    public static String storedKeyPrefix(@Nonnull String filename) {
        return filename.endsWith(".lang") ? filename.substring(0, filename.length() - ".lang".length()) : filename;
    }

    /**
     * The STORED/SYNCED key an {@code entryKey} written into {@code filename} resolves to on the
     * client: {@code storedKeyPrefix(filename) + "." + entryKey}. The one authority a consumer's
     * display code and generator share so an emitted lookup key can never drift from the generated
     * entry key (the M4 filename-prefix rule). Pure.
     */
    @Nonnull
    public static String storedKey(@Nonnull String filename, @Nonnull String entryKey) {
        return storedKeyPrefix(filename) + "." + entryKey;
    }

    /**
     * Escape a lang VALUE to the {@code key = value} grammar {@code I18nModule}'s parser reads
     * (feature-matched to the MMO's {@code EnglishLangWriter.escapeValue}, the proven-in-game
     * escaper): backslash / newline / tab escaped, carriage return dropped; a value with
     * leading/trailing whitespace is wrapped in quotes (its inner quotes escaped). Inner
     * double-quotes in a NON-wrapped value stay bare - the engine parser tolerates them (a
     * generated {@code <color is="#hex">} description proves it). Pure.
     */
    @Nonnull
    static String escapeValue(@Nonnull String value) {
        String out = value
                .replace("\\", "\\\\")
                .replace("\n", "\\n")
                .replace("\t", "\\t")
                .replace("\r", "");
        if (!out.equals(out.trim())) {
            return "\"" + out.replace("\"", "\\\"") + "\"";
        }
        return out;
    }

    /**
     * Write one {@code .lang} file per locale under {@code root/Server/Languages/<locale>/filename}
     * from {@code perLocale} (locale bcp47 -> an ORDERED {@code entryKey -> value} map; iteration
     * order is the written line order). Idempotent: each call overwrites the file wholesale, so a
     * boot-time regeneration always reflects current state and stale entries vanish. An
     * empty-value entry is skipped (Hytale's {@code LangFileParser} throws on an empty value). The
     * caller owns key namespacing - {@code filename}'s prefix (see {@link #storedKeyPrefix}) is
     * prepended by the engine, not here.
     *
     * @param headerComment optional leading {@code #} comment block (each line auto-prefixed with
     *                      {@code "# "}); {@code null}/blank writes no header.
     * @throws IOException on a genuine filesystem failure (the caller logs + leaves any prior
     *                     generation in place).
     */
    public static void writeLocaleFiles(@Nonnull Path root, @Nonnull String filename,
            @Nonnull Map<String, Map<String, String>> perLocale, @Nullable String headerComment) throws IOException {
        for (Map.Entry<String, Map<String, String>> locale : perLocale.entrySet()) {
            writeLocaleFile(root, filename, locale.getKey(), locale.getValue(), headerComment);
        }
    }

    private static void writeLocaleFile(@Nonnull Path root, @Nonnull String filename, @Nonnull String bcp47,
            @Nonnull Map<String, String> entries, @Nullable String headerComment) throws IOException {
        StringBuilder sb = new StringBuilder(entries.size() * 64 + 256);
        if (headerComment != null && !headerComment.isBlank()) {
            for (String line : headerComment.split("\n", -1)) {
                sb.append("# ").append(line).append('\n');
            }
            sb.append('\n');
        }
        for (Map.Entry<String, String> e : entries.entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            if (key == null || key.isEmpty() || value == null || value.isEmpty()) {
                continue;
            }
            sb.append(key).append(" = ").append(escapeValue(value)).append('\n');
        }
        Path localeDir = root.resolve("Server").resolve("Languages").resolve(bcp47);
        Files.createDirectories(localeDir);
        Files.writeString(localeDir.resolve(filename), sb.toString(), StandardCharsets.UTF_8);
    }

    /**
     * Build a fresh IMMUTABLE {@code .zip} from every {@code Server/Languages/<locale>/*.lang} file
     * under {@code stagingRoot} plus a root {@code manifest.json}, then register it RUNTIME via
     * {@code AssetModule.registerPack} (unregistering any prior registration of the same id first,
     * so a re-invocation refreshes rather than logging a duplicate). The zip is written to a
     * sibling temp file and atomically moved into place, so {@code registerPack} - which opens the
     * path via {@code FileSystems.newFileSystem} - never observes a half-written archive. Guarded:
     * any failure returns {@code false} (logged at fine level) and leaves any prior registration
     * untouched, never a throw into the caller.
     *
     * @return {@code true} once the pack registered (or re-registered) successfully.
     */
    public static boolean registerZipPack(@Nonnull Path stagingRoot, @Nonnull Path zipTarget,
            @Nonnull String group, @Nonnull String name, @Nonnull String version, @Nonnull String description) {
        try {
            Semver semver = Semver.fromString(version);
            Path zip = zipTarget.toAbsolutePath().normalize();
            if (!rebuildZip(stagingRoot, zip, group, name, semver)) {
                return false;
            }
            PluginManifest manifest = buildManifest(group, name, semver, description);
            String packId = new PluginIdentifier(manifest).toString();
            AssetModule am = AssetModule.get();
            if (am == null) {
                warn("registerZipPack", "AssetModule unavailable - '" + packId + "' not registered");
                return false;
            }
            // Unregister any stale same-id registration so a re-invocation opens a fresh
            // FileSystem onto the rebuilt zip (registerPack treats a duplicate id at the same
            // priority as a mistake and no-ops the swap otherwise).
            try {
                am.unregisterPack(packId);
            } catch (Throwable ignored) {
                // no prior registration on the first call; nothing to unregister
            }
            boolean ok = am.registerPack(packId, zip, manifest, AssetPack.PackSource.RUNTIME);
            if (!ok) {
                warn("registerZipPack", "pack '" + packId + "' was rejected");
            }
            return ok;
        } catch (Throwable t) {
            warn("registerZipPack", t.getMessage());
            return false;
        }
    }

    @Nonnull
    private static PluginManifest buildManifest(@Nonnull String group, @Nonnull String name, @Nonnull Semver version,
            @Nonnull String description) {
        return new PluginManifest(
                group,
                name,
                version,
                description,
                List.of(),
                null,
                null,
                SemverRange.WILDCARD,
                new LinkedHashMap<>(),
                new LinkedHashMap<>(),
                new LinkedHashMap<>(),
                List.of(),
                false);
    }

    private static boolean rebuildZip(@Nonnull Path stagingRoot, @Nonnull Path zip, @Nonnull String group,
            @Nonnull String name, @Nonnull Semver version) {
        Path tmp = null;
        try {
            Files.createDirectories(zip.getParent());
            tmp = Files.createTempFile(zip.getParent(), "generated-lang-", ".zip.tmp");
            writeZipContents(tmp, stagingRoot, group, name, version);
            moveIntoPlace(tmp, zip);
            return true;
        } catch (Throwable t) {
            warn("rebuildZip", t.getMessage());
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // best-effort cleanup of the abandoned temp file only
                }
            }
            return false;
        }
    }

    private static void moveIntoPlace(@Nonnull Path tmp, @Nonnull Path target) throws IOException {
        try {
            Files.move(tmp, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeZipContents(@Nonnull Path zipPath, @Nonnull Path stagingRoot, @Nonnull String group,
            @Nonnull String name, @Nonnull Semver version) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(Files.newOutputStream(zipPath)), StandardCharsets.UTF_8)) {
            zos.putNextEntry(new ZipEntry("manifest.json"));
            zos.write(manifestJson(group, name, version).getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            writeLanguageEntries(zos, stagingRoot);
        }
    }

    /**
     * Minimal manifest.json for a lang-only asset pack. Group/name are simple plugin identifiers
     * (no JSON-special characters), so a template is safe without a full JSON serializer.
     */
    @Nonnull
    private static String manifestJson(@Nonnull String group, @Nonnull String name, @Nonnull Semver version) {
        return "{\"Group\":\"" + group + "\",\"Name\":\"" + name + "\",\"Version\":\"" + version
                + "\",\"ServerVersion\":\"*\",\"IncludesAssetPack\":true}";
    }

    /**
     * Copy every {@code Server/Languages/<locale>/*.lang} file out of {@code stagingRoot} into the
     * zip under the SAME relative path, entry names built with explicit forward slashes (never
     * {@code Path.toString()}, which uses {@code \\} on Windows and would write an unreadable pack).
     */
    private static void writeLanguageEntries(@Nonnull ZipOutputStream zos, @Nonnull Path stagingRoot)
            throws IOException {
        Path languagesRoot = stagingRoot.resolve("Server").resolve("Languages");
        if (!Files.isDirectory(languagesRoot)) {
            return;
        }
        try (var localeDirs = Files.newDirectoryStream(languagesRoot, Files::isDirectory)) {
            for (Path localeDir : localeDirs) {
                String bcp47 = localeDir.getFileName().toString();
                try (var files = Files.newDirectoryStream(localeDir,
                        p -> p.getFileName().toString().endsWith(".lang"))) {
                    for (Path file : files) {
                        zos.putNextEntry(new ZipEntry("Server/Languages/" + bcp47 + "/" + file.getFileName()));
                        Files.copy(file, zos);
                        zos.closeEntry();
                    }
                }
            }
        }
    }

    private static void warn(@Nonnull String op, @Nullable String message) {
        try {
            ZiggfreedCommonPlugin.LOGGER.atFine()
                    .log("[ZiggfreedCommon] GeneratedLangPack." + op + " failed: " + message);
        } catch (Throwable ignored) {
            // a log-manager-less unit JVM must not crash on the logging facade itself
        }
    }
}
