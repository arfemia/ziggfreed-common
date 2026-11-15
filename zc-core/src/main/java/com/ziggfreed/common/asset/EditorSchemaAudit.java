package com.ziggfreed.common.asset;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;

import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.server.core.schema.SchemaGenerator;
import com.ziggfreed.common.validation.Finding;

/**
 * The boot check that the in-game Asset Editor can open on this server: the schema set the editor is sent,
 * made the engine's own way ({@code SchemaGenerator.generateAssetSchemas}, then {@code NPCRole.json} and
 * {@code other.json} left out, as {@code AssetEditorPlugin} leaves them out), read by
 * {@link EditorSchemaCheck}, and a count of each pick list it is handed (the kinds an authored {@code Kind}
 * offers, the asset ids a step's {@code Target} suggests).
 *
 * <p>Every store on the server is read, every mod's and the engine's: one schema the editor cannot read
 * leaves every type unusable, so a broken one is an {@code ERROR} whoever registered it. An empty list it
 * was handed is a {@code WARNING} (an author would find nothing to choose); the rest is {@code INFO}. Run once at
 * boot, under zc-core's boot-audit switch ({@code BootAudit}); never on an owner's server otherwise, since
 * making the whole set costs what opening the editor costs.
 */
public final class EditorSchemaAudit {

    /** The label its lines carry. */
    public static final String LOG_LABEL = "[editor] schema audit";

    /** The domain its findings are stamped with. */
    public static final String DOMAIN = "editor";

    /** The custom schema the editor drops before it sends the set (the NPC editor's own). */
    private static final String NPC_ROLE = "NPCRole.json";

    /** The asset type the NPC editor serves without a schema file in the set. */
    private static final String NPC_ROLE_TYPE = "NPCRole";

    private EditorSchemaAudit() {
    }

    /**
     * Make the set, read it, and count {@code kindLists} (pick lists served through {@link EditorDataSets}).
     * Never throws: a set that cannot be made is one {@code ERROR}.
     */
    @Nonnull
    public static List<Finding> audit(@Nonnull Collection<String> kindLists) {
        List<Finding> out = new ArrayList<>();
        Map<String, Schema> set;
        try {
            set = new LinkedHashMap<>(SchemaGenerator.generateAssetSchemas());
        } catch (Throwable t) {
            out.add(Finding.error(DOMAIN, "SCHEMA_SET_UNMADE", "The Asset Editor's schema set could not be made, so "
                    + "the editor opens on nothing: " + t, "schemas"));
            return out;
        }
        set.remove(NPC_ROLE);
        set.remove(EditorSchemaCheck.OTHER);
        List<EditorSchemaCheck.Problem> problems = EditorSchemaCheck.check(set);
        for (EditorSchemaCheck.Problem problem : problems) {
            out.add(Finding.error(DOMAIN, "SCHEMA_BREAKS_EDITOR", problem + ". One schema the Asset Editor cannot "
                    + "read leaves every type unusable.", problem.file()));
        }
        out.add(Finding.info(DOMAIN, "SCHEMA_SET", set.size() + " schema file(s) the Asset Editor is sent, "
                + problems.size() + " shape(s) it cannot read", "schemas"));
        Map<String, String> unknownTypes = EditorSchemaCheck.unknownPickerTypes(set, Set.of(NPC_ROLE_TYPE));
        if (!unknownTypes.isEmpty()) {
            List<String> named = new ArrayList<>();
            unknownTypes.forEach((type, file) -> named.add(type + " (" + file + ")"));
            out.add(Finding.info(DOMAIN, "PICKER_TYPE_UNKNOWN", "picker(s) naming an asset type the editor has no "
                    + "schema for, which offer nothing: " + String.join(", ", named), "schemas"));
        }
        for (String list : kindLists) {
            Collection<String> values = EditorDataSets.values(list);
            int size = values == null ? 0 : values.size();
            if (size == 0) {
                out.add(Finding.warning(DOMAIN, "EMPTY_PICK_LIST", "the pick list " + list + " offers nothing, so "
                        + "an author picking from it in the Asset Editor finds no choice", list));
            } else {
                out.add(Finding.info(DOMAIN, "PICK_LIST", "the pick list " + list + " offers " + size + ": "
                        + preview(values), list));
            }
        }
        return out;
    }

    /** The first few values, for a line a reader can check by eye. */
    @Nonnull
    private static String preview(@Nonnull Collection<String> values) {
        List<String> first = values.stream().limit(8).toList();
        return String.join(", ", first) + (values.size() > first.size() ? ", ..." : "");
    }
}
