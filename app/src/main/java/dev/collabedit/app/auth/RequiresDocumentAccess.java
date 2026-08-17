package dev.collabedit.app.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequiresDocumentAccess {
    Capability value();

    String param() default "documentId";

    Source from() default Source.DOCUMENT;

    boolean includeDeleted() default false;

    enum Source {
        DOCUMENT,
        COMMENT_THREAD
    }
}
