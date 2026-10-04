# Kosher Scan for Android

Native CameraX + bundled ML Kit scanner, Hebrew RTL UI based on `index.html`.
The HTML remains the original design reference; the Android APK does not load it.

## Build and test

Use Java 17 or 21, Android SDK 35 and the checked-in Gradle 8.7 wrapper:

```
./gradlew testDebugUnitTest lintDebug assembleDebug
./gradlew connectedDebugAndroidTest
```

The second command needs a running emulator/device. The first produces
`app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions builds the same tasks,
compiles the instrumented tests, and uploads the APK and verification reports.
Instrumented tests are executed locally, not on the CI runner.

## Data and status policy

* Product names/photos: Open Food Facts API v2, a community-maintained source.
* Certification: structured product rows from the public endpoint used by
  [OU's product search](https://oukosher.org/product-search/),
  `https://productsearch-v2.oukosher.org/api/v1/product`.
  This is a website integration, not a guaranteed/supported third-party API.
* Only `KOSHER`, `NOT_KOSHER`, `UNKNOWN` are certification statuses.
  Transport errors are separate from these three statuses.
* `KOSHER` requires an exact normalized brand and product name (including flavor,
  original/sugar-free variants), a record identifier, a recognized OU symbol,
  and known certification conditions. Brand-only/generic product names,
  related results, conflicting symbols, and unknown restrictions fail closed.
* The UI explicitly requires checking the symbol on the package and shows the
  Passover exclusion. Name/brand matching cannot validate a physical package,
  factory, batch, region, or future changes to certification.
* Only an exact explicit negative OFF label can yield `NOT_KOSHER`, with the
  community source disclosed. Missing OU results, errors, ingredients, and
  “not kosher for Passover” never imply a year-round negative verdict.
* A positive OFF label alone never certifies the product.
* No certification cache: each scan checks current services. Requests have
  bounded timeouts, bounded JSON responses, cancellation, and HTTPS endpoints.
* Product-not-found, offline, transport/DNS, timeout, malformed response,
  OFF unavailable, and OU unavailable have different messages. An OU failure
  retains the identified product and photo with an unknown verdict.

## Scanner and recovery

Camera permission denial provides retry/settings and manual barcode entry.
CameraX is lifecycle-bound; analysis closes every image, limits ML Kit to one
in-flight image and prevents duplicate lookups while a card is visible.
The model survives Activity recreation and cancels HTTP calls on destruction.
The result card scrolls for large font sizes; barcode digits remain LTR.
Custom vector launcher artwork is supplied for API 24+ and adaptive launchers.

## Verification scope

Unit tests cover matching safety and real HTTP parsing/error classification via
MockWebServer. Device tests exercise the bundled ML Kit decoder with a generated
EAN-13 image, all three status cards, Activity recreation and scan-again.
These do not replace camera testing with physical packages on a real phone.
