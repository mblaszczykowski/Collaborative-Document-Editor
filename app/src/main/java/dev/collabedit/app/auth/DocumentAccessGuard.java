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

/**
 * One place where every document-scoped REST request is authorized (CP16).
 *
 * <h2>The problem this replaces</h2>
 * Until CP16 authorization lived in about fifteen places across four files. Each check was
 * correct; nothing required the next one to exist. That class of bug bit this project three
 * times:
 * <ol>
 *   <li>CP11 shipped an IDOR — knowing an identifier was enough to read any document;</li>
 *   <li>{@code HistoryController} had <b>no check at all</b> for a whole checkpoint, including
 *       an endpoint that let a reader overwrite someone else's text;</li>
 *   <li>{@code POST /api/documents/recovered} set owner and visibility only inside
 *       {@code if (caller != null)}, so an expired token produced a link-visible document —
 *       and that endpoint exists precisely for clients whose token has expired.</li>
 * </ol>
 * All three were found by review or audit, never by a test. The lesson written into ADR-0009
 * was that a safeguard which depends on somebody remembering is not a safeguard.
 *
 * <h2>Fail closed</h2>
 * The important line in this class is the {@code default} case: a request-mapped method that
 * declares <b>neither</b> {@link RequiresDocumentAccess} nor {@link PublicEndpoint} is refused
 * with 500, not served. A new controller therefore starts <i>broken and loud</i> instead of
 * open and quiet, which is the entire point — forgetting is now a state the system reports
 * about itself rather than one it hides.
 *
 * <p>500 rather than 404 is deliberate: this is a programming error in <i>our</i> code, not a
 * caller doing something wrong, and it should read that way in logs and in tests.
 *
 * @see EndpointAuthorizationArchitectureTest the build-time half of the same rule
 */
@Component
public class DocumentAccessGuard implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(DocumentAccessGuard.class);

    /**
     * Where the resolved role is left for the controller.
     *
     * <p>The guard has already paid for the lookup, and {@code PermissionService} caches for
     * five seconds, so a second call would usually be free — but "usually free" across a
     * cache expiry boundary means a controller can observe a <b>different</b> role than the one
     * it was admitted with. Passing the decision forward removes that window entirely.
     */
    public static final String RESOLVED_ROLE = "collabedit.resolvedRole";

    /** 404 rather than 403, same reason as everywhere else: not confirming the document exists. */
    private static final HttpStatus DENIED = HttpStatus.NOT_FOUND;

    private final PermissionService permissions;
    private final ThreadDocumentResolver threads;

    public DocumentAccessGuard(PermissionService permissions, ThreadDocumentResolver threads) {
        this.permissions = permissions;
        this.threads = threads;
    }

    /** The role this request was admitted with; empty on public endpoints. */
    public static Optional<Role> resolvedRole(HttpServletRequest request) {
        Object attribute = request.getAttribute(RESOLVED_ROLE);
        return attribute instanceof Role role ? Optional.of(role) : Optional.empty();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod method)) {
            // CORS preflight and static resources are not our endpoints and have no document.
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
            // 401, not 404: no resource identity is involved, so there is nothing to hide by
            // pretending it does not exist. The caller is simply not logged in.
            if (TokenAuthFilter.principalOf(request).isEmpty()) {
                response.setStatus(HttpStatus.UNAUTHORIZED.value());
                return false;
            }
            return true;
        }

        UUID documentId = documentIdFor(request, guarded);
        if (documentId == null) {
            // Unparseable, missing, or a thread that does not exist. All three are "no such
            // document" from the caller's point of view, and none of them may leak which.
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
        // Loud on purpose. This is the message that should have existed when HistoryController
        // shipped without any authorization at all.
        log.error("Endpoint {}#{} {} — refusing to serve it. Every request-mapped method must "
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
            // A malformed identifier is a 404, not a 400: telling the caller their id was the
            // wrong *shape* is a smaller leak than telling them it was the wrong *value*, but
            // it is still a distinction we have no reason to hand out.
            return null;
        }
    }
}
