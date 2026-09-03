# Nexus Skyrim Radar v0.10

Android-Prototyp zum Sammeln und Filtern von Nexus-Mod-Metadaten für Skyrim Special Edition / Anniversary Edition.

## Kernfunktionen

- integrierter Nexus-WebView mit normaler Nutzer-Session
- lokale Room/SQLite-Datenbank
- NEW / UPDATED / UNCHANGED Klassifizierung
- Zeitraum 1 Tag bis 6 Jahre
- persistente Scan-Queue mit Pause/Fortsetzen
- Requirements und `Mods requiring this file`
- Adult-Metadaten bleiben erhalten, wenn sie in der eigenen Nexus-Sitzung sichtbar sind
- Translation/Localization, Screenshots/Bilder, Videos und Savegames werden ausgeschlossen
- JSON-Chunk-Export, Standard 100 Mods pro Block
- dauerhaft gespeicherter Exportordner mit sichtbarer Fehlerprüfung
- Export wahlweise NEW + UPDATED, Zeitraum oder gesamter Katalog
- nach echten Nexus-Kategorien gruppierter, durchsuchbarer Katalog
- Hamburger-Menü mit Kategorie-, Status-, Zeitraum-, Größen-, NSFW- und SKSE-Filtern
- Sortierung nach Alter, Name, Dateigröße, Endorsements und Downloads
- aufklappbare Modkarten mit Autor, Statistiken und Abhängigkeitszählern
- optionale Erfassung der größten Hauptdatei über den Nexus-Files-Tab
- vollständiges JSON-Schema 8 einschließlich leerer Listen und Standardfelder
- `required_by_count` wird auch aus dynamischen Nexus-Abschnitten zuverlässig übernommen
- JSON-Exporte enthalten Scanstart sowie Beginn und Ende des gewählten Zeitraums
- robuster Parser mit Lade-Wiederholungen für die dynamische Nexus-Seite
- GitHub Actions Workflow baut eine installierbare Debug-APK

Siehe `BUILD_APK.md` für den APK-Build.

Nur mit einem Android-Handy: siehe `UPLOAD_VOM_HANDY.md`.
