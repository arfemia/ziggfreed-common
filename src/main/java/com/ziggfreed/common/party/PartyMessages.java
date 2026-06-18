package com.ziggfreed.common.party;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

/**
 * The locale-free player-facing text provider for a {@link PartyService} (the
 * {@code QueueMessages} twin). Common calls these on its delivery path and writes the
 * returned {@link Message} as a packet; the consumer returns pre-built, client-resolved
 * Messages from its own {@code Lang}, so common never reads a locale. Every method is
 * {@code @Nullable} default-null - a null return simply skips that delivery.
 *
 * <p>Usernames are passed in as already-resolved {@code String}s (a proper noun is
 * locale-neutral), resolved live from {@code Universe.getPlayer} at the call site,
 * mirroring how the lobby's {@code QueueMessages} takes flat int args.
 */
public interface PartyMessages {

    /** Toast to the invitee: "{inviter} invited you to their party ({size}/{max})". */
    @Nullable
    default Message invited(@Nonnull String inviterName, int size, int maxSize) {
        return null;
    }

    /** Banner primary line shown to the invitee. */
    @Nullable
    default Message inviteBannerPrimary(@Nonnull String inviterName) {
        return null;
    }

    /** Banner secondary line shown to the invitee. */
    @Nullable
    default Message inviteBannerSecondary() {
        return null;
    }

    /** Toast to the inviter: "Invite sent to {invitee}". */
    @Nullable
    default Message inviteSent(@Nonnull String inviteeName) {
        return null;
    }

    /** Toast to the inviter: "{invitee} declined your invite". */
    @Nullable
    default Message inviteDeclined(@Nonnull String inviteeName) {
        return null;
    }

    /** Toast to the invitee when their invite expires. */
    @Nullable
    default Message inviteExpired() {
        return null;
    }

    /** Broadcast to the party: "{name} joined the party ({size}/{max})". */
    @Nullable
    default Message memberJoined(@Nonnull String name, int size, int maxSize) {
        return null;
    }

    /** Broadcast to the party: "{name} left the party". */
    @Nullable
    default Message memberLeft(@Nonnull String name) {
        return null;
    }

    /** Toast to the kicked player. */
    @Nullable
    default Message kicked() {
        return null;
    }

    /** Broadcast to the party: "{name} was removed from the party". */
    @Nullable
    default Message kickedMember(@Nonnull String name) {
        return null;
    }

    /** Broadcast to the party: "The party was disbanded". */
    @Nullable
    default Message disbanded() {
        return null;
    }

    /** Broadcast to the party: "{name} is now the party leader". */
    @Nullable
    default Message ownerTransferred(@Nonnull String newOwnerName) {
        return null;
    }
}
