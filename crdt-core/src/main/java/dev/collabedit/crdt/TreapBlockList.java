package dev.collabedit.crdt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * A {@link BlockList} implementation based on a treap with aggregates in the nodes — the O(log n)
 * version.
 *
 * <p>It was written <b>after</b> {@link ArrayBlockList} and after measuring that one with a JMH
 * benchmark. The order is deliberate: first the obviously correct version, then numbers showing it
 * is too slow, and only then the optimisation — with the same property test suite on both, so that
 * the optimisation changed nothing in the behaviour
 * ({@code BlockListEquivalenceProperties.naiveAndIndexedProduceIdenticalDocuments}).
 *
 * <h2>Three aggregates in every node</h2>
 * <ul>
 *   <li>{@code size} — the number of blocks in the subtree, which gives lookup by block index,</li>
 *   <li>{@code visible} — the number of visible characters, which gives lookup by position in the
 *       text <b>skipping tombstones</b> without visiting them,</li>
 *   <li>{@code total} — all characters, which gives the tombstone overhead in O(1).</li>
 * </ul>
 *
 * <p>Lookup by identifier cannot be done with the tree alone, because the tree is ordered by
 * position rather than by identifier. Hence an additional map
 * {@code replica → (starting lamport → node)}: {@code floorEntry} finds the candidate, and its
 * position is computed by climbing the parent pointers.
 *
 * <p>The heap priorities are <b>deterministic</b> — computed from the block's identifier rather
 * than random. That way the same sequence of operations produces an identical tree on every run,
 * which is a precondition for reproducing counterexamples from the property-based tests.
 */
public final class TreapBlockList implements BlockList {

    private static final class Node {
        Block block;
        final int priority;
        Node left;
        Node right;
        Node parent;
        int size = 1;
        int visible;
        int total;

        Node(Block block) {
            this.block = block;
            this.priority = priorityOf(block.start());
        }
    }

    private Node root;
    /** replica → (block's starting lamport → node). For lookup by identifier. */
    private final Map<Integer, NavigableMap<Long, Node>> index = new HashMap<>();

    /**
     * The heap priority, computed deterministically from the identifier (a splitmix64 variant). A
     * random generator would give the same balance but would take away reproducibility.
     */
    private static int priorityOf(Id id) {
        long h = id.replicaId() * 0x9E3779B97F4A7C15L + id.lamport() * 0xBF58476D1CE4E5B9L;
        h ^= (h >>> 31);
        h *= 0x94D049BB133111EBL;
        h ^= (h >>> 33);
        return (int) h;
    }

    // ------------------------------------------------------------------
    // Aggregates
    // ------------------------------------------------------------------

    private static int sizeOf(Node node) {
        return node == null ? 0 : node.size;
    }

    private static int visibleOf(Node node) {
        return node == null ? 0 : node.visible;
    }

    private static int totalOf(Node node) {
        return node == null ? 0 : node.total;
    }

    private static void pull(Node node) {
        node.size = 1 + sizeOf(node.left) + sizeOf(node.right);
        node.visible = node.block.visibleLength() + visibleOf(node.left) + visibleOf(node.right);
        node.total = node.block.length() + totalOf(node.left) + totalOf(node.right);
    }

    /** Updates the aggregates from the given node up to the root. */
    private static void pullUp(Node from) {
        for (Node node = from; node != null; node = node.parent) {
            pull(node);
        }
    }

    // ------------------------------------------------------------------
    // Rotations
    // ------------------------------------------------------------------

    private void replaceInParent(Node node, Node replacement) {
        Node parent = node.parent;
        if (parent == null) {
            root = replacement;
        } else if (parent.left == node) {
            parent.left = replacement;
        } else {
            parent.right = replacement;
        }
        if (replacement != null) {
            replacement.parent = parent;
        }
    }

    private void rotateRight(Node node) {
        Node pivot = node.left;
        replaceInParent(node, pivot);
        node.left = pivot.right;
        if (node.left != null) {
            node.left.parent = node;
        }
        pivot.right = node;
        node.parent = pivot;
        pull(node);
        pull(pivot);
    }

    private void rotateLeft(Node node) {
        Node pivot = node.right;
        replaceInParent(node, pivot);
        node.right = pivot.left;
        if (node.right != null) {
            node.right.parent = node;
        }
        pivot.left = node;
        node.parent = pivot;
        pull(node);
        pull(pivot);
    }

    // ------------------------------------------------------------------
    // Navigation
    // ------------------------------------------------------------------

    private Node nodeAt(int blockIndex) {
        Node node = root;
        int remaining = blockIndex;
        while (node != null) {
            int leftSize = sizeOf(node.left);
            if (remaining < leftSize) {
                node = node.left;
            } else if (remaining == leftSize) {
                return node;
            } else {
                remaining -= leftSize + 1;
                node = node.right;
            }
        }
        throw new IndexOutOfBoundsException("no block at index " + blockIndex);
    }

    /** A node's position in in-order traversal — climbing the parents, O(log n). */
    private static int rankOf(Node node) {
        int rank = sizeOf(node.left);
        for (Node current = node; current.parent != null; current = current.parent) {
            if (current.parent.right == current) {
                rank += sizeOf(current.parent.left) + 1;
            }
        }
        return rank;
    }

    private static Node rightmost(Node node) {
        Node current = node;
        while (current.right != null) {
            current = current.right;
        }
        return current;
    }

    // ------------------------------------------------------------------
    // BlockList
    // ------------------------------------------------------------------

    @Override
    public int size() {
        return sizeOf(root);
    }

    @Override
    public Block get(int blockIndex) {
        return nodeAt(blockIndex).block;
    }

    @Override
    public void insert(int blockIndex, Block block) {
        Node node = new Node(block);
        pull(node);

        if (root == null) {
            root = node;
        } else if (blockIndex == size()) {
            Node target = rightmost(root);
            target.right = node;
            node.parent = target;
        } else {
            Node target = nodeAt(blockIndex);
            if (target.left == null) {
                target.left = node;
                node.parent = target;
            } else {
                Node predecessor = rightmost(target.left);
                predecessor.right = node;
                node.parent = predecessor;
            }
        }
        pullUp(node.parent);
        registerIndex(node);

        // Restoring the heap property. Rotations do not change the contents of the ancestors'
        // subtrees, so their aggregates stay correct — a pull inside the rotation is enough.
        while (node.parent != null && node.priority > node.parent.priority) {
            if (node.parent.left == node) {
                rotateRight(node.parent);
            } else {
                rotateLeft(node.parent);
            }
        }
    }

    @Override
    public void set(int blockIndex, Block block) {
        Node node = nodeAt(blockIndex);
        unregisterIndex(node);
        node.block = block;
        registerIndex(node);
        pullUp(node);
    }

    @Override
    public void remove(int blockIndex) {
        Node node = nodeAt(blockIndex);
        unregisterIndex(node);

        // Rotate the node down to a leaf, always promoting the child with the higher priority —
        // that keeps the heap property intact with no extra repair.
        while (node.left != null || node.right != null) {
            if (node.right == null
                || (node.left != null && node.left.priority > node.right.priority)) {
                rotateRight(node);
            } else {
                rotateLeft(node);
            }
        }
        Node parent = node.parent;
        replaceInParent(node, null);
        node.parent = null;
        pullUp(parent);
    }

    @Override
    public void markDeleted(int blockIndex, Id deleteId) {
        Node node = nodeAt(blockIndex);
        boolean wasVisible = !node.block.deleted();
        // Called even when the block is ALREADY a tombstone, unlike the previous version which
        // returned early. Recording the deleter takes the minimum of the identifiers seen
        // (Block.markDeleted), so a second, smaller delete arriving later still has to reach the
        // block — otherwise the recorded deleter would depend on delivery order, and it is written
        // into the snapshot.
        node.block.markDeleted(deleteId);
        if (!wasVisible) {
            // The aggregates already count this block as invisible; nothing moved.
            return;
        }
        // Without this the `visible` aggregate in the ancestors would go stale, and lookup by
        // position would start returning wrong results — silently, and only sometimes.
        pullUp(node);
    }

    @Override
    public int visibleLength() {
        return visibleOf(root);
    }

    @Override
    public int totalLength() {
        return totalOf(root);
    }

    @Override
    public Locator locateVisible(int visibleIndex) {
        if (visibleIndex < 0 || visibleIndex >= visibleOf(root)) {
            return null;
        }
        Node node = root;
        int remaining = visibleIndex;
        while (node != null) {
            int leftVisible = visibleOf(node.left);
            if (remaining < leftVisible) {
                node = node.left;
                continue;
            }
            remaining -= leftVisible;
            int own = node.block.visibleLength();
            if (remaining < own) {
                return new Locator(rankOf(node), remaining);
            }
            remaining -= own;
            node = node.right;
        }
        return null;
    }

    @Override
    public Locator locateId(Id id) {
        NavigableMap<Long, Node> byLamport = index.get(id.replicaId());
        if (byLamport == null) {
            return null;
        }
        Map.Entry<Long, Node> candidate = byLamport.floorEntry(id.lamport());
        if (candidate == null || !candidate.getValue().block.containsId(id)) {
            return null;
        }
        Node node = candidate.getValue();
        return new Locator(rankOf(node), node.block.offsetOf(id));
    }

    @Override
    public int visibleOffsetOf(int blockIndex) {
        int accumulated = 0;
        Node node = root;
        int remaining = blockIndex;
        while (node != null) {
            int leftSize = sizeOf(node.left);
            if (remaining < leftSize) {
                node = node.left;
            } else if (remaining == leftSize) {
                return accumulated + visibleOf(node.left);
            } else {
                accumulated += visibleOf(node.left) + node.block.visibleLength();
                remaining -= leftSize + 1;
                node = node.right;
            }
        }
        return accumulated;
    }

    /**
     * In-order traversal without materialising the list.
     *
     * <p>This used to copy every block into an {@code ArrayList} on every call, which is the one
     * place in this class where the O(log n) claim in the class javadoc did not hold: three of the
     * callers — {@code RgaDocument.text()}, {@code snapshot()} and {@code gc()} — only walk the
     * blocks in order, and each was paying a full-size allocation and copy to do it. The nodes
     * already carry {@code parent} pointers for the rotations, so the successor is reachable
     * without any auxiliary structure.
     *
     * <p>The iterator does not detect concurrent modification, and neither did the copy — it
     * merely hid it behind a stale snapshot of the list. No caller mutates while iterating:
     * {@code gc} completes its reference pass before it removes anything, and does the removing by
     * index.
     */
    @Override
    public Iterator<Block> iterator() {
        return new Iterator<>() {
            private Node next = leftmost(root);

            @Override
            public boolean hasNext() {
                return next != null;
            }

            @Override
            public Block next() {
                if (next == null) {
                    throw new java.util.NoSuchElementException();
                }
                Block block = next.block;
                next = successor(next);
                return block;
            }
        };
    }

    private static Node leftmost(Node node) {
        if (node == null) {
            return null;
        }
        while (node.left != null) {
            node = node.left;
        }
        return node;
    }

    private static Node successor(Node node) {
        if (node.right != null) {
            return leftmost(node.right);
        }
        // Climb until we come up from a LEFT child: that parent is the next block in order.
        while (node.parent != null && node.parent.right == node) {
            node = node.parent;
        }
        return node.parent;
    }

    // ------------------------------------------------------------------

    private void registerIndex(Node node) {
        index.computeIfAbsent(node.block.start().replicaId(), key -> new TreeMap<>())
            .put(node.block.start().lamport(), node);
    }

    private void unregisterIndex(Node node) {
        NavigableMap<Long, Node> byLamport = index.get(node.block.start().replicaId());
        if (byLamport != null) {
            byLamport.remove(node.block.start().lamport());
        }
    }
}
