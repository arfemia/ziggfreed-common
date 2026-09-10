package com.ziggfreed.common.dialogue.schema;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The server's shared option groups, keyed by name: the lines several conversations repeat, written
 * once as a file under {@code Server/ZiggfreedCommon/DialogueFragments/} and pulled into any screen
 * with {@code IncludeOptions}, or into any group with {@code Include}.
 *
 * <p>A conversation's own {@code Fragments} block still exists and still comes first, so the two
 * read as a local declaration in front of a server-wide one: write a group inside the conversation
 * when only that conversation says those lines, and a file when a whole cast does. A file is what
 * lets one "safe travels" farewell serve every guide on the server without any of them naming each
 * other.
 *
 * <p>A file is PULL-ONLY: it carries lines and nothing else, so it lands only where a screen or a
 * group names it, never on a screen of somebody else's conversation by tag. A group that names its
 * own screens is declared inside the conversation those screens belong to.
 *
 * <p>Names are matched without regard to case, so the file's capitalisation and the
 * {@code IncludeOptions} spelling never have to agree. The fold mechanics live in
 * {@link AbstractKeyedAssetConfig}; this singleton adds only the group type binding and the two
 * lookups the engine and the content audit need.
 */
public final class DialogueFragmentConfig extends AbstractKeyedAssetConfig<DialogueFragmentGroup> {

    private static final DialogueFragmentConfig INSTANCE = new DialogueFragmentConfig();

    @Nonnull
    public static DialogueFragmentConfig getInstance() {
        return INSTANCE;
    }

    private DialogueFragmentConfig() {
    }

    /** The shared group of that name, or null when no file declares one. */
    @Nullable
    public DialogueFragmentGroup group(@Nullable String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return resolve(name.trim());
    }

    /** Whether any file declares a shared group of that name. */
    public boolean declares(@Nullable String name) {
        return group(name) != null;
    }
}
