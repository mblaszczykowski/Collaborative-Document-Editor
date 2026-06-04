# Projekt struktury CRDT — decyzje na poziomie implementacji

Uzupełnienie [ADR-0001](adr/0001-crdt-vs-ot.md). ADR odpowiada na pytanie „dlaczego RGA".
Ten dokument odpowiada na pytanie „jak dokładnie", i zapisuje trzy rozstrzygnięcia, które
wyszły dopiero przy pisaniu kodu i które łatwo zrobić źle.

---

## 1. Zegar Lamporta jest wymogiem poprawności, nie detalem

Reguła wstawiania w RGA brzmi: *znajdź origin, potem idź w prawo tak długo, jak napotkane
elementy mają wyższy znacznik czasu niż wstawiany, i wstaw w pierwszym miejscu, gdzie
napotkany element ma niższy*.

Ta reguła jest poprawna **tylko** wtedy, gdy znacznik czasu jest zegarem Lamporta, czyli gdy
zachodzi: *jeśli E powstało przyczynowo po S, to `ts(E) > ts(S)`*.

Dlaczego to jest krytyczne — kontrprzykład z licznikiem sekwencyjnym per replika:

```
Origin O. Replika A (licznik 100) wstawia S po O.
Replika B (licznik 3) wstawia X po S.      →  X jest DZIECKIEM S
Replika C (licznik 50) wstawia N po O.     →  N jest RODZEŃSTWEM S

Skan przy wstawianiu N:
  pozycja O+1: S,  ts=(100,A) > (50,C)  → pomijam
  pozycja O+2: X,  ts=(3,B)   < (50,C)  → STOP, wstawiam tutaj

Wynik: O S N X  —  N wylądowało WEWNĄTRZ poddrzewa S.
```

Struktura się rozjeżdża, bo inna replika, widząca te operacje w innej kolejności, dojdzie
do innego wyniku. Z zegarem Lamporta `ts(X) > ts(S)` z definicji, więc X też zostaje pominięty
i N ląduje tam, gdzie powinno — za całym poddrzewem S.

**Konsekwencja implementacyjna:** przy zastosowaniu operacji zdalnej zegar musi zostać
podbity: `clock = max(clock, ostatni_lamport_operacji + 1)`. Pominięcie tego jest błędem,
którego nie widać na testach dwóch replik pisających naprzemiennie — ujawnia się dopiero
przy trzech replikach i konkretnej kolejności. Dokładnie po to jest property test
`convergence` z losowymi permutacjami dostarczenia.

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
