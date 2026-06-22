package dev.collabedit.app.ws;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/**
 * The STOMP message protocol.
 *
 * <p>Channels:
 * <pre>
 *   client -> server   /app/doc/{id}/op         {@link ClientOp}
 *                      /app/doc/{id}/sync       {@link SyncRequest}
 * /app/doc/{id}/join (empty body)
 * /app/doc/{id}/progress {@link SyncRequest} (its version vector only)
 *                      /app/doc/{id}/presence   {@link ClientPresence}
 *
 *   server -> clients  /topic/doc/{id}/ops      {@link OpBroadcast}
 *                      /topic/doc/{id}/acks     {@link OpAck}
 *                      /topic/doc/{id}/presence {@link PresenceUpdate}
 *                      /topic/doc/{id}/access   {@link AccessChanged}
 * server -> client /user/queue/session {@link SessionInfo}
 * /user/queue/sync {@link SyncResponse}
 *                      /user/queue/errors       {@link ErrorMessage}
 * </pre>
 *
 * <p>The table is the whole protocol, and {@code DocumentAccessInterceptor.PRIVATE_USER_QUEUES}
 * enumerates the three private destinations above — an unlisted destination is refused, so adding
 * a channel means adding it in both places deliberately.
 *
 * <h2>Why the broadcast and the acknowledgement are TWO messages</h2>
 * The ordering of steps on the server follows from the cluster design: merge -> broadcast to our own clients
 * -> write -> publish to Redis. The broadcast comes before the write, because the broadcast is what
 * determines what the user <b>sees</b> — someone else's text should appear immediately, not after
 * the database confirms.
 *
 * <p>The acknowledgement, however, must come <b>after</b> the write, because only then may a client
 * release the operation from its offline buffer. If it released it on the broadcast, an instance
 * crash between the broadcast and the write would mean an operation others already see and nobody
 * can reproduce. That is exactly the failure mode described in the README.
 *
 * <p>Hence two messages rather than one enriched one. The price: an extra frame of some 30 bytes
 * per operation. The gain: propagation does not wait on disk, and nothing is lost regardless.
 *
 * <h2>Why acknowledgements go to a topic rather than to one client</h2>
 * An acknowledgement carries {@code serverSeq}, which is the resynchronisation cursor. Broadcasting
 * it to everyone tells every client how far the log has got — including a client that was not
 * writing anything. After a disconnect it can then ask for the missing operations from the right
 * place instead of downloading the whole history.
 */
public final class Messages {

    private Messages() {
    }

    // ------------------------------------------------------------------
    // client -> server
    // ------------------------------------------------------------------

    /** A CRDT operation in the {@code OpCodec} format (single-letter fields). */
    public record ClientOp(JsonNode op) {
    }

    /**
     * A request for the missing operations after reconnecting.
     *
     * @param sinceServerSeq the last sequence number the client definitely holds; 0 means "send
     *                       everything"
     * @param version        the client's version vector — used to compute causal stability for GC
     *, not to choose which operations to send
     */
    public record SyncRequest(long sinceServerSeq, Map<Integer, Long> version) {
    }

    /**
     * The cursor and selection position.
     *
     * <p>The positions are <b>CRDT character identifiers</b>, not offsets. An offset
     * drifts when someone inserts text before the cursor — an identifier moves with the text,
     * because it is attached to a character rather than to a place.
     */
    public record ClientPresence(
        Integer cursorReplicaId, Long cursorLamport,
        Integer anchorReplicaId, Long anchorLamport,
        boolean typing
    ) {
    }

    // ------------------------------------------------------------------
    // server -> clients
    // ------------------------------------------------------------------

    /**
     * An operation broadcast. No {@code serverSeq} — that arrives in {@link OpAck}.
     *
     * <p>It used to carry a {@code userId} too, hard-coded to {@code null} at its only construction
     * site and read by nobody on the client. A nullable field that is always null is a field
     * somebody will eventually trust.
     */
    public record OpBroadcast(JsonNode op) {
    }

    /** Confirmation of a durable write. {@code r}/{@code l} is the operation's identifier. */
    public record OpAck(int r, long l, long serverSeq) {
    }

    /**
     * A chunk of the operations a client asked for, plus how far the log reaches.
     *
     * @param last whether this is the FINAL chunk of the response. The client advances its cursor
     * to {@code headServerSeq} only when it sees this — see {@code onSyncResponse}.
     * Every chunk used to carry the same head and the client moved its cursor on each
     * one, so losing chunk 2 of 5 left it believing it held everything up to the head.
     * Nothing could ever detect that: the only gap detector fires on an acknowledgement
     * for an operation the client does not have, and operations in a lost sync chunk
     * were acknowledged long ago and are never acknowledged again. On a quiet document
     * the client stayed wrong for good.
     */
    public record SyncResponse(List<JsonNode> ops, long headServerSeq, boolean last) {
    }

    /**
     * The response to joining: the replica identifier assigned to this session.
     *
     * The client MUST NOT pick one at random. Replica identifiers have to be globally unique,
     * because a collision makes the algorithm treat two different characters as the same one and
     * silently lose one author's text — a bug that leaves no trace in the structure. Hence a single
     * counter in Redis hands them out.
     */
    public record SessionInfo(int replicaId, String userId) {
    }

    public record PresenceBroadcast(
        int replicaId,
        String userId,
        String displayName,
        String color,
        Integer cursorReplicaId, Long cursorLamport,
        Integer anchorReplicaId, Long anchorLamport,
        boolean typing,
        boolean online
    ) {
    }

    /**
     * A presence change — either the full list or a single entry.
     *
     * <h2>Why this distinction exists at all</h2>
     * Cursor movement is the <b>most frequent event in the whole system</b>: it happens on every
     * keystroke and on every arrow-key movement (key auto-repeat is ~30 events per second). Sending
     * the FULL participant list to EVERYONE in response gives traffic growing with the square of
     * the number of people, 99% of whose content did not change.
     *
     * <p>Joining and leaving are rare and do need the full list, because a leave has to be
     * expressed by the <b>absence</b> of an entry — a delta cannot say "this person is gone" unless
     * we added a third message type. At that frequency the full list is cheaper than an extra type
     * in the protocol.
     *
     * <p>Since CP26 the list spans instances: what arrives here is this instance's participants
     * merged with what the other instances last announced (PresenceBus).
     *
     * @param full     {@code true} -> replace the whole list; {@code false} -> merge by
     *                 {@code replicaId}
     * @param entries  the participants: all of them when {@code full}, one when a delta
     * @param complete {@code false} when this instance could not reach the cluster channel, so the
     *                 list may be missing people connected elsewhere (CP26). The one place in this
     *                 system where best-effort is shown to the user instead of being hidden behind
     *                 convergence — a participant list that is quietly short looks exactly like a
     *                 document nobody else is in
     */
    public record PresenceUpdate(boolean full, List<PresenceBroadcast> entries, boolean complete) {
    }

    /**
     * Access to this document changed — <b>and deliberately says nothing else</b> (CP21).
     *
     * <p>Sent on {@code /topic/doc/{id}/access} after a grant, a revocation or a visibility
     * change. Every client re-asks the server what its own role is now.
     *
     * <p>The first version carried the affected user's identifier and their new role, and a
     * review of CP21 found that this contradicted the checkpoint's own decision one file away:
     * reading the member list requires {@code MANAGE_ACCESS}, because "who has access to this" is
     * a different question from "what does this say". Broadcasting a name and a role to every
     * subscriber answered that question for anybody holding the document open — including
     * viewers, and including about people who are not in the room.
     *
     * <p>The cost of an empty frame is one small request per connected client per access change,
     * which is a rare event. The alternative was a channel that leaks exactly what the endpoint
     * beside it refuses to hand out.
     */
    public record AccessChanged() {
    }

    /**
     * An error the client has to react to.
     *
     * <p>The codes, and what each one asks of the client:
     * <table>
     * <caption>code -> what the client must do</caption>
     * <tr><th>{@code code}</th><th>Meaning</th></tr>
     * <tr><td>{@code RATE_LIMITED}</td>
     * <td>Wait {@code retryAfterMillis} and resend. The only code where time helps, and the
     * only one that sets that field.</td></tr>
     * <tr><td>{@code RESYNC_REQUIRED}</td>
     * <td>The situation from the tombstone-collection design: the client was offline for longer than the GC window
     * and its operations point at tombstones that no longer exist. The server
     * <b>rejects them explicitly</b>, rather than gluing the text into a random place or
     * silently dropping it — both alternatives are worse than an honest "I cannot merge
     * this". Also sent when the replica is already holding the maximum number of
     * operations waiting for a cause.</td></tr>
     * <tr><td>{@code SNAPSHOT_REQUIRED}</td>
     * <td>The client asked to resynchronise from a cursor so far behind that the answer would
     * exceed {@code max-sync-operations}. <b>No operations are sent with it</b> — the
     * whole point is that a truncated response would be indistinguishable from a complete
     * one, which is the CP8 failure at chunk granularity. The client re-opens through
     * {@code GET /api/documents/{id}/snapshot}, which is O(document size) rather than
     * O(log size) and is exactly why CP24 built it.</td></tr>
     * <tr><td>{@code REPLICA_ID_CONFLICT}</td>
     * <td>The operation was submitted under a replica identifier that belongs to another
     * user. Neither a retry nor a resynchronisation helps; the session has to obtain a
     * fresh identifier, which means reconnecting.</td></tr>
     * <tr><td>{@code BAD_OPERATION} / {@code OPERATION_REJECTED}</td>
     * <td>The frame could not be decoded, or the merge threw. Both are bugs somewhere rather
     * than states with a recovery.</td></tr>
     * </table>
     *
     * @param retryAfterMillis how long the client should wait before retrying, or 0 when
     *        waiting will not help. Only {@code RATE_LIMITED} sets it: a refusal for lack of
     *        permission is not something time fixes, and telling the client to try again later
     *        would be a lie plus a hint that the document exists.
     */
    public record ErrorMessage(String code, String message, long retryAfterMillis) {

        public ErrorMessage(String code, String message) {
            this(code, message, 0);
        }
    }
}
