package dev.collabedit.crdt;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The mark type table must be identical in Java and in TypeScript.
 *
 * <h2>What the vector corpus does NOT guard</h2>
 * The conformance vectors contain operations that have <b>already been created</b> — they carry
 * concrete anchor sides, chosen at generation time. The decision "does bold grow when typing at its
 * end" is made <b>when creating</b> an operation, so changing the table on one side changes not a
 * single vector and the whole corpus stays green.
 *
 * <p>The divergence would surface only at the user, and in the worst possible way: "bold grew as I
 * typed, it did not for you" — that is, the same gesture produces a different document depending on
 * who performed it.
 *
 * <p>The mark layer claimed the table was guarded by "a separate unit test on both sides". A review found
 * that no such test existed <b>on either side</b> — the documentation described a safeguard that
 * did not exist. This file and its twin {@code markTypes.test.ts} read the same JSON file, so
 * changing one side fails that side's tests.
 */
class MarkTypesConformanceTest {

    private static final Path TABLE = Path.of("..", "conformance", "schema", "mark-types.json");

    private static List<String> stringsAt(JsonNode root, String field) {
        JsonNode array = root.get(field);
        assertThat(array)
            .as("the shared type table has no %s field — the file was changed in a way neither "
                + "implementation expects", field)
            .isNotNull();
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    @Test
    @DisplayName("MarkTypes agrees with the shared table the client also reads")
    void expansionTableMatchesSharedSource() throws IOException {
        JsonNode root = OpCodec.mapper().readTree(Files.readString(TABLE));

        List<String> expanding = stringsAt(root, "expandsAtEnd");
        List<String> notExpanding = stringsAt(root, "doesNotExpandAtEnd");

        assertThat(expanding)
            .as("an empty list of expanding types would let any regression through")
            .isNotEmpty();
        assertThat(notExpanding)
            .as("an empty list of non-expanding types would let any regression through — the "
                + "guard above existed for the other list only, and the TypeScript twin checked "
                + "both, so the two sides disagreed about how carefully to read the same file")
            .isNotEmpty();

        for (String type : expanding) {
            assertThat(MarkTypes.expandsAtEnd(type))
                .as("type %s expands on typing in the client but not in Java", type)
                .isTrue();
        }
        for (String type : notExpanding) {
            assertThat(MarkTypes.expandsAtEnd(type))
                .as("type %s expands on typing in Java but not in the client", type)
                .isFalse();
        }

        // A type unknown to either side does NOT expand. The cautious choice: a mark that expands
        // unexpectedly changes the appearance of text the user did not format, whereas a mark that
        // fails to expand costs at most a repeated gesture.
        assertThat(MarkTypes.expandsAtEnd("type-from-a-future-version"))
            .as("an unknown type expands — a newer client would change the look of somebody "
                + "else's text")
            .isFalse();
    }

    /**
     * The table has to be COMPLETE, not merely consistent.
     *
     * <p>The assertions above walk the file and check Java agrees with each entry. Nothing walked
     * the other way, so a thirteenth constant added to {@link MarkTypes} and forgotten in
     * {@code mark-types.json} left both suites green — and the client, which reads only the table,
     * would never learn the type exists. The failure is the exact one this file was written to
     * prevent, arriving through the one direction it did not check: the same gesture producing a
     * different document depending on who made it.
     *
     * <p>Reflection rather than a hand-maintained list, deliberately. A list here would be a third
     * copy of the table, and a third copy is a third thing to forget.
     */
    @Test
    @DisplayName("every declared mark type appears in the shared table")
    void tableCoversEveryDeclaredType() throws IOException {
        JsonNode root = OpCodec.mapper().readTree(Files.readString(TABLE));
        List<String> listed = new ArrayList<>(stringsAt(root, "expandsAtEnd"));
        listed.addAll(stringsAt(root, "doesNotExpandAtEnd"));

        List<String> declared = new ArrayList<>();
        for (java.lang.reflect.Field field : MarkTypes.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (field.getType() == String.class
                && java.lang.reflect.Modifier.isPublic(modifiers)
                && java.lang.reflect.Modifier.isStatic(modifiers)) {
                try {
                    declared.add((String) field.get(null));
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("cannot read " + field.getName(), e);
                }
            }
        }

        assertThat(declared)
            .as("no mark type constants were found; the scan is broken, not the code")
            .isNotEmpty();
        assertThat(listed)
            .as("a mark type is declared in MarkTypes and missing from the shared table. The "
                + "client reads ONLY the table, so it does not know this type exists — and the "
                + "expansion behaviour of the same gesture then depends on who performed it, "
                + "which is the divergence this file exists to prevent.")
            .containsAll(declared);
        assertThat(declared)
            .as("the shared table lists a type MarkTypes does not declare — the two have drifted "
                + "the other way")
            .containsAll(listed);
    }
}
