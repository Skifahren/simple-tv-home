# Simple TV Home installeren (Nederlands)

Simple TV Home is een eenvoudig, snel startscherm voor Android TV en Google TV: een rij app-tegels, een klok, HDMI-ingangen,
een netwerktegel en een Instellingen-menu (apps toevoegen, verplaatsen, vervangen en verwijderen, tegelstijl, achtergrond en
aantal apps per rij).

Gemaakt en getest op een **Epson EF-61W** beamer (Google TV, Android 14). Op andere Android TV / Google TV apparaten zou
het moeten werken, maar dat is niet getest. Het is een bewerking van [Ansub/tv-detox](https://github.com/Ansub/tv-detox)
(MIT); alleen het startscherm zit hierin.

Er verandert niets onomkeerbaar: je kunt altijd terug naar het eigen startscherm van je tv (zie "Weer ongedaan maken").

## Wat je nodig hebt

- Een Android TV of Google TV met internet.
- Het bestand **`tvhome.apk`** van de pagina **Releases** van deze repository (rechts op de repositorypagina).
- Een manier om het op de tv te zetten: **A** zonder computer (Downloader-app), of **B** met een computer (`adb`).

## A. Zonder computer (Downloader-app)

1. Installeer op de tv de app **Downloader** (van AFTVnews) uit de Play Store.
2. Open de pagina **Releases** van deze repository in de browser van je telefoon of computer, klik met de rechtermuisknop
   (of lang indrukken) op **tvhome.apk** en kopieer het adres. Typ dat adres in Downloader (of gebruik een korte link als
   de eigenaar die geeft) en download het bestand.
3. Android vraagt of Downloader apps mag installeren: zet dat **aan** (de tv stuurt je naar "Onbekende apps installeren";
   de naam en plek verschillen per tv) en tik daarna op **Installeren**. Als Google Play Protect waarschuwt dat de app onbekend is,
   kies **Toch installeren**.
4. Zet de **Home button**-service aan, zodat de Home-knop van de afstandsbediening dit startscherm opent:
   Instellingen > Toegankelijkheid (Accessibility) > **Home button** > **Aan**. Android toont dan een waarschuwing; die
   hoort bij deze service (zie "Privacy" hieronder).
5. Druk op **Home**. Je ziet nu Simple TV Home.

Is de schakelaar bij stap 4 grijs of kan je hem niet aanzetten ("beperkte instelling")? Dan blokkeert Android dit voor apps
die van een bestand zijn geïnstalleerd. Installeer dan met een computer (B). Zo werkte het op de Epson EF-61W.

## B. Met een computer (adb)

1. Zet op de tv **Ontwikkelaarsopties** aan (Instellingen > Systeem > Info > tik 7 keer op "Build") en zet daarin
   **USB-foutopsporing / netwerk-foutopsporing** aan. Zet dat daarna weer **uit**.
2. Zorg dat `adb` op je computer staat (Mac: `brew install android-platform-tools`).
3. Haal deze repository op (groene knop **Code > Download ZIP**), pak hem uit en zet `tvhome.apk` uit Releases in dezelfde map
   als `install.sh`.
4. Kijk eerst of het kan (verandert niets op de tv) en installeer daarna, met het IP-adres van je tv:

   ```sh
   sh supports.sh 192.168.1.50
   sh install.sh 192.168.1.50
   ```

5. Keur op de tv de melding "Foutopsporing toestaan?" goed (vink "Altijd toestaan" aan).
6. Op Google TV laat het script het eigen startscherm van Google TV aan staan. Zet daarna Instellingen > Toegankelijkheid >
   **Home button** aan, zoals bij A, stap 4.

## Gebruiken

- Ga met de pijltjes naar het **Instellingen-icoon** (rechtsonder) voor het Instellingen-menu: achtergrond, tegelstijl,
  apps per rij, tv-instellingen, app toevoegen, Play Store.
- Houd **OK** ingedrukt op een app-tegel om hem te verplaatsen, te vervangen of van het startscherm te halen.
- **Terug** in een instellingenpagina brengt je terug bij het Instellingen-menu.

## Privacy

- De app heeft **geen internetrechten**: er kan niets de tv uit.
- De Home button-service kijkt alleen wanneer het eigen startscherm van Google TV naar voren komt. Hij kan **niet lezen wat er
  op het scherm staat** en ziet **geen toetsaanslagen**. Android toont bij zo'n service altijd een waarschuwing; die geldt
  ook hier. De code staat in `src/` (`HomeButton.java`); zie ook de [README](README.md).

## Weer ongedaan maken

- Zet **Home button** uit in Instellingen > Toegankelijkheid: dan opent Home weer het startscherm van je tv.
- Verwijderen kan via Instellingen > Apps > **Home** > Verwijderen, of met een computer: `sh undo.sh <IP-adres>`.

## Foto's

De achtergrondfoto's komen van Wikimedia Commons (CC BY / CC BY-SA). Fotograaf, licentie en wijzigingen staan in
[WALLPAPER_CREDITS.txt](WALLPAPER_CREDITS.txt).
