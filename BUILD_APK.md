# APK automatisch mit GitHub Actions bauen

Dieses Projekt enthält `.github/workflows/android-apk.yml`.

> Wichtig: Die Projekt-ZIP darf nicht als einzelne Datei in GitHub liegen. GitHub
> muss `app/`, `gradle/`, `gradlew`, `build.gradle.kts` und `.github/` direkt im
> Repository sehen. Auf Android ist dafür der Termux-Weg in
> `UPLOAD_VOM_HANDY.md` vorgesehen.

## Einmalige Einrichtung

1. Ein neues GitHub-Repository erstellen.
2. Den kompletten Inhalt dieses Ordners in das Repository hochladen. `gradlew`, `app/`, `gradle/` und `.github/` müssen direkt im Repository-Stamm liegen.
3. Im Repository den Tab **Actions** öffnen.
4. Workflow **Build Android APK** auswählen.
5. **Run workflow** drücken und `main` auswählen.
6. Nach erfolgreichem Lauf den Workflow öffnen.
7. Unter **Artifacts** `NexusSkyrimRadar-v0.10-debug-apk` herunterladen.
8. ZIP entpacken und `NexusSkyrimRadar-v0.10-debug.apk` auf Android installieren.

## Automatisch

Jeder Push auf `main` baut ebenfalls eine neue Debug-APK.

## Android-Hinweis

Die APK ist eine Debug-APK und wird von GitHub Actions mit dem Debug-Key signiert. Für private Tests reicht das. Für eine spätere veröffentlichbare Release-APK sollte ein eigener Signing-Key verwendet werden.
