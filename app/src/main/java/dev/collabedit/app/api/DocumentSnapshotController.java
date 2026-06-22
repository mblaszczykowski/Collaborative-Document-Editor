package dev.collabedit.app.api;

import dev.collabedit.app.auth.Capability;
import dev.collabedit.app.auth.RequiresDocumentAccess;
import dev.collabedit.app.domain.DocumentRegistry;
import dev.collabedit.app.domain.DocumentService;
import dev.collabedit.crdt.DocumentSnapshot;
import dev.collabedit.crdt.RgaDocument;
import dev.collabedit.crdt.SnapshotCodec;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The document's state as a CRDT snapshot, for a client that is opening it (CP24).
 *
 * <h2>What this replaces</h2>
 * Until CP24 a client opened a document by asking for <b>every operation from zero</b>. A hundred
 * thousand characters typed one at a time is a hundred thousand operations across the socket, each
 * one run through the merge algorithm, to arrive at a state the server was already holding as one
 * object. The server has had snapshots since CP3 and the client had none — the largest remaining
 * product debt by plan v3's own account.
 *
 * <h2>Why the sequence number may lag the snapshot, and why that is safe</h2>
 * The snapshot is taken under the replica's lock and includes operations that have been merged but
 * not yet written, so their {@code server_seq} does not exist yet. The number returned here is the
 * last <b>written</b> one, which means the client resynchronises from a point slightly behind what
 * it already holds and receives a handful of operations twice.
 *
 * <p>That is not a flaw to be fixed with a cleverer boundary: duplicate delivery is free here,
 * because merging is idempotent, while the opposite error — a number ahead of the
 * snapshot — would silently skip operations. When one of two mistakes costs nothing and the other
 * loses text, the boundary belongs on the cheap side of the line.
 *
 * <h2>Binary, not base64 in JSON</h2>
 * The point of this endpoint is bytes on the wire; wrapping them in base64 would add a third for
 * nothing. The sequence number travels in a header for the same reason.
 */
@RestController
public class DocumentSnapshotController {

    /**
     * The last written sequence number this snapshot is guaranteed to cover.
     *
     * <p>Exposed through the CORS mapping in {@code WebMvcConfig}, because a browser hides every response header a
     * cross-origin response does not name explicitly — and the snapshot without this number is
     * useless: the client would not know where to resynchronise from.
     */
    public static final String THROUGH_SEQ_HEADER = "X-Through-Server-Seq";

    private final DocumentRegistry registry;
    private final DocumentService documents;

    public DocumentSnapshotController(DocumentRegistry registry, DocumentService documents) {
        this.registry = registry;
        this.documents = documents;
    }

    @GetMapping(value = "/api/documents/{documentId}/snapshot",
        produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @RequiresDocumentAccess(Capability.READ)
    public ResponseEntity<byte[]> snapshot(@PathVariable("documentId") UUID documentId) {
        // The written sequence number is read BEFORE the snapshot, so it can only be behind the
        // state, never ahead of it — see the class javadoc for why that direction is the safe one.
        long throughServerSeq = documents.headServerSeq(documentId);

        // TAKEN under the lock, ENCODED outside it. The encode used to be inside the lambda, so
        // every document open blocked that document's lock for a full structure walk plus gzip —
        // and the lock it blocks is the one the 10 ms broadcast needs on every pass. Opening a
        // large document therefore added latency to the typing of everybody already in it, which is
        // the one cost this system is least willing to pay.
        //
        // Safe because DocumentSnapshot is fully immutable: its compact constructor copies all four
        // components, and RgaDocument.copyOf copies every block. Nothing the replica does after the
        // lock is released can reach the object being encoded — which is what makes moving the work
        // out a correctness-preserving change rather than a race being introduced for speed.
        DocumentSnapshot snapshot = registry.get(documentId).withLock(RgaDocument::snapshot);
        byte[] encoded = SnapshotCodec.encode(snapshot);

        return ResponseEntity.ok()
            .header(THROUGH_SEQ_HEADER, Long.toString(throughServerSeq))
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(encoded);
    }
}
