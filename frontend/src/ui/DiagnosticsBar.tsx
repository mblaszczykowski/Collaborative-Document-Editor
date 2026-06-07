import type { RgaDocument } from '../crdt/document'
import type { NetworkConditions } from '../transport/simulator'

/**
 * Pasek diagnostyczny — WYŁĄCZNIE przy `?debug=1`.
 *
 * Symulator sieci napędza demo i testy chaosu, a podgląd stanu CRDT był nieoceniony przy
 * pracy nad algorytmem. Żadna z tych rzeczy nie ma prawa pojawić się przed użytkownikiem:
 * pierwsza sprawia, że produkt wygląda na stanowisko laboratoryjne, druga posługuje się
 * pojęciami, których nie ma obowiązku znać.
 */
export function DiagnosticsBar(props: {
  network: NetworkConditions
  onChange: (patch: Partial<NetworkConditions>) => void
  document: RgaDocument | null
  characters: number
}) {
  const { network, onChange, document: model } = props
  return (
    <div className="diagnostics">
      <span className="diag-label">DIAGNOSTYKA</span>

      <label className="diag-slider">
        opóźnienie
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

      <label className="diag-slider">
        strata
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
        {network.partitioned ? 'Przywróć łączność' : 'Odetnij sieć'}
      </button>

      {model && (
        <span className="diag-stats">
          bloki {model.blockCount} · tombstony {model.tombstoneCount} · bez przyczyny{' '}
          {model.pendingCount}
        </span>
      )}
    </div>
  )
}
