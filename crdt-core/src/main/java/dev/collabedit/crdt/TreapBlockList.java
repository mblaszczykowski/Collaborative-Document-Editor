package dev.collabedit.crdt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Implementacja {@link BlockList} oparta na treapie z agregatami w węzłach — wersja O(log n).
 *
 * <p>Powstała <b>po</b> {@link ArrayBlockList} i po zmierzeniu jej benchmarkiem JMH. Kolejność
 * jest celowa: najpierw wersja oczywiście poprawna, potem liczby pokazujące, że jest za wolna,
 * dopiero potem optymalizacja — i ten sam zestaw property testów na obu, żeby optymalizacja
 * niczego nie zmieniła w zachowaniu (property {@code naiveAndIndexedAgree}).
 *
 * <h2>Trzy agregaty w każdym węźle</h2>
 * <ul>
 *   <li>{@code size} — liczba bloków w poddrzewie, daje wyszukiwanie po indeksie bloku,</li>
 *   <li>{@code visible} — liczba widocznych znaków, daje wyszukiwanie po pozycji w tekście
 *       <b>z pominięciem tombstone'ów</b> bez ich odwiedzania,</li>
 *   <li>{@code total} — wszystkie znaki, daje pomiar narzutu tombstone'ów w O(1).</li>
 * </ul>
 *
 * <p>Wyszukiwanie po identyfikatorze nie da się zrobić samym drzewem, bo drzewo jest
 * uporządkowane pozycyjnie, a nie po identyfikatorach. Stąd dodatkowa mapa
 * {@code replika → (początkowy lamport → węzeł)}: {@code floorEntry} znajduje kandydata,
 * a wyliczenie jego pozycji odbywa się przez wspinaczkę po wskaźnikach do rodzica.
 *
 * <p>Priorytety kopca są <b>deterministyczne</b> — liczone z identyfikatora bloku, nie losowe.
 * Dzięki temu ta sama sekwencja operacji daje identyczne drzewo przy każdym uruchomieniu,
 * co jest warunkiem odtwarzalności kontrprzykładów z testów property-based.
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
    /** replika → (początkowy lamport bloku → węzeł). Do wyszukiwania po identyfikatorze. */
    private final Map<Integer, NavigableMap<Long, Node>> index = new HashMap<>();

    /**
     * Priorytet kopca wyliczony deterministycznie z identyfikatora (wariant splitmix64).
     * Losowy generator dałby to samo zrównoważenie, ale zabrałby odtwarzalność.
     */
    private static int priorityOf(Id id) {
        long h = id.replicaId() * 0x9E3779B97F4A7C15L + id.lamport() * 0xBF58476D1CE4E5B9L;
        h ^= (h >>> 31);
        h *= 0x94D049BB133111EBL;
        h ^= (h >>> 33);
        return (int) h;
    }

    // ------------------------------------------------------------------
    // Agregaty
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

    /** Aktualizuje agregaty od podanego węzła w górę do korzenia. */
    private static void pullUp(Node from) {
        for (Node node = from; node != null; node = node.parent) {
            pull(node);
        }
    }

    // ------------------------------------------------------------------
    // Rotacje
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
    // Nawigacja
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
        throw new IndexOutOfBoundsException("brak bloku o indeksie " + blockIndex);
    }

    /** Pozycja węzła w porządku in-order — wspinaczka po rodzicach, O(log n). */
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

        // Przywrócenie własności kopca. Rotacje nie zmieniają zawartości poddrzew
        // przodków, więc ich agregaty pozostają poprawne — wystarczy pull w rotacji.
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

        // Zsuwamy węzeł do liścia rotacjami, zawsze podnosząc dziecko o wyższym priorytecie —
        // dzięki temu własność kopca zostaje zachowana bez dodatkowej naprawy.
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
    public void markDeleted(int blockIndex) {
        Node node = nodeAt(blockIndex);
        if (node.block.deleted()) {
            return;
        }
        node.block.markDeleted();
        // Bez tego agregat `visible` w przodkach zostałby nieaktualny, a wyszukiwanie
        // po pozycji zaczęłoby zwracać złe wyniki — cicho i tylko czasem.
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

    @Override
    public BlockList newEmpty() {
        return new TreapBlockList();
    }

    @Override
    public Iterator<Block> iterator() {
        List<Block> ordered = new ArrayList<>(size());
        collect(root, ordered);
        return ordered.iterator();
    }

    private static void collect(Node node, List<Block> into) {
        if (node == null) {
            return;
        }
        collect(node.left, into);
        into.add(node.block);
        collect(node.right, into);
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
