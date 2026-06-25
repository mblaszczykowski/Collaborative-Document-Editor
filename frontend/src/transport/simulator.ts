/**
 * A network conditions simulator.
 *
 * It comes straight from `docs/brief.md` ("the conflict simulator") and is one of the two things
 * that really sell this project in an interview — because it lets you **show live** that the
 * algorithm works, rather than assert it.
 *
 * ## What this file is, and what it is not — a correction
 *
 * This header used to claim the simulator was "one mechanism with two uses", driving both the demo
 * panel and the chaos tests, and that two implementations "would drift apart, and then the demo
 * would show something other than the tests". **That was not true when it was written and is not
 * true now.** The chaos and cluster simulations are Java and live in
 * `app/src/test/java/dev/collabedit/app/simulation/` — `DeterministicSimulation`, `SimulatedCluster`,
 * `ClusterSimulationTest`. There are, and always were, two implementations.
 *
 * They are two on purpose, and the reason is the thing the old comment got backwards: they do not
 * simulate the same layer. The Java one is **deterministic** — a seed, a virtual clock, a
 * reproducible interleaving — because a chaos test that cannot be replayed is a chaos test that
 * cannot be debugged. This one is driven by `Math.random` and by a person moving a slider, because
 * its output is a screen somebody is watching. A shared implementation would have to be one or the
 * other, and neither choice serves the other use.
 *
 * The drift they can genuinely suffer is in what they MODEL — delay, loss, reordering, partition —
 * and both model exactly that list, which is short enough to keep in step by reading it.
 *
 * ## The level it operates at
 *
 * ABOVE the transport layer, at the level of individual messages. It does not fake TCP packet loss
 * — it fakes the loss and delay of *operations*, which is exactly what the algorithm sees. That is
 * the right level of abstraction for what we want to demonstrate.
 */

export interface NetworkConditions {
  /** A fixed delay in milliseconds, added in both directions. */
  latencyMs: number
  /**
   * Random spread of the delay, ± this value. Models an unstable link.
   *
   * Until now **nothing set it**: it was declared, defaulted to 0 and consumed in `deliver`, and
   * there was no slider, no test and no harness that ever moved it off zero. The header two
   * paragraphs up claimed a jitter slider existed. The choice was between deleting the field and
   * giving it the producer the documentation already promised, and the slider won — jitter is the
   * only condition here that induces REORDERING, which is the property a CRDT exists to tolerate
   * and the one this demo is least able to argue for by talking. Deleting it would have removed
   * the ability to show the interesting half of the claim.
   */
  jitterMs: number
  /** The fraction of messages lost, 0–1. */
  packetLoss: number
  /**
   * A total cut. Messages are not lost — they wait in a queue and are sent once connectivity
   * returns, exactly like the offline buffer (CP6).
   */
  partitioned: boolean
}

const PERFECT_NETWORK: NetworkConditions = {
  latencyMs: 0,
  jitterMs: 0,
  packetLoss: 0,
  partitioned: false,
}

type Send = (payload: unknown) => void

export class NetworkSimulator {
  private conditions: NetworkConditions = { ...PERFECT_NETWORK }
  private held: unknown[] = []
  private listeners = new Set<(conditions: NetworkConditions) => void>()

  /**
   * The send function is supplied ONCE, at construction.
   *
   * It used to be taken as an argument by `send` and `update` — and that was a real design bug, not
   * a matter of style: the interface allowed a different function (or an empty one) to be passed at
   * `update`, so operations held during a network cut were released into nothing once connectivity
   * returned. Found by an end-to-end test. A single send source makes the bug inexpressible.
   */
  private readonly send: Send

  constructor(send: Send) {
    this.send = send
  }

  get current(): NetworkConditions {
    return { ...this.conditions }
  }

  onChange(listener: (conditions: NetworkConditions) => void): () => void {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  update(patch: Partial<NetworkConditions>): void {
    const wasPartitioned = this.conditions.partitioned
    this.conditions = { ...this.conditions, ...patch }

    // Leaving the partition: everything that was waiting is released. This is a miniature of what
    // the offline buffer does on a real reconnect — and the reason the network-cut demo looks
    // convincing rather than like a state reset.
    if (wasPartitioned && !this.conditions.partitioned) {
      const queued = this.held
      this.held = []
      for (const payload of queued) this.deliver(payload)
    }
    this.notify()
  }

  /**
   * Passes a message through the configured network conditions.
   *
   * Returns immediately — the delay is realised with `setTimeout`, so with non-zero jitter the
   * delivery order can CHANGE. That is intended: reordering is one of the things a CRDT is supposed
   * to tolerate, and we want to actually induce it rather than merely assume it.
   */
  transmit(payload: unknown): void {
    if (this.conditions.partitioned) {
      this.held.push(payload)
      this.notify()
      return
    }
    if (this.conditions.packetLoss > 0 && Math.random() < this.conditions.packetLoss) {
      // The message is gone without a trace. Recovering it is the job of the acknowledgements and
      // the resynchronisation mechanism (CP6) — the simulator deliberately does not help.
      return
    }
    this.deliver(payload)
  }

  private deliver(payload: unknown): void {
    const jitter = this.conditions.jitterMs > 0
      ? (Math.random() * 2 - 1) * this.conditions.jitterMs
      : 0
    const delay = Math.max(0, this.conditions.latencyMs + jitter)
    if (delay === 0) {
      this.send(payload)
      return
    }
    setTimeout(() => this.send(payload), delay)
  }

  private notify(): void {
    for (const listener of this.listeners) listener(this.current)
  }
}
