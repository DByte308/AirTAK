# Firmware per USB installieren – technische Anleitung

Die Downloads stehen unter [Firmwares/](../Firmwares/README.md). Immer die Datei für
**genau das eigene Board** verwenden. Vor einer Neuinstallation Einstellungen sichern.

Diese Anleitung setzt Python und das jeweilige Flash-Werkzeug auf dem Computer voraus.
Es gibt noch keinen grafischen Ein-Klick-Installer. Unter ChromeOS sind zusätzlich eine
geeignete Linux-Umgebung und USB-Durchreichung nötig; dieser Weg ist nicht geprüft.

## Heltec V3/V4, LilyGO T3 S3 und T-Deck

Für die Erstinstallation **`firmware.factory.bin`** verwenden. Sie enthält Bootloader,
Partitionstabelle und Anwendung und wird mit **esptool 5** ab Adresse `0x0` geschrieben:

```bash
python -m esptool --chip esp32s3 --port /dev/ttyACM0 write-flash 0x0 firmware.factory.bin
```

Port anpassen: unter Windows beispielsweise `COM5`, unter macOS häufig
`/dev/cu.usbmodem…`. Das Board gegebenenfalls mit BOOT/RESET in den Downloadmodus
versetzen; Tastenfolge und Treiber richten sich nach dem Hersteller.

**Achtung:** Das Factory-Image kann Einstellungen und Board-Identität überschreiben.
Kein `erase-flash` ohne Sicherung ausführen.

**`firmware.bin`** ist dagegen nur die Anwendung für ein App-Update auf einem bereits
passend eingerichteten AirTAK-Board. Nicht ab `0x0` schreiben. Eine fremde
Partitionierung lässt sich nicht durch ein App-Update in eine Erstinstallation umwandeln.

## Heltec T114, LilyGO T-Echo und T-Echo Plus

Die **`firmware.zip` nicht entpacken**. Diese Pakete benötigen einen passenden
Adafruit-kompatiblen USB-DFU-Bootloader. In dessen Modus wechseln (üblicherweise
RESET zweimal schnell drücken) und mit **adafruit-nrfutil** übertragen:

```bash
adafruit-nrfutil dfu serial -p /dev/ttyACM0 -b 115200 --singlebank -pkg firmware.zip
```

Auch hier den USB-Port anpassen. Nicht durch ein beliebiges Nordic-nrfutil-Werkzeug
ersetzen: Die Pakete verwenden Adafruit/Legacy-DFU 0.5 und fordern SoftDevice-ID
`0xB6`. Bootloader und SoftDevice sind nicht enthalten. Bei einem leeren Board zuerst
die Bootloader-Installationsanleitung des jeweiligen Herstellers befolgen.
Die Dateien sind keine UF2-Dateien zum Kopieren auf ein USB-Laufwerk.

Die Kompatibilität dieser konkreten ZIPs mit dem App-Update ist **nicht auf Hardware
bestätigt**. Nicht allein aus der Dateiendung auf BLE-/USB-App-DFU-Kompatibilität schließen.

## Prüfung und Herkunft

Alle sieben produktiven Board-Varianten wurden lokal gebaut; 147 native Tests bestanden.
ESP32-Factory-Images wurden gegen Anwendung, Bootloader und Partitionstabelle geprüft,
nRF52-ZIPs auf Integrität, Manifest und BIN-/DAT-Inhalte. Kein neuer Hardwaretest aller
sieben Boards und kein Flashvorgang waren Teil der Veröffentlichung.
Experimentelle Runtime-/Selection-Testimages sind nicht als Download enthalten.

- [Hardware-Prüfstand](firmware-verification.md)
- [Dateien, Quellcommit und SHA-256-Werte](../Firmwares/manifest.json)
- [Prüfsummen](../Firmwares/SHA256SUMS): im Ordner `Firmwares/` mit `sha256sum -c SHA256SUMS` prüfen.

## Downloads lokal aktualisieren

```bash
./tools/setup_firmware_env.sh
python3 tools/package_firmwares.py
python3 tools/package_firmwares.py --verify
```

Firmware-Quelländerungen vor dem Bündeln committen. `--collect-only` nur verwenden,
wenn alle sieben Boards soeben erfolgreich gebaut wurden — vorhandene Builddateien
allein belegen keine Aktualität. Das Werkzeug flasht keine Geräte und lädt nichts hoch.
