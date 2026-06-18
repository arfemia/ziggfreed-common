package com.ziggfreed.common.party;

/**
 * The policy knobs of a {@link PartyService}, a pure value object the consumer builds
 * (the {@code LobbyConfig} twin). Clamped in the canonical constructor.
 *
 * <ul>
 *   <li>{@code maxSize} (&ge;1) - the most players a party may hold (typically the
 *       instance's {@code maxParty}).</li>
 *   <li>{@code inviteTimeoutSeconds} (&ge;0) - how long a pending invite stays valid
 *       before it auto-expires. 0 = never expires.</li>
 *   <li>{@code ownerOnlyInvite} - when true, only the party owner may send invites;
 *       when false, any member may.</li>
 * </ul>
 */
public record PartyConfig(int maxSize, int inviteTimeoutSeconds, boolean ownerOnlyInvite) {

    public PartyConfig {
        maxSize = Math.max(1, maxSize);
        inviteTimeoutSeconds = Math.max(0, inviteTimeoutSeconds);
    }
}
