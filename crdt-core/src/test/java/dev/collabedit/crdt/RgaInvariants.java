package dev.collabedit.crdt;

import java.util.ArrayList;
import java.util.List;

final class RgaInvariants {
    private RgaInvariants() {
    }

    static List<String> violations(RgaDocument document) {
        BlockList blocks = document.blockList();
        List<String> problems = new ArrayList<>();

        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            Id origin = block.originLeft();
            if (origin == null) {
                continue;
            }

            BlockList.Locator originLoc = blocks.locateId(origin);
            if (originLoc == null) {
                problems.add("block " + block + " has origin " + origin + ", which is not in the structure");
                continue;
            }
            if (origin.compareTo(block.start()) >= 0) {
                problems.add("block " + block + " has a timestamp at or below its origin " + origin
                    + " - the Lamport clock was not advanced on receipt");
            }
            if (originLoc.blockIndex() > i) {
                problems.add("block " + block + " stands BEFORE its origin " + origin);
                continue;
            }
            for (int between = originLoc.blockIndex() + 1; between < i; between++) {
                Block middle = blocks.get(between);
                if (middle.start().compareTo(block.start()) <= 0) {
                    problems.add("between origin " + origin + " and block " + block
                        + " lies " + middle + " with a LOWER timestamp - RGA rule violated");
                }
            }
        }
        return problems;
    }

    static List<String> fragmentation(RgaDocument document) {
        BlockList blocks = document.blockList();
        List<String> problems = new ArrayList<>();
        for (int i = 0; i + 1 < blocks.size(); i++) {
            if (blocks.get(i).canAppend(blocks.get(i + 1))) {
                problems.add("blocks " + blocks.get(i) + " and " + blocks.get(i + 1)
                    + " should have been merged");
            }
        }
        return problems;
    }
}
