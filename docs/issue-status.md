# Offene Issues – Prüfung vom 1. November 2026

| Issue | Ergebnis | Nächster nötiger Schritt |
|---|---|---|
| #30 Zahleneingaben | Behoben und auf S24 geprüft (`0f392a7`), geschlossen | Keine Gerätekonfiguration beim Test verändert. |
| #29 Heltec-OLED | Im Code bestätigt, noch nicht behoben | GPS auf eigene Seite; Haupt-/Radioseite innerhalb 128×64 gestalten. Nachrichten/Spieler sind derzeit Platzhalter und brauchen echte Datenanbindung plus Auswahl der neuesten Einträge. |
| #5 V3-Batterie | Offen, neue Messwerte vorhanden; Ursache nicht sicher geklärt | Vollständige, nicht abgeschnittene Rohwerte, tatsächlicher Messpunkt der 4,09 V, Vergleich mit GPIO1-Spannung und verifizierter Schaltlogik. |
| #24 Peer-Namenslöschung | Android-Sender/Empfänger und Bindings vorhanden; zusätzlicher Routerfehler behoben | Firmware-PlayerState-TODOs und echte Übertragung/Leerung über mindestens zwei Funkgeräte prüfen. |
| #4 nRF-OTG | Nicht auf nRF-Hardware verifiziert | Passendes nRF-Board/Bootloader per Android-OTG, Paket-/Protokollkompatibilität und vollständigen Flash-/Neustartablauf testen. |
| #23 V4-Hardware | Offen | RX-LNA/FEM-Umschaltung, PSRAM-Allokation, Akkuvergleich mit Multimeter und Dauerbetrieb tatsächlich prüfen. |
| #3 ROADMAP-Gates | Offen | Die einzelnen Exit-Gates abarbeiten, insbesondere mehrere Funkgeräte, Reichweite, Feldbetrieb und Interoperabilität. |

## Korrektur zu #5

Die frühere Aussage, Kalibrierung oder Firmware seien bereits ausgeschlossen,
war nicht ausreichend belegt. Mit LOW meldete der Tester `347m/83c`; mit der
HIGH-Diagnose meldet er 100 % und `10777m/252…`, wobei die Rohwertanzeige abgeschnitten
ist. Die Änderung des Steuerpins beeinflusst das Signal erheblich, aber 10,777 V
sind keine plausible LiPo-Akkuspannung. Aus den abgeschnittenen Ziffern darf keine
vollständige ADC-Zahl rekonstruiert werden.

Die angenommene Teiler-Schaltung und die Umrechnung müssen gegen die konkrete
Board-Revision und eine Messung geprüft werden. Ein fester Rohwertbereich oder ein
geänderter Multiplikator ersetzt diese Prüfung nicht. Die Diagnoseanzeige selbst
muss ebenfalls lesbar werden; das hängt mit #29 zusammen. Der Vorschlag, den Teiler
nur zum Messen einzuschalten, ist sinnvoll zu untersuchen, aber ohne gesicherte
Polarität/Schaltung und Einschwingzeit noch keine bestätigte Fehlerbehebung.

## Bestätigter Softwarefehler aus #24

`is_state_transition` steht in `PlayerStatePacket` auf Feld **5**, nicht Feld 7.
Beide Routerpfade und der bisherige Testencoder lasen/schrieben fälschlich 7.
Nach Korrektur nur des Encoders schlugen drei vorhandene Tests fehl. Der Router
verwendet jetzt den generierten Feld-Tag; Squad-Daten (Feld 7) dürfen die
ACK-Entscheidung nicht verändern. Fehlende/ungültige Transition-Felder bleiben
weiterhin auf der sicheren ACK-Seite.

Die generierten Android-Bindings unterstützen bereits `hasCallsign` und die
optionalen Einheitsfelder. Drei neue Tests prüfen explizites Leeren, fehlende Felder
und Setzen/Behalten/Leeren/Umbenennen mit dem echten generierten Sender statt nur
mit einem eigenen Testencoder. Das ersetzt keinen Funk-Peer-Test.

## Geprüft und nicht geprüft

- 387 Android-Tests, Discovery-Audit sauber; zwölf Python-Sprachtests.
- 149 native Firmware-Tests, darunter Router-Regressionsfälle.
- Firmware-Builds `heltec_v4` und `t_echo` erfolgreich; BLE-Memory-Guard für V4 bestanden.
- Zahleneingaben für #30 auf dem S24 einschließlich Löschen, führender Nullen,
  Überlauf, Hex-Eingabe, gesperrtem Übernehmen und Lesen-Rücksetzung geprüft.
- **Keine Firmware bei dieser Prüfung auf ein Board geflasht.**
- Vorhandene Firmware-Dateien unter `Firmwares/` bleiben die anhand ihres Manifests
  identifizierten Snapshots; ein Quellcode-Fix aktualisiert sie nicht automatisch.
