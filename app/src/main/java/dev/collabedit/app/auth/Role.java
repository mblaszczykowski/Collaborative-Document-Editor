package dev.collabedit.app.auth;

/**
 * A user's role with respect to a document (CP12).
 *
 * <p>The roles are <b>ordered</b>, and that ordering is part of the model rather than an
 * implementation convenience: each one can do everything the previous one can. That makes checking
 * a permission a comparison rather than a table of exceptions — and a table of exceptions is where
 * holes appear.
 *
 * <p><b>What is deliberately absent:</b> an "administrator" role spanning every document. There is
 * no permission here that does not follow from a user's relationship to a specific document or
 * workspace — which is what makes the answer to "who has access to this document" always complete
 * and local.
 */
public enum Role {

    /** Czyta dokument i widzi kursory innych. Nie zmienia niczego. */
    VIEWER,

    /**
     * As above plus comments (CP14).
     *
     * <p>This role is the reason authorization has to look at the <b>operation type</b> and not
     * only at the document: a commenter sends CRDT operations over the same channel as an editor,
     * so the distinction between "may write" and "may comment" does not exist at the level of a
     * STOMP destination.
     */
    COMMENTER,

    /** Changes the content and the formatting. */
    EDITOR,

    /** As above plus managing the document's access and visibility. */
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

    /**
     * Reading a role from the database.
     *
     * <p>An unknown value gives {@link #VIEWER}, that is, the <b>smallest</b> permission — not an
     * exception and not the largest. A role added by a future migration this version of the code
     * knows nothing about should at worst restrict a user rather than let them further than they
     * should go.
     */
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
