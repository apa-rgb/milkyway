# Milkyway na iPhone’a

Natywny projekt **SwiftUI, iOS 17 lub nowszy**, wersja 0.1.0. Otwórz `Milkyway.xcodeproj` w **Xcode 16+ na Macu**. Android pozostaje osobną aplikacją; oba klienty korzystają z tej samej bazy i istniejących kont operatorów 01–10.

## Co jest w projekcie

- Pulpit z trzema zmianami (PIN 5522), produkcją, magazynem, tablicą ogłoszeń, dokumentacją i menu Inne. Istniejąca wesoła krówka jest ikoną Apple.
- Katalog wszystkich 46 zbiorników z Androida, stan i delikatny pasek napełnienia, edycja w m³ / wyświetlanie w litrach, parametry, kierunek C/F i krystalizator 1–6. Dolewanie i transfer zapisują historię i aktualizują oba zbiorniki atomowo.
- Oleje: jeden kafelek, rodzaj, temperatura, ważność, uwagi, automatyczny czas napełnienia, zerowanie z zachowaniem historii. Daty i uwagi korzystają z istniejącego formatu zdarzeń Androida. Ostrzeżenie bordowe zaczyna się po południu poprzedniego dnia; godzina nie jest wyświetlana na kafelku.
- Masłownia, Proszkownia i UHT: zamówienia po prawej, produkcja po lewej. Przeciągnij kafelek na dwugodzinne pole wybranego dnia albo z powrotem do zamówień. Kalendarz podąża za przewijanym dniem; menu przytrzymanego kafelka pozwala zmienić kolejność. Okno przewijania obejmuje 14 dni wstecz i 60 naprzód; dowolny dalszy dzień można wybrać kalendarzem.
- Masło/Mix, kolory i jednolity rozmiar kart po obu stronach, dwa słowa notatki na karcie i pełny tekst po jej otwarciu. Kod dnia roku tylko w Proszkowni, automatyczny przy zaplanowaniu; ręczna zmiana pozostaje zachowana.
- Częściowe wykonanie, nadmiar, wybrakowany towar, usuwanie PIN-em 5522, magazyn z datami i kolorami wykonania. Dzień produkcyjny 09:00–09:00 w strefie Europe/Warsaw; zapis przed 09:00 trafia do poprzedniego dnia. Usunięcie wpisu magazynu nie kasuje historii wykonania.
- Notatki bez tytułu, autor z logowania, kolory, Ważne/Przypomnienie/Załatwione, kalendarz i zegarek systemowy. Na pulpicie przypomnienie delikatnie pulsuje z przyciskami Zrobione/OK/Pilne. OK jest zapisywane oddzielnie dla każdego konta.
- Softlab i dokumentacja są pustymi sekcjami, tak jak obecnie na Androidzie. Przybliżenia produkcji i wpisy przeniesione do Inne.

## Podłączenie istniejącego Firebase

Nie twórz nowej bazy ani nowych operatorów.

1. W konsoli Firebase otwórz **Milkyway → Ustawienia projektu → Ogólne → Dodaj aplikację → iOS**.
2. Wpisz identyfikator pakietu **`pl.apargb.milkyway.ios`**. Pobierz **`GoogleService-Info.plist`** dla tej aplikacji Apple. Plik Androida `google-services.json` nie zastępuje konfiguracji Apple.
3. W terminalu Maca, z katalogu repozytorium, uruchom poniższą komendę. Adres bazy i identyfikator zakładu muszą być **identyczne jak w Androidzie** (`databaseUrl` i `plantId` w lokalnym `firebase.properties`; nie udostępniaj całego pliku).

```bash
python3 ios/scripts/configure.py \
  --plist ~/Downloads/GoogleService-Info.plist \
  --database-url https://ADRES_ISTNIEJACEJ_BAZY.firebasedatabase.app \
  --plant IDENTYFIKATOR_ZAKLADU
```

Skrypt sprawdza projekt `milyway-41e9e` i identyfikator Apple, nie wypisuje klucza i zapisuje ignorowany `ios/Resources/MilkywayCloud.plist`. Pliki konfiguracji nie trafiają do GitHub ani do archiwum projektu. Istniejący przykładowy plik można zastąpić prawdziwą konfiguracją tą komendą.

Klient używa oficjalnych API REST Firebase Authentication i Realtime Database, więc nie wymaga dodawania Firebase SDK ani CocoaPods. Klucz API musi dopuszczać Identity Toolkit i Secure Token API; jeśli ma ograniczenia do samego Androida, przygotuj konfigurację dla klienta Apple w ustawieniach projektu, bez wyłączania reguł bazy.

Token odświeżania jest przechowywany w Keychain. Hasło nie jest zapisywane. Imię operatora i założenia przybliżeń są lokalne. Claims `plant`, `account`, `role` oraz reguły `cloud/database.rules.json` nadal chronią dane. **PIN służy do potwierdzenia operacji w interfejsie; nie zastępuje uprawnień Firebase.** Wszystkie obecne konta są operatorami; oznaczanie pomiarów jako laboratoryjne wymaga roli `laboratory`, nawet po PIN-ie 2426.

Zapis pobiera najnowszą domenę i używa **ETag + If-Match**. Konflikt powoduje ponowny odczyt i powtórzenie operacji, a nie nadpisanie stanu z pamięci telefonu. Snapshoty, identyfikatory Base64 URL, ilości dziesiętne jako tekst, `_rowid`, `_actor`, schemat produkcji 2 oraz schematy zbiorników/notatek 1 są zgodne z Androidem. Nieznane pola wierszy zostają zachowane. Starszy schemat produkcji 1 można odczytać; zapis aktualizuje go do 2. Niedostępny internet oznacza brak zapisu, bez ukrytej kolejki offline. Zmiany na otwartej aplikacji odbierane są strumieniem zdarzeń Firebase.

## Uruchomienie i instalacja

1. Otwórz `ios/Milkyway.xcodeproj` w Xcode 16+.
2. W **Milkyway → Signing & Capabilities** wybierz swój **Team**. Używamy automatycznego podpisywania. Jeśli Apple wymaga innego identyfikatora pakietu, zmień go również w rejestracji Firebase i walidacji skryptu konfiguracji.
3. Podłącz iPhone’a z iOS 17+, wybierz go w górnym pasku Xcode i kliknij **Run**. Telefon może wymagać trybu dewelopera i zaufania certyfikatowi.
4. Dla wygodnej instalacji na wielu iPhone’ach: płatne **Apple Developer Program → Product → Archive → Distribute App → App Store Connect → TestFlight**. Zaproś użytkowników w TestFlight po przetworzeniu i wymaganej weryfikacji Apple.

Bezpłatne Apple ID umożliwia testowanie na własnym podłączonym telefonie, z ograniczeniami podpisu i jego ważności. TestFlight wymaga Apple Developer Program. Nie można zainstalować na iPhonie pliku APK; potrzebna jest podpisana kompilacja Apple. Artefakt `.app` symulatora z CI również nie instaluje się na telefonie.

## Sprawdzenie projektu

Logika jest oddzielona od interfejsu i można ją testować także na Linuxie ze Swift 5.9+:

```bash
bash ios/scripts/test-core.sh
```

Na Macu, bez Firebase i bez podpisu, można sprawdzić build ekranów:

```bash
python3 ios/scripts/configure.py --example
xcodebuild -project ios/Milkyway.xcodeproj -scheme Milkyway \
  -configuration Debug -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
```

`--example` nie nadpisuje istniejącej konfiguracji; dla prawdziwego logowania użyj wcześniejszej komendy `--plist`. Projekt Xcode jest zapisany w repozytorium. Po dodaniu/usunięciu plików źródłowych odtwórz go przez `python3 ios/scripts/generate-project.py`; generator jest deterministyczny. Lokalne ustawienie Team w Xcode wybierz ponownie po regeneracji.

Workflow `.github/workflows/ios.yml` uruchamia testy i kompilacje dla symulatora oraz urządzenia bez podpisu na macOS. Nie wdraża aplikacji, nie publikuje w App Store i nie otrzymuje produkcyjnych haseł ani konfiguracji Firebase.

**Stan weryfikacji przy przygotowaniu:** 23 testy logiki przeszły na Swift 6.0.3/Linux; sprawdzono składnię wszystkich plików Swift i konfiguracje projektu. W tym środowisku nie ma Xcode, więc build SwiftUI, wygląd w symulatorze, podpisanie i instalacja na iPhonie oraz logowanie/synchronizacja z produkcyjnym Firebase wymagają jeszcze sprawdzenia na Macu. Wynik workflow sprawdź w GitHub → Actions → iOS.

Powiadomienia lokalne dotyczą przypomnień już pobranych przez telefon. Gdy aplikacja jest zamknięta, nowe wpisy innych osób nie mogą się pojawić bez infrastruktury push APNs. Takiego backendu jeszcze nie ma. W otwartej aplikacji działa bieżący strumień Firebase; po ponownym otwarciu pobierane są aktualne wpisy. iOS limituje liczbę zaplanowanych lokalnych powiadomień — planujemy 60 najbliższych.
