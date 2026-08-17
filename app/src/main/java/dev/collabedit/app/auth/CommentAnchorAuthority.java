package dev.collabedit.app.auth;

import java.util.UUID;

@FunctionalInterface
public interface CommentAnchorAuthority {
    boolean mayAnchorThread(String userId, UUID documentId, UUID threadId);
}
