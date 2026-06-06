# ADR-0002 — Transport czasu rzeczywistego: WebSocket + STOMP

- **Status:** Zaakceptowany
- **Data:** 2026-08-14
- **Dotyczy checkpointów:** CP3 (implementacja), CP6 (reconnect), CP7 (propagacja traceparent)

---

## Kontekst

Potrzebujemy kanału, którym:
1. klient wysyła operacje CRDT do serwera (**upstream**, wysoka częstotliwość — każde
   naciśnięcie klawisza to potencjalna operacja),
2. serwer rozsyła operacje innych userów oraz zdarzenia presence (**downstream**, fan-out
   do N klientów),
3. da się przenieść kontekst śledzenia (`traceparent`) na potrzeby ADR-0007 / CP7,
4. da się wykryć zerwanie połączenia **szybciej niż domyślny timeout TCP**, bo cały mechanizm
   offline (CP6) zależy od tego, że klient wie, że jest offline.

Charakterystyka ruchu: dużo małych wiadomości w obie strony, opóźnienie ważniejsze niż
przepustowość, połączenie długożyjące.

---

## Rozważane opcje

### Opcja A — Raw WebSocket + własny protokół ramek

**Za:**
- Najmniejszy narzut na wiadomość — kilka bajtów nagłówka własnego formatu binarnego.
- Pełna kontrola nad formatem, łatwo o kompaktową serializację operacji.
- Brak zależności od modelu pub/sub narzuconego z zewnątrz.

**Przeciw:**
- Trzeba samodzielnie zbudować: multipleksowanie (jeden socket, wiele dokumentów), routing
  wiadomości do handlerów, ack/correlation-id, heartbeat, nagłówki na metadane.
  To jest dokładnie przepisywanie STOMP-a, tylko gorzej i bez testów.
- Metadane per wiadomość (potrzebne dla `traceparent`) wymagają własnej konwencji.
- Praca włożona w reimplementację warstwy transportowej nie demonstruje niczego, co ten
  projekt ma demonstrować. Trudność ma być w CRDT, nie w ramkowaniu.

### Opcja B — SSE (downstream) + HTTP POST (upstream)

**Za:**
- Przechodzi przez każde proxy i firewall bez negocjacji Upgrade.
- Automatyczny reconnect z `Last-Event-ID` wbudowany w przeglądarkę — de facto darmowa
  resynchronizacja po stronie downstream.
- Prostszy model serwerowy (zwykły HTTP).

**Przeciw:**
- **Kanał upstream jest osobnym połączeniem.** Każde naciśnięcie klawisza to POST — narzut
  nagłówków HTTP przewyższa rozmiar samej operacji, i tracimy kolejność w ramach jednego
  strumienia bez dodatkowej pracy.
- Dwa kanały = dwa niezależne stany połączenia = wykrywanie „jestem offline" robi się
  dwuznaczne (downstream żyje, upstream padł). To bezpośrednio komplikuje CP6.
- Limit połączeń na origin w HTTP/1.1 (6) — kilka otwartych kart dokumentu wyczerpuje pulę.
  Z HTTP/2 to znika, ale nie chcę uzależniać poprawności od wersji protokołu u klienta.

### Opcja C — WebSocket + STOMP (Spring `spring-websocket` + `spring-messaging`)

STOMP to prosty protokół tekstowy nad WebSocketem: ramki z komendą, nagłówkami i ciałem,
z semantyką pub/sub (`SUBSCRIBE /topic/...`, `SEND /app/...`).

**Za:**
- Multipleksowanie i routing dostajemy gotowe — jeden socket obsługuje N dokumentów przez
  osobne destynacje `/topic/doc/{id}/ops`, `/topic/doc/{id}/presence`.
- **Nagłówki na poziomie ramki** — `traceparent` (W3C Trace Context) wkładamy tam wprost,
  bez zaśmiecania payloadu operacji. To realnie upraszcza CP7.
- Pierwszorzędna integracja ze Springiem: `@MessageMapping`, `SimpMessagingTemplate`,
  interceptory kanałów (potrzebne do auth przy `CONNECT` i do propagacji kontekstu OTel).
- Heartbeat jest częścią protokołu, negocjowany przy `CONNECT` — nie trzeba go wymyślać.
- Ustandaryzowany, więc istnieją klienty poza przeglądarką — istotne dla Gatlinga w CP8.

**Przeciw:**
- Ramki tekstowe, więc payload binarny wymaga base64 (~33% narzutu) albo trzymania się JSON-a.
- Narzut nagłówków STOMP na wiadomość jest większy niż w opcji A.
- Kolejna warstwa abstrakcji, którą trzeba rozumieć przy debugowaniu.

### Opcja D — WebTransport / HTTP/3

**Za:** strumienie bez head-of-line blocking, natywnie datagramy — teoretycznie idealne.
**Przeciw:** wsparcie w przeglądarkach niepełne, po stronie Javy brak dojrzałego stacku
serwerowego, wymaga HTTPS z prawdziwym certyfikatem nawet lokalnie. Przedwczesne.

---

## Decyzja

**WebSocket + STOMP (opcja C).**

Payload operacji CRDT: **JSON** w wersji pierwszej. Kompaktowa serializacja binarna
(base64 w ramce STOMP) jest przygotowana w `crdt-core` od CP2, ale włączamy ją dopiero
wtedy, gdy CP8 pokaże liczbami, że rozmiar payloadu faktycznie ogranicza przepustowość.
Optymalizacja bez pomiaru nie wchodzi.

### Parametry i ich uzasadnienie

Żaden z tych parametrów nie jest wartością domyślną „bo tak było w tutorialu".

| Parametr | Wartość | Uzasadnienie |
|---|---|---|
| Heartbeat serwer→klient | **10 000 ms** | Musi być wyraźnie krótszy niż typowy idle timeout pośredników (nginx `proxy_read_timeout` domyślnie 60 s, wiele CDN/LB ma 30–60 s). 10 s daje ~3–6× margines, a przy tysiącu sesji to 100 ramek/s — pomijalne. |
| Heartbeat klient→serwer | **10 000 ms** | Symetrycznie. Serwer uznaje sesję za martwą po przekroczeniu tolerancji, zwalniając wpis presence. |
| Tolerancja braku heartbeatu | **2,5 × interwał (25 s)** | Jeden zgubiony heartbeat nie może zrywać sesji — na mobilnym łączu pojedyncze opóźnienie >10 s jest normalne. Dwa z rzędu to już realna przesłanka. Kompromis: wykrycie zerwania trwa do 25 s, ale fałszywe rozłączenia są rzadkie. Zbyt agresywna wartość jest gorsza niż zbyt luźna, bo każde fałszywe rozłączenie uruchamia pełną resynchronizację (CP6). |
| Limit rozmiaru wiadomości | **64 KiB** | Pojedyncza operacja to rzędu 100 B; 64 KiB mieści też duże wklejenie tekstu. Wyższy limit to wektor DoS przy braku rate limitingu (świadome uproszczenie, patrz README). |
| Bufor wysyłkowy sesji | **512 KiB** | Wolny klient nie może zablokować broadcastu do reszty. Po przepełnieniu sesja jest zamykana i klient przechodzi ścieżką reconnect+resync z CP6 — kontrolowana degradacja zamiast zatoru. |
| Limit czasu wysyłki | **20 000 ms** | Powyżej tego klient jest uznany za nieodbierającego. |
| SockJS fallback | **wyłączony** | Dodaje ścieżki transportowe (xhr-streaming, jsonp), których nie będziemy testować, więc byłyby nieprzetestowanym kodem w ścieżce krytycznej. WebSocket ma dziś powszechne wsparcie. Świadome ograniczenie zakresu. |

Wartości żyją w `application.yml` z komentarzem przy każdej, nie w kodzie.

---

## Konsekwencje

### Pozytywne
- Nagłówki STOMP dają darmowe miejsce na `traceparent`, więc trace end-to-end (CP7) nie
  wymaga modyfikacji formatu operacji.
- Jeden socket na klienta niezależnie od liczby otwartych dokumentów.
- Interceptor kanału daje jeden punkt na auth (przy `CONNECT`) i jeden na instrumentację —
  zamiast rozsypania po handlerach.

### Negatywne
- **Narzut nagłówków STOMP** na każdą operację jest realny i przy 250 userach × wysoka
  częstotliwość edycji może zdominować ruch. **Do zmierzenia w CP8** — jeśli okaże się
  dominujący, mitygacją jest batchowanie kilku operacji w jedną ramkę, nie zmiana protokołu.
- **Payload JSON, nie binarny** — świadomy dług, do zrewidowania po pomiarze.
- **Brak SockJS** oznacza, że klient za egzotycznym proxy blokującym Upgrade nie połączy się
  wcale, zamiast zdegradować do pollingu.
- Wykrycie zerwanego połączenia zajmuje do 25 s w najgorszym przypadku. Dla użytkownika
  znaczy to, że wskaźnik „offline" w UI może się pojawić z opóźnieniem — mitygacją po stronie
  klienta jest dodatkowo nasłuchiwanie zdarzeń `navigator.onLine` i błędów socketu (CP6).

### Zweryfikowane w CP8

- **Limit 64 KiB na wiadomość okazał się mieć konsekwencję, której nie przewidziałem.**
  Nie dotyczy pojedynczej operacji (te mają ~100 B), tylko **odpowiedzi na resynchronizację**:
  klient proszący o zaległości dużego dokumentu dostawał jedną ramkę grubo powyżej limitu,
  która po prostu nie docierała. Efekt: klient, który najbardziej potrzebował danych, nie mógł
  ich dostać, i zostawał trwale w tyle — bez żadnego błędu w logu. Naprawione wysyłaniem
  w porcjach po 300 operacji. Limit zostaje bez zmian; błędem było założenie, że dotyczy
  wyłącznie ruchu przyrostowego.
- **Bufor 512 KiB nie okazał się wąskim gardłem** przy 50 klientach — statystyki brokera
  nie wykazały ani jednego zamknięcia sesji z powodu przepełnienia. Odnotowano natomiast
  błędy transportu, których źródła nie ustaliłem (patrz README, punkt degradacji).
- **Narzutu STOMP+JSON nie zmierzyłem osobno.** Wyniki pokazały, że wąskim gardłem nie jest
  rozmiar ramki, więc optymalizacja formatu (serializacja binarna zamiast JSON) pozostaje
  nieuzasadniona danymi — czyli dokładnie tam, gdzie powinna zostać.
- **Tolerancji heartbeatu nie udało się sensownie sprawdzić**: klienci i serwer działali na
  jednej maszynie, więc jitter, przed którym ta wartość ma chronić, praktycznie nie występował.
  Zostaje jako wartość uzasadniona rozumowaniem, nie pomiarem.
