package com.ziggfreed.common.npc.placement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.annotation.Nonnull;

import com.google.gson.JsonObject;
import com.ziggfreed.common.world.WorldSelectorValidator.Issue;

/**
 * Audits authored {@link NpcBaseRoleAsset}s for the one mistake that fails SILENTLY: a base role
 * with no usable payload generates nothing, and every placement naming it as {@code
 * Identity.BaseRole} just never appears - indistinguishable, at runtime, from
 * {@link NpcPlacementValidator}'s {@code UNREGISTERED_BASE_ROLE} (which catches the placement
 * side of the same failure: a base role nothing registered at all).
 *
 * <p>Findings are the same neutral {@link Issue} values every validator in this package (and
 * {@code world/WorldSelectorValidator}) uses, so a consumer maps them into its own reporting
 * command without learning a second shape.
 */
public final class NpcBaseRoleValidator {

    private NpcBaseRoleValidator() {
    }

    @Nonnull
    public static List<Issue> validateAll(@Nonnull Collection<NpcBaseRoleAsset> assets) {
        List<Issue> out = new ArrayList<>();
        for (NpcBaseRoleAsset asset : assets) {
            if (asset != null) {
                validate(asset, out);
            }
        }
        return out;
    }

    @Nonnull
    public static List<Issue> validate(@Nonnull NpcBaseRoleAsset asset) {
        List<Issue> out = new ArrayList<>();
        validate(asset, out);
        return out;
    }

    private static void validate(@Nonnull NpcBaseRoleAsset asset, @Nonnull List<Issue> out) {
        String id = asset.getId() == null ? "<unnamed>" : asset.getId();
        JsonObject payload = asset.getPayloadAsJsonObject();
        if (payload == null || payload.entrySet().isEmpty()) {
            out.add(Issue.error("EMPTY_BASE_ROLE_PAYLOAD",
                    "Payload is empty or not a JSON object, so this base role generates nothing and any "
                            + "placement naming it as Identity.BaseRole will not appear", id));
        }
    }
}
