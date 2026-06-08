# ADR-0008 — Rozszerzenie zakresu z artefaktu do produktu

- **Status:** Zaakceptowany
- **Data:** 2026-08-15
- **Dotyczy checkpointów:** CP10–CP15
- **Zastępuje:** [ADR-0007](0007-zakres-ui.md) w części dotyczącej zakresu funkcjonalnego

---

## Uczciwa uwaga o kolejności

[Plan v2](../PLAN-v2.md) zakładał, że ten dokument powstanie **przed** pierwszą linijką
kodu CP10. Powstał po zamknięciu CP10, CP11 i CP12 — czyli reguła z `CLAUDE.md`
(„ADR przed kodem domenowym") została w tym jednym przypadku złamana.

Zapisuję to, zamiast antydatować, bo cała wartość tego katalogu polega na tym, że opisuje,
jak było. ADR napisany po fakcie jest wart mniej niż napisany przed — i czytelnik ma prawo
o tym wiedzieć, żeby odpowiednio zważyć zawarte niżej „rozważane opcje".

---

## Kontekst

Wersja 1 osiągnęła swój cel. Rdzeń CRDT jest udowodniony property testami, których zęby
sprawdzono mutacją; zbieżność jest zmierzona; granice są nazwane, łącznie z anomalią
przeplotu RGA i ograniczoną skutecznością GC. Ostatnie otwarte pytanie — brak powtarzalnej
zbieżności powyżej dziesięciu klientów — zostało w CP10 rozstrzygnięte i okazało się wadą
stanowiska pomiarowego, nie systemu.

[ADR-0007](0007-zakres-ui.md) odrzucił zakres produktowy mockupu: rich text, komentarze,
wiele dokumentów, uprawnienia. Decyzja była słuszna i nadal by taka była, gdyby rdzeń
był niegotowy. **Przestała być słuszna w momencie, w którym rdzeń jest gotowy** — dalsze
pogłębianie tego samego miejsca ma malejący zwrot, a cztery odrzucone funkcje są jedynym
kierunkiem, w którym ten rdzeń da się *wykorzystać*, a nie tylko pokazać.

Pytanie brzmi: **czy rozszerzać, a jeśli tak, to o co?**

---

## Rozważane opcje

### Opcja A — Zostać przy v1 i pogłębiać rdzeń

Fugue zamiast RGA (usuwa anomalię przeplotu), undo świadome CRDT, przeprojektowanie
reprezentacji bloku pod skuteczniejszy GC.

**Za:**
- Projekt zachowuje jedno zdanie opisu: „własna implementacja CRDT, zbadana do dna".
  To jest jego największa siła i najłatwiej ją stracić przez rozcieńczenie.
- Każda z tych rzeczy jest trudna i mierzalna — czyli dokładnie tym, co ten projekt
  umie pokazać najlepiej.
- Zero ryzyka, że powstanie „kolejny klon Google Docs, tylko gorszy".

**Przeciw:**
- Malejący zwrot. Druga implementacja algorytmu sekwencji dowodzi tego samego, co pierwsza,
  tylko dokładniej. Czytelnik, który uwierzył w pierwszą, nie potrzebuje drugiej.
- Rdzeń bez zastosowania pozostaje ćwiczeniem. Pytanie „czy to się do czegoś nadaje"
  zostaje bez odpowiedzi.

### Opcja B — Rozszerzyć wybiórczo: tylko wiele dokumentów i uprawnienia

Bez rich textu i komentarzy.

**Za:** najtańsze, a domyka projekt produktowo — dokumenty, dostęp, współdzielenie.
**Przeciw:** to jest praca *wokół* rdzenia, a nie *w* nim. Nic nie dokłada do tezy projektu;
CRUD i autoryzacja są kompetencją, którą widać w każdym CV.

### Opcja C — Rozszerzyć w pełni: rich text, komentarze, wiele dokumentów, uprawnienia

**Za:**
- **Rich text jest problemem algorytmicznym, nie produktowym.** Znaczniki formatowania
  muszą przetrwać współbieżne wstawienia w środek zakresu, czego atrybuty per znak nie
  zapewniają. To jest druga trudna rzecz w tym projekcie, tej samej klasy co pierwsza,
  i wprost pogłębia tezę zamiast ją rozcieńczać.
- Komentarze reużywają warstwę kotwic z rich textu — koszt krańcowy jest niski,
  a funkcja produktowo widoczna.
- Uprawnienia zmuszają do autoryzacji **per typ operacji**, bo rola „komentujący" nie da się
  wyrazić na poziomie kanału. To jest nieoczywisty problem projektowy, a nie CRUD.

**Przeciw:**
- Największy zakres i największe ryzyko rozcieńczenia.
- Podwójny koszt każdej zmiany algorytmu (Java + TypeScript) obejmie także znaczniki.
- GC tombstone'ów, już dziś słaby, stanie się jeszcze słabszy.

---

## Decyzja

**Opcja C.** Z jednym twardym warunkiem, który jest częścią tej decyzji, a nie życzeniem:

> **README ma nadal otwierać się rdzeniem i demem, a nie listą funkcji.** Rich text ma być
> przedstawiony jako *konsekwencja* dobrego rdzenia — „ten sam algorytm unosi formatowanie" —
> a nie jako osobna pozycja na liście możliwości.

Kolejność checkpointów wynika z trzech twardych zależności opisanych w [planie](../PLAN-v2.md):
fundament przed wszystkim (bo rich text zwielokrotni ruch), wiele dokumentów przed
uprawnieniami (bo bez wielu dokumentów uprawnienia nie mają czego chronić), uprawnienia
przed rich textem (bo autoryzacja musi objąć typy operacji), rich text przed komentarzami
(bo komentarz to zakotwiczony zakres i reużywa tamtą warstwę).

---

## Konsekwencje

### Pozytywne
- Projekt odpowiada na pytanie „czy ten rdzeń się do czegoś nadaje", zamiast zostawiać je otwarte.
- Rich text dokłada drugi trudny problem algorytmiczny zamiast pracy dookoła.
- Uprawnienia wymusiły autoryzację per typ operacji — problem projektowy, który przy
  samym CRUD-zie by się nie pojawił.

### Negatywne

- **Ryzyko rozcieńczenia jest realne i nie znika przez zapisanie warunku.** Recenzent
  z trzema minutami może wyjść z „kolejny edytor" zamiast „ktoś rozumie CRDT".
  **Do zweryfikowania w CP15:** czy README nadal czyta się jako jedna całość.
- **Podwójny koszt implementacji rośnie proporcjonalnie do zakresu.** Znaczniki formatowania
  muszą powstać w Javie i w TypeScripcie, a korpus konformancyjny — urosnąć o ich wektory.
- **GC tombstone'ów stanie się jeszcze mniej skuteczny.** [ADR-0005](0005-tombstone-gc.md)
  zabrania usuwania tombstone'u będącego originem; po CP13 dojdzie „będącego kotwicą
  znacznika", po CP14 „kotwicą komentarza". Może to wymusić przeprojektowanie reprezentacji
  bloku, które ADR-0005 odkładał. **Decyzja do podjęcia na starcie CP13, nie w trakcie.**
- **Wszystkie liczby wydajnościowe stają się nieaktualne** po CP13, bo rich text zmienia
  profil ruchu. Dlatego CP15 (ponowny pomiar) jest w planie obowiązkowy, a nie opcjonalny.
- **`CLAUDE.md` przestaje odpowiadać projektowi** w sekcji „Czego unikać" i wymaga poprawki
  razem z tym ADR-em. Instrukcja odradzająca to, co repozytorium właśnie robi, jest gorsza
  niż jej brak.

### Zweryfikowane po CP10–CP12

- **Kolejność checkpointów okazała się słuszna, i to nietrywialnie.** CP11 (wiele dokumentów)
  otworzył podatność IDOR, którą CP12 zamknął — gdyby kolejność była odwrotna, autoryzacja
  powstałaby dla modelu „jeden dokument" i trzeba by ją przepisać.
- **Uprawnienia rzeczywiście wymusiły autoryzację per typ operacji**, tak jak zakładał plan.
  Rola „komentujący" nie daje się wyrazić na poziomie destynacji STOMP, bo komentarze idą
  tym samym kanałem co edycja.
- **Rozcieńczenia na razie nie widać**, ale CP13 jeszcze nie ruszył — a to on wnosi
  największą zmianę w opisie projektu. Ocena należy do CP15.
