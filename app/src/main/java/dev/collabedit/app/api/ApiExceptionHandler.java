package dev.collabedit.app.api;

import dev.collabedit.app.auth.AuthorizationWiringException;
import dev.collabedit.app.domain.DocumentReplica;
import dev.collabedit.app.domain.DocumentService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    public record ApiError(String message) {
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> onInvalidBody(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
            .findFirst()
            .map(error -> error.getField() + " " + error.getDefaultMessage())
            .orElse("the request body is not valid");
        return ResponseEntity.badRequest().body(new ApiError(detail));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiError> onInvalidParameter(HandlerMethodValidationException e) {
        String detail = e.getParameterValidationResults().stream()
            .flatMap(result -> result.getResolvableErrors().stream()
                .map(error -> result.getMethodParameter().getParameterName()
                    + " " + error.getDefaultMessage()))
            .findFirst()
            .orElse("a request parameter is not valid");
        return ResponseEntity.badRequest().body(new ApiError(detail));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> onIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(new ApiError(e.getMessage()));
    }

    @ExceptionHandler(AuthorizationWiringException.class)
    public ResponseEntity<ApiError> onWiringFault(AuthorizationWiringException e,
                                                  HttpServletRequest request) {
        log.error("Authorization wiring fault handling {} {}", request.getMethod(),
            request.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(new ApiError("The server could not process the request."));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> onIllegalState(IllegalStateException e,
                                                   HttpServletRequest request) {
        log.warn("Conflict handling {} {}: {}", request.getMethod(), request.getRequestURI(),
            e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(e.getMessage()));
    }

    @ExceptionHandler(DocumentService.ResyncRequiredException.class)
    public ResponseEntity<ApiError> onResyncRequired(DocumentService.ResyncRequiredException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(e.getMessage()));
    }

    @ExceptionHandler(DocumentReplica.StaleReplicaException.class)
    public ResponseEntity<ApiError> onStaleReplica(DocumentReplica.StaleReplicaException e) {
        log.warn("A document replica was evicted twice during one request: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(new ApiError("the document is being reloaded - retry"));
    }
}
