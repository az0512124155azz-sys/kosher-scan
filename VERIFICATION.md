# Verification — 2026-10-04

Environment: Windows host, Java 21, Gradle 8.7, SDK 35; Pixel 6 AVD running
Android 17 / API 37. No physical phone was connected.

## Automated checks

* 32 JVM tests: 17 certification/matching cases and 15 HTTP/repository cases.
* 4 Android instrumented smoke tests: bundled ML Kit EAN-13 decoding,
  rendering all three verdicts / Activity recreation / scan-again reset,
  an unclipped scan-again button after result text changes, and custom barcode
  dialog validation/cancellation.
* Android lint and APK compilation. Lint has no errors; warnings include
  newer dependency availability, Hebrew string localization, drawing allocations,
  portrait orientation, and the existing AGP 8.5 / SDK 35 compatibility warning.

HTTP tests use MockWebServer to exercise 404/status=0, 503, malformed JSON,
offline, DNS/transport failure, timeout, OU unavailable/malformed/timeout,
positive structured OU matching, empty results, image metadata and cancellation.
Green/red cards are tested with deterministic fixtures, not asserted as live
certification of any physical package.

## Manual emulator checks

* Fresh launch requests camera permission; denial displays recovery controls.
* Retry permits granting camera access; CameraX displays the virtual camera
  scene full-screen with the rounded scanning corners and animated green line.
* Entered `3017620422003` through the visible manual-entry dialog. Live OFF
  returned Nutella/Ferrero and its actual product photo, which loaded in the card.
  Live OU returned the exact product row `Nutella` / `Nutella`, symbol `OU-D`.
  Version 1.2 correctly displays KOSHER with the package-symbol condition and
  Passover exclusion. This fixes the previous rule that incorrectly rejected
  explicit product records whose name equaled the brand.
* Disabled both Wi-Fi and mobile data in the emulator: lookup displayed the
  explicit offline message with UNKNOWN and a retry button.
* Restored network and tapped retry: the product/photo returned successfully
  without restarting the application.
* Crash log buffer was empty during these manual checks.
* Manual-entry and camera-denial screens were inspected after replacing default
  rectangular/purple controls with the application's dark rounded cards and
  white rounded buttons.

## Limits

No physical-camera/package, autofocus under poor light, or real-device OEM
permission testing was possible. ML Kit decoded a generated barcode image and
CameraX's virtual camera ran; those are separate checks, not a claim that a real
package was scanned. Live external services can change. OU's public website
endpoint is not a contracted API, and name/brand matching cannot verify a
specific package, plant, batch or regional certification. Package-symbol
conditions are shown in positive results. The app intentionally returns UNKNOWN
where evidence is insufficient.
