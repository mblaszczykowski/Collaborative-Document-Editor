package dev.collabedit.crdt;

import java.util.List;

/**
 * A CRDT operation — the unit of change sent between replicas and written to the operation log
 * (ADR-0003, where {@code (doc_id, replica_id, lamport)} is the primary key, so every operation
 * must have its own identifier).
 *
 * <p>Operations are <b>commutative</b> and <b>idempotent</b>: any order of application gives the
 * same result, and applying one twice changes nothing. That is the property the {@code convergence}
 * property test proves — and the only reason the architecture from ADR-0004 (every backend instance
 * a peer replica, unreliable broadcast) is safe.
 */
public sealed interface Op permits Op.Insert, Op.Delete, Op.AddMark, Op.RemoveMark {

    /**
     * The operation's identifier. For an insertion it is also the identifier of the first
     * character.
     */
    Id id();

    /**
     * How many Lamport clock values this operation occupies. Inserting {@code n} characters
     * occupies {@code n} (character {@code i} has the identifier {@code id.plus(i)}); a deletion
     * occupies 1.
     */
    int span();

    /**
     * The highest lamport this operation occupies — needed to bump the clock on receipt.
     */
    default long maxLamport() {
        return id().lamport() + span() - 1;
    }

    /**
     * Inserting a run of characters directly after the character {@code originLeft}.
     *
     * <p>Run-length encoding: {@code text} of length {@code n} is one object and one operation
     * rather than {@code n} operations. The character at index {@code i} has the identifier
     * {@code id.plus(i)}, and its left origin is the character {@code i-1}. That way ordinary
     * typing — the most common usage pattern — does not produce an object per character.
     *
     * @param originLeft the character we insert after; {@code null} means the start of the document
     */
    record Insert(Id id, Id originLeft, String text) implements Op {

        public Insert {
            if (text == null || text.isEmpty()) {
                throw new IllegalArgumentException("inserting empty text makes no sense");
            }
        }

        @Override
        public int span() {
            return text.length();
        }
    }

    /**
     * Deleting ranges of characters (marking them as tombstones).
     *
     * <p>Ranges rather than individual identifiers, because deleting a selected fragment of text
     * typically covers a contiguous interval of identifiers from one replica — but it may also cut
     * across several blocks from different replicas, hence the list.
     *
     * <p>Deletion is idempotent, so we deliberately do <b>not</b> detect its duplicates: setting a
     * tombstone again is a no-op.
     */
    record Delete(Id id, List<Range> targets) implements Op {

        public Delete {
            if (targets == null || targets.isEmpty()) {
                throw new IllegalArgumentException("a deletion with no targets makes no sense");
            }
            targets = List.copyOf(targets);
        }

        @Override
        public int span() {
            return 1;
        }
    }

    /**
     * Applying formatting to a range of text (CP13).
     *
     * <h2>Why this is a SEPARATE operation rather than a character attribute</h2>
     * Formatting as a character attribute looks simpler and is wrong. Consider: Ania bolds a range,
     * and Bartek — not yet seeing her change — types text into the middle of that range. With
     * per-character attributes the inserted text has nowhere to get the bold from: at the moment it
     * was created Ania's operation had not reached Bartek, and when it does it concerns characters
     * that are not in it. The result: a hole in the bolding that nobody made.
     *
     * <p>A mark stored as a <b>range between anchors</b> does not have that problem, because the
     * anchors move with the text and text inserted into the middle of a range is in the middle of
     * the range — regardless of delivery order.
     *
     * <h2>Conflict resolution</h2>
     * For a given character and a given mark type, the winner is the operation with the highest
     * {@code (lamport, replicaId)} among those covering that character. This is the same total
     * order RGA's insertion rule uses — with no new arbitration mechanism and no new assumption
     * about clocks.
     *
     * @param type  the kind of formatting, e.g. {@code bold}, {@code italic}, {@code link}
     * @param value the value for types that need one (a link's address); {@code null} for the rest
     */
    record AddMark(Id id, String type, String value, Anchor start, Anchor end) implements Op {

        public AddMark {
            if (type == null || type.isBlank()) {
                throw new IllegalArgumentException("a mark must have a type");
            }
            if (start == null || end == null) {
                throw new IllegalArgumentException("a mark must have both anchors");
            }
        }

        @Override
        public int span() {
            return 1;
        }
    }

    /**
     * Removing formatting from a range (CP13).
     *
     * <h2>Why a range rather than a reference to a specific AddMark</h2>
     * Because removing formatting is not undoing somebody else's operation. Two people may bold
     * overlapping ranges and a third unbold their intersection — and then there is no single
     * operation to point at as "the one to undo".
     *
     * <p>Removal is therefore a peer range operation, resolved by the same order as application:
     * the one with the higher {@code (lamport, replicaId)} wins.
     */
    record RemoveMark(Id id, String type, Anchor start, Anchor end) implements Op {

        public RemoveMark {
            if (type == null || type.isBlank()) {
                throw new IllegalArgumentException("a mark must have a type");
            }
            if (start == null || end == null) {
                throw new IllegalArgumentException("a mark must have both anchors");
            }
        }

        @Override
        public int span() {
            return 1;
        }
    }

    /** A contiguous range of one replica's identifiers: {@code [start, start+length)}. */
    record Range(Id start, int length) {

        public Range {
            if (length <= 0) {
                throw new IllegalArgumentException("range length must be positive: " + length);
            }
        }

        public boolean contains(Id id) {
            return id.replicaId() == start.replicaId()
                && id.lamport() >= start.lamport()
                && id.lamport() < start.lamport() + length;
        }

        public Id endExclusive() {
            return start.plus(length);
        }
    }
}
