package dev.collabedit.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.collabedit.app.auth.PublicEndpoint;
import dev.collabedit.app.auth.RequiresAuthentication;
import dev.collabedit.app.auth.RequiresDocumentAccess;
import dev.collabedit.app.ws.DocumentAccessInterceptor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The build-time half of the CP16 authorization rule.
 *
 * <h2>What this exists to prevent</h2>
 * Three times in this project a path to a document shipped with no authorization on it, and
 * all three times it was found by a human reading code rather than by anything automatic:
 * <ol>
 *   <li>CP11's IDOR — knowing an identifier was enough to read any document;</li>
 *   <li>{@code HistoryController} — three endpoints with no check at all for a whole
 *       checkpoint, including one that let a reader overwrite somebody else's document;</li>
 *   <li>{@code POST /api/documents/recovered} — owner and visibility set only inside
 *       {@code if (caller != null)}, found while writing plan v3 against the code.</li>
 * </ol>
 *
 * <p>The plan for CP12 named the second one <b>explicitly</b> and it still shipped. That is the
 * finding this test is built on: a safeguard that depends on somebody remembering a line in a
 * document is not a safeguard. {@code DocumentAccessGuard} refuses undeclared endpoints at
 * runtime; this test refuses them at build time, so the feedback arrives before the deploy
 * rather than after it.
 *
 * <h2>Why reflection rather than ArchUnit</h2>
 * The whole rule is one pass over the request-mapped methods, which is a few lines of
 * reflection. A dependency would buy a nicer failure message and cost a dependency — the same
 * trade {@code TokenService} makes against a JWT library, resolved the same way. It also keeps
 * the test honest about what it checks: there is no fluent DSL between the assertion and the
 * thing being asserted.
 */
@DisplayName("Every endpoint declares how it is authorized")
class EndpointAuthorizationArchitectureTest {

    private static final String BASE_PACKAGE = "dev.collabedit.app";

    /**
     * The only endpoints allowed to skip authorization entirely.
     *
     * <p>Pinned by name on purpose. Silencing the guard on a new endpoint means editing this
     * list, which shows up in review as a line saying "this endpoint needs no authorization" —
     * exactly the sentence that deserves a second reader. Without the pin, {@code @PublicEndpoint}
     * would be a way to make the test green rather than a decision.
     */
    private static final List<String> EXPECTED_PUBLIC = List.of(
        "DocumentRestController#login");

    @Test
    @DisplayName("no REST endpoint is missing an access declaration")
    void everyRequestMappedMethodDeclaresItsAccess() {
        List<String> undeclared = new ArrayList<>();
        List<String> ambiguous = new ArrayList<>();

        for (Method method : requestMappedMethods()) {
            int declarations = 0;
            declarations += AnnotatedElementUtils.hasAnnotation(method, PublicEndpoint.class) ? 1 : 0;
            declarations += AnnotatedElementUtils.hasAnnotation(method, RequiresAuthentication.class) ? 1 : 0;
            declarations += AnnotatedElementUtils.hasAnnotation(method, RequiresDocumentAccess.class) ? 1 : 0;

            if (declarations == 0) {
                undeclared.add(describe(method));
            } else if (declarations > 1) {
                ambiguous.add(describe(method));
            }
        }

        assertThat(undeclared)
            .as("""
                Endpoints with no access declaration. Every request-mapped method must carry \
                exactly one of @RequiresDocumentAccess, @RequiresAuthentication or \
                @PublicEndpoint — see DocumentAccessGuard. This is the check that would have \
                caught HistoryController shipping with no authorization at all.""")
            .isEmpty();

        assertThat(ambiguous)
            .as("Endpoints declaring more than one access annotation, so their intent is unclear")
            .isEmpty();
    }

    @Test
    @DisplayName("the set of endpoints skipping authorization is exactly the pinned set")
    void publicEndpointsAreExactlyTheExpectedOnes() {
        List<String> actual = requestMappedMethods().stream()
            .filter(method -> AnnotatedElementUtils.hasAnnotation(method, PublicEndpoint.class))
            .map(EndpointAuthorizationArchitectureTest::describe)
            .sorted()
            .toList();

        assertThat(actual)
            .as("""
                An endpoint was added to or removed from the set that skips authorization \
                entirely. If that is deliberate, update EXPECTED_PUBLIC in this test — the \
                point of the pin is that the change is visible in review, not that it is \
                forbidden.""")
            .containsExactlyInAnyOrderElementsOf(EXPECTED_PUBLIC);
    }

    /**
     * Placeholder in a STOMP destination template, e.g. the {@code {documentId}} in
     * {@code /doc/{documentId}/op}.
     */
    private static final Pattern DESTINATION_PLACEHOLDER = Pattern.compile("\\{[^}]+}");

    @Test
    @DisplayName("every WebSocket destination is one the frame interceptor can authorize")
    void everyMessageMappingIsReachableByTheFrameInterceptor() {
        List<Method> mappings = messageMappedMethods();

        // A guard against the test quietly passing because the scan found nothing — the same
        // failure mode as the *Measurement class that was green by never running at all.
        assertThat(mappings)
            .as("no @MessageMapping methods found; the scan is broken, not the code")
            .isNotEmpty();

        List<String> unreachable = new ArrayList<>();
        for (Method method : mappings) {
            for (String destination : method.getAnnotation(MessageMapping.class).value()) {
                // Substitute a real identifier for the template placeholder and ask the
                // interceptor's own parser whether it can find a document in the result. This
                // asserts the two agree, rather than asserting a pattern the parser might not
                // actually use.
                Matcher matcher = DESTINATION_PLACEHOLDER.matcher(destination);
                String concrete = matcher.replaceFirst(UUID.randomUUID().toString());
                if (DocumentAccessInterceptor.documentIdOf(concrete) == null) {
                    unreachable.add(describe(method) + " -> " + destination);
                }
            }
        }

        assertThat(unreachable)
            .as("""
                STOMP destinations that DocumentAccessInterceptor cannot resolve a document \
                from, which means frames sent to them are never authorized. A destination must \
                carry the document id where the interceptor looks for it — /doc/{documentId}/…""")
            .isEmpty();
    }

    // ------------------------------------------------------------------
    // Scanning
    // ------------------------------------------------------------------

    private static List<Method> requestMappedMethods() {
        return methodsIn(Controller.class, method ->
            AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class));
    }

    private static List<Method> messageMappedMethods() {
        return methodsIn(Controller.class, method -> method.isAnnotationPresent(MessageMapping.class));
    }

    /**
     * Methods of every Spring stereotype bean in the project matching a predicate.
     *
     * <p>Scans for {@link Controller} because {@code @RestController} is meta-annotated with it,
     * so both REST and STOMP controllers are found by one filter.
     */
    private static List<Method> methodsIn(Class<? extends java.lang.annotation.Annotation> stereotype,
                                          java.util.function.Predicate<Method> matching) {
        ClassPathScanningCandidateComponentProvider scanner =
            new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(stereotype));

        List<Method> found = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(BASE_PACKAGE)) {
            Class<?> type;
            try {
                type = Class.forName(definition.getBeanClassName());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("scanned class is not loadable: " + definition, e);
            }
            for (Method method : type.getDeclaredMethods()) {
                if (method.isSynthetic() || !matching.test(method)) {
                    continue;
                }
                found.add(method);
            }
        }
        return found;
    }

    private static String describe(Method method) {
        return method.getDeclaringClass().getSimpleName() + "#" + method.getName();
    }
}
