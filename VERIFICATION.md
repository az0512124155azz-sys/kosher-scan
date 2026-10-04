# Verification — 2026-10-04

Environment: Windows host, Java 21, Gradle 8.7, SDK 35; Pixel 6 AVD running
Android 17 / API 37. No physical phone was connected.

## Automated checks

* 49 JVM tests: 22 certification/matching cases, 19 HTTP/repository cases,
  and 8 conservative plain-water rule cases. All passed for version 1.3.0.
  Regression coverage includes specific certification labels without the generic
  kosher parent, raw labels, legacy categories, explicit negative precedence,
  Passover-only negatives, and rejecting ambiguous/social labels.
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

Version 1.3.0 follow-up:

* Entered the user's screenshot barcode `3800000602733`. Live OFF returned Devin,
  spring-water categories and the complete Bulgarian ingredient declaration
  `Изворна вода`. The app displays KOSHER under OU's general water-only guidance,
  requires checking that the package's only ingredient is water, and explicitly
  disclaims OU certification of the brand. No barcode/brand exceptions exist.
* Entered `0013764027053` (Dave's Killer Bread). Live OFF supplied explicit kosher
  labels. The actual card/photo loaded and displayed KOSHER with the community
  source and package-symbol condition. This is a separate non-water live case,
  not a claim to have reproduced all of the user's unspecified regressions.
* Unit fixtures reject flavored/vitamin/juice/coconut categories, additive or
  missing ingredients, conflicting translations, and names contradicting water-only
  metadata. The metadata still comes from a community source and may be incomplete.

Previous 1.2.0 manual checks (UI/scanner unchanged in this follow-up):

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
