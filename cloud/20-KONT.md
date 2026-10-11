# 20 kont na bezpłatnym Firebase

Android 0.7.23 i Windows 0.1.1 obsługują loginy `oper01`–`oper20` (6 znaków), osiem znaków w haśle początkowym, zmianę hasła i reset przez e-mail. Wszystkie konta mają rolę operatora. Nie wymagają Cloud Functions ani planu Blaze.

Przy pierwszym logowaniu ekran wymaga zmiany hasła oraz podania adresu do odzyskiwania dostępu. Nowe hasło w aplikacji musi mieć dokładnie 8 liter i cyfr, z co najmniej jedną literą i cyfrą. Firebase Authentication zapisuje hasło; aplikacje nie przechowują jego kopii. W profilu konta są tylko data zmiany, wersja konfiguracji i e-mail, dostępne wyłącznie po uwierzytelnieniu właściciela.

Firebase wysyła link **potwierdzający zmianę adresu konta**. Dopiero jego otwarcie wiąże prawdziwy e-mail z kontem i umożliwia późniejszy reset. Niepotwierdzona zmiana adresu zachowuje poprzedni adres techniczny, a aplikacja obsługuje ten okres przejściowy. Nie potwierdzaj starego linku po ponownym ustawieniu innego adresu.

Na nowym telefonie lub komputerze trzeba raz podać e-mail przypisany do wybranego loginu. Adres jest zapamiętywany lokalnie; nie ma publicznego katalogu prywatnych adresów e-mail. Po resecie loguj się tym samym loginem, adresem i nowym hasłem. Jeden adres e-mail może należeć do jednego konta. „Nie pamiętam hasła” pyta o adres, wysyła standardowy link Firebase i nie ujawnia, czy adres należy do innej osoby. Skrypt `configure-password-policy.mjs` ustawia w Firebase wymaganie dokładnie 8 znaków i co najmniej jednej cyfry, również na stronie resetowania. Zachowuje kopię poprzedniej polityki; nie zmienia innych ustawień Authentication. Formularze aplikacji dodatkowo wymagają co najmniej jednej litery.

## Wdrożenie

1. Zaktualizuj wszystkie telefony do 0.7.23 i Windows do 0.1.1.
2. Wgraj nowy plik `database.rules.json` przez Firebase Console lub `update-rules.mjs`. Reguły zachowują walidację produkcji w schemacie 3 i dodają właścicielowi dostęp do `accountProfiles/{uid}` oraz konta 11–20.
3. W Cloud Shell uruchom `upgrade-accounts.mjs` z uprawnieniami administratora Firebase Authentication. Nie uruchamiaj ponownie starego bootstrapu projektu.

```sh
gcloud config set project milyway-41e9e
gcloud auth application-default login --scopes=https://www.googleapis.com/auth/firebase,https://www.googleapis.com/auth/firebase.database,https://www.googleapis.com/auth/userinfo.email
node upgrade-accounts.mjs --project milyway-41e9e --plant default --output hasla-20-kont.csv
node configure-password-policy.mjs --project milyway-41e9e
```

Migracja **jednorazowo zastępuje dotychczasowe hasła kont 01–10** ośmioznakowymi hasłami początkowymi i tworzy 11–20. Zachowuje identyfikatory istniejących użytkowników, dane produkcji, notatki i uprawnienia operatorów. Rozbieżność zakładu lub roli któregokolwiek konta zatrzymuje migrację przed zmianami.

Plik `hasla-20-kont.csv` (uprawnienia 600) zawiera loginy i początkowe hasła. Powstaje przed zmianą Authentication, więc przerwaną migrację można ponowić z **tym samym plikiem**. Zachowaj go prywatnie i nie wysyłaj na GitHub ani do czatu. Nie jest aktualizowany po zmianie hasła przez użytkownika. Już przeniesione konta nie dostają nowych haseł przy kolejnym uruchomieniu. Nie usuwaj pliku przed zakończeniem migracji.

## Weryfikacja lokalna

```sh
npm test
npm run test:logins
npm run test:desktop
```

`test:logins` używa prawdziwych emulatorów Auth i Realtime Database w projekcie demonstracyjnym: 20 logowań, zmiana hasła, potwierdzenie e-maila, reset, zachowanie UID, odrzucenie starych haseł i izolacja profili. Nie wysyła prawdziwych wiadomości. W środowisku chmurowym zachowaj proxy i ustaw `FIREBASE_EMULATORS_PATH`, `XDG_CONFIG_HOME` oraz `XDG_CACHE_HOME` na zapisywalne katalogi. Nie odczytuj plików debug Firebase, które mogą zawierać pełne środowisko procesu.
