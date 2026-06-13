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
 * Tablica typów znaczników musi być identyczna w Javie i w TypeScripcie.
 *
 * <h2>Czego NIE pilnuje korpus wektorów</h2>
 * Wektory konformancyjne zawierają operacje <b>już utworzone</b> — mają w sobie konkretne
 * strony kotwic, wybrane w chwili generowania. Decyzja „czy pogrubienie rośnie przy pisaniu
 * na jego końcu" jest podejmowana <b>przy tworzeniu</b> operacji, więc zmiana tablicy po
 * jednej stronie nie zmienia ani jednego wektora i cały korpus przechodzi na zielono.
 *
 * <p>Rozjazd objawiłby się dopiero u użytkownika i w najgorszy możliwy sposób: „u mnie
 * pogrubienie rosło przy pisaniu, u ciebie nie" — czyli ten sam gest daje inny dokument
 * zależnie od tego, kto go wykonał.
 *
 * <p>ADR-0010 twierdził, że tablicy pilnuje „osobny test jednostkowy po obu stronach".
 * Przy przeglądzie okazało się, że takiego testu <b>nie było po żadnej ze stron</b> —
 * dokumentacja opisywała zabezpieczenie, które nie istniało. Ten plik i jego bliźniak
 * {@code markTypes.test.ts} czytają ten sam plik JSON, więc zmiana jednej strony
 * wywala testy tej strony.
 */
class MarkTypesConformanceTest {

    private static final Path TABLE = Path.of("..", "conformance", "schema", "mark-types.json");

    private static List<String> stringsAt(JsonNode root, String field) {
        JsonNode array = root.get(field);
        assertThat(array)
            .as("wspólna tablica typów nie ma pola %s — plik został zmieniony niezgodnie "
                + "z oczekiwaniem obu implementacji", field)
            .isNotNull();
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    @Test
    @DisplayName("MarkTypes zgadza się ze wspólną tablicą, z której korzysta też klient")
    void expansionTableMatchesSharedSource() throws IOException {
        JsonNode root = OpCodec.mapper().readTree(Files.readString(TABLE));

        List<String> expanding = stringsAt(root, "expandsAtEnd");
        List<String> notExpanding = stringsAt(root, "doesNotExpandAtEnd");

        assertThat(expanding)
            .as("pusta lista typów rozszerzających przepuściłaby każdą regresję")
            .isNotEmpty();

        for (String type : expanding) {
            assertThat(MarkTypes.expandsAtEnd(type))
                .as("typ %s rośnie przy pisaniu w kliencie, a nie rośnie w Javie", type)
                .isTrue();
        }
        for (String type : notExpanding) {
            assertThat(MarkTypes.expandsAtEnd(type))
                .as("typ %s rośnie przy pisaniu w Javie, a nie rośnie w kliencie", type)
                .isFalse();
        }

        // Typ nieznany żadnej ze stron NIE rośnie. Wybór ostrożny: znacznik rozszerzający się
        // niespodziewanie zmienia wygląd tekstu, którego użytkownik nie formatował, a znacznik,
        // który się nie rozszerza, wymaga najwyżej powtórzenia gestu.
        assertThat(MarkTypes.expandsAtEnd("typ-z-przyszłej-wersji"))
            .as("nieznany typ rozszerza się — nowsza wersja klienta zmieni wygląd cudzego tekstu")
            .isFalse();
    }
}
