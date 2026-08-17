package dev.collabedit.app.domain;

import java.util.UUID;

public final class RegistryTestAccess {
    private RegistryTestAccess() {
    }

    public static void forget(DocumentRegistry registry, UUID documentId) {
        registry.forget(documentId);
    }
}
