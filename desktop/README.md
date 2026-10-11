Aktualizacja 0.1.1: loginy `oper01`–`oper20`, pierwsza zmiana hasła, ustawienia konta i reset e-mail. [Wdrożenie 20 kont i ograniczenia bezpłatnego planu](../cloud/20-KONT.md). Po powiązaniu e-maila podaj go raz na nowym komputerze; hasło nie jest zapamiętywane.

# Milkyway — Zamówienia na Windows 10/11

Osobna aplikacja dla Windows **x64**, instalowana dla bieżącego użytkownika, ze skrótem z krówką na pulpicie. Korzysta z tych samych kont operatorów **oper01–oper20** i tego samego zakładu Firebase co Android. Formularz wysyła nazwę, ilość, opis i opcjonalną ważną notatkę z autorem do **oczekujących** Masłowni, Proszkowni lub UHT. Masłownia wymaga wyboru Masło/Mix. Ilości są w kg, a UHT w litrach.

## Uruchomienie wspólnej pracy

1. Zainstaluj Android **0.7.22 lub nowszy na wszystkich telefonach**. Numery zamówień wymagają schematu produkcji 3; starsze wersje nie zachowują nowego pola.
2. Zaktualizuj **wyłącznie reguły Realtime Database** z `cloud/database.rules.json`. Można wkleić cały plik w Firebase Console → Realtime Database → Rules → Publish albo użyć przygotowanej paczki aktualizacji reguł w Cloud Shell. Nie uruchamiaj ponownie tworzenia projektu, bazy ani kont.
3. Zainstaluj `Milkyway-Zamowienia-0.1.0-Windows-x64.exe` i otwórz skrót **Milkyway — Zamówienia**. Zaloguj się istniejącym numerem konta i hasłem. Imię jest opcjonalne.

Instalator zawiera konfigurację klienta tego samego projektu; nie trzeba kopiować plików Firebase na każdy komputer. Nie zawiera haseł ani uprawnień administracyjnych. Hasła i tokeny sesji nie są zapisywane na dysku. Zapisy wymagają Internetu. Przy niepewnym wyniku wysyłania ponów to samo zamówienie — jego identyfikator chroni przed duplikatem. Na dysku pozostaje tylko numer konta, imię i niewysłany formularz.

Instalator nie ma podpisu wydawcy, ponieważ nie dostarczono certyfikatu podpisywania Windows. Może pojawić się informacja o nieznanym wydawcy. Nie wyłączaj ochrony systemu. Instalacja na rzeczywistym Windows wymaga jeszcze sprawdzenia; okna i scenariusze sprawdzono w Electron na Linux.

## Numer zamówienia i status

Nowe zamówienia z komputera otrzymują wspólny, rosnący numer **#000001**, **#000002**, itd., niezależnie od działu i komputera. Numer zostaje nadany dopiero po potwierdzonym zapisie. Przy równoczesnych zapisach serwer sprawdza wersję całego dokumentu, a klient ponawia transakcję z aktualnym licznikiem. Usunięcie zamówienia nie cofa numeracji. Reguły nie pozwalają cofnąć licznika ani zmienić istniejącego numeru.

Android pokazuje ten sam numer w oczekujących, kolejce, notatkach produktu i magazynie. Częściowe wykonanie zachowuje numer: lista zamówień pokazuje zamówioną, wykonaną i pozostałą ilość. Starsze wpisy i zamówienia utworzone bezpośrednio w Androidzie nie otrzymują numerów wstecz. Lista **Złożone zamówienia** pokazuje zamówienia wysłane z modułu Windows przez wszystkich operatorów zakładu.

## Podgląd kolejki i magazynu

**Kolejka produkcji** pokazuje wszystkie zaplanowane pozycje z telefonu i komputera na osi czasu, w kolumnach Masłownia, Proszkownia, UHT. Wybierz dzień kalendarzem lub strzałkami i opcjonalnie filtr działu. Oś ma wiersze co dwie godziny; kafelek pokazuje dokładną godzinę, numer zamówienia, pozostałą ilość i notatkę. Proszkownia pokazuje również kod produkcji. Pozycje bez godziny mają oddzielny wiersz. Są to godziny rozpoczęcia; aplikacja nie wyznacza długości produkcji. Wpisy oczekujące i wykonane w całości nie zajmują osi.

**Podgląd magazynu** pokazuje wspólną produkcję i wybrakowany towar z wybranego dnia, również dodane na telefonie. Dzień magazynu trwa od **09:00 do 09:00 następnego dnia**, według Europe/Warsaw. Kafelki są delikatnie zielone dla całości, żółte dla części i niebieskie dla nadmiaru. Usunięte z widoku magazynu wpisy nie wracają. Podglądy są tylko do odczytu; planowanie, wykonanie i usuwanie pozostają w Androidzie. Dane odświeżają się co **5 sekund** i przyciskiem Odśwież.

## Rozwój i budowanie

Wymagany Node 22+ i npm. W katalogu `desktop`:

```sh
npm ci
npm run configure -- --from ../firebase.properties
npm test
npm run test:ui
npm start
npm run dist:win
```

Konfiguracja może też pochodzić z JSON z polami `projectId`, `apiKey`, `databaseUrl`, `plantId`. Skrypt zapisuje ignorowany `config/firebase.json`; nie publikuj go w Git. `npm run fixture` odtwarza fixture używany w testach Androida.

Test okien uruchamia wyłącznie lokalne serwery testowe na 127.0.0.1:9099 i :9000. W Linux wymaga Xvfb oraz `DISPLAY`; nie uruchamiaj go jednocześnie z emulatorami Firebase. Ustawienie `MILKYWAY_E2E` jest ignorowane przez spakowaną aplikację. Renderer ma izolowany kontekst, bez Node i dostępu do sieci; połączenie Firebase odbywa się w procesie głównym z wykorzystaniem ustawień proxy systemu.

Budowa instalatora NSIS na Linux używa czytnika PE/NSIS dostarczonego przez przypięty electron-builder 26.15.3: odczytuje wygenerowany deinstalator tak samo jak standardowa ścieżka buildera na macOS. Nie wymaga Wine i nie zastępuje testu instalacji na Windows. W środowisku chmurowym ustaw zapisywalne katalogi npm, Electron i electron-builder, zachowując proxy, CA i weryfikację sum kontrolnych. Zobacz zapisane instrukcje uruchamiania środowiska. Pakiet aplikacji obejmuje wyłącznie źródła klienta, interfejs, ikonę i konfigurację klienta; testy, konta i skrypty administracyjne nie są pakowane.
