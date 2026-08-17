package dev.collabedit.crdt;

import java.util.List;

public sealed interface Op permits Op.Insert, Op.Delete, Op.AddMark, Op.RemoveMark {
    Id id();

    int span();

    default long maxLamport() {
        return id().lamport() + span() - 1;
    }

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

    record Range(Id start, int length) {
        public Range {
            if (length <= 0) {
                throw new IllegalArgumentException("range length must be positive: " + length);
            }
        }
    }
}
