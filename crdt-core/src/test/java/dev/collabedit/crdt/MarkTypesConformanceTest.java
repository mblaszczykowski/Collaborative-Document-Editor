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

class MarkTypesConformanceTest {
    private static final Path TABLE = Path.of("..", "conformance", "schema", "mark-types.json");

    private static List<String> stringsAt(JsonNode root, String field) {
        JsonNode array = root.get(field);
        assertThat(array)
            .as("the shared type table has no %s field - the file was changed in a way neither "
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
            .as("an empty list of non-expanding types would let any regression through - the "
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

        assertThat(MarkTypes.expandsAtEnd("type-from-a-future-version"))
            .as("an unknown type expands - a newer client would change the look of somebody "
                + "else's text")
            .isFalse();
    }

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
                + "client reads ONLY the table, so it does not know this type exists - and the "
                + "expansion behaviour of the same gesture then depends on who performed it, "
                + "which is the divergence this file exists to prevent.")
            .containsAll(declared);
        assertThat(declared)
            .as("the shared table lists a type MarkTypes does not declare - the two have drifted "
                + "the other way")
            .containsAll(listed);
    }
}
