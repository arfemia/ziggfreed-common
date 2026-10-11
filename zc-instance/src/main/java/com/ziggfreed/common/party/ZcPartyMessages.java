package com.ziggfreed.common.party;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

/** The library party's toasts and invite banner, each a line of {@code ziggfreedcommon.party.lang}. */
public final class ZcPartyMessages implements PartyMessages {

    @Override
    @Nullable
    public Message invited(@Nonnull String inviterName, int size, int maxSize) {
        return ZcPartyText.line("msg.invited", inviterName, size, maxSize);
    }

    @Override
    @Nullable
    public Message inviteBannerPrimary(@Nonnull String inviterName) {
        return ZcPartyText.line("msg.banner", inviterName);
    }

    @Override
    @Nullable
    public Message inviteBannerSecondary() {
        return ZcPartyText.line("msg.banner.sub");
    }

    @Override
    @Nullable
    public Message inviteSent(@Nonnull String inviteeName) {
        return ZcPartyText.line("msg.sent", inviteeName);
    }

    @Override
    @Nullable
    public Message inviteDeclined(@Nonnull String inviteeName) {
        return ZcPartyText.line("msg.declined", inviteeName);
    }

    @Override
    @Nullable
    public Message inviteExpired() {
        return ZcPartyText.line("msg.expired");
    }

    @Override
    @Nullable
    public Message memberJoined(@Nonnull String name, int size, int maxSize) {
        return ZcPartyText.line("msg.joined", name, size, maxSize);
    }

    @Override
    @Nullable
    public Message memberLeft(@Nonnull String name) {
        return ZcPartyText.line("msg.left", name);
    }

    @Override
    @Nullable
    public Message kicked() {
        return ZcPartyText.line("msg.kicked");
    }

    @Override
    @Nullable
    public Message kickedMember(@Nonnull String name) {
        return ZcPartyText.line("msg.kicked_member", name);
    }

    @Override
    @Nullable
    public Message disbanded() {
        return ZcPartyText.line("msg.disbanded");
    }

    @Override
    @Nullable
    public Message ownerTransferred(@Nonnull String newOwnerName) {
        return ZcPartyText.line("msg.owner", newOwnerName);
    }
}
