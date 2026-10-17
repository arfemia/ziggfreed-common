package com.ziggfreed.common.objectives.gate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonValue;

import com.hypixel.hytale.assetstore.AssetPack;
import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerCondition;
import com.hypixel.hytale.codec.CollectingExtraInfo;
import com.hypixel.hytale.server.core.asset.AssetModule;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.ValidationReport;

/**
 * Every boot, reads each loaded pack's trigger-volume effect files ({@code Server/TriggerVolumes/Effects/**.json},
 * the engine's {@code TriggerEffectAsset} store) for a {@code ZigRequires} condition ({@link
 * ZigRequiresTriggerCondition}) that lets a volume act for players it was written to refuse, and names each one
 * as an ERROR line carrying the pack, the file within it and the condition's index.
 *
 * <p>Why the engine never says so itself: it decodes an effect's {@code Conditions} through a tolerant array
 * ({@code TriggerVolumeCodecs.TOLERANT_CONDITIONS}) that logs an element which throws as one WARNING naming no
 * file and drops it, so the volume loads without that gate and nothing on the decoded asset shows the drop. That
 * covers a malformed block and a type that never registered. A misspelt key ({@code "Quest"} for
 * {@code "Quests"}) does not throw at all: the codec sets it aside as unknown and the block decodes asking for
 * nothing, which admits everyone.
 *
 * <p>So each file is read raw (never through the tolerant codec), and each element of its root
 * {@code Conditions} array whose {@code Type} is exactly {@code ZigRequires} (the engine's own id lookup is
 * case-sensitive) is decoded the way the engine decodes it, through {@code TriggerCondition.CODEC}, with a fresh
 * {@link CollectingExtraInfo}. A decode that throws, or one that leaves unknown keys, is a finding. So is an
 * element whose {@code Type} is {@code ZigRequires} only up to case or surrounding spaces, unless a condition type
 * is registered under that very spelling: the engine skips it as an unknown type. A file with a good
 * {@code ZigRequires}, or none, logs nothing.
 *
 * <p>Prefab-carried volumes and a world's own volume data are not read here. Total and fail-soft: a file or
 * a pack that cannot be read costs one WARNING line and the walk goes on; nothing escapes {@link #run}. The
 * same walk answers its findings through {@link #auditAll}, which a consumer's content audit reports.
 */
public final class RequiresVolumeAudit {

    /** The label every line of this pass carries. */
    public static final String LOG_LABEL = "[gate] audit";

    /** The finding domain. */
    public static final String DOMAIN = "gate";

    /** No {@code ZigRequires} condition type is registered, so the engine skips every such condition. */
    public static final String UNREGISTERED = "ZIG_REQUIRES_UNREGISTERED";

    /** The condition throws on decode, so the engine skips it. */
    public static final String MALFORMED = "ZIG_REQUIRES_MALFORMED";

    /** The condition decodes but leaves keys no codec read, so its gate asks for less than written. */
    public static final String UNKNOWN_KEY = "ZIG_REQUIRES_UNKNOWN_KEY";

    /**
     * The condition's {@code Type} is {@code ZigRequires} spelt in another case or with spaces around it, which
     * no condition type is registered under, so the engine skips it.
     */
    public static final String NEAR_MISS = "ZIG_REQUIRES_NEAR_MISS";

    private static final String CONDITIONS = "Conditions";

    /** The engine's own filter on an asset file ({@code AssetStore.loadAssetsFromDirectory}). */
    private static final String EXTENSION = ".json";

    private RequiresVolumeAudit() {
    }

    /** Walk every loaded pack's effect files ({@link #auditAll}) and log each finding as one ERROR line. Never throws. */
    public static void run() {
        try {
            for (Finding finding : auditAll()) {
                SafeLog.severe(ValidationReport.format(LOG_LABEL, finding));
            }
        } catch (Throwable t) {
            SafeLog.warn(LOG_LABEL + ": the trigger-volume effect findings could not be logged: " + cause(t));
        }
    }

    /**
     * Every loaded pack's effect files walked, and every finding they hold, in pack, file and element order: what
     * {@link #run} logs at boot, for a consumer's own audit to report beside its other domains (domain
     * {@value #DOMAIN}). A file or a pack that cannot be read costs one WARNING line as it is walked, and the walk
     * goes on. Never throws.
     */
    @Nonnull
    public static List<Finding> auditAll() {
        List<Finding> out = new ArrayList<>();
        try {
            AssetModule assets = AssetModule.get();
            if (assets == null) {
                return out;
            }
            for (AssetPack pack : new ArrayList<>(assets.getAssetPacks())) {
                auditPack(pack, out);
            }
        } catch (Throwable t) {
            SafeLog.warn(LOG_LABEL + ": the trigger-volume effect files could not be walked: " + cause(t));
        }
        return out;
    }

    private static void auditPack(@Nullable AssetPack pack, @Nonnull List<Finding> out) {
        if (pack == null) {
            return;
        }
        String name = pack.getName() == null ? "<unnamed pack>" : pack.getName();
        try {
            Path root = pack.getRoot();
            Path effects = root.resolve("Server").resolve("TriggerVolumes").resolve("Effects");
            if (!Files.isDirectory(effects)) {
                return;
            }
            List<Path> files;
            try (Stream<Path> walk = Files.walk(effects)) {
                files = walk.filter(Files::isRegularFile)
                        .filter(file -> file.toString().endsWith(EXTENSION))
                        .sorted()
                        .toList();
            }
            for (Path file : files) {
                auditFile(name, root.relativize(file).toString().replace('\\', '/'), file, out);
            }
        } catch (Throwable t) {
            SafeLog.warn(LOG_LABEL + ": the trigger-volume effect files of pack '" + name + "' could not be read: "
                    + cause(t));
        }
    }

    private static void auditFile(@Nonnull String pack, @Nonnull String path, @Nonnull Path file,
            @Nonnull List<Finding> out) {
        try {
            BsonDocument root = BsonDocument.parse(Files.readString(file, StandardCharsets.UTF_8));
            out.addAll(audit(pack, path, root));
        } catch (Throwable t) {
            SafeLog.warn(LOG_LABEL + ": '" + path + "' in pack '" + pack + "' could not be read: " + cause(t));
        }
    }

    /**
     * One effect file's findings, in element order: each {@code ZigRequires} in its root {@code Conditions}
     * array that does not decode, or that decodes leaving unknown keys, and each element whose {@code Type} is
     * a near miss of it.
     *
     * @param pack the pack's name, for the line
     * @param path the file's path within the pack, the finding's source
     * @param root the file's raw JSON
     */
    @Nonnull
    static List<Finding> audit(@Nonnull String pack, @Nonnull String path, @Nonnull BsonDocument root) {
        List<Finding> out = new ArrayList<>();
        BsonValue conditions = root.get(CONDITIONS);
        if (conditions == null || !conditions.isArray()) {
            return out;
        }
        BsonArray elements = conditions.asArray();
        for (int i = 0; i < elements.size(); i++) {
            BsonValue element = elements.get(i);
            String type = typeOf(element);
            if (type == null) {
                continue;
            }
            Finding finding = ZigRequiresTriggerCondition.TYPE_NAME.equals(type)
                    ? check(pack, path, i, element.asDocument())
                    : nearMiss(pack, path, i, type);
            if (finding != null) {
                out.add(finding);
            }
        }
        return out;
    }

    /** The element's type id as written, or null when it is not a document carrying a string type. */
    @Nullable
    private static String typeOf(@Nullable BsonValue element) {
        if (element == null || !element.isDocument()) {
            return null;
        }
        BsonValue type = element.asDocument().get(TriggerCondition.CODEC.getKey());
        return type != null && type.isString() ? type.asString().getValue() : null;
    }

    /**
     * A {@code Type} that is {@code ZigRequires} only up to case or surrounding spaces. The engine's id lookup is
     * exact, so unless a condition type is registered under that very spelling, the engine skips the element as
     * it does a malformed one, and the volume admits everyone the gate was written to refuse.
     */
    @Nullable
    private static Finding nearMiss(@Nonnull String pack, @Nonnull String path, int index, @Nonnull String type) {
        if (!ZigRequiresTriggerCondition.TYPE_NAME.equalsIgnoreCase(type.strip())
                || TriggerCondition.CODEC.getCodecFor(type) != null) {
            return null;
        }
        return Finding.error(DOMAIN, NEAR_MISS, "in pack '" + pack + "', " + CONDITIONS + "[" + index + "] has Type '"
                + type + "', which no condition type is registered under, so the engine skips it and keeps the volume"
                + " without this gate: the Type must be spelt exactly " + ZigRequiresTriggerCondition.TYPE_NAME, path);
    }

    @Nullable
    private static Finding check(@Nonnull String pack, @Nonnull String path, int index,
            @Nonnull BsonDocument element) {
        String at = "in pack '" + pack + "', " + CONDITIONS + "[" + index + "] is a "
                + ZigRequiresTriggerCondition.TYPE_NAME;
        if (TriggerCondition.CODEC.getCodecFor(ZigRequiresTriggerCondition.TYPE_NAME) == null) {
            return Finding.error(DOMAIN, UNREGISTERED, at + ", but no condition type of that name is registered on"
                    + " this server, so the engine skips it and keeps the volume without this gate", path);
        }
        CollectingExtraInfo extraInfo = new CollectingExtraInfo();
        try {
            TriggerCondition.CODEC.decode(element, extraInfo);
        } catch (Exception e) {
            return Finding.error(DOMAIN, MALFORMED, at + " that does not decode (" + cause(e) + "), so the engine"
                    + " never loads this gate as written: it skips the condition and keeps the volume without it,"
                    + " or refuses the whole effect file", path);
        }
        List<String> unknown = unknownKeys(extraInfo.getUnknownKeys());
        if (unknown.isEmpty()) {
            return null;
        }
        return Finding.error(DOMAIN, UNKNOWN_KEY, at + " with keys it does not read (" + String.join(", ", unknown)
                + "), so the engine decodes it without them and the gate asks for less than written: a misspelt"
                + " Requires key asks for nothing", path);
    }

    /**
     * The keys no codec read, less the element's own type key: the type lookup reads it, but on this decode
     * path the condition's codec records it as unknown too. A nested one carries its path and stays.
     */
    @Nonnull
    private static List<String> unknownKeys(@Nonnull List<String> recorded) {
        String typeKey = TriggerCondition.CODEC.getKey();
        List<String> out = new ArrayList<>();
        for (String key : recorded) {
            if (key != null && !key.equals(typeKey)) {
                out.add(key);
            }
        }
        return out;
    }

    /** A throwable as one line: the first line of each distinct message down its cause chain. */
    @Nonnull
    private static String cause(@Nonnull Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable at = t;
        for (int depth = 0; at != null && depth < 6; depth++) {
            String line = firstLine(at.getMessage());
            String part = line.isEmpty() ? at.getClass().getSimpleName() : line;
            if (sb.indexOf(part) < 0) {
                sb.append(sb.length() == 0 ? "" : ": ").append(part);
            }
            at = at.getCause() == at ? null : at.getCause();
        }
        return sb.toString();
    }

    @Nonnull
    private static String firstLine(@Nullable String message) {
        return message == null ? "" : message.strip().lines().findFirst().orElse("").strip();
    }
}
