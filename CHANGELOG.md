# Changelog

## 0.1.0 — 2026-10-14

- Portable Windows application (Compose Desktop, bundled Java 21 and Android platform-tools adb).
- USB and Wi-Fi connection: live device list, `adb connect`, wireless-debugging pairing, USB → Wi-Fi switch.
- Drag-and-drop queue for APK files and cache folders; streamed `cmd package install` with progress.
- Cache layout detection (OBB, data, mirrored `Android/` folder, Rookie-style packages) with a
  confirmation dialog for unknown layouts and a Download-folder fallback when direct writes are denied.
- Installed-version check, downgrade and signature-mismatch handling with "Uninstall and retry".
- Tray notifications, session logs, settings for install flags and verification.
- Verified in-app updates from GitHub releases with backup and rollback of the previous version.
