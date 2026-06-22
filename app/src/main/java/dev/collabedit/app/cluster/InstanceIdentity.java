package dev.collabedit.app.cluster;

import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * What this process calls itself on the cluster.
 *
 * <h2>Why this is a bean and not three fields</h2>
 * Redis pub/sub delivers a message back to its publisher, so every channel has to filter out its
 * own traffic. All three buses did that correctly — and each one minted its own
 * {@code UUID.randomUUID} to do it, so one process announced itself under three different names.
 *
 * <p>Nothing was broken by that: each bus compared its own identifier against its own messages. It
 * cost something else. An instance misbehaving on the operation channel and the same instance
 * misbehaving on the presence channel appeared in the logs, in a trace and in another instance's
 * {@code remote} map as three unrelated identities, and nothing could join them up. That is the
 * kind of thing nobody notices until they are diagnosing a cluster at two in the morning.
 *
 * <p>One value, three users, and the concept named once. The three CHANNELS stay separate — the
 * ADRs argue that case individually and it still holds; what was shared by accident is now shared
 * on purpose.
 */
@Component
public class InstanceIdentity {

    /**
     * Random per process rather than derived from a hostname or a pod name.
     *
     * <p>Two instances of this application on one host must not collide, and a restarted instance
     * must not be mistaken for the one it replaced — the presence layer expires an instance that
     * goes quiet, and reusing a name would make the new process inherit the old one's ghosts.
     */
    private final String id = UUID.randomUUID().toString();

    public String id() {
        return id;
    }

    @Override
    public String toString() {
        return "instance " + id;
    }
}
