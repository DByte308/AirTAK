# LoRa-Board-Firmwares herunterladen

Hier liegen die **lokal gebauten AirTAK-Firmwares für die sieben Board-Varianten**.
Auf GitHub den passenden Download anklicken und die Datei speichern.

> **Wichtig:** Exakt das eigene Board auswählen. Heltec V3, V4 und T114 sind
> unterschiedliche Geräte! Die Dateien sind Build-geprüfte Testversionen, keine
> Zusicherung, dass jede Variante auf echter Hardware getestet wurde.

## Downloads

| Board | USB-Erstinstallation | Update über die Android-App |
|---|---|---|
| **Heltec V3** | [firmware.factory.bin](Heltec-V3/firmware.factory.bin?raw=true) | [firmware.bin](Heltec-V3/firmware.bin?raw=true) |
| **Heltec V4** | [firmware.factory.bin](Heltec-V4/firmware.factory.bin?raw=true) | [firmware.bin](Heltec-V4/firmware.bin?raw=true) |
| **Heltec T114** | [firmware.zip – USB-DFU](Heltec-T114/firmware.zip?raw=true) | Bootloader-Kompatibilität beachten, siehe unten |
| **LilyGO T-LoRa T3 S3** | [firmware.factory.bin](LilyGO-T3S3/firmware.factory.bin?raw=true) | [firmware.bin](LilyGO-T3S3/firmware.bin?raw=true) |
| **LilyGO T-Deck** | [firmware.factory.bin](LilyGO-T-Deck/firmware.factory.bin?raw=true) | [firmware.bin](LilyGO-T-Deck/firmware.bin?raw=true) |
| **LilyGO T-Echo** | [firmware.zip – USB-DFU](LilyGO-T-Echo/firmware.zip?raw=true) | Bootloader-Kompatibilität beachten, siehe unten |
| **LilyGO T-Echo Plus** | [firmware.zip – USB-DFU](LilyGO-T-Echo-Plus/firmware.zip?raw=true) | Bootloader-Kompatibilität beachten, siehe unten |

### ESP32-S3: Heltec V3/V4, T3 S3 und T-Deck

- **`firmware.factory.bin`** ist das zusammengeführte USB-Image mit Bootloader,
  Partitionstabelle und Anwendung. Ab Adresse **`0x0`** flashen. Nur für
  Erstinstallation bzw. bewusste Neuinstallation; vorher Daten sichern.
  **Vorhandene Einstellungen und Board-Identität können dabei verloren gehen.**
- **`firmware.bin`** ist nur die Anwendung. Für Updates eines bereits mit einer
  passenden AirTAK-Partitionierung eingerichteten Boards in der App auswählen.
  **Nicht ab `0x0` flashen und nicht mit dem Factory-Image verwechseln.**
- Die App kann keinen beliebigen anderen Bootloader oder eine fremde
  Partitionierung in eine AirTAK-Erstinstallation umwandeln.

USB-Beispiel mit installiertem **esptool 5** (Port anpassen, Board ggf. mit
BOOT/RESET in den Downloadmodus versetzen):

```bash
python -m esptool --chip esp32s3 --port /dev/ttyACM0 write-flash 0x0 firmware.factory.bin
```

Unter Windows beispielsweise `--port COM5` verwenden. **Kein `erase-flash`
ohne vorherige Sicherung ausführen.** Die konkreten USB-Tasten und Treiber
richten sich nach dem Board.

### nRF52840: T-Echo, T-Echo Plus und Heltec T114

- Die **`firmware.zip`** bleibt ungeöffnet: Sie enthält die Anwendung und die
  DFU-Metadaten für den **Adafruit-kompatiblen USB-DFU-Bootloader**.
- Board in den Bootloader versetzen (üblicherweise RESET zweimal schnell drücken),
  dessen USB-Port auswählen und mit **adafruit-nrfutil** übertragen:

```bash
adafruit-nrfutil dfu serial -p /dev/ttyACM0 -b 115200 --singlebank -pkg firmware.zip
```

- **Keinen beliebigen Nordic-nrfutil-Ersatz verwenden.** Diese erzeugten Pakete
  haben das Adafruit/Legacy-DFU-Format 0.5, nicht automatisch das Nordic-Secure-DFU-Format.
  Die Pakete fordern SoftDevice-ID `0xB6`. Bootloader und SoftDevice müssen passen.
- **App-DFU ist für diese konkreten Pakete nicht als hardwareverifiziert
  ausgewiesen.** Nicht allein aus der Dateiendung `.zip` auf Kompatibilität mit
  jedem BLE-/USB-DFU-Bootloader schließen. Für diese Downloads den beschriebenen
  USB-Weg verwenden.
- Bootloader/SoftDevice sind **nicht** enthalten. Ein leeres Board ohne passenden
  Bootloader benötigt zunächst die Installationsanleitung des jeweiligen Herstellers.
- Dies sind **keine UF2-Dateien** zum Kopieren auf ein USB-Laufwerk.

## Was wurde geprüft?

- Alle sieben produktiven PlatformIO-Environments lokal erfolgreich gebaut.
- 147 native Firmware-Tests erfolgreich.
- ESP32-Dateien auf gültigen Image-Header und die Übereinstimmung von
  Anwendung, Bootloader und Partitionstabelle im Factory-Image geprüft.
- nRF52-ZIPs auf ZIP-Prüfsumme, Manifest und nicht leere BIN-/DAT-Dateien geprüft.
- Jede veröffentlichte Datei mit Größe und SHA-256-Prüfsumme erfasst.

**Kein Flashvorgang und kein neuer Sieben-Board-Hardwaretest** im Rahmen dieser
Veröffentlichung. Frühere Hardwaretests und deren Grenzen stehen in
[firmware-verification.md](../docs/firmware-verification.md).
Die experimentellen `runtime_s3_*`- und Selection-Testimages werden hier bewusst
nicht angeboten, insbesondere nicht das nicht flashbare `runtime_s3_shared`.

## Prüfsummen und Herkunft

- [SHA256SUMS](SHA256SUMS) – aus diesem Ordner prüfen: `sha256sum -c SHA256SUMS`
- [manifest.json](manifest.json) – Quellcommit, Fingerabdruck der Firmware-Quelldateien,
  Buildwerkzeug, Board-Zuordnung, Dateigrößen und SHA-256-Werte.

## Lokal neu bauen und diesen Ordner aktualisieren

```bash
./tools/setup_firmware_env.sh
python3 tools/package_firmwares.py
python3 tools/package_firmwares.py --verify
```

Das baut ausschließlich lokal, ohne GitHub-Actions-Runner. Firmware-Quelländerungen
vor dem Bündeln committen, damit die Herkunft eindeutig ist. Wenn alle sieben Boards
soeben erfolgreich gebaut wurden, kann `--collect-only` das erneute Bauen überspringen.
Ohne diese Voraussetzung **nicht** verwenden: Ein vorhandenes Buildverzeichnis allein
belegt keine Aktualität. Es werden keine Geräte automatisch geflasht und keine Dateien
von diesem Werkzeug automatisch nach GitHub hochgeladen.
