package com.ziggfreed.common.npc.placement;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Stream;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.assetstore.AssetPack;
import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.common.plugin.PluginManifest;
import com.hypixel.hytale.common.semver.Semver;
import com.hypixel.hytale.common.semver.SemverRange;
import com.hypixel.hytale.server.core.asset.AssetModule;
import com.ziggfreed.common.util.SafeLog;

/**
 * Builds one NPC role per placement at runtime, so a pack that only wants a different FACE does
 * not have to ship a near-identical role file per NPC.
 *
 * <p>A placement authoring {@code Identity.Appearance} with no explicit {@code Role} gets a role
 * cloned from its {@code Identity.BaseRole}, with three things substituted: the appearance, the
 * nameplate translation key, and the press-F hint. Everything else - the whole idle/watch/interact
 * behaviour, including the press-F action - is inherited verbatim from the one base, so a fix to
 * the base flows into every generated role instead of into N copies.
 *
 * <p><b>The base role's JSON has to come from somewhere, and it is not this library.</b> This
 * library ships no roles: a consumer registers the raw JSON of its own base role once (from its
 * own jar resource, typically), and generation clones that. That is what keeps role generation
 * generic without this package knowing any mod's asset layout.
 *
 * <pre>{@code
 * NpcRoleGenerator.registerBaseRoleResource("Zc_Placement_Npc",
 *         MyPlugin.class, "/Server/NPC/Roles/Passive/My_Base_Role.json");
 * }</pre>
 *
 * <p><b>Timing.</b> Call {@link #generateAndRegister} once per boot, AFTER the asset load event
 * has folded {@link NpcPlacementConfig} (so the placements are known and the engine will actually
 * dispatch the pack-register event) and BEFORE any world streams chunks (so no placement can try
 * to spawn a role that does not exist yet). The roles materialize into a directory pack under
 * {@code mods/ziggfreedcommon/generated-roles} registered as {@link AssetPack.PackSource#RUNTIME},
 * the lowest precedence, so a real pack can still override any of them by id.
 */
public final class NpcRoleGenerator {

    /** Where the generated roles land (a directory pack, not a zip). */
    private static final Path PACK_DIR = Paths.get("mods", "ziggfreedcommon", "generated-roles");
    private static final Path ROLES_DIR =
            PACK_DIR.resolve("Server").resolve("NPC").resolve("Roles").resolve("Passive");

    /** Stable identity for the generated pack. */
    private static final String PACK_GROUP = "Ziggfreed";
    private static final String PACK_NAME = "ZiggfreedCommonGeneratedRoles";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Base role id (lower-cased) to a supplier of its raw JSON body. */
    private static final Map<String, Supplier<String>> BASE_ROLES = new ConcurrentHashMap<>();

    /** Base role ids currently sourced from an authored {@code NpcBaseRoles} asset, not Java. */
    private static final Set<String> ASSET_SOURCED = ConcurrentHashMap.newKeySet();

    /** Placement id to the role name generated for it, populated by {@link #generateAndRegister}. */
    private static final Map<String, String> GENERATED = new ConcurrentHashMap<>();

    private NpcRoleGenerator() {
    }

    // ==================== base role registration ====================

    /** Register the raw JSON body of a base role a placement may clone. */
    public static void registerBaseRole(@Nullable String baseRoleId, @Nullable Supplier<String> jsonSupplier) {
        if (baseRoleId == null || baseRoleId.isBlank() || jsonSupplier == null) {
            return;
        }
        BASE_ROLES.put(baseRoleId.trim().toLowerCase(Locale.ROOT), jsonSupplier);
    }

    /**
     * Register a base role read from {@code owner}'s classpath (the usual case: a consumer's own
     * jar resource, e.g. {@code "/Server/NPC/Roles/Passive/My_Base_Role.json"}). The resource is
     * read lazily, so registration order relative to class loading does not matter.
     */
    public static void registerBaseRoleResource(@Nullable String baseRoleId, @Nonnull Class<?> owner,
            @Nonnull String resourcePath) {
        registerBaseRole(baseRoleId, () -> readResource(owner, resourcePath));
    }

    /** Is {@code baseRoleId} registered? Used by the validator to report a generation that cannot run. */
    public static boolean hasBaseRole(@Nullable String baseRoleId) {
        return baseRoleId != null && !baseRoleId.isBlank()
                && BASE_ROLES.containsKey(baseRoleId.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Register (or replace) a base role's raw JSON body from an authored {@code NpcBaseRoles}
     * pack asset ({@link NpcBaseRoleConfig}, on every fold). This is the ASSET half of base-role
     * registration: on a base id already registered from Java, the asset WINS (defaults < pack <
     * owner precedence), logged once at INFO naming both, matching every other framework store's
     * override rule. A blank id or body is ignored.
     */
    public static void registerBaseRoleFromAsset(@Nullable String baseRoleId, @Nullable String json) {
        if (baseRoleId == null || baseRoleId.isBlank() || json == null || json.isBlank()) {
            return;
        }
        String key = baseRoleId.trim().toLowerCase(Locale.ROOT);
        if (hasBaseRole(key) && ASSET_SOURCED.add(key)) {
            SafeLog.info("[placement] base role '" + key + "' was registered in Java and is now overridden by "
                    + "the authored NpcBaseRoles asset '" + key + "' (pack content wins)");
        } else {
            ASSET_SOURCED.add(key);
        }
        registerBaseRole(key, () -> json);
    }

    /** Every registered base role id, sorted (diagnostics). */
    @Nonnull
    public static List<String> registeredBaseRoles() {
        return BASE_ROLES.keySet().stream().sorted().toList();
    }

    /** The raw JSON body currently registered for {@code baseRoleId}, or {@code null}. Tests only. */
    @Nullable
    static String currentBaseRoleJson(@Nonnull String baseRoleId) {
        Supplier<String> supplier = BASE_ROLES.get(baseRoleId.trim().toLowerCase(Locale.ROOT));
        return supplier == null ? null : supplier.get();
    }

    // ==================== generation ====================

    /**
     * The role name generated for {@code placementId}. Deterministic, so a placement that has
     * already been generated resolves to the same role across restarts.
     */
    @Nonnull
    public static String generatedRoleName(@Nonnull String placementId) {
        return "Zc_Gen_" + placementId.trim().toLowerCase(Locale.ROOT);
    }

    /** Was a role actually generated for {@code placementId} this boot? */
    public static boolean wasGenerated(@Nonnull String placementId) {
        return GENERATED.containsKey(placementId.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Materialize a role for every placement that wants one and register the pack. Idempotent per
     * call in the sense that the roles directory is rebuilt from scratch, so a removed or renamed
     * placement leaves nothing stale behind. Never throws: a failure leaves the affected
     * placements unspawnable and logs.
     *
     * @param version the version stamped on the generated pack (a consumer's own mod version)
     */
    public static void generateAndRegister(@Nonnull Semver version) {
        GENERATED.clear();
        try {
            Map<String, JsonObject> bases = new LinkedHashMap<>();
            cleanRolesDir();

            int written = 0;
            for (NpcPlacementAsset placement : NpcPlacementConfig.getInstance().all().values()) {
                NpcPlacementAsset.Identity identity = placement == null ? null : placement.getIdentity();
                if (identity == null || !identity.usesGeneratedRole()) {
                    continue;
                }
                String placementId = placement.getId();
                if (placementId == null || placementId.isBlank()) {
                    continue;
                }
                String baseRoleId = identity.getBaseRole();
                if (baseRoleId == null || baseRoleId.isBlank()) {
                    SafeLog.warn("[placement] '" + placementId + "' authors an Appearance but no BaseRole to clone"
                            + " - it will not spawn");
                    continue;
                }
                JsonObject base = bases.computeIfAbsent(baseRoleId.toLowerCase(Locale.ROOT),
                        NpcRoleGenerator::loadBase);
                if (base == null) {
                    SafeLog.warn("[placement] no base role registered as '" + baseRoleId + "' for placement '"
                            + placementId + "' - it will not spawn");
                    continue;
                }
                try {
                    String roleName = generatedRoleName(placementId);
                    writeRole(roleName, substitute(base, identity));
                    GENERATED.put(placementId, roleName);
                    written++;
                } catch (Exception e) {
                    SafeLog.warn("[placement] failed to generate a role for '" + placementId + "': " + e.getMessage());
                }
            }

            if (written == 0) {
                return;
            }
            registerPack(version, written);
        } catch (Throwable t) {
            SafeLog.severe("[placement] runtime role generation failed: " + t.getMessage(), t);
        }
    }

    /**
     * The PURE substitution: clone {@code base} and stamp this identity's appearance, nameplate
     * key and press-F hint onto the copy. Unit-testable with a plain JSON fixture.
     */
    @Nonnull
    public static JsonObject substitute(@Nonnull JsonObject base, @Nonnull NpcPlacementAsset.Identity identity) {
        JsonObject role = base.deepCopy();
        String appearance = identity.getAppearance();
        if (appearance != null && !appearance.isBlank()) {
            role.addProperty("Appearance", appearance);
        }
        String nameKey = identity.getNameKey();
        if (nameKey != null && !nameKey.isBlank()) {
            setNameKey(role, nameKey);
        }
        String hintKey = identity.getHintKey();
        if (hintKey != null && !hintKey.isBlank()) {
            setHint(role, hintKey);
        }
        return role;
    }

    /** Substitute the nameplate key at {@code Parameters.NameTranslationKey.Value}. */
    private static void setNameKey(@Nonnull JsonObject role, @Nonnull String nameKey) {
        JsonObject params = role.getAsJsonObject("Parameters");
        if (params == null) {
            params = new JsonObject();
            role.add("Parameters", params);
        }
        JsonObject nameTk = params.getAsJsonObject("NameTranslationKey");
        if (nameTk == null) {
            nameTk = new JsonObject();
            params.add("NameTranslationKey", nameTk);
        }
        nameTk.addProperty("Value", nameKey);
    }

    /**
     * Substitute the press-F hint. A recursive walk sets EVERY {@code Hint} string rather than one
     * known path, so a base role that grows a second interaction still gets a correct hint.
     */
    private static void setHint(@Nonnull JsonElement element, @Nonnull String hintKey) {
        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            for (String key : new ArrayList<>(obj.keySet())) {
                JsonElement child = obj.get(key);
                if ("Hint".equals(key) && child.isJsonPrimitive() && child.getAsJsonPrimitive().isString()) {
                    obj.addProperty("Hint", hintKey);
                } else {
                    setHint(child, hintKey);
                }
            }
        } else if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (JsonElement child : array) {
                setHint(child, hintKey);
            }
        }
    }

    @Nullable
    private static JsonObject loadBase(@Nonnull String baseRoleId) {
        Supplier<String> supplier = BASE_ROLES.get(baseRoleId);
        if (supplier == null) {
            return null;
        }
        try {
            String json = supplier.get();
            if (json == null || json.isBlank()) {
                return null;
            }
            JsonElement parsed = JsonParser.parseString(json);
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (Exception e) {
            SafeLog.warn("[placement] could not read base role '" + baseRoleId + "': " + e.getMessage());
            return null;
        }
    }

    @Nullable
    private static String readResource(@Nonnull Class<?> owner, @Nonnull String resourcePath) {
        try (InputStream in = owner.getResourceAsStream(resourcePath)) {
            if (in == null) {
                return null;
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                StringBuilder sb = new StringBuilder();
                char[] buffer = new char[4096];
                int read;
                while ((read = reader.read(buffer)) >= 0) {
                    sb.append(buffer, 0, read);
                }
                return sb.toString();
            }
        } catch (Exception e) {
            SafeLog.warn("[placement] could not read base role resource " + resourcePath + ": " + e.getMessage());
            return null;
        }
    }

    private static void writeRole(@Nonnull String roleName, @Nonnull JsonObject role) throws Exception {
        Files.createDirectories(ROLES_DIR);
        try (Writer writer = Files.newBufferedWriter(ROLES_DIR.resolve(roleName + ".json"), StandardCharsets.UTF_8)) {
            GSON.toJson(role, writer);
        }
    }

    /** Delete stale generated roles so a removed or renamed placement leaves none behind. */
    private static void cleanRolesDir() {
        if (!Files.isDirectory(ROLES_DIR)) {
            return;
        }
        try (Stream<Path> files = Files.list(ROLES_DIR)) {
            files.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // Best effort: an undeletable stale file is simply overwritten if regenerated.
                }
            });
        } catch (Exception e) {
            SafeLog.fine("[placement] could not clean the generated-roles dir: " + e.getMessage());
        }
    }

    // ==================== registration ====================

    /**
     * Write the pack's root manifest so the directory pack looks like a real on-disk pack. The
     * engine revalidates every pack when a server op opens the in-game asset editor and drops a
     * manifest-less directory pack, which would unload the generated roles mid-session.
     */
    private static void writeManifestFile(@Nonnull Semver version) {
        try {
            Files.createDirectories(PACK_DIR);
            JsonObject manifest = new JsonObject();
            manifest.addProperty("Group", PACK_GROUP);
            manifest.addProperty("Name", PACK_NAME);
            manifest.addProperty("Version", version.toString());
            manifest.addProperty("ServerVersion", "*");
            manifest.addProperty("IncludesAssetPack", true);
            try (Writer w = Files.newBufferedWriter(PACK_DIR.resolve("manifest.json"), StandardCharsets.UTF_8)) {
                GSON.toJson(manifest, w);
            }
        } catch (Exception e) {
            SafeLog.warn("[placement] failed to write the generated-roles manifest: " + e.getMessage());
        }
    }

    private static void registerPack(@Nonnull Semver version, int roleCount) {
        AssetModule assetModule = AssetModule.get();
        if (assetModule == null) {
            SafeLog.warn("[placement] AssetModule unavailable - generated roles not registered");
            return;
        }
        writeManifestFile(version);
        PluginManifest manifest = new PluginManifest(
                PACK_GROUP,
                PACK_NAME,
                version,
                "Ziggfreed Common - runtime-generated NPC placement roles.",
                List.of(),
                null,
                null,
                SemverRange.WILDCARD,
                new LinkedHashMap<>(),
                new LinkedHashMap<>(),
                new LinkedHashMap<>(),
                List.of(),
                false);
        String packId = new PluginIdentifier(manifest).toString();
        try {
            boolean ok = assetModule.registerPack(packId, PACK_DIR.toAbsolutePath().normalize(), manifest,
                    AssetPack.PackSource.RUNTIME);
            if (ok) {
                SafeLog.info("[placement] registered runtime role pack '" + packId + "' ("
                        + roleCount + " generated role(s))");
            } else {
                SafeLog.warn("[placement] runtime role pack '" + packId
                        + "' was rejected - generated roles may not load");
            }
        } catch (Throwable t) {
            SafeLog.severe("[placement] registerPack failed for '" + packId + "': " + t.getMessage(), t);
        }
    }

    /** Drop every registration and generation record (tests). */
    static void clearForTests() {
        BASE_ROLES.clear();
        ASSET_SOURCED.clear();
        GENERATED.clear();
    }
}
