package com.ziggfreed.common.ui.route;

import java.util.List;

import javax.annotation.Nonnull;

import com.ziggfreed.common.validation.Finding;

/**
 * The optional audit a destination type runs over its OWN authored fields.
 *
 * <p>Whether the {@code Type} is readable at all is settled at decode (an unknown one fails the
 * read). What is left is whether the fields beside it name something real - a skill in the roster, a
 * storefront that exists - and only the mod that registered the type can answer that, which is why
 * the check travels with the registration rather than living in whichever content validator happened
 * to walk the file.
 *
 * <p>{@code sourceId} is the label of whatever authored the destination (a placement id, a dialogue
 * id plus its node), for the finding to point at. Return an empty list when there is nothing to say;
 * report under the checking mod's own domain.
 *
 * @param <D> the destination type this check reads
 */
@FunctionalInterface
public interface DestinationCheck<D extends Destination> {

    @Nonnull
    List<Finding> check(@Nonnull D destination, @Nonnull String sourceId);
}
