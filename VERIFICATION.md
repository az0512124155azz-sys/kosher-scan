# Verification — version 1.4.2, 2026-10-05

## Validation expansion

The loading caption for both normal and extended lookup is exactly
`בודק במאגרי כשרות`; neither displays a duration. Lookup budgets are unchanged.

12,110 JVM tests passed locally: 12,000 new parameterized adversarial cases
plus 110 existing cases. The new JUnit XML contains 12,000 unique test names:

| Family | Samples | Mutations per sample | Executed cases |
|---|---:|---:|---:|
| OU brand/name/variant/symbol/condition matching | 200 | 20 | 4,000 |
| Exact-barcode structured authority parsing | 200 | 20 | 4,000 |
| Whole community labels and negative precedence | 100 | 20 | 2,000 |
| Water-only metadata and conflicting evidence | 100 | 20 | 2,000 |

These are distinct synthetic inputs with explicit expected results, not live
lookups for 12,000 products. They test condition/order variations, Hebrew/English
identity, quantities, flavors, unknown symbols, revoked/related records, missing
fields, duplicate rows, mismatched barcodes, Passover-only negatives, exact-label
precedence, ingredient additives and contradictory translations. Generated
barcodes include independently computed EAN-13 check digits. Fixtures are test
inputs only and do not add runtime barcode/brand exceptions.

The formatting cases also cover condition text with repeated whitespace and
different casing. Passover-exclusion display now uses the same normalized clause
meaning as matching; accepted spacing variations no longer hide the exclusion.

The 33 recorded real product cases still yield 28 KOSHER / five UNKNOWN. They
are included in the existing 110 cases and do not establish universal coverage.

## Latency follow-up (version 1.4.1)

The former path could await OFF (15 seconds) before a serial OU search
(20 seconds). OU now starts after metadata arrives, concurrently with the
barcode source. Normal lookups have a 12-second overall budget; OFF metadata
has five seconds and OU has eight. A completed exact authority result waits at
most an additional 1.5 seconds for OFF before cancelling optional work. Slow
metadata can leave a photo missing, disclosed in the card's reason. Matching
criteria and the three-status policy are unchanged. No verdict cache was added.

TIMEOUT exposes an explicit extended search (35-second total / 20-second OU),
with an explicit user action. A completed exact OU result is
preserved if the other source times out. Conflicting explicit labels available
within the metadata window still fail closed. No claim is made that a cancelled,
unfinished source has been checked; its missing evidence is disclosed.

Eight JVM latency regressions cover cancelled optional metadata, concurrent OU
start, delayed conflicting evidence, partial product recovery, retained community
disclosure, preservation of completed OU results, timeout classification and
extended search. Five Android tests include the extended-search button and its
loading state, in addition to the four scanner/UI tests below.

## Version 1.4.0 coverage baseline

Environment: Windows, Java 21, Gradle 8.7, SDK 35; Pixel 6 AVD running
Android 17 / API 37. No physical phone was connected.

## Automated checks

* 102 JVM tests: 23 matching/status cases, 19 original HTTP cases, eight water
  policy cases, 15 independent authority/integration cases, four OU search cases
  and 33 recorded product cases. All passed.
* Four Android instrumented smoke tests cover bundled ML Kit EAN-13 decoding,
  all three cards/recreation/reset, unclipped source/scan-again controls and
  manual-entry validation/cancellation.
* Android lint, debug APK and instrumented-test APK compilation passed. Lint
  has no errors; existing AGP/SDK compatibility and localization/style warnings
  remain.

New HTTP tests cover exact authority barcode matching, leading-zero equivalence,
missing/duplicate fields, contradictory labels, unavailable/malformed services,
cancelled requests and positive authority results with OFF 404 or 503. An
unapproved catalogue record does not become NOT_KOSHER. OU tests check a later
declared brand, second-page matches, both languages, preserved variants and
incomplete result rejection. Explicit negative fixtures render/test the red
status; they are not assertions that a live physical product is non-kosher.

## Recorded coverage comparison

Public catalogue responses were captured on 2026-10-04 UTC / 2026-10-05 Israel.
The corpus has 33 records in 14 categories: snacks, milk, oil, coffee, chocolate,
tuna, cookies, bread, rice, yogurt, cheese, ketchup, juice and Bisli. It contains
28 approved records, three unapproved yogurt records and two invalid internal
five-digit SKUs retained as controls. OFF identified 22 records and lacked 11,
including nine approved records.

On the same saved OFF/OU snapshots, the previous OFF/first-OU-query path returned
KOSHER for one record and UNKNOWN for 32. The independent source path returns
KOSHER for 28 and UNKNOWN for five. No controls turn red or green. This compares
recorded responses and source coverage; it does not establish that every package
in every country, batch or date is certified. The sample was selected from the
authority's catalogue, not randomly from the whole market. It proves a broad
repair beyond water/Nutella, not universal coverage.

Fixtures and source links are in `app/src/test/resources/coverage/manifest.json`.
Tests emit per-record before/after JSON into `app/build/reports/coverage`, which
GitHub Actions uploads in `verification-reports`.

## Live emulator checks

* Manually entered `7290019587538` (Tomer canola oil). The live card showed KOSHER
  from an exact Kosharot barcode record, named agencies, package conditions and
  the product image. The source control opened the exact record URL in Chrome.
* CameraX displayed the virtual camera after granting the current Android camera
  permission. The scanning overlay and custom rounded manual-entry dialog ran.
* Entered `7290119380459` (Elite hazelnut instant coffee), absent from OFF in the
  captured response. The live app still identified it from the exact Kosharot
  record and showed KOSHER with named agencies and the source link. This checks
  the previously blocked path with a non-water product.
* Disabled Wi-Fi and mobile data: lookup displayed the explicit offline message
  and a retry control, rather than treating an unknown product as a network error.
  Restored both and tapped retry: the oil's name, photo and positive authority
  result returned without restarting the app. The crash log buffer was empty.

Earlier versions also exercised Nutella's live OU result, Devin's conditional
water rule, a community-labelled bread, denied permission recovery, offline
lookup and retry after restoring connectivity. These historical checks are
separate from the new source coverage corpus.

## Limits

No physical-camera/package, poor-light autofocus or OEM permission testing was
possible. ML Kit decoded a generated image; CameraX displayed the emulator's
virtual scene. Public website integrations may change and none covers every
product. Positive cards disclose the source and require checking package
details/conditions. Missing evidence stays UNKNOWN, never an invented verdict.
The former 2 GB emulator was killed by Android's low-memory killer; verification
used a restarted 4 GB AVD. This was not an application exception.
