import type { RgaDocument } from '../crdt/document'
import type { NetworkConditions } from '../transport/simulator'

/**
 * The diagnostics bar — ONLY under `?debug=1`.
 *
 * The network simulator drives the demo and the chaos tests, and the CRDT state view was invaluable
 * while working on the algorithm. Neither has any business appearing in front of a user: the first
 * makes the product look like a laboratory rig, the second uses concepts the user has no obligation
 * to know.
 */
export function DiagnosticsBar(props: {
  network: NetworkConditions
  onChange: (patch: Partial<NetworkConditions>) => void
  document: RgaDocument | null
}) {
  const { network, onChange, document: model } = props
  return (
    <div className="diagnostics">
      <span className="diag-label">DIAGNOSTICS</span>

      <label className="diag-slider">
        latency
        <input
          type="range"
          min={0}
          max={2000}
          step={50}
          value={network.latencyMs}
          onChange={(event) => onChange({ latencyMs: Number(event.target.value) })}
        />
        <span className="value">{network.latencyMs} ms</span>
      </label>

      {/* The slider `NetworkConditions.jitterMs` was documented as having and did not have.
          Nothing in the application, the tests or the harness ever moved that field off zero, so
          the one condition that induces REORDERING was unreachable — and reordering is the
          property the whole CRDT argument rests on. Latency alone delays messages without
          changing their order, which demonstrates patience rather than convergence.

          Bounded at 1000 ms rather than at the latency slider's 2000: `deliver` computes
          `latency ± jitter` and clamps at zero, so a spread wider than the delay it is spread
          around does nothing beyond what the top of the range already does. */}
      <label className="diag-slider">
        jitter
        <input
          type="range"
          min={0}
          max={1000}
          step={50}
          value={network.jitterMs}
          onChange={(event) => onChange({ jitterMs: Number(event.target.value) })}
        />
        <span className="value">±{network.jitterMs} ms</span>
      </label>

      <label className="diag-slider">
        loss
        <input
          type="range"
          min={0}
          max={50}
          step={5}
          value={Math.round(network.packetLoss * 100)}
          onChange={(event) => onChange({ packetLoss: Number(event.target.value) / 100 })}
        />
        <span className="value">{Math.round(network.packetLoss * 100)}%</span>
      </label>

      <button
        className={`diag-btn${network.partitioned ? ' on' : ''}`}
        onClick={() => onChange({ partitioned: !network.partitioned })}
      >
        {network.partitioned ? 'Restore connectivity' : 'Cut the network'}
      </button>

      {model && (
        <span className="diag-stats">
          blocks {model.blockCount} · tombstones {model.tombstoneCount} · without cause{' '}
          {model.pendingCount}
        </span>
      )}
    </div>
  )
}
