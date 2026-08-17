package dev.collabedit.app.auth;

import java.util.function.Predicate;

public enum Capability {
    READ(Role::canRead),

    COMMENT(Role::canComment),

    WRITE_TEXT(Role::canWriteText),

    MANAGE_ACCESS(Role::canManageAccess);

    private final Predicate<Role> permitted;

    Capability(Predicate<Role> permitted) {
        this.permitted = permitted;
    }

    public boolean permittedFor(Role role) {
        return role != null && permitted.test(role);
    }
}
