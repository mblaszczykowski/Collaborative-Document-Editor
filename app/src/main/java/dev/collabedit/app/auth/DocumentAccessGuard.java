package dev.collabedit.app.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

@Component
public class DocumentAccessGuard implements HandlerInterceptor {
    private static final Logger log = LoggerFactory.getLogger(DocumentAccessGuard.class);

    public static final String RESOLVED_ROLE = "collabedit.resolvedRole";

    private static final HttpStatus DENIED = HttpStatus.NOT_FOUND;

    private final PermissionService permissions;
    private final ThreadDocumentResolver threads;

    public DocumentAccessGuard(PermissionService permissions, ThreadDocumentResolver threads) {
        this.permissions = permissions;
        this.threads = threads;
    }

    public static Optional<Role> resolvedRole(HttpServletRequest request) {
        Object attribute = request.getAttribute(RESOLVED_ROLE);
        return attribute instanceof Role role ? Optional.of(role) : Optional.empty();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }

        PublicEndpoint open = method.getMethodAnnotation(PublicEndpoint.class);
        RequiresAuthentication authenticated =
            method.getMethodAnnotation(RequiresAuthentication.class);
        RequiresDocumentAccess guarded = method.getMethodAnnotation(RequiresDocumentAccess.class);

        int declarations = (open == null ? 0 : 1)
            + (authenticated == null ? 0 : 1)
            + (guarded == null ? 0 : 1);
        if (declarations != 1) {
            return refuseUndeclared(response, method, declarations == 0
                ? "declares none of @RequiresDocumentAccess, @RequiresAuthentication, "
                    + "@PublicEndpoint"
                : "declares more than one access annotation, so its intent is ambiguous");
        }

        if (open != null) {
            return true;
        }
        if (authenticated != null) {
            if (TokenAuthFilter.principalOf(request).isEmpty()) {
                response.setStatus(HttpStatus.UNAUTHORIZED.value());
                return false;
            }
            return true;
        }

        UUID documentId = documentIdFor(request, guarded);
        if (documentId == null) {
            response.setStatus(DENIED.value());
            return false;
        }

        String caller = TokenAuthFilter.principalOf(request)
            .map(TokenService.Principal::userId)
            .orElse(null);

        Role role = (guarded.includeDeleted()
            ? permissions.roleOfDeleted(caller, documentId)
            : permissions.roleOf(caller, documentId)).orElse(null);

        if (!guarded.value().permittedFor(role)) {
            response.setStatus(DENIED.value());
            return false;
        }

        request.setAttribute(RESOLVED_ROLE, role);
        return true;
    }

    private boolean refuseUndeclared(HttpServletResponse response, HandlerMethod method,
                                     String what) {
        log.error("Endpoint {}#{} {} - refusing to serve it. Every request-mapped method must "
                + "declare one or the other; see DocumentAccessGuard.",
            method.getBeanType().getSimpleName(), method.getMethod().getName(), what);
        response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
        return false;
    }

    private UUID documentIdFor(HttpServletRequest request, RequiresDocumentAccess guarded) {
        UUID fromPath = pathVariable(request, guarded.param());
        if (fromPath == null) {
            return null;
        }
        return switch (guarded.from()) {
            case DOCUMENT -> fromPath;
            case COMMENT_THREAD -> threads.documentOf(fromPath);
        };
    }

    private static UUID pathVariable(HttpServletRequest request, String name) {
        Object attribute = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(attribute instanceof Map<?, ?> variables)) {
            return null;
        }
        Object raw = variables.get(name);
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
