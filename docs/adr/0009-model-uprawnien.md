# ADR-0009 — Model uprawnień

- **Status:** Zaakceptowany
- **Data:** 2026-08-15
- **Dotyczy checkpointów:** CP12 (implementacja), CP14 (rola komentującego nabiera treści)

---

## Kontekst

Do CP11 model dostępu brzmiał „każdy zalogowany może wszystko z każdym dokumentem" i był
spójny, bo dokument był jeden i wspólny. CP11 wprowadził wiele dokumentów, a identyfikator
dokumentu jest częścią adresu podawanego przez klienta — zarówno w destynacji STOMP
(`/topic/doc/{id}/ops`), jak i w ścieżce REST (`/api/documents/{id}`).

To samo uproszczenie przestało więc być uproszczeniem, a stało się podatnością: dowolny
zalogowany użytkownik mógł czytać cudzy dokument na żywo albo pobrać jego treść jednym
żądaniem GET.

Trzy pytania do rozstrzygnięcia:
1. **Jak wyrazić uprawnienia** — płaska lista czy porządek?
2. **Gdzie je egzekwować** — i czy wystarczy jedno miejsce?
3. **Co z dokumentami, które już istnieją** i nie mają żadnego właściciela?

---

## Rozważane opcje

### 1. Wyrażenie uprawnień

**Opcja A — zbiór niezależnych uprawnień** (`READ`, `WRITE`, `COMMENT`, `SHARE`).

**Za:** elastyczne; da się wyrazić „może udostępniać, ale nie edytować".
**Przeciw:** każde sprawdzenie to zapytanie o obecność w zbiorze, a kombinacji jest
wykładniczo wiele. Sensownych jest z nich cztery, a reszta to stany, o których nikt nie
pomyślał — i to w nich powstają dziury.

**Opcja B — role uporządkowane** (`viewer` < `commenter` < `editor` < `owner`).

**Za:** sprawdzenie uprawnienia jest **porównaniem**, a nie tablicą wyjątków. Zbiór stanów
jest skończony i wypisany. Każda rola może wszystko to, co poprzednia, więc nie da się
skonstruować roli, która może więcej i mniej jednocześnie.
**Przeciw:** nie wyrazi „może udostępniać, ale nie edytować". Świadomie: nie jest to
kombinacja, której ktokolwiek potrzebuje w edytorze dokumentów.

### 2. Miejsce egzekwowania

**Opcja A — tylko warstwa WebSocket.** Tam idzie edycja, więc tam jest wartość.
**Przeciw:** REST zwraca pełną treść dokumentu (`GET /api/documents/{id}`) i historię zmian.
Zamknięcie jednej z dwóch dróg do tych samych danych jest **gorsze niż niezamykanie żadnej**,
bo daje fałszywe poczucie rozwiązania problemu.

**Opcja B — obie warstwy, jedna reguła.** Wspólny `PermissionService`, dwa punkty wejścia:
interceptor kanału STOMP i filtr HTTP.

### 3. Dokumenty bez właściciela

**Opcja A — zamknąć wszystko, wymusić jawne nadanie dostępu.**
**Przeciw:** migracja odbiera dostęp do dokumentów, które wcześniej działały. Zabezpieczenie,
które psuje istniejące dane, nie wchodzi na produkcję — zostaje wyłączone i nie chroni niczego.

**Opcja B — widoczność jako właściwość dokumentu**, domyślnie `link` („każdy z adresem może
edytować"), przełączalna na `private`.

---

## Decyzja

**1B + 2B + 3B.** Role uporządkowane, egzekwowanie w obu warstwach z jednej reguły,
widoczność domyślnie `link`.

### Kolejność rozstrzygania roli

1. **Właściciel** → `owner`. Nie da się właścicielowi odebrać dostępu wpisem w tabeli,
   bo to on tymi wpisami zarządza.
2. **Wpis wprost dla dokumentu** → nadpisuje dziedziczenie. Bez tego nie dałoby się odebrać
   dostępu do JEDNEGO dokumentu bez wyrzucania kogoś z całego workspace'u.
3. **Członkostwo w workspace** → dziedziczone przez jego dokumenty.
4. **Widoczność `link`** → `editor` dla każdego zalogowanego.
5. W pozostałych przypadkach → **brak dostępu**.

### Autoryzacja musi widzieć TYP OPERACJI

To jest nieoczywista część tej decyzji. Rola `commenter` wolno czytać i komentować,
ale nie zmieniać tekstu — a komentarze (CP14) idą **tym samym kanałem operacji CRDT** co
edycja. Rozróżnienie „wolno pisać" od „wolno komentować" nie istnieje na poziomie destynacji
STOMP i musi zejść do typu operacji.

Mapowanie „rola → dozwolone typy" jest dlatego w jednym miejscu
(`DocumentAccessInterceptor.allowedToSend`), żeby dołożenie typu operacji w CP13 i CP14
było wpisem w tablicy, a nie polowaniem na rozsiane po kodzie warunki.

### Odmowa zwraca 404, nie 403

Odpowiedź „ten dokument istnieje, ale nie masz do niego dostępu" jest sama w sobie wyciekiem:
pozwala sprawdzać, które identyfikatory są prawdziwe. Z punktu widzenia kogoś bez uprawnień
dokument ma po prostu nie istnieć — tak samo, jak po stronie WebSocketa, gdzie subskrypcja
po prostu nie działa.

---

## Konsekwencje

### Pozytywne
- Jedna reguła, dwa punkty wejścia. Nie da się dodać trzeciej drogi do danych bez zauważenia,
  że brakuje jej sprawdzenia — bo obie istniejące wołają tę samą metodę.
- Domyślna widoczność `link` sprawia, że migracja niczego nie psuje, a mechanizm jest realny.
- Rola `commenter` wymusiła projekt, który uniesie CP14 bez zmiany architektury.

### Negatywne

- **Unieważnienie cache'u uprawnień jest LOKALNE dla instancji.** Przy wielu instancjach
  pozostałe zobaczą odebranie dostępu dopiero po wygaśnięciu wpisu, czyli w granicy 5 sekund.
  Pełne rozwiązanie to rozgłoszenie unieważnienia kanałem Redisa — tym samym, którym idą
  operacje. **Niezrobione, wymienione w README.**
- **Cache uprawnień jest kompromisem, nie optymalizacją bez wady.** Sprawdzenie dzieje się
  przy każdej ramce, więc zapytanie do bazy za każdym razem byłoby zapytaniem na naciśnięcie
  klawisza. Pięć sekund to jednocześnie górna granica opóźnienia odebrania dostępu — i jest
  to jedyna gwarancja, jakiej ten mechanizm udziela.
- **Domyślna widoczność `link` jest permisywna.** Dokument utworzony bez świadomej zmiany
  ustawień jest dostępny dla każdego zalogowanego. Jest to zgodne z zachowaniem sprzed CP12
  i z tym, jak działa większość edytorów współdzielonych linkiem — ale ktoś, kto zakłada
  „prywatne dopóki nie udostępnię", będzie zaskoczony. Domyślna wartość `private` byłaby
  bezpieczniejsza i wymagałaby interfejsu do udostępniania, którego CP12 nie buduje.
- **Nie ma interfejsu do zarządzania dostępem.** `PermissionService` udostępnia `grant`,
  `revoke` i `setVisibility`, ale nie ma ekranu, z którego dałoby się je wywołać. Model działa
  i jest przetestowany; brakuje mu ostatniego kroku do bycia funkcją produktu.
- **Token nadal jest sprawdzany raz, przy `CONNECT`.** Unieważnienie samego tokenu w trakcie
  sesji nie zrywa połączenia — zmieniło się to, że *uprawnienia* są sprawdzane przy każdej
  ramce, ale *tożsamość* nadal pochodzi z jednorazowego sprawdzenia. Wymienione w README.

### Do zweryfikowania w CP14
Czy mapowanie „rola → typy operacji" faktycznie uniesie komentarze jednym wpisem, czy okaże
się, że wymaga zaglądania w treść operacji głębiej, niż zakłada obecny projekt.
