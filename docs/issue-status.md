# Offene Issues – Prüfung vom 2. November 2026

| Issue | Ergebnis | Nächster nötiger Schritt |
|---|---|---|
| #31 OLED-Helligkeit/Timeout | Codefix und Test-Builds fertig, Hardwarebestätigung offen | V3-Testfirmware plus neue App prüfen: Helligkeit, Timeout, Aufwecken und Fortsetzung der Funkverbindung. |
| #30 Zahleneingaben | Behoben und auf S24 geprüft (`0f392a7`), geschlossen | Keine Gerätekonfiguration beim Test verändert. |
| #29 Heltec-OLED | Im Code bestätigt, noch nicht behoben | GPS auf eigene Seite; Haupt-/Radioseite innerhalb 128×64 gestalten. Nachrichten/Spieler sind derzeit Platzhalter und brauchen echte Datenanbindung plus Auswahl der neuesten Einträge. |
| #5 V3-Batterie | Offen, neue Messwerte vorhanden; Ursache nicht sicher geklärt | Vollständige, nicht abgeschnittene Rohwerte, tatsächlicher Messpunkt der 4,09 V, Vergleich mit GPIO1-Spannung und verifizierter Schaltlogik. |
| #24 Peer-Namenslöschung | Android-Sender/Empfänger und Bindings vorhanden; zusätzlicher Routerfehler behoben | Firmware-PlayerState-TODOs und echte Übertragung/Leerung über mindestens zwei Funkgeräte prüfen. |
| #4 nRF-OTG | Nicht auf nRF-Hardware verifiziert | Passendes nRF-Board/Bootloader per Android-OTG, Paket-/Protokollkompatibilität und vollständigen Flash-/Neustartablauf testen. |
| #23 V4-Hardware | Offen | RX-LNA/FEM-Umschaltung, PSRAM-Allokation, Akkuvergleich mit Multimeter und Dauerbetrieb tatsächlich prüfen. |
| #3 ROADMAP-Gates | Offen | Die einzelnen Exit-Gates abarbeiten, insbesondere mehrere Funkgeräte, Reichweite, Feldbetrieb und Interoperabilität. |

## Befund zu #31 (2. November 2026, vor Implementierung)

`Display::setBrightness`, `sleep` und `wake` sind bei Heltec V3, V4 und T3S3
leere Methoden. Die Display-Task liest Helligkeit nur beim Start; live gespeicherte
Änderungen erreichen den Treiber nicht. Der Timeout setzt zwar einen Softwarezustand,
sendet aber kein OLED-Off-Kommando. Vorgesehen sind ein gemeinsam getesteter
SSD1306-Powercontroller, Live-Übernahme und ein überlaufsicherer Idle-Timer.
Zusätzlicher Transportfall: POWER mit ausschließlich Nullwerten wird von Proto3
als leere Nutzlast kodiert; die Firmware versteht leer aber als Commit-Marker.
Die App muss für diesen Fall einen expliziten Null-Skalar senden, damit 0 %
wirklich gespeichert werden kann. Die physische OLED-Prüfung ist noch nicht erfolgt.

## Softwarekorrektur zu #31

- V3, V4/Runtime-OLED und T3S3 senden jetzt SSD1306-Kontrast- sowie Display-Off/On-Kommandos.
- Geänderte Helligkeit wird ohne Neustart übernommen. 0 % schaltet das Panel aus.
- Timeout 0 verhindert automatisches Abschalten und weckt ein zuvor idle-abgeschaltetes Panel.
  Geänderte Timeouts starten die Wartezeit neu; große Sekundenwerte laufen nicht über.
- Button/Touch wecken das Panel. Funk und Spieler-Timer laufen bei dunklem Display weiter.
- Kopplungscode und Recovery-/Shutdown-Hinweise werden vorübergehend sichtbar, auch bei 0 %;
  danach wird die konfigurierte Helligkeit wiederhergestellt.
- Eine ganz leere POWER-Protobuf-Nutzlast wird in der App mit einem expliziten Nullfeld
  gesendet, statt als reiner Commit-Marker verwechselt zu werden. Das gilt für Stage und Apply.
- 28 neue native Controller-/Timeout-/Codec-Tests und fünf Android-Wire-Tests bestanden.

Gebaut: `heltec_v3`, `heltec_v4`, `t3s3`, `runtime_s3_v4`, `heltec_v3_diag_high`, `t_echo`.
BLE-Memory-Guard für alle fünf ESP32-Images bestanden. **Noch keine physische OLED-Prüfung
oder Board-Programmierung im Rahmen dieses Fixes; #31 bleibt bis zur Rückmeldung offen.**
Die HIGH-Diagnose behält die Schaltlogik aus #5; Akkuberechnung und Layoutprobleme aus #29
werden durch diesen Display-Power-Fix nicht als behoben erklärt.

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

- 392 Android-Tests, Discovery-Audit sauber; zwölf Python-Sprachtests.
- 177 native Firmware-Tests, darunter Router- und Display-Power-Regressionsfälle.
- Die sechs oben genannten Firmware-Builds erfolgreich, BLE-Memory-Guard für die fünf ESP32-Images bestanden.
- Zahleneingaben für #30 auf dem S24 einschließlich Löschen, führender Nullen,
  Überlauf, Hex-Eingabe, gesperrtem Übernehmen und Lesen-Rücksetzung geprüft.
- **Keine Firmware bei dieser Prüfung auf ein Board geflasht.**
- Vorhandene Firmware-Dateien unter `Firmwares/` bleiben die anhand ihres Manifests
  identifizierten Snapshots; ein Quellcode-Fix aktualisiert sie nicht automatisch.
