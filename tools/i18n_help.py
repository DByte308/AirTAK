#!/usr/bin/env python3
"""Issue #11: generate the in-app Help guide from string resources.

`ui/help/HelpContent.kt` held the whole guide as Kotlin string literals, so none of it
could be translated. This script is the durable generator:

  * English is embedded below as ENGLISH. It was parsed out of the Kotlin guide this
    replaced, and it lives here now so `check` keeps working after HelpContent.kt stopped
    containing the text.
  * German is authored in GERMAN below, keyed by the same key names.
  * Output is res/values/help.xml and res/values-de/help.xml.

Keys are positional (`help_t3_e5_body`) because the guide has no natural ids and the
order is the on-screen order. Renumbering is safe: run `check` and the build fails if a
key referenced by HelpContent.kt is missing from either locale.

Usage:
    python3 tools/i18n_help.py android/app/src/main/res write
    python3 tools/i18n_help.py android/app/src/main/res check
"""
import pathlib
import re
import sys


# German guide text, keyed by resource name. Authored in context: the English entries are
# written as short imperative hints, and the German keeps that register rather than the
# English sentence structure.
GERMAN = {
    # Topic 1 — Getting started and pairing
    "help_t1_title": "Erste Schritte und Kopplung",
    "help_t1_intro": "Mit AirTAK findest du deine Teammitglieder und teilst Nachrichten und Marker während eines Airsoft-Spiels. Telefon oder Tablet sprechen per BLE mit dem Funkmodul; das Funkmodul bringt den Verkehr über das LoRa-Mesh hinaus.",
    "help_t1_e1_label": "Gerät koppeln",
    "help_t1_e1_body": "Schalte dein kompatibles AirTAK-Funkmodul ein und tippe oben in der App auf den Verbindungs-Chip. Erlaue Bluetooth- und Standortabfragen, suche danach das Gerät und wähle es aus.",
    "help_t1_e2_label": "Kopplungscode prüfen",
    "help_t1_e2_body": "Vergleiche den sechsstelligen Code auf dem E-Ink-Display des Funkmoduls mit dem Android-Kopplungsfenster. Bestätige nur, wenn beide Codes gleich sind.",
    "help_t1_e3_label": "Trennen und neu verbinden",
    "help_t1_e3_body": "„Trennen“ beendet die aktuelle Verbindung, das Funkmodul bleibt gespeichert. „Neu verbinden“ versucht es erneut; „Entfernen“ löscht die gespeicherte Kopplung, damit du neu beginnen kannst.",
    "help_t1_e4_label": "Vor dem Spielbeitritt",
    "help_t1_e4_body": "Setze dein Rufzeichen unter Konfiguration > Spieler und nutze dieselbe Funk- und Kanaleinstellung wie dein Team. Lade Kartendaten und Routendaten, solange du noch Internet hast.",
    "help_t1_e5_label": "Nutzung ohne Internet",
    "help_t1_e5_body": "Geladene Karten und Funkverkehr brauchen während des Spiels kein Mobilfunknetz. Ortssuche, Downloads und neues Kartenmaterial können weiterhin eine Internetverbindung benötigen.",
    # Topic 2 — Map and navigation
    "help_t2_title": "Karte und Navigation",
    "help_t2_intro": "In der Karte siehst du Positionen, geteilte Marker und deine geplante Route. Die zuletzt gemeldete Position eines Spielers kann alt sein — sieh vorher in der Roster nach, ob er wirklich noch dort ist.",
    "help_t2_e1_label": "Suche",
    "help_t2_e1_body": "Suche einen Ort, Platz oder Koordinaten und verschiebe die Karte dorthin. Nutze das, um dein Spielfeld zu finden, bevor du seine Karte lädst.",
    "help_t2_e2_label": "Marker hinzufügen",
    "help_t2_e2_body": "Wähle die Markerart und tippe auf die Karte, um ihre Punkte zu setzen. „Rückgängig“ entfernt den letzten Punkt, „Übernehmen“ öffnet die Details und „Senden“ teilt ihn mit dem gewählten Empfängerkreis.",
    "help_t2_e3_label": "Pause und Fortsetzen",
    "help_t2_e3_body": "„Pause“ markiert deine eigene Spielaktivität als inaktiv, „Fortsetzen“ wieder als aktiv. Das Spiel selbst wird für niemand anderen angehalten.",
    "help_t2_e4_label": "Spielerstatus",
    "help_t2_e4_body": "Tippe auf deinen Status, um Aktiv, HIT, RESPAWN oder ELIMINATED zu melden. Diese Meldungen informieren andere; die App durchsetzt die Platzregeln nicht.",
    "help_t2_e5_label": "Schaltfläche Spieler",
    "help_t2_e5_body": "Blendet Spielerpositionen auf der Karte ein oder aus. Das ändert nur deine Ansicht, nicht ob diese Spieler senden können.",
    "help_t2_e6_label": "Schaltfläche Wegpunkte",
    "help_t2_e6_body": "Blendet Wegpunkte ein oder aus. Zum Suchen, Ansehen oder Anfahren eines bestimmten Markers öffne den Marker-Bereich.",
    "help_t2_e7_label": "Kartenüberlagerung",
    "help_t2_e7_body": "Importiere ein Bild des Geländes oder Einsatzplans und richte es auf der Karte aus. Passe Ecken und Deckkraft an; „Ausrichtung teilen“ überträgt nur die Lage — das passende Bild braucht jede Person selbst.",
    "help_t2_e8_label": "Kacheln laden",
    "help_t2_e8_body": "Wähle zwei Ecken, prüfe Zoombereich und Kachel-URL und lade dann die Kartenkacheln. Lade eine überschaubare Fläche, bevor du den Internetempfang verlässt.",
    "help_t2_e9_label": "Schaltfläche Ebenen",
    "help_t2_e9_body": "Diese Schaltfläche wählt eine Fläche für BRouter-Routendaten, keine Kartenebenen. Lade diese Dateien, wenn Routen offline Straßen und Wegen folgen sollen.",
    "help_t2_e10_label": "Route planen",
    "help_t2_e10_body": "Tippe Start- und Zielpunkt, um eine Route zu berechnen. Ohne Routendaten zeigt die App eine gerade Linie; eine Route ist keine Zusage, dass der Weg erlaubt oder begehbar ist.",
    "help_t2_e11_label": "Routenprofil",
    "help_t2_e11_body": "Tippe auf den Profil-Chip, um die Berechnungsart zu wählen. Nimm das Profil, das zu deiner Fortbewegung passt, und prüfe die Route gegen die Regeln des Geländes.",
    "help_t2_e12_label": "Zentrieren",
    "help_t2_e12_body": "Bringt die Karte zurück auf deine eigene Position. Dafür muss eine verwertbare GPS-Position vorliegen.",
    "help_t2_e13_label": "Karten-Bereich",
    "help_t2_e13_body": "Zurück zur Feldkarte mit Zeichen- und Routenwerkzeug.",
    "help_t2_e14_label": "Net-Bereich",
    "help_t2_e14_body": "Funknachrichten lesen und senden, ohne die App zu verlassen.",
    "help_t2_e15_label": "Roster-Bereich",
    "help_t2_e15_body": "Spieler finden, ihre letzten Meldungen ansehen und Aktionen wie Nachricht oder Zeigen öffnen.",
    "help_t2_e16_label": "Marker-Bereich",
    "help_t2_e16_body": "Alle empfangenen Punkte, Routen, Flächen und Ziele durchsuchen.",
    "help_t2_e17_label": "Konfigurations-Bereich",
    "help_t2_e17_body": "Identität, Funk, Kanäle und App-Voreinstellungen ändern. Bei Fragen hilft hier die Anleitung.",
    # Topic 3 — Link and HUD readings
    "help_t3_title": "Verbindung und HUD-Werte",
    "help_t3_intro": "Die obere Leiste zeigt, ob die App ein Funkmodul erreicht und welche Geräteinformationen aktuell sind. Ein fehlender Wert ist nicht dasselbe wie eine Null.",
    "help_t3_e1_label": "Keine Verbindung",
    "help_t3_e1_body": "Die App ist gerade nicht per BLE mit dem Funkmodul verbunden. Tippe auf den Chip, um zu koppeln oder neu zu verbinden. Eine Verbindung zum Funkmodul bedeutet nicht, dass ein anderer Spieler im Mesh erreichbar ist.",
    "help_t3_e2_label": "Akku",
    "help_t3_e2_body": "Akkustand des Funkmoduls — nicht der Akkustand deines Telefons.",
    "help_t3_e3_label": "GPS",
    "help_t3_e3_body": "Satelliten- oder Fix-Information der gewählten Positionsquelle. Ohne Fix: freiere Himmelsrichtung und „Position & Energie“ prüfen.",
    "help_t3_e4_label": "RSSI",
    "help_t3_e4_body": "Empfangene Signalstärke in dBm. Nahe bei 0 ist stärker; der Wert ist keine Entfernung und keine Zustellzusicherung über das Mesh.",
    "help_t3_e5_label": "DC",
    "help_t3_e5_body": "Funkauslastung und Warnstufe des Funkmoduls. Hohe Last kann Sendungen verzögern oder Weiterleitungen kosten — besser unnötigen Verkehr reduzieren statt die Funkgrenzen zu umgehen.",
    # Topic 4 — Net and messages
    "help_t4_title": "Net und Nachrichten",
    "help_t4_intro": "Net trennt Kanalunterhaltungen von Direktnachrichten. Nachrichten laufen über das Funkmesh, die Zustellung hängt also an Funkabdeckung und freier Sendezeit.",
    "help_t4_e1_label": "Mesh",
    "help_t4_e1_body": "Der gemeinsame Hauptkanal für Verkehr im weiteren Mesh, inklusive Meshtastic-kompatibler Geräte. Ein offener Kanal ist öffentlich zu behandeln.",
    "help_t4_e2_label": "Spiel",
    "help_t4_e2_body": "Der zweite AirTAK-Kanal für die Spielabstimmung. Sein Name kann abweichen, wenn eure Gruppe den Kanal umbenannt hat.",
    "help_t4_e3_label": "Weitere Kanal-Tabs",
    "help_t4_e3_body": "Aktivierte Kanal-Slots erscheinen als Tabs. Wähle den Slot deiner Einheit und prüfe vor dem Senden, ob er offen oder verschlüsselt ist.",
    "help_t4_e4_label": "Direkt",
    "help_t4_e4_body": "Wähle einen gehörten Knoten, um eine Unterhaltung nur mit dieser Person zu führen statt mit dem ganzen Kanal. Das Mesh leitet die Nachricht weiter; direkte Adressierung ist keine Zustellzusicherung.",
    "help_t4_e5_label": "Nachrichten-Sichtbarkeit",
    "help_t4_e5_body": "Wähle „Alle Spieler“ oder eine Einheitsebene als vorgesehenen Empfängerkreis. Stimme Einheitshierarchie und passende Kanäle vorher mit dem Team ab.",
    "help_t4_e6_label": "Schreiben und senden",
    "help_t4_e6_body": "Nachricht schreiben und auf „Senden“ tippen. Kurz halten spart Funk-Sendezeit.",
    "help_t4_e7_label": "Byte-Zähler",
    "help_t4_e7_body": "Der Zähler zählt UTF-8-Bytes gegen das 237-Byte-Limit, keine Buchstaben. Akzente und andere Nicht-ASCII-Zeichen belegen mehr als ein Byte.",
    "help_t4_e8_label": "Aktualisieren",
    "help_t4_e8_body": "Fordere eine Synchronisation von Nachrichten und Markern an, wenn deine Ansicht etwas zu vermissen scheint. Daten, die kein erreichbares Funkmodul hat, kann sie nicht beschaffen.",
    # Topic 5 — Roster
    "help_t5_title": "Roster",
    "help_t5_intro": "Die Roster zeigt im Mesh gehörte Spieler, ihre Einheiten und ihre letzten Meldungen. Eine alte Meldung beweist weder Ort noch Status.",
    "help_t5_e1_label": "Suche und Filter",
    "help_t5_e1_body": "Suche ein Rufzeichen oder eine Einheit. Nutze die Filter, statt die ganze Liste durchzublättern.",
    "help_t5_e2_label": "Spieldetails",
    "help_t5_e2_body": "Öffne einen Spieler für Status, verfügbare Position und Funkinformationen. Prüfe das Alter der Meldungen, bevor du eine Bewegung darauf aufbaust.",
    "help_t5_e3_label": "Nachricht",
    "help_t5_e3_body": "Öffnet eine Direktnachrichten-Unterhaltung mit dieser Person.",
    "help_t5_e4_label": "Zeigen",
    "help_t5_e4_body": "Verschiebt die Karte auf die gemeldete Position. Ohne Position warte auf ein Update.",
    "help_t5_e5_label": "Ping",
    "help_t5_e5_body": "Fordert das Funkmodul des Spielers auf zu antworten. Eine ausbleibende Antwort kann schlechte Abdeckung bedeuten, nicht dass die Person das Feld verlassen hat.",
    "help_t5_e6_label": "Marker holen",
    "help_t5_e6_body": "Fordert Marker dieser Person an, um Lücken in deiner Markerliste zu füllen.",
    "help_t5_e7_label": "Abstimmungskick",
    "help_t5_e7_body": "Stimme in der gewählten Sichtbarkeit ab. Drei verschiedene Wähler im selben Bereich blenden die Person 24 Stunden in deiner App aus; ihre Paketleitet das Mesh weiterhin weiter.",
    # Topic 6 — Markers
    "help_t6_title": "Marker",
    "help_t6_intro": "Marker teilen Ort, Route, Fläche oder Ziel, ohne Koordinaten in den Chat zu tippen. Empfängerkreis, Partei und Priorität helfen dem Team beim Einordnen.",
    "help_t6_e1_label": "Suche und Filter",
    "help_t6_e1_body": "Durchsuche Bezeichnungen und filtere nach Art oder Sichtbarkeit. „Alle Filter löschen“, wenn ein Marker zu fehlen scheint.",
    "help_t6_e2_label": "Marker anfordern",
    "help_t6_e2_body": "Fordere ein Update von allen Spielern oder einem bestimmten Knoten an — nützlich nach dem Verbinden oder beim verspäteten Spielbeitritt.",
    "help_t6_e3_label": "Öffnen und anzeigen",
    "help_t6_e3_body": "Sieh Beschreibung, Urheber, Partei und Priorität und fahre den Marker auf der Karte an. „Navigieren“ berechnet eine Route mit dem gewählten Routenprofil.",
    "help_t6_e4_label": "Anlegen und bearbeiten",
    "help_t6_e4_body": "Setze Punkte oder zeichne Routen und Flächen mit dem Marker-Werkzeug der Karte. Vergib eine klare Bezeichnung und die gewünschte Sichtbarkeit vor dem Senden; eigene Marker kannst du bearbeiten.",
    "help_t6_e5_label": "Ziel",
    "help_t6_e5_body": "Ein Ziel-Marker zeigt eine Spielposition und die kontrollierende Partei. Stimm die Bedeutung mit den Schiedsrichtern ab — Punkte oder Einnahmeregeln leiten sich daraus nicht automatisch ab.",
    "help_t6_e6_label": "Löschen",
    "help_t6_e6_body": "Das Löschen eines eigenen Markers sendet einen Löschbefehl, damit andere ihre Kopien ebenfalls entfernen. Außerhalb der Funkreichweite kommt die Änderung möglicherweise verspätet an.",
    # Topic 7 — Config sections
    "help_t7_title": "Konfigurationsbereiche",
    "help_t7_intro": "„Lesen“ holt die aktuelle Einstellungsgruppe vom Funkmodul. „Anwenden“ sendet deine Änderungen dorthin; Funkänderungen können den LoRa-Empfang kurz unterbrechen, während BLE bestehen bleibt.",
    "help_t7_e1_label": "Spieler",
    "help_t7_e1_body": "Setze Rufzeichen, Gerätenamen und Kartenfarbe. Optional Namen für Trupp, Gruppe, Zug und Kompanie — und verknüpfe jede Ebene mit ihrem vereinbarten Kanal.",
    "help_t7_e2_label": "HIT-Taste am Gerät",
    "help_t7_e2_body": "Aktiviere den HIT-Schalter, wenn ein lange gedrückter Benutzerknopf des Funkmoduls einen Treffer melden soll. Kläre vor Spielbeginn, welcher Knopf und welche Geste das bei deiner Hardware ist.",
    "help_t7_e3_label": "LoRa-Funk",
    "help_t7_e3_body": "Wähle zulässige Region, Sendeleistung und PHY-Profile für euer Gelände. Stimm das mit dem Team ab — unpassende Profile verhindern, dass Funkmodule einander hören.",
    "help_t7_e4_label": "Kanäle",
    "help_t7_e4_body": "Verwalte acht Slots mit PSK bzw. gemeinsamem Schlüssel. Slot 0 und 1 sind Gemeinschafts- und Spielkanal, Slot 2 ist für künftige Audio reserviert, Slot 3 bis 7 können Einheitsverkehr tragen.",
    "help_t7_e5_label": "Passende Kanaleinstellung",
    "help_t7_e5_body": "Teammitglieder brauchen für den geplanten Einheitskanal denselben Slot, Namen und Schlüssel. Ein leerer Schlüssel macht den Kanal offen; für Positionsverteilung einen vereinbarten verschlüsselten Kanal nutzen.",
    "help_t7_e6_label": "Position & Energie",
    "help_t7_e6_body": "Wähle Telefon-GPS oder das GPS des Funkmoduls, den Positionskanal und das Sendeintervall. Smart Beaconing passt die Meldungen an; kürzere Intervalle kosten mehr Sendezeit und Energie.",
    "help_t7_e7_label": "Energieoptionen",
    "help_t7_e7_body": "Passe verfügbare Display-Timeout-Helligkeit an. Die GPS-Arbeitszyklus-Steuerung ist für eine spätere Phase vorgesehen — verlasse dich beim Stromsparen noch nicht darauf.",
    "help_t7_e8_label": "Sicherheit",
    "help_t7_e8_body": "Setze die erlaubte Anzahl BLE-Kopplungen des Funkmoduls. Das regelt gespeicherte Bluetooth-Kopplungen, nicht wer euren Kanal-Schlüssel kennt.",
    "help_t7_e9_label": "Moderation",
    "help_t7_e9_body": "Sieh aktive Abstimmungssperren, ihre Reichweite und Ablauf. „Entsperren“ zeigt die Person in deiner Ansicht wieder; ihr Funkmodul bleibt im Mesh.",
    "help_t7_e10_label": "Anwendung",
    "help_t7_e10_body": "Wähle Design und optionale Kartenserver-URL. Die URL braucht Zoom- und Koordinaten-Platzhalter des Servers, und eine Quelle, deren Download du überhaupt nutzen darfst.",
    "help_t7_e11_label": "Sprache",
    "help_t7_e11_body": "Wähle Englisch, Deutsch oder System für die Beschriftungen. Ein Sprachwechsel baut die Ansichten neu und schließt die gerade offene Ansicht.",
    "help_t7_e12_label": "Hilfe",
    "help_t7_e12_body": "Öffne diese Anleitung, um eine Bedienung nachzusehen oder die App auf ein Spiel vorzubereiten.",
    "help_t7_e13_label": "Konfiguration teilen",
    "help_t7_e13_body": "Exportiere einen QR für Teammitglieder oder scanne einen und prüfe die Übersicht vor dem Anwenden. Ein geteiltes Setup kann Kanal-Geheimnisse enthalten — zeige es nur Personen, die beitreten sollen.",
    "help_t7_e14_label": "Info",
    "help_t7_e14_body": "Prüfe Diagnose, starte den Funk neu oder das Gerät, wenn du etwas einkreist. „Firmware aktualisieren“ öffnet sich hier; die Werkseinstellung ist als spätere Funktion markiert, kein lauffähiger Notfallknopf.",
    "help_t7_e15_label": "Akku-Optimierung",
    "help_t7_e15_body": "Öffne die Android-Akkoeinstellungen, wenn Hintergrundbeschränkungen deine Verbindung ständig unterbrechen. Android regelt das, die Formularen unterscheiden sich zwischen Geräten.",
    # Topic 8 — Firmware updates
    "help_t8_title": "Firmware-Aktualisierungen",
    "help_t8_intro": "Die Firmware läuft auf dem Funkmodul, nicht in der Android-App. Spiele nur eine Datei auf, die exakt zu deinem Gerät und Board-Revision passt.",
    "help_t8_e1_label": "Datei wählen",
    "help_t8_e1_body": "Öffne Konfiguration > Info > Firmware aktualisieren und wähle das passende .bin- oder .zip-Paket. Flösche keine Datei für andere Hardware, nur weil der Name ähnlich aussieht.",
    "help_t8_e2_label": "Update über Bluetooth",
    "help_t8_e2_body": "Nutze BLE, wenn das Funkmodul gekoppelt und in der Nähe ist. Halte es während Übertragung, Prüfung und Neustart mit Strom und in Reichweite.",
    "help_t8_e3_label": "Update über USB-Kabel",
    "help_t8_e3_body": "Verbinde das Funkmodul mit einem Daten-USB-Kabel und wähle die USB-Aktualisierung. Erlaue Android bei Nachfrage den Zugriff auf das USB-Gerät.",
    "help_t8_e4_label": "Fortschritt und Prüfung",
    "help_t8_e4_body": "Warte, bis die App das Image übertragen und geprüft hat, und lass das Funkmodul neu starten. Trenne währenddessen weder Strom noch schalte aus.",
    "help_t8_e5_label": "Fehler und Protokoll",
    "help_t8_e5_body": "Lies Fehlermeldung und Protokoll, bevor du erneut versuchst. Prüfe Datei, Strom und Verbindung, statt wiederholt ein unbekanntes Image aufzuspielen.",
}


TITLE_RE = re.compile(r'title = "((?:[^"\\]|\\.)*)",\s*intro = "((?:[^"\\]|\\.)*)"', re.S)
ENTRY_RE = re.compile(r'HelpEntry\("((?:[^"\\]|\\.)*)",\s*"((?:[^"\\]|\\.)*)"\)')


ENGLISH = {'help_t1_title': 'Getting started and pairing',
 'help_t1_intro': 'AirTAK helps you find your teammates and share messages and markers during an '
                  'airsoft game. Your phone or tablet talks to the radio over BLE; the radio '
                  'carries traffic over the LoRa mesh.',
 'help_t1_e1_label': 'Pair a device',
 'help_t1_e1_body': 'Power on your compatible AirTAK radio and tap the link chip at the top of the '
                    'app. Grant Bluetooth and location permissions when asked, then scan and '
                    'select your radio.',
 'help_t1_e2_label': 'Check the pairing code',
 'help_t1_e2_body': "Compare the six-digit code on the radio's e-ink display with the Android "
                    'pairing prompt. Confirm only if both codes match.',
 'help_t1_e3_label': 'Disconnect and reconnect',
 'help_t1_e3_body': 'Disconnect drops the current link but keeps the radio remembered. Reconnect '
                    'tries that radio again; Forget removes the saved pairing so you can start '
                    'over.',
 'help_t1_e4_label': 'Before you join the game',
 'help_t1_e4_body': 'Set your callsign under Config > Player and use the same radio and channel '
                    "setup as your teammates. Download the field's map and routing data while you "
                    'still have internet access.',
 'help_t1_e5_label': 'Offline use',
 'help_t1_e5_body': 'Downloaded maps and radio traffic do not need mobile internet during play. '
                    'Location searches, downloads and new map imagery may still need an internet '
                    'connection.',
 'help_t2_title': 'Map and navigation',
 'help_t2_intro': "Use the map to see positions, shared markers and your planned route. A player's "
                  'last reported position may be old, so check the roster before assuming they are '
                  'still there.',
 'help_t2_e1_label': 'Search',
 'help_t2_e1_body': 'Find a town, place or coordinate and move the map there. Use this to find '
                    'your field before downloading its map.',
 'help_t2_e2_label': 'Add marker',
 'help_t2_e2_body': 'Choose the kind of marker to draw, then tap the map to place its points. Undo '
                    'removes the last point, Commit opens its details, and Send shares it with the '
                    'selected audience.',
 'help_t2_e3_label': 'Pause and resume',
 'help_t2_e3_body': 'Pause marks your own game activity as inactive; resume makes it active again. '
                    'This does not pause the game for everyone else.',
 'help_t2_e4_label': 'Player status',
 'help_t2_e4_body': 'Tap your status to report Active, HIT, RESPAWNING or ELIMINATED as '
                    'appropriate. These reports inform other players; the app does not enforce the '
                    "site's game rules.",
 'help_t2_e5_label': 'Players button',
 'help_t2_e5_body': 'Show or hide player positions on the map. This changes your map view, not '
                    'whether those players can send updates.',
 'help_t2_e6_label': 'Waypoints button',
 'help_t2_e6_body': 'Show or hide waypoint markers on the map. Open the Markers tab when you want '
                    'to search, inspect or locate a particular shared marker.',
 'help_t2_e7_label': 'Imagery',
 'help_t2_e7_body': 'Import an image of the field or mission plan and align it with the map. '
                    'Adjust its corners and opacity; sharing alignment sends its placement, so '
                    'another player still needs the matching image file.',
 'help_t2_e8_label': 'Download tiles',
 'help_t2_e8_body': 'Tap two corners to select an area, check the zoom range and tile URL, then '
                    'download its map tiles. Download a manageable area before leaving internet '
                    'coverage.',
 'help_t2_e9_label': 'Layers button',
 'help_t2_e9_body': 'This button selects an area for downloading BRouter routing data, not a list '
                    'of map layers. Download these files if you want routes that follow roads and '
                    'trails offline.',
 'help_t2_e10_label': 'Plan route',
 'help_t2_e10_body': 'Tap a start point and an end point to calculate a route. Without the '
                     'required routing data, the app falls back to a straight line; a route is not '
                     'a guarantee that access is allowed or the ground is safe.',
 'help_t2_e11_label': 'Routing profile',
 'help_t2_e11_body': 'Tap the profile chip to choose how a route is calculated. Pick the profile '
                     'that suits how you plan to move, then check the resulting route against the '
                     "field's rules.",
 'help_t2_e12_label': 'Recenter',
 'help_t2_e12_body': 'Return the map to your own position after looking elsewhere. A usable GPS '
                     'position must be available first.',
 'help_t2_e13_label': 'Map tab',
 'help_t2_e13_body': 'Return to the field map and its drawing and route tools.',
 'help_t2_e14_label': 'Net tab',
 'help_t2_e14_body': 'Read and send radio messages without leaving the app.',
 'help_t2_e15_label': 'Roster tab',
 'help_t2_e15_body': 'Find players, inspect their latest reports and open actions such as Message '
                     'or Locate.',
 'help_t2_e16_label': 'Markers tab',
 'help_t2_e16_body': 'Browse the shared points, routes, areas and objectives you have received.',
 'help_t2_e17_label': 'Config tab',
 'help_t2_e17_body': 'Change your identity, radio, channels and app preferences. Use Help here '
                     'when you need a reminder during setup.',
 'help_t3_title': 'Link and HUD readings',
 'help_t3_intro': 'The top bar helps you check whether the app has a radio link and recent device '
                  'information. Missing readings are not the same as a zero reading.',
 'help_t3_e1_label': 'No link',
 'help_t3_e1_body': 'The app is not currently connected to its radio over BLE. Tap this chip to '
                    'open device pairing or reconnect; being linked to a radio does not prove that '
                    'another player is within mesh reach.',
 'help_t3_e2_label': 'BAT',
 'help_t3_e2_body': "Check the battery level reported by your radio. It is not your phone's "
                    'battery level.',
 'help_t3_e3_label': 'GPS',
 'help_t3_e3_body': 'Check satellite or fix information from the selected position source. If no '
                    'fix is available, give the receiver a clearer view of the sky and check '
                    'Position & Power.',
 'help_t3_e4_label': 'RSSI',
 'help_t3_e4_body': 'Check the reported received signal strength in dBm. A value closer to zero is '
                    'stronger, but this reading is not a distance measurement or a guarantee of '
                    'delivery across the mesh.',
 'help_t3_e5_label': 'DC',
 'help_t3_e5_body': "Check the radio's duty-cycle load and warning level. High load can delay "
                    'transmissions or cause relays to be dropped; reduce unnecessary traffic '
                    'rather than bypassing the radio limits.',
 'help_t4_title': 'Net and messages',
 'help_t4_intro': 'Net keeps channel conversations separate from direct messages. Messages travel '
                  'over the radio mesh, so delivery depends on radio coverage and available '
                  'airtime.',
 'help_t4_e1_label': 'Mesh',
 'help_t4_e1_body': 'Use the primary shared channel for traffic intended for the wider mesh, '
                    'including compatible Meshtastic users. Treat an open channel as public.',
 'help_t4_e2_label': 'Game',
 'help_t4_e2_body': 'Use the secondary AirTAK channel for game coordination. Its name can differ '
                    'if your group has renamed the channel.',
 'help_t4_e3_label': 'Other channel tabs',
 'help_t4_e3_body': 'Enabled channel slots appear as tabs. Select the slot used by your unit and '
                    'check whether it is open or encrypted before sending.',
 'help_t4_e4_label': 'Direct',
 'help_t4_e4_body': 'Choose a heard node to open a conversation addressed to that player rather '
                    'than the whole channel. The mesh may relay the message along the way; private '
                    'addressing is not a promise of delivery.',
 'help_t4_e5_label': 'Message scope',
 'help_t4_e5_body': 'Choose All players or a unit level to set the intended audience. Set up your '
                    'unit hierarchy and matching channels with your team before relying on scoped '
                    'traffic.',
 'help_t4_e6_label': 'Write and send',
 'help_t4_e6_body': 'Type your message and tap Send. Keep it short so it takes less radio airtime.',
 'help_t4_e7_label': 'Byte counter',
 'help_t4_e7_body': 'The counter measures UTF-8 bytes against the 237-byte limit, not letters. '
                    'Accents and other non-ASCII characters can use more than one byte.',
 'help_t4_e8_label': 'Refresh',
 'help_t4_e8_body': 'Request a sync of messages and markers when you think your local view is '
                    'missing information. It cannot recover data that no reachable radio has '
                    'available.',
 'help_t5_title': 'Roster',
 'help_t5_intro': 'The roster shows players heard on the mesh, their units and their latest '
                  'reports. An old report does not prove a player is still in the same place or '
                  'status.',
 'help_t5_e1_label': 'Search and filters',
 'help_t5_e1_body': 'Search a callsign or unit to find a teammate. Use the available filters to '
                    'narrow the list rather than scrolling through everyone.',
 'help_t5_e2_label': 'Player details',
 'help_t5_e2_body': 'Open a player to inspect their status and available position and radio '
                    'information. Check how old those reports are before using them to coordinate '
                    'a move.',
 'help_t5_e3_label': 'Message',
 'help_t5_e3_body': 'Open a direct conversation with this player.',
 'help_t5_e4_label': 'Locate',
 'help_t5_e4_body': "Move the map to the player's reported position. If no position is available, "
                    'wait for an update.',
 'help_t5_e5_label': 'Ping',
 'help_t5_e5_body': "Send a request to the player's radio to check whether it responds. A missing "
                    'response can mean poor coverage, not that the player has left the field.',
 'help_t5_e6_label': 'Get markers',
 'help_t5_e6_body': 'Request markers from this player to fill gaps in your local marker list.',
 'help_t5_e7_label': 'Vote-kick',
 'help_t5_e7_body': 'Cast a vote at the selected scope. Three distinct voters at the same scope '
                    'hide that player from the local interface for 24 hours; their radio packets '
                    'still pass through the mesh.',
 'help_t6_title': 'Markers',
 'help_t6_intro': 'Markers let you share a place, route, area or objective without typing '
                  'coordinates into chat. Their audience, team and priority help your group decide '
                  'what each marker means.',
 'help_t6_e1_label': 'Search and filters',
 'help_t6_e1_body': 'Search labels and narrow the list by type or scope. Clear all filters if a '
                    'marker seems to be missing.',
 'help_t6_e2_label': 'Request markers',
 'help_t6_e2_body': 'Request an update from all players or a particular heard node. This is useful '
                    'after reconnecting or joining a game late.',
 'help_t6_e3_label': 'Open and locate',
 'help_t6_e3_body': "Inspect a marker's description, creator, team and priority, then locate it on "
                    'the map. Navigate calculates a route toward it using your selected routing '
                    'profile.',
 'help_t6_e4_label': 'Create and edit',
 'help_t6_e4_body': "Use the map's add-marker tool to place a point or draw a route or area. Set a "
                    'clear label and the intended visibility before sending; edit controls are '
                    'available for markers you own.',
 'help_t6_e5_label': 'Objective',
 'help_t6_e5_body': 'Use an objective marker to show a game location and its controlling team. '
                    'Agree on its meaning with the marshals; it does not automatically award '
                    'points or enforce capture rules.',
 'help_t6_e6_label': 'Delete',
 'help_t6_e6_body': 'Deleting a marker you own broadcasts a removal record so other players can '
                    'remove their copies too. Players outside radio reach may not see that change '
                    'immediately.',
 'help_t7_title': 'Config sections',
 'help_t7_intro': 'Read loads the current group of settings from the radio. Apply sends your edits '
                  'to it; changing radio settings can briefly interrupt LoRa reception while BLE '
                  'stays connected.',
 'help_t7_e1_label': 'Player',
 'help_t7_e1_body': 'Set your callsign, device names and map colour. Add optional Fireteam, Squad, '
                    'Platoon and Company names and link each unit level to its agreed channel.',
 'help_t7_e2_label': 'Device HIT button',
 'help_t7_e2_body': "Enable the HIT toggle if you want a long press on the radio's user button to "
                    'report a hit. Make sure you know which button and gesture your hardware uses '
                    'before the game starts.',
 'help_t7_e3_label': 'LoRa Radio',
 'help_t7_e3_body': 'Choose the legal region, TX power and PHY profiles for your site. Coordinate '
                    'these with your team; incompatible profiles can prevent radios hearing each '
                    'other.',
 'help_t7_e4_label': 'Channels',
 'help_t7_e4_body': 'Manage eight slots and their PSKs or shared passwords. Slots 0 and 1 provide '
                    'the shared and game channels, slot 2 is reserved for future audio, and slots '
                    '3 to 7 can carry unit traffic.',
 'help_t7_e5_label': 'Matching channel settings',
 'help_t7_e5_body': 'Teammates need matching slot, name and password settings for the intended '
                    'unit channel. A blank password makes a channel open; use an agreed encrypted '
                    'channel for position sharing.',
 'help_t7_e6_label': 'Position & Power',
 'help_t7_e6_body': "Choose phone GPS or the radio's GPS, the position channel and broadcast "
                    'timing. Smart beaconing adjusts position reports; shorter intervals use more '
                    'airtime and power.',
 'help_t7_e7_label': 'Power controls',
 'help_t7_e7_body': 'Adjust the available display timeout and brightness settings. The GPS '
                    'duty-cycle control is marked for a later development phase, so do not depend '
                    'on it for battery savings yet.',
 'help_t7_e8_label': 'Security',
 'help_t7_e8_body': 'Set the allowed BLE bond count for the radio. This controls remembered '
                    'Bluetooth pairings, not who shares your mesh channel password.',
 'help_t7_e9_label': 'Moderation',
 'help_t7_e9_body': 'Review active vote-kick bans, their scopes and expiry. Unban restores a '
                    'hidden player in your local view; it does not remove their radio from the '
                    'mesh.',
 'help_t7_e10_label': 'Application',
 'help_t7_e10_body': 'Choose your theme and optional map tile server. The tile URL needs the '
                     "server's zoom and coordinate placeholders; use a source you are allowed to "
                     'download from.',
 'help_t7_e11_label': 'Language',
 'help_t7_e11_body': "Choose English, German or the system default for the app's labels. Changing "
                     'language rebuilds the screens and closes the view you had open.',
 'help_t7_e12_label': 'Help',
 'help_t7_e12_body': 'Open this guide when you need to check a control or prepare the app for a '
                     'game.',
 'help_t7_e13_label': 'Share Config',
 'help_t7_e13_body': 'Export a QR setup for teammates or scan one and review its summary before '
                     'applying it. A shared setup can contain channel secrets, so show it only to '
                     'people who should join your group.',
 'help_t7_e14_label': 'About',
 'help_t7_e14_body': 'Inspect diagnostics, restart the radio or reboot the device when '
                     'troubleshooting. Open Update Firmware here; factory reset is marked as a '
                     'later feature, not a working recovery button.',
 'help_t7_e15_label': 'Battery Optimization',
 'help_t7_e15_body': "Open Android's battery settings if background restrictions keep interrupting "
                     'your connection. Android controls this setting, so its wording can vary '
                     'between devices.',
 'help_t8_title': 'Firmware updates',
 'help_t8_intro': 'Firmware runs on the radio, not in the Android app. Update it with a file '
                  'intended for your exact device and board revision.',
 'help_t8_e1_label': 'Choose a file',
 'help_t8_e1_body': 'Open Config > About > Update Firmware and select the appropriate .bin or .zip '
                    'package. Do not flash a file for different hardware just because its name '
                    'looks similar.',
 'help_t8_e2_label': 'Update via Bluetooth',
 'help_t8_e2_body': 'Use BLE when the radio is paired and nearby. Keep it powered and within range '
                    'throughout transfer, verification and restart.',
 'help_t8_e3_label': 'Update via USB cable',
 'help_t8_e3_body': 'Connect the radio with a data-capable USB cable and use the USB update '
                    'option. Grant Android access to the USB device if prompted.',
 'help_t8_e4_label': 'Progress and verification',
 'help_t8_e4_body': 'Wait while the app transfers and verifies the image, then let the radio '
                    'restart. Do not unplug it or switch it off during the update.',
 'help_t8_e5_label': 'Failure and logs',
 'help_t8_e5_body': 'If the update fails, read the error and debug log before retrying. Check the '
                    'file, power and connection rather than repeatedly flashing an unknown image.'}


def esc(value: str) -> str:
    return (
        value.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("'", "\\'")
    )


def build():
    en, de = dict(ENGLISH), {}
    missing = sorted(set(en) - set(GERMAN))
    extra = sorted(set(GERMAN) - set(en))
    if missing:
        raise SystemExit(f"German text missing for: {missing}")
    if extra:
        raise SystemExit(f"German text has no matching English key: {extra}")
    for key in en:
        de[key] = GERMAN[key]
    return en, de


def render(values, header):
    out = [f'<?xml version="1.0" encoding="utf-8"?>', "<resources>", f"    {header}", ""]
    for key, value in values.items():
        out.append(f'    <string name="{key}">{esc(value)}</string>')
    out.append("</resources>")
    return "\n".join(out) + "\n"


def main() -> int:
    base = pathlib.Path(sys.argv[1])
    mode = sys.argv[2] if len(sys.argv) > 2 else "write"
    en, de = build()
    for path, values, header in (
        (base / "values/help.xml", en, "<!-- In-app Help guide (English). Generated by tools/i18n_help.py. -->"),
        (base / "values-de/help.xml", de, "<!-- In-App-Hilfe (Deutsch). Erzeugt von tools/i18n_help.py. -->"),
    ):
        text = render(values, header)
        if mode == "check":
            if path.read_text(encoding="utf-8") != text:
                raise SystemExit(f"{path}: out of date, re-run tools/i18n_help.py write")
            print(f"{path}: up to date")
            continue
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        print(f"{path}: {len(values)} strings")
    topics = len({k.split("_")[1] for k in en})
    print(f"help guide: {topics} topics, {len(en)} strings, both locales")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
