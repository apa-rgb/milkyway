# Wspólna aplikacja dla 10 operatorów

Android korzysta z **Firebase Authentication** i **Firebase Realtime Database**. Konta mają numery **01–10**, każde z osobnym hasłem. Wszystkie są operatorami, zgodnie z wyborem użytkownika. Nie ma samodzielnej rejestracji. Firebase używa wewnętrznych adresów `konto01@PROJECT.accounts.invalid`; pracownik wybiera tylko numer i wpisuje hasło.

## Nowy projekt

Wymagane: Node.js 22+, JDK 17 i skonfigurowany dostęp Google Cloud do tworzenia projektów oraz zarządzania Firebase. W zarządzanym środowisku wybierz właściwą tożsamość GCP z `OIC_MANIFEST_PATH`, używając selektorów podanych przez środowisko. Skrypty korzystają z Google Application Default Credentials. Nie umieszczaj kluczy ani haseł w czacie lub GitHubie.

```bash
cd /workspace/milkyway/cloud
npm ci
npm run bootstrap -- --project UNIKALNY_ID_PROJEKTU --plant default
```

Skrypt sprawdza poświadczenia, tworzy projekt Firebase, aplikację Android `pl.apargb.milkyway` i domyślną Realtime Database w **europe-west1**. Włącza logowanie hasłem, zapisuje reguły dostępu, przygotowuje konfigurację Androida oraz dziesięć kont. Nie włącza płatnego planu. Jeżeli organizacja wymaga wskazania folderu projektu, utwórz projekt w konsoli Firebase i użyj ścieżki poniżej.

Nowe, różne hasła są zapisywane w lokalnym pliku `accounts-TIMESTAMP.csv` z uprawnieniami **0600**. Plik jest ignorowany przez Git; skrypt nie wypisuje haseł. Rozdaj każdemu pracownikowi jego hasło. Ponowne tworzenie kont nie zmienia istniejących haseł ani uprawnień.

Bootstrap nie usuwa zasobów przy błędzie. Jeśli część konfiguracji się udała, dokończ ją poniższymi poleceniami. Nie twórz kolejnego projektu pod inną nazwą bez ustalenia, który ma być używany.

## Istniejący projekt / dokończenie konfiguracji

W konsoli Firebase zarejestruj aplikację Android o pakiecie `pl.apargb.milkyway`, włącz **Authentication → Email/Password** i utwórz **Realtime Database**. Pobierz konfigurację Androida do `app/google-services.json`. Jest ignorowana przez Git i zawiera publiczną konfigurację aplikacji, nie hasła.

```bash
cd /workspace/milkyway/cloud
npm ci
npm run configure -- --database-url https://TWOJA_BAZA.europe-west1.firebasedatabase.app --plant default
npx firebase deploy --only database --project TWOJ_PROJEKT --non-interactive
npm run accounts -- --project TWOJ_PROJEKT --plant default
```

Konfigurator tworzy `firebase.properties` i celowo nie nadpisuje istniejącego pliku. Przykład: `../firebase.properties.example`. Zbuduj ponownie APK i zainstaluj tę samą konfigurację projektu oraz zakładu na wszystkich telefonach. Dopiero skonfigurowana wersja pokazuje logowanie. APK bez konfiguracji nadal działa lokalnie i informuje, że chmura czeka na konfigurację.

## Synchronizacja i dane

Wspólne dane: zbiorniki, ruchy i dolewania, przypomnienia i notatki zmian, zamówienia i plan, wykonania, notatki produktów oraz wybrakowany towar. Nasłuchiwanie Firebase odświeża widoki po wpisach innego operatora. Transakcje przeliczają stan na aktualnych danych i są ponawiane przy równoczesnych zapisach. Transfer zmienia oba zbiorniki i historię razem. Identyfikatory wykonań zapobiegają podwójnemu naliczeniu ponowionego wpisu. Ilości dziesiętne są tekstem, aby nie traciły dokładności.

Przy zmienionych wierszach zapisujemy autora, numer konta i czas w `_actor`. Wersja schematu jest sprawdzana przed odczytem. W aplikacji 0.7.1 domena produkcji zapisuje schemat **2** z oznaczeniami usunięcia z widoku magazynu; zbiorniki i notatki zachowują schemat **1**. Nowa aplikacja wczytuje również wcześniejszy schemat produkcji 1. Przed aktywacją należy wdrożyć aktualne `database.rules.json`, a urządzenia z dostępem do produkcji zaktualizować do 0.7.1. W ten sposób starsza aplikacja nie może nadpisać oznaczeń usunięcia. Usuwanie nie zmienia ilości wyprodukowanej i zachowuje zapisy wykonania; po podłączeniu Firebase oznaczenia trafiają do wspólnej bazy. Reguły ograniczają dostęp do zakładu użytkownika i chronią znaczniki pomiarów laboratoryjnych. Żadne z dziesięciu kont nie jest kontem Laboratorium; lokalny PIN nie nadaje uprawnień chmurowych.

Bez internetu można przeglądać pobrane wcześniej dane. Nowy zapis wymaga połączenia; brak internetu przed zapisem zwraca komunikat i zachowuje formularz. Gdy połączenie zniknie podczas transakcji, aplikacja czeka na potwierdzenie i pokazuje stan oczekiwania. Nie ogłasza niepotwierdzonego wpisu jako zakończonej produkcji. Firebase nie zachowuje oczekujących transakcji po zamknięciu procesu – po ponownym uruchomieniu sprawdź rejestr przed ponowieniem wpisu.

Dotychczasowe lokalne bazy SQLite pozostają na telefonie i **nie są automatycznie wysyłane ani łączone**. Wspólna baza zaczyna pusta. Migrację należy wykonać osobno, wybierając źródło, aby nie podwoić wykonań. Katalog zbiorników i instrukcje Softlab są w APK. Założenia szacowania produkcji pozostają indywidualnymi ustawieniami telefonu.

Każda domena jest spójnym stanem odtwarzanym w tymczasowej SQLite; nie przesyłamy plików baz pomiędzy telefonami. Rozwiązanie przeznaczone jest dla niewielkiej grupy. Rosnąca historia zwiększa rozmiar transakcji i transfer; limit zapisu klienta Firebase to 16 MB. Przed osiągnięciem tego rozmiaru trzeba wydzielić historię do osobnych węzłów lub archiwum. Nie usuwaj rejestru automatycznie.

## Testy

```bash
cd /workspace/milkyway/cloud
npm test
npm run test:auth
npm run test:emulators
```

`npm test` sprawdza dokładnie dziesięć kont, ochronę istniejących kont i konfigurację Androida. `test:auth` uruchamia emulator Firebase Auth: loguje każde konto, sprawdza role operatorów, błędne hasło, plik haseł i ponowienie tworzenia. Testowe dane są lokalne i usuwane.

`test:emulators` dodatkowo sprawdza aktualizację na żywo pomiędzy klientami, równoczesne wykonania, ponowienie wpisu i reguły zakładu oraz Laboratorium. Wymaga oficjalnego emulatora RTDB z `storage.googleapis.com`. Host był tu zablokowany; wymaganie zapisano w projekcie konfiguracji środowiska. Test czeka na zastosowanie ustawień sieci i **nie został wykonany**.

Androidowe `SharedRowsTest` sprawdzają wspólny stan, transfery, dokładność ilości, paginację, autorów i wykonania dwóch operatorów. `CloudLoginUiTest` sprawdza wybór konta 10 i obsługę hasła. Testy te nie zastępują próby skonfigurowanego APK na dwóch telefonach.

**Stan:** kod i skrypty są przygotowane. Produkcyjny projekt, konta i synchronizacja na telefonach pozostają nieaktywne do czasu podłączenia Google Cloud i prawdziwej konfiguracji Firebase. Bootstrap tworzący produkcyjny projekt nie został uruchomiony bez poświadczeń.
