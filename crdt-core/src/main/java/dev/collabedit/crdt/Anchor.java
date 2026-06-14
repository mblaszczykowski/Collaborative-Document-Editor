package dev.collabedit.crdt;

/**
 * A place in the document pinned to a <b>character</b> rather than to a position.
 *
 * <h2>Why not an offset</h2>
 * The same reason cursors are character identifiers (ADR-0006): an offset stops pointing at the
 * same place as soon as somebody inserts text earlier on. An anchor moves with the text, because it
 * is attached to a character.
 *
 * <h2>Why the side matters, and is not cosmetic</h2>
 * This is the heart of rich text in a CRDT and the easiest thing to overlook. An anchor "after
 * character X" and an anchor "before character Y" point at the same place in the text {@code …XY…}
 * — <b>until somebody inserts something between X and Y</b>. Then they diverge, and it is precisely
 * that difference that decides how formatting behaves:
 *
 * <pre>
 *   text:             a [b] c          ("b" is bold)
 *
 *   end = AFTER(b):      the range ends just after "b"
 *                        typing "x" after "b" → a [b] x c     — the bold does NOT grow
 *
 *   end = BEFORE(c):     the range ends just before "c"
 *                        typing "x" after "b" → a [b x] c     — the bold DOES grow
 * </pre>
 *
 * <p>Typing at the end of a bold fragment should become bold, while typing at the end of a link
 * should not become part of it. Both are expressed by the same mechanism; the only difference is
 * which side the end anchor sits on. Without {@code Side} this would have to be settled by a
 * separate "should it expand" field, which describes the same thing only worse — because it is
 * detached from where the anchor actually is.
 *
 * @param charId the character the anchor is attached to
 * @param side   which side of it
 */
public record Anchor(Id charId, Side side) {

    public enum Side {
        /** Just before the character. Text inserted directly before it ends up AFTER the anchor. */
        BEFORE,
        /** Just after the character. Text inserted directly after it ends up AFTER the anchor. */
        AFTER
    }

    public Anchor {
        if (charId == null) {
            throw new IllegalArgumentException("an anchor must point at a character");
        }
        if (side == null) {
            throw new IllegalArgumentException("an anchor must have a side");
        }
    }

    public static Anchor before(Id charId) {
        return new Anchor(charId, Side.BEFORE);
    }

    public static Anchor after(Id charId) {
        return new Anchor(charId, Side.AFTER);
    }

    @Override
    public String toString() {
        return (side == Side.BEFORE ? "◂" : "▸") + charId;
    }
}
