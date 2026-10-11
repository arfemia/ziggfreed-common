package com.ziggfreed.common.party;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.party.page.PartyScreenMessages;
import com.ziggfreed.common.ui.UiText;

/** The library party screen's chrome, each a line of {@code ziggfreedcommon.party.lang}. */
public final class ZcPartyScreenMessages implements PartyScreenMessages {

    @Override
    @Nonnull
    public Message title() {
        return ZcPartyText.line("title");
    }

    @Override
    @Nonnull
    public Message tabParty() {
        return ZcPartyText.line("tab.party");
    }

    @Override
    @Nonnull
    public Message tabInvite() {
        return ZcPartyText.line("tab.invite");
    }

    @Override
    @Nonnull
    public Message emptyInviteList() {
        return ZcPartyText.line("empty.invite");
    }

    @Override
    @Nonnull
    public Message emptyParty() {
        return ZcPartyText.line("empty.party");
    }

    @Override
    @Nonnull
    public Message inviteButton() {
        return ZcPartyText.line("btn.invite");
    }

    @Override
    @Nonnull
    public Message kickButton() {
        return ZcPartyText.line("btn.kick");
    }

    @Override
    @Nonnull
    public Message leaveButton() {
        return ZcPartyText.line("btn.leave");
    }

    @Override
    @Nonnull
    public Message disbandButton() {
        return ZcPartyText.line("btn.disband");
    }

    @Override
    @Nonnull
    public Message queueButton() {
        return ZcPartyText.line("btn.queue");
    }

    @Override
    @Nonnull
    public Message acceptButton() {
        return ZcPartyText.line("btn.accept");
    }

    @Override
    @Nonnull
    public Message declineButton() {
        return ZcPartyText.line("btn.decline");
    }

    @Override
    @Nonnull
    public Message ownerBadge() {
        return ZcPartyText.line("owner_badge");
    }

    /**
     * The page paints the count on a {@code .Text} sink, which resolves a translation but never substitutes its
     * params, so the line is resolved here, in the server's language, and sent as plain characters.
     */
    @Override
    @Nonnull
    public Message memberCount(int size, int maxSize) {
        return Msg.raw(UiText.flatten(ZcPartyText.line("count", size, maxSize)));
    }

    @Override
    @Nonnull
    public Message privacyPublic() {
        return ZcPartyText.line("privacy.public");
    }

    @Override
    @Nonnull
    public Message privacyPrivate() {
        return ZcPartyText.line("privacy.private");
    }

    @Override
    @Nonnull
    public Message toastInviteSent(@Nonnull String playerName) {
        return ZcPartyText.line("msg.sent", playerName);
    }

    @Override
    @Nonnull
    public Message toastInviteFailed() {
        return ZcPartyText.line("toast.invite_failed");
    }
}
