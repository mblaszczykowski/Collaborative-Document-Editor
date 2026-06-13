package dev.collabedit.app.auth;

/**
 * What an endpoint needs to be allowed to do — the vocabulary of {@link RequiresDocumentAccess}.
 *
 * <p>This is deliberately <b>not</b> the same thing as {@link Role}. A role is what somebody
 * <i>is</i> with respect to a document; a capability is what an endpoint <i>demands</i>. Keeping
 * them apart is what lets the rule be written once: the endpoint declares the demand, and the
 * single mapping below turns it into a question about the caller's role.
 *
 * <p>Collapsing the two would mean every endpoint naming a minimum role, and every future role
 * inserted into the ordering would silently change what a dozen endpoints require. That is the
 * table-of-exceptions failure {@link Role} already warns about, one level up.
 */
public enum Capability {

    /** Sees the document at all. Everyone who has any role at all can read. */
    READ(Role::canRead),

    /** Writes comment threads, but not the text itself (CP14). */
    COMMENT(Role::canComment),

    /**
     * Changes the text or its formatting.
     *
     * <p>Note that history restore needs <b>this</b>, not {@link #READ}, even though it reads
     * from history: it overwrites the document. That confusion is exactly how the restore
     * endpoint stayed open to readers for a whole checkpoint.
     */
    WRITE_TEXT(Role::canWriteText),

    /** Changes who may do the above. Owner only. */
    MANAGE_ACCESS(Role::canManageAccess);

    private final java.util.function.Predicate<Role> permitted;

    Capability(java.util.function.Predicate<Role> permitted) {
        this.permitted = permitted;
    }

    public boolean permittedFor(Role role) {
        return role != null && permitted.test(role);
    }
}
