# ADR-0010 — Formatowanie tekstu jako osobna warstwa CRDT

- **Status:** Zaakceptowany
- **Data:** 2026-08-15
- **Dotyczy checkpointów:** CP13 (implementacja), CP14 (komentarze reużywają kotwic), CP15 (pomiar)

---

## Kontekst

[ADR-0008](0008-rozszerzenie-zakresu.md) rozszerzył zakres o rich text, uzasadniając to tym,
że jest to **problem algorytmiczny, a nie produktowy**. Ten dokument opisuje, na czym ten
problem polega i dlaczego rozwiązanie wyglądające na oczywiste jest błędne.

Sytuacja do rozstrzygnięcia jest jedna i wystarcza za całą specyfikację:

> Ania pogrubia zakres tekstu. Bartek — nie widząc jeszcze jej zmiany — wpisuje tekst
> w środek tego zakresu. Co powinno się stać z formatowaniem wstawionego tekstu?

Odpowiedź, której oczekuje użytkownik: wstawiony tekst jest pogrubiony, bo znalazł się
w środku pogrubionego fragmentu. Odpowiedź, którą daje naiwna implementacja: nie jest,
bo w chwili powstania operacji pogrubienie do Bartka nie dotarło.

---

## Rozważane opcje

### Opcja A — Formatowanie jako atrybut znaku

Każdy znak niesie zbiór znaczników. Pogrubienie zakresu to operacja ustawiająca atrybut
na wymienionych znakach.

**Za:** trywialne w implementacji, zero nowych pojęć, zerowy koszt wyliczania wyglądu.

**Przeciw — i to jest decydujące:** **nie rozwiązuje sytuacji z kontekstu.** Operacja Ani
wymienia znaki, które istniały w chwili jej powstania. Znaki Bartka nie są w tej liście
i nie mają jak się w niej znaleźć, bo powstały równolegle. Wynik: dziura w pogrubieniu,
której nikt nie zrobił i której nie da się wyjaśnić użytkownikowi.

Nie jest to problem do obejścia „doklejaniem atrybutu przy wstawianiu", bo replika wstawiająca
o pogrubieniu nie wie — dowie się dopiero za chwilę.

### Opcja B — Zakres zapisany jako para offsetów

Znacznik trzyma pozycje początku i końca.

**Przeciw:** offsety rozjeżdżają się przy każdej cudzej edycji. Jest to ten sam błąd, który
[ADR-0006](0006-warstwa-edytora.md) odrzucił przy kursorach, tylko groźniejszy: kursor
w złym miejscu jest widoczny natychmiast, a formatowanie w złym miejscu wygląda jak decyzja
autora.

### Opcja C — Zakres między kotwicami zaczepionymi w znakach (Peritext)

Znacznik to osobna operacja: `(typ, wartość, kotwica początkowa, kotwica końcowa)`, gdzie
kotwica wskazuje **znak** i **stronę** tego znaku. Wygląd tekstu jest wyliczany przy
renderowaniu, a nie przechowywany.

**Za:** rozwiązuje sytuację z kontekstu bez żadnego przypadku szczególnego — kotwice
przesuwają się razem z tekstem, więc tekst wstawiony w środek zakresu **jest** w środku
zakresu, niezależnie od kolejności dostarczenia operacji.

**Przeciw:** więcej pojęć, wyliczanie wyglądu przy każdym renderowaniu, i — co okazało się
najdotkliwsze — pogorszenie skuteczności GC.

---

## Decyzja

**Opcja C.** Wraz z dwoma rozstrzygnięciami, które z niej wynikają:

### Strona kotwicy decyduje o rozszerzaniu

Kotwica „za znakiem X" i „przed znakiem Y" wskazują to samo miejsce w tekście `XY` —
**dopóki nikt nie wstawi czegoś między nie**. Wtedy się rozchodzą:

```
tekst:            a [b] c          (pogrubione „b")

koniec = ZA „b":     wpisanie „x" → a [b] x c     — pogrubienie NIE rośnie
koniec = PRZED „c":  wpisanie „x" → a [b x] c     — pogrubienie ROŚNIE
```

Cała różnica między „pogrubienie rośnie, gdy dopisuję na jego końcu" a „link nie rośnie"
to wybór jednej strony jednej kotwicy. Alternatywą byłoby osobne pole „czy rozszerzać",
które opisuje to samo — tylko w oderwaniu od tego, gdzie kotwica faktycznie jest, więc
gorzej.

**Rozszerzanie jest własnością TYPU znacznika, nie operacji.** Użytkownik nie podejmuje tej
decyzji przy każdym pogrubieniu; ma oczekiwanie związane z rodzajem formatowania. Gdyby to
była własność operacji, dwa pogrubienia w jednym dokumencie mogłyby zachowywać się różnie,
a użytkownik nie miałby jak przewidzieć którego.

### Konflikty rozstrzyga porządek, który już mamy

Dla danego znaku i typu znacznika wygrywa operacja o najwyższym `(lamport, replicaId)`
spośród obejmujących ten znak. Jest to **ten sam porządek totalny**, którego używa reguła
wstawiania RGA — bez nowego mechanizmu arbitrażu i bez nowego założenia o zegarze.

Zdjęcie formatowania jest operacją **zakresową**, nie referencją do konkretnego nadania.
Powód: zdejmowanie nie jest cofaniem cudzej operacji. Dwoje ludzi może pogrubić zachodzące
na siebie zakresy, a trzeci odgrubić ich część wspólną — nie ma wtedy jednej operacji,
którą dałoby się wskazać.

---

## Konsekwencje

### Pozytywne
- Sytuacja z kontekstu działa bez przypadku szczególnego. Jest to wprost sprawdzone
  wektorem konformancyjnym `201-mark-inherited-by-concurrent-insert`.
- Sekwencja znaków **nie zmieniła się ani o jedno pole**. `Block` i `BlockList` są w CP13
  identyczne jak w CP2 — co jest najlepszym dowodem, że warstwy są rzeczywiście rozdzielone.
- Warstwa kotwic jest gotowa pod komentarze (CP14) bez żadnej dodatkowej pracy: komentarz
  to zakotwiczony zakres, czyli dokładnie to samo pojęcie.
- Podniesienie wersji formatu snapshotu okazało się darmowe dzięki decyzji z ADR-0003
  (snapshot niezgodny jest ignorowany, nie migrowany).

### Negatywne

- **GC tombstone'ów stał się słabszy proporcjonalnie do ilości formatowania.** Każdy zakres
  przypina dwa tombstone'y, zwykle w środku tekstu. Szczegóły i kierunek rozwiązania
  w [ADR-0005](0005-tombstone-gc.md), sekcja „Zweryfikowane w CP13".
- **Wyliczanie wyglądu jest O(m²)** przy `m` operacjach znacznikowych. Przy setkach znaczników
  to ułamek milisekundy, przy dziesiątkach tysięcy — zauważalne. Właściwe rozwiązanie to
  zamiatanie z kopcem aktywnych zakresów, `O(m log m)`. **Niezrobione**, bo wymaga pomiaru,
  którego nie mam. Do CP15.
- **Zakres kończący się na samym końcu dokumentu nie może rosnąć**, bo nie ma znaku, o który
  zaczepić kotwicę rozszerzającą. Peritext używa tam wartownika końca dokumentu; tutaj go nie
  ma, więc pogrubienie postawione na ostatnim słowie nie obejmie tekstu dopisanego po nim.
- **Tekst wpisany w miejsce skasowanego sformatowanego fragmentu nie dziedziczy formatowania.**
  Wstawienie ląduje przed tombstone'ami (reguła RGA), czyli poza zakresem. Przewidywalne
  i wynikające wprost z algorytmu, ale różne od zachowania edytorów opartych na offsetach.
  Utrwalone testem, żeby zmiana wymagała świadomej decyzji.
- **Dług dwóch implementacji urósł.** Warstwa znaczników istnieje w Javie i w TypeScripcie,
  bo klient musi wyliczać wygląd offline. Korpus konformancyjny chroni przed rozjazdem
  operacji **już utworzonych**, ale nie przed rozjazdem tabeli typów rozszerzalnych — ta jest
  używana przy TWORZENIU operacji, więc wektory jej nie widzą. Pilnuje jej osobny test
  jednostkowy po obu stronach, co jest zabezpieczeniem słabszym.

### Do zweryfikowania w CP15
- Ile realnie kosztuje wyliczanie wyglądu przy dokumencie z setkami znaczników.
- Ile pamięci zajmują tombstone'y przypięte kotwicami — czyli czy przeprojektowanie
  reprezentacji bloku (ADR-0005) jest warte swojego kosztu.
- Jak formatowanie zmienia profil ruchu i liczby propagacji z README.
