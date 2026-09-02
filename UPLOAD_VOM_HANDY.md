# Komplettes Projekt vom Android-Handy hochladen

Der GitHub-Dateidialog in Brave zeigt auf manchen Samsung-Geräten nur Kamera
und Fotos. Außerdem würde eine einzelne ZIP im Repository den APK-Workflow nicht
startfähig machen. Mit Termux bleiben alle Ordner erhalten, einschließlich
`.github/workflows/`.

## 1. Termux installieren

Termux aus F-Droid installieren und öffnen. Danach nacheinander ausführen:

```bash
termux-setup-storage
pkg update
pkg install git gh unzip
```

Den Speicherzugriff erlauben.

## 2. Bei GitHub anmelden

```bash
gh auth login --web --git-protocol https
```

`GitHub.com` auswählen und den angezeigten Einmalcode im Browser bestätigen.
Kein Passwort oder Token gehört in das Projekt.

## 3. ZIP entpacken und Repository befüllen

Die heruntergeladene Datei `NexusSkyrimRadar_v0.7_GitHub_APK(2).zip` muss im
Ordner `Download` liegen. Dann:

```bash
NEXUS_UPLOAD_DIR="$(mktemp -d "$PREFIX/tmp/nexus-radar-XXXXXX")"
unzip ~/storage/downloads/'NexusSkyrimRadar_v0.7_GitHub_APK(2).zip' -d "$NEXUS_UPLOAD_DIR/source"
git clone https://github.com/buldid1231/Nexus-analyser.git "$NEXUS_UPLOAD_DIR/repo"
cp -a "$NEXUS_UPLOAD_DIR/source/NexusSkyrimRadar_v0.7_project/." "$NEXUS_UPLOAD_DIR/repo/"
cd "$NEXUS_UPLOAD_DIR/repo"
git config user.name "buldid1231"
git config user.email "android-upload@users.noreply.github.com"
git add .
git commit -m "Add Nexus Skyrim Radar v0.7 Android project"
git push origin main
```

## 4. APK herunterladen

Der Push startet den Workflow automatisch. Im Repository:

1. `Actions` öffnen.
2. Den neuesten Lauf `Build Android APK` öffnen.
3. Warten, bis der Lauf grün ist.
4. Unter `Artifacts` auf `NexusSkyrimRadar-v0.7-debug-apk` tippen.
5. Die geladene ZIP entpacken und die APK installieren.

Android kann vor der Installation fragen, ob Brave oder Eigene Dateien Apps aus
dieser Quelle installieren darf. Das nur für diese selbst erstellte Test-APK
erlauben.
