package com.ziggfreed.common.asset;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetPack;
import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.common.plugin.PluginManifest;
import com.hypixel.hytale.common.semver.Semver;
import com.hypixel.hytale.common.semver.SemverRange;
import com.hypixel.hytale.server.core.asset.AssetModule;
import com.ziggfreed.common.util.SafeLog;

/**
 * Warns once per boot for every loaded asset pack whose manifest declares a version range for this
 * plugin that its running version does not satisfy.
 *
 * <p>The engine checks a CODE plugin's required range and drops the plugin when it fails, so such a
 * plugin's pack is never in the loaded list. It never compares an asset pack's range on a dependency
 * that loads in the same pass (that range is a load-order edge only), and never reads an
 * {@code OptionalDependencies} range at all. A pack that needs a newer version of this library would
 * otherwise load beside an older one with nothing said; this says it, naming the pack, the range and
 * the running version. It changes nothing about what loads.
 */
public final class PackRangeAudit {

    /** One loaded pack's declared range for this plugin. */
    public record Declared(@Nonnull String pack, @Nonnull SemverRange range, boolean optional) {
    }

    private static final AtomicBoolean RAN = new AtomicBoolean();

    private PackRangeAudit() {
    }

    /** Walk every loaded pack once per boot and warn for each range {@code self}'s version fails. Never throws. */
    public static void warnOnce(@Nonnull PluginManifest self) {
        if (!RAN.compareAndSet(false, true)) {
            return;
        }
        try {
            AssetModule assets = AssetModule.get();
            if (assets == null) {
                return;
            }
            String name = self.getGroup() + ":" + self.getName();
            List<Declared> declared = declaredFor(new PluginIdentifier(self.getGroup(), self.getName()),
                    assets.getAssetPacks());
            for (String line : failures(name, self.getVersion(), declared)) {
                SafeLog.warn(line);
            }
        } catch (Throwable t) {
            SafeLog.fine("[packs] the dependency range check could not run: " + t.getMessage());
        }
    }

    /** Every range a loaded pack declares for {@code self}, required and optional, in pack order. */
    @Nonnull
    static List<Declared> declaredFor(@Nonnull PluginIdentifier self, @Nonnull Collection<AssetPack> packs) {
        List<Declared> out = new ArrayList<>();
        for (AssetPack pack : packs) {
            PluginManifest manifest = pack == null ? null : pack.getManifest();
            if (manifest == null) {
                continue;
            }
            add(out, pack.getName(), manifest.getDependencies(), self, false);
            add(out, pack.getName(), manifest.getOptionalDependencies(), self, true);
        }
        return out;
    }

    private static void add(@Nonnull List<Declared> out, @Nullable String pack,
            @Nullable Map<PluginIdentifier, SemverRange> ranges, @Nonnull PluginIdentifier self, boolean optional) {
        SemverRange range = ranges == null ? null : ranges.get(self);
        if (range != null) {
            out.add(new Declared(pack == null ? "<unnamed pack>" : pack, range, optional));
        }
    }

    /** One warning line per declared range {@code running} fails, in the order given. */
    @Nonnull
    public static List<String> failures(@Nonnull String self, @Nonnull Semver running,
            @Nonnull List<Declared> declared) {
        List<String> out = new ArrayList<>();
        for (Declared entry : declared) {
            if (entry.range().satisfies(running)) {
                continue;
            }
            out.add("[packs] '" + entry.pack() + "' declares " + self + " " + entry.range()
                    + (entry.optional() ? " (optional)" : "") + ", but this server runs " + self + " " + running
                    + ". The engine never checks this range for a pack, so its content may not load or behave as "
                    + "written. Update " + self + ".");
        }
        return out;
    }
}
