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

@RestController
public class DocumentSnapshotController {
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
        long throughServerSeq = documents.headServerSeq(documentId);

        DocumentSnapshot snapshot = registry.get(documentId).withLock(RgaDocument::snapshot);
        byte[] encoded = SnapshotCodec.encode(snapshot);

        return ResponseEntity.ok()
            .header(THROUGH_SEQ_HEADER, Long.toString(throughServerSeq))
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(encoded);
    }
}
