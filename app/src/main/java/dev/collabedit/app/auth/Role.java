package dev.collabedit.app.auth;

public enum Role {
    VIEWER,

    COMMENTER,

    EDITOR,

    OWNER;

    public boolean atLeast(Role required) {
        return ordinal() >= required.ordinal();
    }

    public boolean canRead() {
        return true;
    }

    public boolean canWriteText() {
        return atLeast(EDITOR);
    }

    public boolean canComment() {
        return atLeast(COMMENTER);
    }

    public boolean canManageAccess() {
        return this == OWNER;
    }

    public static Role parse(String value) {
        if (value == null) {
            return VIEWER;
        }
        return switch (value.toLowerCase()) {
            case "owner" -> OWNER;
            case "editor" -> EDITOR;
            case "commenter" -> COMMENTER;
            default -> VIEWER;
        };
    }

    public String wire() {
        return name().toLowerCase();
    }
}
