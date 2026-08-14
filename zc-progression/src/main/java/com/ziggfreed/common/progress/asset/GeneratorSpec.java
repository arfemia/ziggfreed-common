package com.ziggfreed.common.progress.asset;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonObject;

/**
 * The four things {@link GeneratorCore} needs to write a family from one file, whatever kind of
 * content the family is: what every child inherits from, how the children are named apart, which
 * axes to walk, and the body to write once per combination.
 *
 * <p>It is an interface rather than a shared asset class because each content type declares its own
 * codec (the Pattern A rule: the codec IS the schema) and only the READING of those four leaves is
 * common. A store's generator asset implements this and gets the whole substitution contract, the
 * axis walk and the findings vocabulary for free.
 */
public interface GeneratorSpec {

    /** The generator's own id, for a finding that has to name the file. */
    @Nonnull
    String generatorId();

    /** Does this generator run at all? Unauthored means true. */
    boolean isEnabled();

    /** The content id every child inherits from, lower-cased; null when unauthored. */
    @Nullable
    String getBase();

    /** How each child's id is spelled, with {@code {token}} placeholders; null when unauthored. */
    @Nullable
    String getIdPattern();

    /** The axes to walk, in authored order (the first varies slowest). Never null. */
    @Nonnull
    GeneratorAxisAsset[] axesOrEmpty();

    /** The body to write once per combination, or null when the generator writes nothing. */
    @Nullable
    JsonObject getChild();
}
