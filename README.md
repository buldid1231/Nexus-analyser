# Nexus Skyrim Radar v0.16

Android-Prototyp zum Sammeln und Filtern von Nexus-Mod-Metadaten für Skyrim Special Edition / Anniversary Edition.

## Kernfunktionen

- integrierter Nexus-WebView mit normaler Nutzer-Session
- lokale Room/SQLite-Datenbank
- NEW / UPDATED / UNCHANGED Klassifizierung
- Zeitraum 1 Tag bis 6 Jahre
- persistente Scan-Queue mit Pause/Fortsetzen
- Ein-Klick-Komplettscan: Listenseiten sammeln und Mod-Queue laufen gemeinsam im Hintergrund
- neueste Nexus-Aktualisierungen werden immer zuerst verarbeitet; unbekannte Datumswerte zuletzt
- temporäre Netzwerk-/WebView-/Parserfehler werden automatisch zweimal mit Wartezeit wiederholt
- echter Android-Hintergrundscan: Der gesamte Lauf bleibt beim Wechsel zu YouTube oder anderen Apps aktiv
- laufende Fortschrittsbenachrichtigung mit Pausen-Aktion und sichtbare Fertigmeldung
- Scanberichte für die letzten 20 abgeschlossenen Läufe mit Dauer und Ergebniszählern
- Fehlerzentrale mit Modname, ID, Versuchen und konkreter Fehlermeldung
- fehlgeschlagene Mods lassen sich aus einem Bericht gesammelt erneut prüfen
- Tippen auf die Fertigmeldung öffnet direkt den neuesten Scanbericht
- absturzsichere Queue: Der aktuelle Mod bleibt bis zum erfolgreichen Scan gespeichert
- Smart-Scan-Gedächtnis: bekannte Mods werden nur bei neuerem Nexus-Datum oder geänderter Version erneut geöffnet
- mehrseitiges Sammeln über Pagination, „Mehr laden“ und endloses Scrollen statt einer 20-Mod-Grenze
- sichtbare Zähler für neue Mods, Updates und übersprungene unveränderte Mods
- Requirements und `Mods requiring this file`
- Adult-Metadaten bleiben erhalten, wenn sie in der eigenen Nexus-Sitzung sichtbar sind
- Translation/Localization, Screenshots/Bilder, Videos und Savegames werden ausgeschlossen
- JSON-Chunk-Export, Standard 100 Mods pro Block
- dauerhaft gespeicherter Exportordner mit sichtbarer Fehlerprüfung
- Export wahlweise NEW + UPDATED, Zeitraum oder gesamter Katalog
- eigener Exportumfang „Seit letztem Export“ mit dauerhaftem Status pro Mod
- echte Änderungshistorie mit vorheriger Version und vorherigem Nexus-Update-Datum
- Katalogfilter und sichtbare Kennzeichnung für noch nicht exportierte Änderungen
- geprüfter ZIP-Export mit Manifest, Dateigrößen und SHA-256-Prüfsummen
- automatische Rückleseprüfung nach dem Schreiben in den Android-Zielordner
- direktes Teilen des fertigen ZIP-Pakets über das Android-Teilen-Menü
- einzelner JSON-Chunk-Export bleibt als kompatible Alternative erhalten
- Vollbackup und Wiederherstellung für Katalog, Abhängigkeiten, Tags, Einstellungen, Filter, Queue und Scanberichte
- Backups werden vor dem Ersetzen vorhandener Daten vollständig geprüft
- Katalogfilter und Sortierung bleiben nach einem App-Neustart erhalten
- nach echten Nexus-Kategorien gruppierter, durchsuchbarer Katalog
- Hamburger-Menü mit Kategorie-, Status-, Zeitraum-, Größen-, NSFW- und SKSE-Filtern
- Sortierung nach Alter, Name, Dateigröße, Endorsements und Downloads
- aufklappbare Modkarten mit Autor, Statistiken, Abhängigkeitszählern und Änderungsverlauf
- optionale Erfassung der größten Hauptdatei über den Nexus-Files-Tab
- vollständiges JSON-Schema 8 einschließlich leerer Listen und Standardfelder
- `required_by_count` wird auch aus dynamischen Nexus-Abschnitten zuverlässig übernommen
- JSON-Exporte enthalten Scanstart sowie Beginn und Ende des gewählten Zeitraums
- robuster Parser mit Lade-Wiederholungen für die dynamische Nexus-Seite
- automatische Tests für die Update-Entscheidung vor jedem APK-Build
- GitHub Actions Workflow testet den Code und baut Debug- sowie ausgerichtete Release-APK
- die weitergegebene APK kann dauerhaft mit demselben privaten Release-Key signiert werden

Siehe `BUILD_APK.md` für den APK-Build.

Nur mit einem Android-Handy: siehe `UPLOAD_VOM_HANDY.md`.
