package com.ziggfreed.common.content;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.annotation.Nonnull;

import com.google.gson.JsonObject;
import com.hypixel.hytale.assetstore.AssetPack;
import com.ziggfreed.common.util.OwnerFiles;

/**
 * {@code /zigpack export}: the server content pack and its text as one zip, a normal content pack that
 * loads from another server's mods folder.
 *
 * <p>The zip carries the pack's files as they sit on disk and the text pack's {@code .lang} files at
 * the same {@code Server/Languages/<locale>/} paths (a language file the pack itself holds at the same
 * path, written in the Asset Editor, comes first in the one file, so its keys keep their words). Its
 * manifest is the live pack's, renamed {@code <Name>_<yyyyMMdd_HHmmss>}: the live id would collide with
 * the other server's own server content pack, and the engine refuses to start with two packs of one id.
 * A zip never changes once loaded, so its language files carry none of a watched folder's hazard.
 */
final class ServerContentExport {

    /** Where exports are written: beside the library's owner files, a folder the engine never scans for packs. */
    static final Path EXPORTS = OwnerFiles.DIRECTORY.resolve("exports");

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT);

    private static final String DESCRIPTION = "Content exported from a server's own content pack.";

    private static final List<String> SKIPPED = List.of(ServerContentPack.MANIFEST, "CommonAssetsIndex.hashes");

    private ServerContentExport() {
    }

    @Nonnull
    static ServerContent.Export write() {
        AssetPack pack = ServerContentPack.loaded();
        if (pack == null) {
            throw new ContentRefusedException(ContentProblem.PACK_UNAVAILABLE,
                    "export: the server content pack is " + ServerContentPack.state());
        }
        Path root = pack.getRoot();
        String liveId = ServerContentPack.idOf(root.resolve(ServerContentPack.MANIFEST));
        String liveName = liveId == null ? ServerContentPack.DEFAULT_NAME : liveId.substring(liveId.indexOf(':') + 1);
        String name = liveName + "_" + LocalDateTime.now().format(STAMP);
        JsonObject manifest = ServerContentPack.manifestRenamed(root.resolve(ServerContentPack.MANIFEST), name, DESCRIPTION);
        if (manifest == null) {
            throw new ContentRefusedException(ContentProblem.IO, "export: the server content pack's manifest could not be read");
        }
        String group = manifest.has("Group") ? manifest.get("Group").getAsString() : ServerContentPack.DEFAULT_GROUP;
        Path zip = EXPORTS.resolve(name + ".zip").toAbsolutePath().normalize();
        Path tmp = null;
        try {
            Files.createDirectories(zip.getParent());
            tmp = Files.createTempFile(zip.getParent(), name, ".zip.tmp");
            int files = writeZip(tmp, root, manifest);
            try {
                Files.move(tmp, zip, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, zip, StandardCopyOption.REPLACE_EXISTING);
            }
            return new ServerContent.Export(zip, group + ":" + name, files, ServerContentText.size());
        } catch (IOException e) {
            throw new ContentRefusedException(ContentProblem.IO, "export: " + zip + " could not be written", e);
        } finally {
            deleteQuietly(tmp);
        }
    }

    /** The zip's every entry; answers how many files besides the manifest it holds. */
    private static int writeZip(@Nonnull Path zipFile, @Nonnull Path root, @Nonnull JsonObject manifest) throws IOException {
        Map<String, byte[]> text = files(ServerContentText.STAGING, "Server/Languages/");
        Map<String, byte[]> own = files(root, "");
        int count = 0;
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(zipFile)),
                StandardCharsets.UTF_8)) {
            put(zos, ServerContentPack.MANIFEST, ServerContentPack.pretty(manifest).getBytes(StandardCharsets.UTF_8));
            for (Map.Entry<String, byte[]> e : own.entrySet()) {
                byte[] ours = text.remove(e.getKey());
                put(zos, e.getKey(), ours == null ? e.getValue() : joined(e.getValue(), ours));
                count++;
            }
            for (Map.Entry<String, byte[]> e : text.entrySet()) {
                put(zos, e.getKey(), e.getValue());
                count++;
            }
        }
        return count;
    }

    /**
     * Every file under {@code root} whose entry name (forward slashes, from {@code root}) starts with
     * {@code under}, sorted, leaving out the manifest, the packaged-assets marker and half-written files.
     */
    @Nonnull
    private static Map<String, byte[]> files(@Nonnull Path root, @Nonnull String under) throws IOException {
        Map<String, byte[]> out = new TreeMap<>();
        if (!Files.isDirectory(root)) {
            return out;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                String entry = root.relativize(p).toString().replace('\\', '/');
                String file = p.getFileName().toString();
                if (entry.startsWith(under) && !SKIPPED.contains(entry) && !file.endsWith(".tmp")) {
                    out.put(entry, Files.readAllBytes(p));
                }
            }
        }
        return out;
    }

    @Nonnull
    private static byte[] joined(@Nonnull byte[] first, @Nonnull byte[] second) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(first.length + second.length + 1);
        out.write(first);
        out.write('\n');
        out.write(second);
        return out.toByteArray();
    }

    private static void put(@Nonnull ZipOutputStream zos, @Nonnull String entry, @Nonnull byte[] bytes) throws IOException {
        zos.putNextEntry(new ZipEntry(entry));
        zos.write(bytes);
        zos.closeEntry();
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // A leftover temp file beside the exports harms nothing.
        }
    }
}
