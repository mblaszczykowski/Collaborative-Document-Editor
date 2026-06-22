package dev.collabedit.crdt;

import java.util.Set;

/**
 * How marks behave when typing at the boundary of a range (CP13).
 *
 * <h2>Why this is a property of the TYPE, not of the operation</h2>
 * "Does bold grow when I type at its end" is not a decision the user makes on every bolding — it is
 * an expectation tied to the kind of formatting. Bold grows, a link does not. If it were a property
 * of the operation, two bolds in the same document could behave differently and the user would have
 * no way of predicting which.
 *
 * <p>The technical consequence: a mark does not have to carry an extra field, because the behaviour
 * follows from its type. The wire format stays one field smaller on an operation there is a lot of
 * anyway.
 *
 * <h2>Where these particular values come from</h2>
 * From how the editors people are used to behave. Typing at the end of a bold word continues the
 * bold; typing at the end of a link does not extend the address, because text appended after a link
 * is almost never part of it. Inline code ({@code code}) does not grow for the same reason as a
 * link — it is a closed unit.
 */
public final class MarkTypes {

    private MarkTypes() {
    }

    public static final String BOLD = "bold";
    public static final String ITALIC = "italic";
    public static final String UNDERLINE = "underline";
    public static final String STRIKETHROUGH = "strike";
    public static final String CODE = "code";
    public static final String LINK = "link";

    /**
     * A comment anchor (CP14).
     *
     * <p>It lives here rather than in the comments layer, because from the CRDT's point of view it
     * is an ordinary mark — the whole difference is that its value is a thread identifier. The
     * constant exists so this string is not typed by hand in frame authorization and in the
     * comments layer, where a typo would cause no error, only a silent change in permissions.
     */
    public static final String COMMENT = "comment";

    /**
     * A proposed insertion (CP23).
     *
     * <p>The third thing built out of this layer, after formatting and comments, and the first one
     * nobody had in mind when the layer was designed. A suggestion is not a separate document, a
     * branch, or a shadow copy: the proposed text goes into the ordinary character sequence and
     * wears a mark saying it is a proposal. Accepting removes the mark; rejecting deletes the
     * characters.
     *
     * <p>The value is the suggestion's identifier, exactly as {@link #COMMENT} carries a thread
     * identifier — the author, the time and the resolution live in a table, because none of them
     * is edited concurrently and none of them belongs in an operation log (the same
     * split, for the same reason).
     */
    public static final String SUGGEST_INSERT = "suggest-insert";

    /** A proposed deletion (CP23). Nothing is deleted until somebody accepts it. */
    public static final String SUGGEST_DELETE = "suggest-delete";

    /**
     * A heading (CP30). The value is the level, {@code "1"} to {@code "3"}.
     *
     * <h2>Block structure as marks, and what that does not buy</h2>
     * The document is a flat character sequence, so a paragraph is not an object here — it is the
     * text between two newlines. A heading is therefore a mark over that range, exactly like
     * bolding, and it needs nothing new in the character layer.
     *
     * <p>What it does <b>not</b> give is a move operation. Moving a paragraph in this model is a
     * deletion plus an insertion, and two people moving the same paragraph concurrently produce
     * two copies of it — a documented, tested limit rather than a bug (and
     * {@code BlockStructureTest}). A real move needs a block identity the sequence does not have.
     */
    public static final String HEADING = "heading";

    /** A quoted paragraph (CP30). */
    public static final String QUOTE = "quote";

    /** A list item (CP30). The value is {@code "bullet"} or {@code "ordered"}. */
    public static final String LIST = "list";

    /**
     * Types whose range <b>grows</b> when text is typed immediately after it.
     *
     * <p>An unknown type does NOT grow. That is the cautious choice: a mark that expands
     * unexpectedly changes the appearance of text the user did not format, whereas a mark that
     * fails to expand costs at most a repeated gesture.
     *
     * <p>The block types (CP30) grow for a different reason from the inline ones. Bold grows
     * because that is what people expect while typing; a heading grows because <b>it describes a
     * line</b>, and typing at the end of a line has to stay in the same line. The end anchor sits
     * before the newline, so growth stops there — a heading cannot swallow the paragraph under
     * it, which is the failure this rule has to avoid rather than the one it is chosen for.
     */
    private static final Set<String> EXPANDING =
        Set.of(BOLD, ITALIC, UNDERLINE, STRIKETHROUGH, HEADING, QUOTE, LIST);

    public static boolean expandsAtEnd(String type) {
        return EXPANDING.contains(type);
    }
}
