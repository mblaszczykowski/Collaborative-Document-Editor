# Projekt struktury CRDT — decyzje na poziomie implementacji

Uzupełnienie [ADR-0001](adr/0001-crdt-vs-ot.md). ADR odpowiada na pytanie „dlaczego RGA".
Ten dokument odpowiada na pytanie „jak dokładnie", i zapisuje trzy rozstrzygnięcia, które
wyszły dopiero przy pisaniu kodu i które łatwo zrobić źle.

---

## 1. Zegar Lamporta — co faktycznie z niego wynika, a co nie

Reguła wstawiania w RGA brzmi: *znajdź origin, potem idź w prawo tak długo, jak napotkane
elementy mają wyższy znacznik czasu niż wstawiany, i wstaw w pierwszym miejscu, gdzie
napotkany element ma niższy*.

Klasyczny argument za poprawnością tej reguły opiera się na tym, że znacznik jest **zegarem
Lamporta**, czyli że *jeśli E powstało przyczynowo po S, to `ts(E) > ts(S)`*. Dzięki temu
pominięcie rodzeństwa S pociąga za sobą pominięcie całego jego poddrzewa, a skan nie wychodzi
poza poddrzewo originu. Implementacja to realizuje: przy odbiorze operacji zdalnej zegar jest
podbijany do `max(clock, lamport_operacji + 1)`.

### Czego NIE udało się potwierdzić

Pierwsza wersja tego dokumentu twierdziła, że bez podbijania zegara implementacja się
**rozjeżdża**, i podawała kontrprzykład. To twierdzenie było za mocne i zostało wycofane.

Weryfikacja: usunąłem podbijanie zegara z kodu produkcyjnego (mutacja `clock = max(clock, 0)`)
i uruchomiłem wyszukiwanie rozbieżności brute-force — **200 000 losowych scenariuszy**,
3–5 replik, po 30 edycji, z agresywnym przeplotem generowania i dostawy, pełną wymianą
w losowej kolejności i podwójnym dostarczeniem każdej operacji.

**Wynik: zero rozbieżności, zero operacji utkniętych w buforze.**

Podany wcześniej kontrprzykład również nie odtwarza się przy ręcznym prześledzeniu: repliki
dochodzą do tego samego wyniku, bo funkcja integracji jest deterministyczna względem
*zbioru* obecnych elementów, a nie kolejności ich dostarczenia — a to wystarcza do zbieżności
niezależnie od tego, czy znaczniki respektują przyczynowość.

### Co z tego wynika dla kodu

Podbijanie zegara **zostaje**, ale z uczciwie nazwanego powodu:

- Jest wymogiem opublikowanego dowodu poprawności RGA i standardem w każdej znanej mi
  implementacji tej rodziny.
- Zbieżność bez niego jest w tej implementacji **zaobserwowana, a nie udowodniona**.
  200 tys. losowych scenariuszy to mocna przesłanka, nie dowód — a rezygnacja z warunku,
  na którym opiera się dowód, w zamian za wynik empiryczny, jest złym interesem.
- Gwarantuje własność, na której opierają się rozważania o zachowaniu intencji autora
  (zwartość poddrzew) — nawet jeśli sama zbieżność jej nie potrzebuje.

### Czego to nauczyło o testach

Pierwotny zestaw property testów **nie wykrywał tej mutacji w ogóle** — i to niezależnie
od tego, czy była szkodliwa. Powód był strukturalny: wszystkie scenariusze miały postać
„najpierw wszystkie edycje lokalnie, potem cała wymiana", w której replika nigdy nie generuje
operacji *po* odebraniu cudzej. Reguła podbijania zegara nie była więc wykonywana ani razu.

Stąd property `convergenceWithInterleavedDelivery` i metoda `ReplicaSet.deliverSome` —
scenariusz, w którym repliki wymieniają się operacjami w trakcie pisania. Dziura została
znaleziona przez celowe wprowadzenie mutacji do kodu produkcyjnego i sprawdzenie, czy testy
ją złapią. Nie złapały. Zestaw testów, który przechodzi na uszkodzonym kodzie, nie jest
zestawem testów.

---

## 2. Dlaczego dedup NIE opiera się na version vectorze

Naturalny odruch: trzymać version vector i odrzucać operacje już widziane. To jest niepoprawne
przy zegarze Lamporta, bo **liczniki per replika nie są ciągłe** — zegar skacze przy odbiorze
operacji z innych replik. Replika A może wygenerować operacje o lamportach 1, 2, 47, 48, 103.
Zapis „widziałem od A wszystko do 48" nie odróżnia dziury po zgubionej operacji od dziury
po skoku zegara.

Zamiast tego:

| Typ operacji | Jak wykrywamy duplikat |
|---|---|
| `InsertOp` | Sprawdzamy, czy znak o tym identyfikatorze **już jest w strukturze**. Dokładne, zerowy koszt pamięciowy — struktura i tak indeksuje po identyfikatorach. |
| `DeleteOp` | Nie wykrywamy wcale. Ustawienie tombstone'u drugi raz jest operacją pustą — delete jest idempotentny z natury. |

Version vector nadal istnieje, ale służy do **innych** celów: stabilności przyczynowej dla GC
([ADR-0005](adr/0005-tombstone-gc.md)) i wymiany „co mam" przy reconnect (CP6). Nie jest
mechanizmem poprawności rdzenia.

To rozdzielenie jest celowe i warto je nazwać: **wykrywanie luk w dostawie to zadanie
transportu (`server_seq`, [ADR-0004](adr/0004-skalowanie-poziome.md)), a nie algorytmu.**
Rdzeń CRDT musi być poprawny przy dowolnej kolejności i dowolnym powtórzeniu — i taki jest.

---

## 3. Blok jako jednostka, czyli run-length w praktyce

Dokument nie jest listą znaków, tylko listą **bloków**:

```java
Block { replicaId, startLamport, String text, Id originLeft, boolean deleted }
```

Znak o indeksie `i` wewnątrz bloku ma identyfikator `(replicaId, startLamport + i)`, a jego
originem jest znak `i-1`. Dzięki temu wpisanie 20 znaków pod rząd to **jeden** obiekt i jedna
operacja, a nie 20.

Dwa niezmienniki, które to utrzymują:

1. **Blok jest w całości żywy albo w całości usunięty.** Usunięcie fragmentu bloku dzieli go
   najpierw na części. Alternatywa (tablica flag per znak) komplikuje każdą operację
   indeksowania, a oszczędza niewiele.
2. **Skanowanie przy wstawianiu jest gruboziarniste — po blokach, nie po znakach.** Jest to
   poprawne, bo znaki w bloku mają rosnące lamporty i są przyczynowo połączone: jeśli pierwszy
   znak bloku ma wyższy znacznik niż wstawiany, to wszystkie pozostałe też. Nigdy nie trzeba
   zatrzymać się w środku bloku — chyba że origin wstawianego znaku *jest* w środku bloku,
   a wtedy blok i tak najpierw dzielimy.

**Scalanie z powrotem:** przy wstawianiu na końcu bloku od tej samej repliki, z ciągłym
lamportem i pasującym originem, dopisujemy do istniejącego bloku zamiast tworzyć nowy.
To jest szybka ścieżka dla najczęstszego wzorca — zwykłego pisania.

---

## 4. Dwie implementacje indeksu, jeden algorytm

`RgaDocument` nie wie, jak przechowywana jest lista bloków — dostaje ją przez interfejs
`BlockList`. Istnieją dwie implementacje:

| Implementacja | Wstawienie bloku | Znajdź po offsecie | Znajdź po identyfikatorze |
|---|---|---|---|
| `ArrayBlockList` | O(n) | O(n) | O(n) |
| `TreapBlockList` | O(log n) | O(log n) | O(log n) |

Obie przechodzą **ten sam** zestaw property testów, w tym test `naiveEqualsIndexed`, który
podaje obu identyczną losową sekwencję operacji i porównuje wyniki krok po kroku. To jest
dowód, że optymalizacja niczego nie zmieniła w zachowaniu — a nie nadzieja, że nie zmieniła.

Kolejność prac jest celowa: najpierw wersja naiwna i komplet testów, potem pomiar (JMH),
dopiero potem wersja zoptymalizowana. Historia commitów pokazuje ten ciąg, bo jest on
uczciwszy niż od razu wrzucona sprytna struktura.
