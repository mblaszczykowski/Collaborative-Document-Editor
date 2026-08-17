export interface NetworkConditions {
  latencyMs: number
  jitterMs: number
  packetLoss: number
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

    if (wasPartitioned && !this.conditions.partitioned) {
      const queued = this.held
      this.held = []
      for (const payload of queued) this.deliver(payload)
    }
    this.notify()
  }

  transmit(payload: unknown): void {
    if (this.conditions.partitioned) {
      this.held.push(payload)
      this.notify()
      return
    }
    if (this.conditions.packetLoss > 0 && Math.random() < this.conditions.packetLoss) {
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
