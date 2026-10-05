# Verification history and current version 1.6.0, 2026-10-05

## Version 1.6.0 dashboard, research agent and Android connection

Local Gradle test/lint/build and 12 instrumented tests passed on Pixel 6 / API 37.
There are 12,159 JVM cases, including the existing 12,000 generated adversarial
matching cases; these are not 12,159 real scanned products. New coverage checks
exact barcode/market/expiry, unapproved AI data, conflicts, background upload,
actual cropped JPEG bytes, connection validation and a native UNKNOWN card
changing to KOSHER after a reviewed response from an isolated MockWebServer.
No invented test verdict was published into the real dashboard database.

Worker tests run against Miniflare and real local D1 SQL, covering role access,
durable/idempotent submission, repeated observations, reviewed result publication,
deletion, webhook isolation and recovery of interrupted final research attempts.
Telegram and Gemini contract tests use mocked transport. Wrangler deployment
dry-run succeeds. CI verifies the agent separately from the Android APK build.

A real emulator manual scan of 9999999999999 returned an OFF test product
Salatgurke / MarcaTest and UNKNOWN, then automatically appeared in the private
local dashboard. Gemini completed a real Google-grounded research request in
approximately 10 seconds, found no supporting certification and kept its
suggestion UNKNOWN. This verifies actual cloud research, not broad coverage.
Dashboard browser checks verified RTL rendering, search/filter, product details
and no JavaScript errors. A manual scan correctly has no barcode camera photo.

No Cloudflare account or BotFather bot exists yet. The configured D1 ID in the
repository is explicitly a placeholder. Public deployment, real Telegram delivery
and production webhook operation remain unverified until those are supplied.
The original independent product lookup sources remain active without the agent.


## Version 1.4.7 product identity and transient lookup recovery

Live investigation confirmed that the official OU endpoint responds, but retail
brands can differ from the parent/licensing brand field. A recorded OFF barcode
and 14-row official search reproduce a failed match in 1.4.6 and a positive
match through the full 1.4.7 parser/repository. There are no product-specific
overrides. The production rule requires the complete branded product name,
with the retail brand explicitly present in the official product name.
Independent adversarial cases cover eight product categories, wrong brands,
flavors, substring brands, ingredient mentions, restrictions and conflicts.
A further 100-row Christie snapshot verifies the additional structured winter
wheat annotation. These are catalogue records, not barcode/market coverage.

An emulator live lookup initially reproduced a per-request timeout; repeating
the same lookup succeeded in 1.084 seconds. Later queries now continue after
per-request errors inside the unchanged total/request budgets. Tests exercise
503 recovery, timeout recovery and a restricted earlier record that must veto
a later positive result. Diagnostic counts distinguish transport failure from
missing identity/unsupported certification. UI text and the three statuses
remain unchanged. No complete OU export or barcode mapping was found, so a
GitHub mirror was not represented as an automatic coverage solution.


## Version 1.4.6 OU catalogue coverage

Inspection of OU's current official search frontend and live endpoint found that
`status` includes supplemental dairy-equipment/Yoshon annotations, while the
underlying `conditions` field is separate. The former concatenation rejected
these records. First-page snapshots across six brands contain 459 actual rows.
Tests run all rows through the production parser and matching policy, with
dedicated adversarial cases for unknown status text, revoked entries, variants,
composite brands and category-dependent descriptors. These are catalogue rows,
not 459 scanned barcodes. Additional repository tests exercise recorded OFF/OU
responses, a match beyond the old 100-row limit, and a conflicting later page.
The existing 12,000 generated adversarial cases and 33 recorded barcode cases
remain. Yoshon eligibility is not inferred from the year-round kosher status.

## Version 1.4.5 restoration

The user clarified that the request concerned package-check wording only.
All domain/status and repository logic from 1.4.3 has been restored, including
the previous 12,000-case adversarial expectations. No additional restrictions
from 1.4.4 remain. Positive display copy no longer asks to check certification
on the package. Dairy and Passover information remain. A new Android regression
checks that OU matches, community positive labels and the water rule still
render green without the removed instruction. Earlier entries below are history.

## Version 1.4.4 verdict consistency

Conditional name/brand matches, community positive labels, and the water rule
now return UNKNOWN, rather than green plus a request to confirm certification.
Explicit exact-barcode approvals remain green only without unresolved conditions.
Unrecognized notes/restrictions downgrade approval; ordinary pareve/dairy/meat
and recognized Passover classifications do not. Green cards contain no instruction
to check whether the product is kosher. Source evidence stays internal.

The 12,000-case adversarial matrix now asserts this stricter policy. Dedicated
presentation tests cover conditional UNKNOWN and an unconditional exact-barcode
green result, and a new Android smoke flow exercises both colors/copy. Earlier
validation counts and live outcomes below document previous versions.

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
product. Positive cards retain source evidence internally and require checking package
details/conditions. Missing evidence stays UNKNOWN, never an invented verdict.
The former 2 GB emulator was killed by Android's low-memory killer; verification
used a restarted 4 GB AVD. This was not an application exception.

## Version 1.4.3 result presentation

Local build, lint and all 12,117 JVM cases pass (12,000 generated adversarial cases, 117 other cases). Seven new presentation cases verify retained OU dairy/Passover conditions, conditional water guidance, community report qualification, distinct transport failures, and preserved barcode-record conditions without administrative/source labels. The five Android smoke tests check the simplified explanation and scan-again visibility along with existing scanner, state, timeout and input flows. Real-device camera/OEM checks remain unavailable.
Live emulator lookup of Nutella (3017620422003) returned KOSHER in 5.874 seconds and displayed only: dairy, check the package certification marking, and not suitable for Passover. The source button is removed from the layout. All five Android smoke tests passed on the API 37 Pixel 6 AVD.
Current local validation: all 12,118 JVM cases passed, including 12,000 generated adversarial cases (not live product checks). Lint, debug APK and instrumented-test APK builds passed. All six Android smoke tests passed on the API 37 Pixel 6 AVD, including yellow conditional evidence versus green unconditional barcode approval. No physical device was available.
Version 1.4.5 local validation: 12,117 JVM cases passed, including the restored 12,000 generated adversarial cases. Lint and APK builds passed. All six Android emulator tests passed on API 37 Pixel 6, including original positive paths rendering green without package-check instructions. No physical device checks were possible.
Current local validation: 12,127 JVM tests passed (12,000 synthetic adversarial cases plus 127 other tests). The catalogue test also checks 459 recorded rows, including 81 supplemental DE/Yoshon rows. All six Android smoke tests passed on API 37 Pixel 6. Lint and APK builds passed. No physical device testing was available. The emulator was initially stopped; it was restarted and the six tests then passed.
## Version 1.5.0 additional authority integrations

Local JVM tests: **12,152 passed**, including 12,000 synthetic adversarial cases
and 152 other tests. These are not 12,152 live scans. Lint and both APK builds
passed. **Eight instrumented tests passed** on the Pixel 6 API 37 emulator.

New recorded-source fixtures cover the current Rabbinate CKAN schema and
September/December expiry boundaries, OK's actual Guylian product table, 25 KLBD
Weetabix rows, and the six-page Graeter's STAR-K certificate with 66 product
rows. Fixtures were retrieved from official public endpoints on 2026-10-05.
Tests cover exact identity, company suffixes versus wrong brands, restricted
rows, expiry, missing/negative/approved distinctions, stale and truncated data,
partial service failure, market scoping and OFF/additional-authority conflicts.

The Android PDF test opens the actual official certificate, extracts all six
pages, checks a recognized sorbet and rejects the store-restricted pie row.
The PDF uses owner encryption but opens without a user password; documents
requiring a user password still fail. A separate Android test changes the
purchase country through the visible dialog, checks persistence across Activity
recreation and confirms the old result is cleared. Existing ML Kit, card/state,
manual entry and loading/retry tests also pass.

Live emulator check: entered barcode 5010029204247 after selecting the UK in
the visible market dialog. OFF identified Crunchy Bran; KLBD returned 25 current
Weetabix rows and the app rendered green KOSHER with only `פרווה.`. OU timed out
and the other sites had no match, but the KLBD result survived. Total lookup time
was 9.865 seconds, including the OU wait; this is functional evidence, not a
claim that every lookup is fast. No barcode-specific exception was introduced.

Live services can change or be unavailable; unsupported restrictions and
incomplete identity remain UNKNOWN. No physical package/camera or OEM tests
were possible. Historical validation below describes earlier app versions.

