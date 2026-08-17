import type { RgaDocument } from '../crdt/document'
import type { NetworkConditions } from '../transport/simulator'

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

      {
}
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
