package com.ziggfreed.common.quest.asset;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.util.OwnerFiles;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.validation.Finding;

/**
 * The SERVER OWNER's last word on quests, at {@code mods/ziggfreedcommon/quests/<Id>.json}: one
 * quest per file in exactly the shape a pack drops into {@code Server/ZiggfreedCommon/Quests/},
 * the file name being the id, read by the store as its highest layer with no registration of any
 * kind.
 *
 * <pre>{@code
 * // mods/ziggfreedcommon/quests/Gather_Copper.json   (retunes the shipped gather_copper)
 * { "Objectives": { "collect": { "Amount": 25 } } }
 *
 * // mods/ziggfreedcommon/quests/Town_Errand.json     (a quest of the owner's own)
 * { "Parent": "gather_copper", "Text": { "TitleKey": "quest.town_errand.title" } }
 * }</pre>
 *
 * <p><b>A same-id file MERGES LEAF BY LEAF over the quest below it.</b> The quest the packs (or the
 * contributed layer) already hold under that id is the file's implicit {@code Parent}, decoded
 * through the very same codec and the very same per-leaf inheritance a pack child uses, so one
 * leaf keeps every other and an author needs no second schema. A file naming a {@code Parent} of
 * its own inherits from THAT quest instead, wherever it lives - a pack file, a contributed body or
 * another file in this folder - and an id nothing else holds stands on its own.
 *
 * <p><b>A malformed file costs that one quest, never the boot.</b> A file that will not parse, a
 * body that will not decode, and a {@code Parent} nothing has are each reported as a finding
 * naming the file (logged once as a warning by the fold, and kept readable for an audit), and the
 * rest of the folder is carried. A {@code $}-prefixed name is documentation and a non-{@code .json}
 * entry is somebody else's, so both are ignored; there are no marked sub-folders here, the folder
 * is flat.
 *
 * <p>Read AFRESH at every fold ({@link QuestAssetStore#resolve}), which is what makes the boot
 * publish, {@code /zigprogress reload} and a hot re-import all pick a change up: an owner entry
 * has nothing to inherit from until the layers below it are in hand, so it is never read at setup.
 */
public final class QuestOwnerLayers {

    /** Where a server owner's files live. */
    public static final Path DEFAULT_DIRECTORY = Paths.get("mods", "ziggfreedcommon");

    /** The folder under it holding one quest per file. */
    public static final String FOLDER = "quests";

    /** The key an owner file names another quest to inherit from with, exactly as a pack file does. */
    static final String PARENT_KEY = "Parent";

    private static final String LOG_TAG = "quest";

    private static final String SUFFIX = ".json";

    @Nonnull
    private static volatile Path directory = DEFAULT_DIRECTORY;

    private QuestOwnerLayers() {
    }

    /** Point the owner folder at a different directory (a test, or a consumer with its own data dir). */
    public static void setDirectory(@Nonnull Path dir) {
        directory = dir;
    }

    /** Where the owner folder is being read from. */
    @Nonnull
    public static Path directory() {
        return directory;
    }

    /** The folder itself, {@code <directory>/quests}. */
    @Nonnull
    public static Path folder() {
        return directory.resolve(FOLDER);
    }

    /**
     * Read the folder and decode every file against {@code below} - the composed view of every
     * layer under this one - into a fresh map keyed by id. Every problem goes onto {@code issues}
     * under the store's own domain; a missing folder is the common case and says nothing.
     */
    @Nonnull
    static Map<String, QuestAsset> read(@Nonnull Map<String, QuestAsset> below, @Nonnull List<Finding> issues) {
        Path folder = folder();
        if (!Files.isDirectory(folder)) {
            return Map.of();
        }
        Map<String, Source> sources = list(folder, issues);
        Map<String, QuestAsset> out = new LinkedHashMap<>();
        for (Source source : sources.values()) {
            QuestAsset decoded = decode(source, sources, below, out, new HashSet<>(), issues);
            if (decoded != null) {
                out.put(source.id(), decoded);
            }
        }
        if (!out.isEmpty()) {
            SafeLog.info("[" + LOG_TAG + "] " + out.size() + " owner quest(s) read from " + folder);
        }
        return out;
    }

    /** One file in the folder: its id, where it is, and its body once parsed. */
    private record Source(@Nonnull String id, @Nonnull Path path, @Nonnull JsonObject body) {

        /** The {@code Parent} the body names, lower-cased, or null when it names none. */
        @Nullable
        String parentId() {
            JsonElement parent = body.get(PARENT_KEY);
            if (parent == null || !parent.isJsonPrimitive() || !parent.getAsJsonPrimitive().isString()) {
                return null;
            }
            String id = parent.getAsString().trim().toLowerCase(Locale.ROOT);
            return id.isEmpty() ? null : id;
        }
    }

    /**
     * Every readable file in the folder, keyed by id, in name order so two files folding to one
     * id are reported deterministically. A file that will not parse is reported here and skipped.
     */
    @Nonnull
    private static Map<String, Source> list(@Nonnull Path folder, @Nonnull List<Finding> issues) {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> entries = Files.list(folder)) {
            entries.filter(QuestOwnerLayers::isQuestFile).forEach(files::add);
        } catch (IOException e) {
            issues.add(Finding.error(QuestPoolValidator.DOMAIN, "OWNER_FOLDER_UNREADABLE",
                    "the owner folder " + folder + " could not be listed, so no owner quest is in force: "
                            + e.getMessage(), FOLDER));
            return Map.of();
        }
        files.sort(null);
        Map<String, Source> out = new LinkedHashMap<>();
        for (Path file : files) {
            String name = file.getFileName().toString();
            String id = name.substring(0, name.length() - SUFFIX.length()).trim().toLowerCase(Locale.ROOT);
            JsonObject body = parse(file, id, issues);
            if (body == null) {
                continue;
            }
            Source previous = out.put(id, new Source(id, file, body));
            if (previous != null) {
                issues.add(Finding.error(QuestPoolValidator.DOMAIN, "DUPLICATE_QUEST_ID",
                        "two owner files both resolve to the quest id '" + id + "' (" + previous.path() + " and "
                                + file + "), so only one of them exists; rename one", id));
            }
        }
        return out;
    }

    /** A regular {@code .json} file whose name is not {@code $}-prefixed documentation. */
    private static boolean isQuestFile(@Nonnull Path path) {
        String name = path.getFileName().toString();
        return Files.isRegularFile(path)
                && name.toLowerCase(Locale.ROOT).endsWith(SUFFIX)
                && name.length() > SUFFIX.length()
                && !OwnerFiles.isReservedKey(name);
    }

    /** The file as a JSON object, or null with a finding naming the file. */
    @Nullable
    private static JsonObject parse(@Nonnull Path file, @Nonnull String id, @Nonnull List<Finding> issues) {
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (root == null || !root.isJsonObject()) {
                issues.add(Finding.error(QuestPoolValidator.DOMAIN, "OWNER_FILE_UNREADABLE",
                        "the owner file " + file + " is not a quest body (a JSON object), so the quest '" + id
                                + "' from it is skipped", id));
                return null;
            }
            return root.getAsJsonObject();
        } catch (Exception e) {
            issues.add(Finding.error(QuestPoolValidator.DOMAIN, "OWNER_FILE_UNREADABLE",
                    "the owner file " + file + " could not be read, so the quest '" + id + "' from it is skipped: "
                            + e.getMessage(), id));
            return null;
        }
    }

    /**
     * Decode one file against the quest it inherits from: the {@code Parent} it names, resolved
     * first among the folder's own files (recursively, so an owner base and its owner children
     * work in any name order) and then among the layers below; or, when it names none, the quest
     * the layers below hold under its own id; or nothing, for a new id standing on its own.
     *
     * @param decoded  what this read has decoded so far, so a shared owner base is decoded once
     * @param visiting the files on the current inheritance path, so a cycle is reported, not looped
     */
    @Nullable
    private static QuestAsset decode(@Nonnull Source source, @Nonnull Map<String, Source> sources,
            @Nonnull Map<String, QuestAsset> below, @Nonnull Map<String, QuestAsset> decoded,
            @Nonnull Set<String> visiting, @Nonnull List<Finding> issues) {
        QuestAsset already = decoded.get(source.id());
        if (already != null) {
            return already;
        }
        String parentId = source.parentId();
        QuestAsset base;
        if (parentId == null) {
            base = below.get(source.id());
            parentId = base == null ? null : source.id();
        } else {
            base = resolveParent(source, parentId, sources, below, decoded, visiting, issues);
            if (base == null) {
                return null;
            }
        }
        try {
            AssetExtraInfo.Data data = new AssetExtraInfo.Data(QuestAsset.class, source.id(), parentId);
            QuestAsset asset = QuestAsset.CODEC.decodeAndInheritJsonAsset(
                    RawJsonReader.fromJsonString(source.body().toString()), base, new AssetExtraInfo<>(data));
            if (asset == null) {
                issues.add(Finding.error(QuestPoolValidator.DOMAIN, "DECODE_FAILED",
                        "the owner file " + source.path() + " produced no quest at all; check it against the "
                                + "quest schema", source.id()));
                return null;
            }
            asset.readFrom(source.path());
            decoded.put(source.id(), asset);
            return asset;
        } catch (Exception e) {
            issues.add(Finding.error(QuestPoolValidator.DOMAIN, "DECODE_FAILED",
                    "the owner file " + source.path() + " could not be read as a quest, so '" + source.id()
                            + "' is skipped: " + e.getMessage(), source.id()));
            return null;
        }
    }

    /** The quest an explicit {@code Parent} names, or null with a finding when nothing has it. */
    @Nullable
    private static QuestAsset resolveParent(@Nonnull Source source, @Nonnull String parentId,
            @Nonnull Map<String, Source> sources, @Nonnull Map<String, QuestAsset> below,
            @Nonnull Map<String, QuestAsset> decoded, @Nonnull Set<String> visiting,
            @Nonnull List<Finding> issues) {
        Source ownerParent = sources.get(parentId);
        if (ownerParent != null && !parentId.equals(source.id())) {
            if (!visiting.add(source.id())) {
                issues.add(Finding.error(QuestPoolValidator.DOMAIN, "PARENT_CYCLE",
                        "the owner file " + source.path() + " inherits from itself through its Parent chain, so '"
                                + source.id() + "' is skipped", source.id()));
                return null;
            }
            try {
                QuestAsset base = decode(ownerParent, sources, below, decoded, visiting, issues);
                if (base == null) {
                    issues.add(Finding.error(QuestPoolValidator.DOMAIN, "UNKNOWN_PARENT",
                            "the owner file " + source.path() + " names Parent '" + parentId + "', an owner file "
                                    + "that could not be read, so '" + source.id() + "' is skipped", source.id()));
                }
                return base;
            } finally {
                visiting.remove(source.id());
            }
        }
        QuestAsset base = below.get(parentId);
        if (base == null) {
            issues.add(Finding.error(QuestPoolValidator.DOMAIN, "UNKNOWN_PARENT",
                    "the owner file " + source.path() + " names Parent '" + parentId + "', which is not a quest "
                            + "anybody authored, so '" + source.id() + "' inherits nothing and is skipped",
                    source.id()));
        }
        return base;
    }
}
