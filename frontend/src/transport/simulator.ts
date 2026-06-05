/**
 * Symulator warunków sieciowych.
 *
 * Pochodzi wprost z `idea.md` („symulator konfliktów") i jest jedną z dwóch rzeczy, które
 * ten projekt naprawdę sprzedają na rozmowie — bo pozwala **pokazać na żywo**, że algorytm
 * działa, zamiast o tym zapewniać.
 *
 * Świadoma decyzja projektowa: to jest jeden mechanizm o dwóch zastosowaniach.
 *  - w interfejsie napędza panel demo (suwaki opóźnienia, jitteru, utraty pakietów),
 *  - w testach chaosu (CP8) napędza scenariusze zrywania i opóźniania łączności.
 * Dwie osobne implementacje rozjechałyby się, a wtedy demo pokazywałoby co innego niż testy.
 *
 * Symulator działa NAD warstwą transportu, na poziomie pojedynczych wiadomości. Nie udaje
 * utraty pakietów TCP — udaje utratę i opóźnienie *operacji*, czyli dokładnie to, co widzi
 * algorytm. To jest właściwy poziom abstrakcji dla tego, co chcemy zademonstrować.
 */

export interface NetworkConditions {
  /** Stałe opóźnienie w milisekundach, dodawane w obie strony. */
  latencyMs: number
  /** Losowy rozrzut opóźnienia, ± tej wartości. Modeluje niestabilne łącze. */
  jitterMs: number
  /** Udział wiadomości gubionych, 0–1. */
  packetLoss: number
  /**
   * Całkowite odcięcie. Wiadomości nie giną — czekają w kolejce i zostaną wysłane
   * po przywróceniu łączności, dokładnie tak jak bufor offline (CP6).
   */
  partitioned: boolean
}

export const PERFECT_NETWORK: NetworkConditions = {
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
   * Funkcja wysyłająca jest podawana RAZ, przy tworzeniu.
   *
   * Wcześniej przyjmowały ją `send` i `update` jako argument — i to był realny błąd
   * projektowy, nie stylistyka: interfejs pozwalał podać przy `update` inną funkcję
   * (albo pustą), przez co operacje wstrzymane na czas odcięcia sieci były po przywróceniu
   * łączności wypuszczane donikąd. Wykryte testem end-to-end. Jedno źródło wysyłki czyni
   * ten błąd niewyrażalnym.
   */
  private readonly send: Send

  constructor(send: Send) {
    this.send = send
  }

  get current(): NetworkConditions {
    return { ...this.conditions }
  }

  get heldCount(): number {
    return this.held.length
  }

  onChange(listener: (conditions: NetworkConditions) => void): () => void {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  update(patch: Partial<NetworkConditions>): void {
    const wasPartitioned = this.conditions.partitioned
    this.conditions = { ...this.conditions, ...patch }

    // Wyjście z partycji: wypuszczamy wszystko, co czekało. To jest miniatura tego,
    // co przy prawdziwym ponownym połączeniu robi bufor offline — i powód, dla którego
    // demo z odcięciem sieci wygląda przekonująco, a nie jak zresetowanie stanu.
    if (wasPartitioned && !this.conditions.partitioned) {
      const queued = this.held
      this.held = []
      for (const payload of queued) this.deliver(payload)
    }
    this.notify()
  }

  /**
   * Przepuszcza wiadomość przez zadane warunki sieciowe.
   *
   * Zwraca natychmiast — opóźnienie jest realizowane przez `setTimeout`, więc kolejność
   * dostarczenia przy niezerowym jitterze może się ZMIENIĆ. To jest zamierzone: przestawienie
   * kolejności to jedna z rzeczy, które CRDT ma znosić, i chcemy ją realnie wywoływać,
   * a nie tylko zakładać.
   */
  transmit(payload: unknown): void {
    if (this.conditions.partitioned) {
      this.held.push(payload)
      this.notify()
      return
    }
    if (this.conditions.packetLoss > 0 && Math.random() < this.conditions.packetLoss) {
      // Wiadomość przepada bez śladu. Odzyskanie jej to zadanie potwierdzeń i mechanizmu
      // resynchronizacji (CP6) — symulator celowo nie pomaga.
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
