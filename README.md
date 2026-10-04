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
* OU-based `KOSHER` requires an exact normalized brand and product name (including flavor,
  original/sugar-free variants), a record identifier, a recognized OU symbol,
  and known certification conditions. Explicit product rows named after the
  brand (such as Nutella) can match; generic names, related results, conflicting
  symbols, and unknown restrictions fail closed. Packaging quantities and a
  repeated brand prefix are ignored, while flavors and variants are retained.
* The UI explicitly requires checking the symbol on the package and shows the
  Passover exclusion. Name/brand matching cannot validate a physical package,
  factory, batch, region, or future changes to certification.
* Only an exact explicit negative OFF label can yield `NOT_KOSHER`, with the
  community source disclosed. Missing OU results, errors, ingredients, and
  “not kosher for Passover” never imply a year-round negative verdict.
* An exact explicit `kosher` OFF label can produce a green result when OU has
  no exact match. The UI discloses that this is a community report, requires
  checking the package, and explicitly says it is not OU certification.
  Vegetarian, vegan, unrelated labels and partial text never certify products.
* Specific OFF certification labels (such as Orthodox Union Kosher, Kosher-parve,
  Star-K and MK), raw comma/semicolon-separated `labels`, and explicit legacy
  category labels are also read. They must match a whole allowlisted label;
  `kosher style`, `possibly kosher`, social labels and Passover-only negatives
  cannot certify or reject a product. These results carry the same community
  source disclosure, rather than claiming a live certifier verification.
* Plain water without flavors or additives has a separate category rule based on
  [OU's water guidance](https://oukosher.org/passover/guidelines/food-items/seltzer-water/).
  It requires a water category, no conflicting category or name, and a complete
  ingredient declaration that matches an allowlisted water-only phrase. Missing
  ingredients, additives, flavored/vitamin water and contradictory localized
  ingredients fail closed. The UI requires checking the package's ingredients,
  identifies OFF as the metadata source, and states this is not brand certification.
  There are no barcode or brand exceptions, including for Devin.
* No certification cache: each scan checks current services. Requests have
  bounded timeouts, bounded JSON responses, cancellation, and HTTPS endpoints.
* Product-not-found, offline, transport/DNS, timeout, malformed response,
  OFF unavailable, and OU unavailable have different messages. An OU failure
  retains the identified product and photo with an unknown verdict.
* These sources do not cover every product or certification agency. An identified
  product without sufficient evidence remains UNKNOWN; this is not a network
  error and cannot be fixed by treating every missing OU record as a verdict.

## Scanner and recovery

Camera permission denial provides retry/settings and manual barcode entry.
CameraX is lifecycle-bound; analysis closes every image, limits ML Kit to one
in-flight image and prevents duplicate lookups while a card is visible.
The model survives Activity recreation and cancels HTTP calls on destruction.
The result card scrolls for large font sizes; barcode digits remain LTR.
Custom vector launcher artwork is supplied for API 24+ and adaptive launchers.
Manual-entry and permission-recovery controls use the same dark rounded cards
and white buttons as the scanner. A custom barcode dialog avoids system-default
purple buttons and plain rectangular dialogs.

## Verification scope

Unit tests cover matching safety and real HTTP parsing/error classification via
MockWebServer. Device tests exercise the bundled ML Kit decoder with a generated
EAN-13 image, all three status cards, Activity recreation and scan-again.
These do not replace camera testing with physical packages on a real phone.
