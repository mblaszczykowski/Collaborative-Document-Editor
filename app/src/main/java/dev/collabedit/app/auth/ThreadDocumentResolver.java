package dev.collabedit.app.auth;

import java.util.UUID;

@FunctionalInterface
public interface ThreadDocumentResolver {
    UUID documentOf(UUID threadId);
}
