package dev.collabedit.app.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

class DocumentAccessGuardTest {
    private static final UUID DOCUMENT = UUID.randomUUID();
    private static final UUID THREAD = UUID.randomUUID();

    @SuppressWarnings("unused")
    static class Endpoints {
        void undeclared() {
        }

        @PublicEndpoint("test fixture")
        void open() {
        }

        @RequiresAuthentication("test fixture")
        void needsUser() {
        }

        @RequiresDocumentAccess(Capability.READ)
        void needsRead() {
        }

        @RequiresDocumentAccess(Capability.WRITE_TEXT)
        void needsWrite() {
        }

        @RequiresDocumentAccess(value = Capability.COMMENT, param = "threadId",
            from = RequiresDocumentAccess.Source.COMMENT_THREAD)
        void needsCommentOnThread() {
        }

        @PublicEndpoint("test fixture")
        @RequiresAuthentication("test fixture")
        void contradictory() {
        }
    }

    private final PermissionService permissions = mock(PermissionService.class);
    private final ThreadDocumentResolver threads = mock(ThreadDocumentResolver.class);
    private final DocumentAccessGuard guard = new DocumentAccessGuard(permissions, threads);

    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    @DisplayName("an endpoint that declares nothing is refused, not served")
    void undeclaredEndpointFailsClosed() throws Exception {
        boolean proceed = guard.preHandle(request(null), response, handler("undeclared"));

        assertThat(proceed)
            .as("an undeclared endpoint was served; forgetting to authorize is silent again")
            .isFalse();
        assertThat(response.getStatus())
            .as("500, because this is a mistake in our code rather than in the caller's request")
            .isEqualTo(500);
    }

    @Test
    @DisplayName("an endpoint declaring two contradictory intents is refused")
    void ambiguousDeclarationFailsClosed() throws Exception {
        assertThat(guard.preHandle(request(null), response, handler("contradictory"))).isFalse();
        assertThat(response.getStatus()).isEqualTo(500);
    }

    @Test
    @DisplayName("a public endpoint is served without a token")
    void publicEndpointNeedsNothing() throws Exception {
        assertThat(guard.preHandle(request(null), response, handler("open"))).isTrue();
    }

    @Test
    @DisplayName("an authenticated endpoint is 401 without a token and served with one")
    void authenticatedEndpointNeedsAnIdentity() throws Exception {
        assertThat(guard.preHandle(request(null), response, handler("needsUser")))
            .as("the endpoint that creates ownerless documents ran for an anonymous caller")
            .isFalse();
        assertThat(response.getStatus())
            .as("401, not 404: no resource identity is involved, so there is nothing to hide")
            .isEqualTo(401);

        assertThat(guard.preHandle(request("anna"), new MockHttpServletResponse(),
            handler("needsUser"))).isTrue();
    }

    @Test
    @DisplayName("a role that is too weak for the capability is refused as 404")
    void insufficientRoleIsDenied() throws Exception {
        when(permissions.roleOf(any(), any())).thenReturn(Optional.of(Role.VIEWER));

        assertThat(guard.preHandle(documentRequest("anna"), response, handler("needsWrite")))
            .as("a viewer was allowed through a write endpoint")
            .isFalse();
        assertThat(response.getStatus())
            .as("404 rather than 403, so the answer does not confirm the document exists")
            .isEqualTo(404);
    }

    @Test
    @DisplayName("a sufficient role is served and its role is handed to the controller")
    void sufficientRolePassesTheResolvedRoleOn() throws Exception {
        when(permissions.roleOf(any(), any())).thenReturn(Optional.of(Role.EDITOR));

        MockHttpServletRequest request = documentRequest("anna");
        assertThat(guard.preHandle(request, response, handler("needsRead"))).isTrue();

        assertThat(DocumentAccessGuard.resolvedRole(request))
            .as("the controller would have to ask again, and could get a different answer")
            .contains(Role.EDITOR);
    }

    @Test
    @DisplayName("a thread-scoped endpoint is authorized against the thread's document")
    void threadScopedEndpointResolvesItsDocument() throws Exception {
        when(threads.documentOf(THREAD)).thenReturn(DOCUMENT);
        when(permissions.roleOf(any(), any())).thenReturn(Optional.of(Role.COMMENTER));

        MockHttpServletRequest request = request("anna");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
            Map.of("threadId", THREAD.toString()));

        assertThat(guard.preHandle(request, response, handler("needsCommentOnThread"))).isTrue();
    }

    @Test
    @DisplayName("a thread that does not exist is 404, not a crash and not a pass")
    void unknownThreadIsDenied() throws Exception {
        when(threads.documentOf(any())).thenReturn(null);

        MockHttpServletRequest request = request("anna");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
            Map.of("threadId", UUID.randomUUID().toString()));

        assertThat(guard.preHandle(request, response, handler("needsCommentOnThread"))).isFalse();
        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("a malformed identifier is 404 rather than an exception")
    void malformedIdentifierIsDenied() throws Exception {
        MockHttpServletRequest request = request("anna");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
            Map.of("documentId", "not-a-uuid"));

        assertThat(guard.preHandle(request, response, handler("needsRead"))).isFalse();
        assertThat(response.getStatus()).isEqualTo(404);
    }

    private static HandlerMethod handler(String methodName) {
        Method method = java.util.Arrays.stream(Endpoints.class.getDeclaredMethods())
            .filter(candidate -> candidate.getName().equals(methodName))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("no fixture method " + methodName));
        return new HandlerMethod(new Endpoints(), method);
    }

    private static MockHttpServletRequest request(String userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (userId != null) {
            request.setAttribute(TokenAuthFilter.PRINCIPAL,
                new TokenService.Principal(userId, userId, "#4C5FD5"));
        }
        return request;
    }

    private static MockHttpServletRequest documentRequest(String userId) {
        MockHttpServletRequest request = request(userId);
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
            Map.of("documentId", DOCUMENT.toString()));
        return request;
    }
}
