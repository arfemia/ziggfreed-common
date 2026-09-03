package com.ziggfreed.common.factor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.server.core.asset.AssetModule;
import com.hypixel.hytale.server.core.plugin.PluginManager;

/**
 * Whether another MOD is installed on this server, as an ordinary factor reading.
 *
 * <p><b>Why it exists.</b> Cross-mod content is authored to be correct on a server that has the
 * other mod and on a server that does not, and the only way to express that was for the other mod to
 * contribute a factor of its own through {@link FactorContributions} - which no mod can do while it
 * is not installed, and which every mod would have to remember to do. This id asks the ENGINE's own
 * plugin table instead, so a pack author can gate a shop row, a board contract, an NPC placement, a
 * dialogue option or a loot roll on a mod's mere presence with no Java on either side and nothing for
 * the other mod to opt into.
 *
 * <p><b>A mod is a code plugin OR an asset-only pack.</b> The engine keeps the two in different
 * tables: a jar with an entry class enters the plugin table, while a pack that is nothing but a
 * {@code manifest.json} and assets never does - it is enumerated by the asset module alone, under
 * the same {@code Group:Name} its manifest declares. This reading asks BOTH, so a content pack that
 * ships no Java at all still reads as installed, exactly as a plugin does.
 *
 * <p><b>It is SERVER-scoped: it needs no entity at all.</b> Which mods are loaded is one fact about
 * the process, so the reading is the same whoever the question is about, and it answers just as well
 * from a placement sweep with no subject as from a dialogue render with one.
 *
 * <p>{@code Param} is the other mod's {@code Group:Name} - exactly the identity pair its own
 * {@code manifest.json} declares, and exactly what a {@code Dependencies} entry names - with exactly
 * one {@code ':'} between them.
 *
 * <table>
 *   <caption>The reading</caption>
 *   <tr><th>Situation</th><th>Value</th></tr>
 *   <tr><td>the named mod is loaded, as a code plugin or as an asset-only pack</td><td>{@code 1}</td></tr>
 *   <tr><td>neither table knows the named mod</td><td>{@code 0}</td></tr>
 *   <tr><td>{@code Param} absent, blank, or not {@code Group:Name}</td><td>{@code null}</td></tr>
 *   <tr><td>a table to ask is not there yet (very early boot), or a read threw</td><td>{@code null}</td></tr>
 * </table>
 *
 * <p><b>Absent is a DEFINITE {@code 0}, and that is the whole point of the id.</b> Every other
 * unanswerable reading in this package resolves {@code null} so a gate on it fails closed, but "that
 * mod is not here" - no plugin AND no pack under that name - is not a failure to answer; it is the
 * answer, and it has to be a real number for both halves of the question to be writable:
 *
 * <pre>
 * // only where RPG Stations is installed - Min: 1 is required, not optional
 * {"Factor": "hytale:mod_installed", "Param": "Ziggfreed:RpgStations", "Min": 1}
 *
 * // only where it is NOT installed - the half a null would make unwritable
 * {"Factor": "hytale:mod_installed", "Param": "Ziggfreed:RpgStations", "Max": 0}
 * </pre>
 *
 * <p>A {@code null} there would shut the {@code Max: 0} gate on every server, including the ones
 * with no such mod, which is the one server it exists for. A MALFORMED {@code Param} is different in
 * kind - there is no mod being asked about at all - so that one stays {@code null} and whatever asked
 * stays shut.
 *
 * <p><b>A bounds-less condition on this id is NOT a presence check.</b> {@link FactorCondition#accepts}
 * passes any non-null finite value when neither bound is authored, and the absent-mod reading is
 * {@code 0.0}, a non-null finite value - so a bounds-less {@code hytale:mod_installed} condition
 * passes whether the mod is installed or not. Always author {@code Min: 1} to require presence.
 *
 * <p><b>The namespace names the vocabulary's OWNER, not the registrant</b>, the same rule the rest of
 * the portable {@code hytale:} set follows: the plugin table belongs to the engine, so two mods
 * converging on {@code hytale:mod_installed} is agreement rather than a collision.
 *
 * <p><b>It is a presence check, never a version check.</b> The engine can answer a version range
 * too, but a factor is one number and a range is not; a mod whose behaviour differs by version is a
 * mod that should contribute a reading of its own for the thing that actually changed.
 */
public final class ModFactors {

    /** Who this registration is attributed to in the registry ledger. */
    public static final String OWNER = "ziggfreedcommon";

    /**
     * {@code hytale:mod_installed} - 1 when the mod whose {@code Group:Name} is named by Param is
     * loaded on this server, 0 when it is not, null when Param names no mod.
     */
    public static final String MOD_INSTALLED = "hytale:mod_installed";

    private static final Double YES = 1.0;
    private static final Double NO = 0.0;

    private ModFactors() {
    }

    // ==================== registration ====================

    /**
     * Claim {@link #MOD_INSTALLED} process-wide. One call from {@code ProgressionBootstrap.setupProgressionRuntime};
     * from then on every {@link FactorRegistry} on the server resolves it, including registries built
     * before this ran. Idempotent - the provider is a stable method reference, so re-running a setup
     * re-registers the same instance silently.
     */
    public static void contribute() {
        FactorContributions.register(MOD_INSTALLED, OWNER, ModFactors::resolveModInstalled);
    }

    /**
     * Register {@link #MOD_INSTALLED} into ONE vocabulary, attributed to {@code owner}. The
     * private-vocabulary form, for a consumer that keeps its own registry (or a test); a local
     * registration always outranks the process-wide claim {@link #contribute()} makes.
     */
    public static void registerInto(@Nonnull FactorRegistry registry, @Nullable String owner) {
        registry.register(MOD_INSTALLED, owner == null || owner.isBlank() ? OWNER : owner,
                ModFactors::resolveModInstalled);
    }

    // ==================== provider ====================

    /**
     * The {@link #MOD_INSTALLED} reading. Asks the engine's plugin table and then its asset-pack
     * registry for the identity named by {@code Param}; see the class javadoc for the whole truth
     * table and for why an absent mod is a definite {@code 0} while a malformed {@code Param} is not.
     *
     * <p>Both engine reads are wrapped whole: a table that does not exist yet, or one that throws,
     * is "cannot tell" rather than "not installed", because reporting a definite {@code 0} there
     * would open every {@code Max: 0} gate on the server for as long as it lasted. The pack read is
     * asked only after the plugin table said no, so a code plugin costs one lookup as before.
     */
    @Nullable
    static Double resolveModInstalled(@Nonnull FactorContext ctx) {
        ModRef mod = parseModRef(ctx.param());
        if (mod == null) {
            return null;
        }
        try {
            PluginManager plugins = PluginManager.get();
            if (plugins == null) {
                return null;
            }
            if (plugins.getPlugin(new PluginIdentifier(mod.group(), mod.name())) != null) {
                return YES;
            }
            AssetModule assets = AssetModule.get();
            if (assets == null) {
                return null;
            }
            return assets.getAssetPack(mod.packName()) == null ? NO : YES;
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================== parsing ====================

    /** One mod's identity pair, parsed out of an authored {@code Param}. */
    record ModRef(@Nonnull String group, @Nonnull String name) {

        /**
         * The name the asset module files a pack under: the manifest's own {@code Group:Name}
         * spelling, which is also what {@code PluginIdentifier#toString} prints.
         */
        @Nonnull
        String packName() {
            return group + ':' + name;
        }
    }

    /**
     * {@code Group:Name} split into its two halves, or null when {@code param} is not that shape:
     * absent, blank, no {@code ':'}, more than one {@code ':'}, or either half empty.
     *
     * <p><b>Parsed by hand rather than through {@code PluginIdentifier.fromString}</b>, which THROWS
     * on anything that is not the pair - and a provider must answer rather than throw, so an
     * authoring mistake costs one shut gate rather than a counted ledger failure against this
     * library. Both halves are trimmed, so whitespace either side of the colon is an author's
     * formatting rather than part of a mod's name; the halves themselves are matched by the engine
     * exactly as written, because a manifest's group and name are case-sensitive.
     */
    @Nullable
    static ModRef parseModRef(@Nullable String param) {
        if (param == null) {
            return null;
        }
        String trimmed = param.trim();
        int colon = trimmed.indexOf(':');
        if (colon < 0 || colon != trimmed.lastIndexOf(':')) {
            return null;
        }
        String group = trimmed.substring(0, colon).trim();
        String name = trimmed.substring(colon + 1).trim();
        if (group.isEmpty() || name.isEmpty()) {
            return null;
        }
        return new ModRef(group, name);
    }
}
