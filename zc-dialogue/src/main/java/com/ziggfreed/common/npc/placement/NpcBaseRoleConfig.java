package com.ziggfreed.common.npc.placement;

import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import com.google.gson.JsonObject;
import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.world.WorldSelectorValidator.Issue;

/**
 * The {@code pack &lt; owner} fold of every {@link NpcBaseRoleAsset}. Common ships no jar
 * defaults for base roles (a consumer with a Java baseline still registers it directly via
 * {@link NpcRoleGenerator#registerBaseRoleResource}); this config exists to hold the AUTHORED
 * ones and push them into the same generator.
 *
 * <p>The resolved value type is the raw {@link NpcBaseRoleAsset} itself, not its parsed payload -
 * unlike a Pattern A config, a malformed entry (an empty or non-object {@code Payload}) must
 * still be visible in {@link #all()} so {@link #validate()} can report it, rather than silently
 * vanishing from the fold the way a null-mapped {@code AssetMergeAdapter} entry would.
 *
 * <p>Every layer write pushes each entry with a usable payload into {@link NpcRoleGenerator} via
 * {@link NpcRoleGenerator#registerBaseRoleFromAsset}, which is what makes a base id already
 * registered from Java get overridden by pack content (defaults &lt; pack &lt; owner
 * precedence) - the invalidation-on-merge idiom {@code world/WorldSelectorConfig} already
 * established for a derived side effect that must never be forgotten at a call site.
 */
public final class NpcBaseRoleConfig extends AbstractKeyedAssetConfig<NpcBaseRoleAsset> {

    private static final NpcBaseRoleConfig INSTANCE = new NpcBaseRoleConfig();

    private NpcBaseRoleConfig() {
    }

    @Nonnull
    public static NpcBaseRoleConfig getInstance() {
        return INSTANCE;
    }

    @Override
    public synchronized void mergePackLayer(@Nonnull Map<String, NpcBaseRoleAsset> layer) {
        super.mergePackLayer(layer);
        registerAllWithGenerator();
    }

    @Override
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, NpcBaseRoleAsset> layer) {
        super.mergeOwnerLayer(layer);
        registerAllWithGenerator();
    }

    /** Audit every folded base role: an empty or malformed payload generates nothing. */
    @Nonnull
    public List<Issue> validate() {
        return NpcBaseRoleValidator.validateAll(all().values());
    }

    private void registerAllWithGenerator() {
        for (Map.Entry<String, NpcBaseRoleAsset> e : all().entrySet()) {
            NpcBaseRoleAsset asset = e.getValue();
            if (asset == null) {
                continue;
            }
            JsonObject payload = asset.getPayloadAsJsonObject();
            if (payload == null || payload.entrySet().isEmpty()) {
                continue; // Reported by validate(); nothing usable to register.
            }
            NpcRoleGenerator.registerBaseRoleFromAsset(e.getKey(), payload.toString());
        }
    }
}
