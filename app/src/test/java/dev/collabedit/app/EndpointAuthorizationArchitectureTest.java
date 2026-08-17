package dev.collabedit.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.collabedit.app.auth.DocumentAccessGuard;
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

@DisplayName("Every endpoint declares how it is authorized")
class EndpointAuthorizationArchitectureTest {
    private static final String BASE_PACKAGE = "dev.collabedit.app";

    private static final List<String> EXPECTED_PUBLIC = List.of(
        "LoginController#login",

        "LoginController#options",

        "LoginController#session");

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
                @PublicEndpoint - see DocumentAccessGuard. This is the check that would have \
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
                entirely. If that is deliberate, update EXPECTED_PUBLIC in this test - the \
                point of the pin is that the change is visible in review, not that it is \
                forbidden.""")
            .containsExactlyInAnyOrderElementsOf(EXPECTED_PUBLIC);
    }

    @Test
    @DisplayName("every declared param is a path variable the endpoint actually has")
    void everyDeclaredParamExistsInItsMapping() {
        List<String> mismatched = new ArrayList<>();

        for (Method method : requestMappedMethods()) {
            RequiresDocumentAccess guarded =
                AnnotatedElementUtils.findMergedAnnotation(method, RequiresDocumentAccess.class);
            if (guarded == null) {
                continue;
            }
            String placeholder = "{" + guarded.param() + "}";
            if (!mappingOf(method).contains(placeholder)) {
                mismatched.add(describe(method) + " declares param=\"" + guarded.param()
                    + "\" but its mapping is " + mappingOf(method));
            }
        }

        assertThat(mismatched)
            .as("""
                An endpoint names a path variable that its own mapping does not declare. \
                DocumentAccessGuard looks that name up, finds nothing and answers 404 - so the \
                endpoint is unreachable for everybody, permanently, with nothing anywhere saying \
                why. Fail-closed and invisible is exactly the class of thing this test exists to \
                make loud.""")
            .isEmpty();
    }

    @Test
    @DisplayName("every endpoint is inside the path scope the guard is registered for")
    void everyEndpointIsWithinTheGuardsScope() {
        List<String> outside = new ArrayList<>();

        for (Method method : requestMappedMethods()) {
            for (String path : fullPathsOf(method)) {
                if (!path.startsWith(GUARDED_PREFIX)) {
                    outside.add(describe(method) + " is mapped at " + path);
                }
            }
        }

        assertThat(outside)
            .as("""
                An endpoint is served from outside the path DocumentAccessGuard is registered for \
                (%s in WebMvcConfig), so its access annotation is decoration: the interceptor never \
                runs for it and nothing refuses the request. Either move it under that prefix, or \
                widen the interceptor's patterns - deliberately, and in the same commit.""",
                GUARDED_PREFIX + "**")
            .isEmpty();
    }

    private static final String GUARDED_PREFIX = "/api";

    private static List<String> fullPathsOf(Method method) {
        String[] classPaths = pathsOf(method.getDeclaringClass());
        String[] methodPaths = pathsOf(method);
        List<String> full = new ArrayList<>();
        if (classPaths.length == 0) {
            full.addAll(List.of(methodPaths));
        } else {
            for (String classPath : classPaths) {
                if (methodPaths.length == 0) {
                    full.add(classPath);
                    continue;
                }
                for (String methodPath : methodPaths) {
                    full.add(classPath + methodPath);
                }
            }
        }
        return full.isEmpty() ? List.of("") : full;
    }

    private static String mappingOf(Method method) {
        return String.join(" ", pathsOf(method.getDeclaringClass()))
            + " " + String.join(" ", pathsOf(method));
    }

    private static String[] pathsOf(java.lang.reflect.AnnotatedElement element) {
        RequestMapping mapping =
            AnnotatedElementUtils.findMergedAnnotation(element, RequestMapping.class);
        return mapping == null ? new String[0] : mapping.value();
    }

    private static final Pattern DESTINATION_PLACEHOLDER = Pattern.compile("\\{[^}]+}");

    @Test
    @DisplayName("every WebSocket destination is one the frame interceptor can authorize")
    void everyMessageMappingIsReachableByTheFrameInterceptor() {
        List<Method> mappings = messageMappedMethods();

        assertThat(mappings)
            .as("no @MessageMapping methods found; the scan is broken, not the code")
            .isNotEmpty();

        List<String> unreachable = new ArrayList<>();
        for (Method method : mappings) {
            for (String destination : method.getAnnotation(MessageMapping.class).value()) {
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
                carry the document id where the interceptor looks for it - /doc/{documentId}/…""")
            .isEmpty();
    }

    private static List<Method> requestMappedMethods() {
        return methodsIn(Controller.class, method ->
            AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class));
    }

    private static List<Method> messageMappedMethods() {
        return methodsIn(Controller.class, method -> method.isAnnotationPresent(MessageMapping.class));
    }

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
