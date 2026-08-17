package dev.collabedit.crdt;

public record Anchor(Id charId, Side side) {
    public enum Side {
        BEFORE,
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
