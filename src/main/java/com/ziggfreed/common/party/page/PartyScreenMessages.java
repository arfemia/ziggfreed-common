package com.ziggfreed.common.party.page;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.Message;

/**
 * The locale-free chrome text for the {@link PartyInvitePage}: title, tab labels, button
 * labels, and the empty-state lines. The consumer returns pre-built, client-resolved
 * {@link Message}s from its own {@code Lang}, so common stays locale-free (the
 * {@code QueueMessages}/{@code PartyMessages} convention). Every method is {@code @Nonnull}
 * - the page always needs a label; a consumer wires them all once.
 */
public interface PartyScreenMessages {

    @Nonnull Message title();

    @Nonnull Message tabParty();

    @Nonnull Message tabInvite();

    /** Shown on the Invite tab when no online player matches the search. */
    @Nonnull Message emptyInviteList();

    /** Shown on the Party tab when the viewer is in no party and has no pending invites. */
    @Nonnull Message emptyParty();

    @Nonnull Message inviteButton();

    @Nonnull Message kickButton();

    @Nonnull Message leaveButton();

    @Nonnull Message disbandButton();

    @Nonnull Message queueButton();

    @Nonnull Message acceptButton();

    @Nonnull Message declineButton();

    /** The leader badge shown next to the owner's name. */
    @Nonnull Message ownerBadge();

    /** A "{size}/{max}" member-count label shown in the header. */
    @Nonnull Message memberCount(int size, int maxSize);
}
