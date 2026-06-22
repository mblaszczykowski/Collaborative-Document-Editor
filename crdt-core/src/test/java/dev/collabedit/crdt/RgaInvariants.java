package dev.collabedit.crdt;

import java.util.ArrayList;
import java.util.List;

/**
 * Checking RGA's structural invariants — regardless of what text comes out.
 *
 * <p>Convergence tests compare replicas against each other, so they will not detect a bug in which
 * every replica is wrong <b>in the same way</b>. This checker looks at a single replica's structure
 * and asks whether it is a valid RGA linearisation at all.
 */
final class RgaInvariants {

    private RgaInvariants() {
    }

    /**
     * The RGA linearisation invariant: for every block B with origin O
     * <ol>
     *   <li>O appears in the sequence <b>before</b> B,</li>
     *   <li>every element lying <b>strictly between</b> O and B has a higher timestamp than B.</li>
     * </ol>
     *
     * <p>The second condition is the heart of RGA: it is what makes an element's position a
     * function of the set of elements rather than of the order they were inserted in. Violating it
     * means two replicas may place the same element in different spots.
     *
     * @return a human-readable list of violations; empty when the structure is valid
     */
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
            // A block's timestamp is ALWAYS above its origin's, because its author had already
            // seen the origin when they created it — that is what the Lamport clock bump on
            // receipt guarantees ({@code RgaDocument.observeClock}, the README).
            //
            // THIS CHECK IS THE ONE THAT WAS MISSING, and its absence is why four separate
            // comments in this suite claimed to catch a mutation they did not. Neutering
            // observeClock entirely — the exact mutation `convergenceWithInterleavedDelivery`,
            // `ReplicaSet.deliverSome` and `EditScript` all say they exist to catch — left every
            // one of the fifteen property tests green at 3000 tries. Convergence cannot see it:
            // all the replicas run the same code, so they agree on the same wrong answer, and the
            // other invariant below only compares blocks BETWEEN the origin and the insertion,
            // never the insertion against the origin itself.
            //
            // Verified both ways before it was committed: with the bump in place the properties
            // stay green, and with it removed `RgaConvergenceProperties` goes red. That is the
            // difference between a test that executes a line and a test that depends on it.
            if (origin.compareTo(block.start()) >= 0) {
                problems.add("block " + block + " has a timestamp at or below its origin " + origin
                    + " — the Lamport clock was not advanced on receipt");
            }
            if (originLoc.blockIndex() > i) {
                problems.add("block " + block + " stands BEFORE its origin " + origin);
                continue;
            }
            // The origin has to be the last character of its block, or the origin's block has to
            // be immediately before us — otherwise "between" would be counted wrongly.
            for (int between = originLoc.blockIndex() + 1; between < i; between++) {
                Block middle = blocks.get(between);
                if (middle.start().compareTo(block.start()) <= 0) {
                    problems.add("between origin " + origin + " and block " + block
                        + " lies " + middle + " with a LOWER timestamp — RGA rule violated");
                }
            }
        }
        return problems;
    }

    /**
     * Whether every run-length block is maximal, i.e. cannot be merged with the next one. A
     * violation is not a correctness bug but a performance one: the structure fragments into
     * one-character blocks and run-length compression stops achieving anything.
     */
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
