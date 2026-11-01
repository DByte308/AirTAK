# Übersetzungen bearbeiten

AirTAK verwendet Android-Stringressourcen:

- Englisch: `android/app/src/main/res/values/strings.xml`
- Deutsch: `android/app/src/main/res/values-de/strings.xml`
- Die meisten UI-Texte stammen aus `ROWS` in `tools/i18n_strings.py`.
  Änderungen innerhalb der XML-Markierungen `i18n:begin generated` / `i18n:end generated`
  **in dieser Tabelle vornehmen**, nicht nur im erzeugten XML.
- Die Hilfe hat einen eigenen Generator: `tools/i18n_help.py`.
- Handgeschriebene Ressourcen außerhalb der generierten Bereiche, etwa die
  Navigationsbeschriftungen, können direkt im jeweiligen XML angepasst werden.

Nach Tabellenänderungen:

```bash
python3 tools/i18n_strings.py android/app/src/main/res
python3 tools/i18n_strings.py android/app/src/main/res check
python3 tools/check_i18n.py .
python3 -m unittest discover -s tools/tests -p 'test_check_i18n.py'
./tools/gradle_android.sh testDebugUnitTest assembleDebug
```

Platzhalter (`%1$s`, `%2$d` usw.) müssen in beiden Sprachen übereinstimmen.
Der Generator prüft doppelte Schlüssel und Schlüsselparität; die Python-Tests
prüfen zusätzlich nicht leere Übersetzungen und die nummerierten Platzhalter.

## Neue Texte im Code

- In Compose: `stringResource(R.string.schluessel, argument)`.
- In Callbacks: `context.getString(R.string.schluessel, argument)` mit dem lokalisierten
  Activity-Kontext. Ein Application-Kontext muss die App-Sprachwahl berücksichtigen
  (`withAppLanguage()`), nicht nur die Systemsprache.
- Validierungsfehler und bekannte Firmware-Fortschrittsschritte transportieren
  Ressourcen-IDs statt fertig eingebauter englischer Texte.
- Enum- und Profilnamen über übersetzte **Anzeigenamen** darstellen. Protokollwerte,
  gespeicherte Geräte-/Spielernamen und eigene Routingprofile nicht übersetzen.
- Technische Rohmeldungen externer Bibliotheken dürfen im Diagnoseprotokoll im
  Original bleiben. Normale Firmware-Fehleranzeigen sind übersetzt; die ursprüngliche
  Fehlermeldung bleibt zur Fehlersuche im Protokoll verfügbar.

## Grenzen der automatischen Prüfung

`check_i18n.py` untersucht mehrzeilige Textargumente, benannte Beschriftungen und
UI-Komponenten wie `TacticalText`, `StatusPill` und `LabelValue`. Es ist ein lexikalischer
Guard, kein Kotlin-Compiler und keine semantische Vollständigkeitsprüfung. Dynamisch
zusammengesetzte Texte aus Modellen sowie fremde Diagnosemeldungen müssen weiterhin
manuell geprüft werden. Ein grüner Guard allein belegt daher keine vollständig
übersetzte Oberfläche; zusätzlich Deutsch und Englisch auf einem Gerät prüfen.
