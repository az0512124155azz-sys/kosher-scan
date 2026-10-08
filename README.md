# Kosher Scan for Android

Native CameraX + bundled ML Kit scanner, Hebrew RTL UI based on `index.html`.
The HTML remains the original design reference; the Android APK does not load it.

Version 1.8.0 uses direct database lookups for the app verdict. `MainActivity`
constructs `ProductRepository` directly; neither AI research nor owner decisions
from the Telegram/dashboard service can overwrite that result. Unknown cases may
still be uploaded for administrative research after the native card is displayed.
There is no Gemini call in the Android lookup path. Category-only water inference
is no longer a certification source. OFF's explicit certification labels remain
community data and are lower priority than matching authority records.

`DecisionEngine` arbitrates evidence consistently: exact authority barcode,
complete authority name/brand, then explicit community labels. Equally strong
opposing statuses remain unknown; dairy/pareve differences suppress only disputed
details. A service failure cannot erase completed positive evidence. Current
verification and its coverage limits are documented in [VERIFICATION.md](VERIFICATION.md).

Version 1.6.0 adds an optional unknown-product agent connection, durable background
uploads, a private RTL dashboard, Gemini research and a paired Telegram bot.
See [agent setup](agent/README.md). It requires deployment and a BotFather token
before it is live. AI suggestions never automatically become certification.
The existing lookup sources and status policy continue to operate independently.

Version 1.7.0 improves physical barcode capture with higher-resolution analysis and ML Kit auto-zoom. It also makes unknown-product research actionable through a single Telegram review message. Version 1.6.1 removed consumer agent settings/actions and the purchase-country
selector. The scanner uses Israel scope and discovers public service routing
automatically. Routing remains disabled until deployment; enabling a tested
service is an administrator operation, not setup required on each phone.

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

Version 1.5.0 adds four live sources alongside the existing OFF, OU and
Kosharot integrations. Searches run concurrently with per-source deadlines;
there is no hardcoded barcode verdict table or copied certification database.

* **Israeli Rabbinate:** discovers the current imported-food CSV resource via
  the public CKAN catalogue, then queries its datastore. Product and manufacturer
  must match, the certificate must be current, and unrecognized batch/package
  conditions prevent approval. Certificate numbers are never treated as barcodes.
* **OK:** reads the public product-search table, requiring a product KID and
  coherent status/symbol. A company listing alone cannot certify a product.
* **STAR-K:** searches the official directory, downloads the linked current PDF
  certificate and matches actual product rows, brands, conditions and expiry.
  Unknown layouts, omitted table rows and broad company searches fail closed.
* **KLBD:** reads the public isitkosher.uk service. `Not Kosher` is an explicit
  negative; `Not Approved` and missing information remain UNKNOWN. Stale datasets,
  incomplete lists and unsupported restrictions cannot certify.

The app uses Israel scope without a purchase-country control. Rabbinate is queried
for Israel. KLBD's adapter is tested for UK scope but UK-only approvals are not
applied to Israeli scans. This is purchase-market scope, not GPS location.
OFF still supplies barcode identity, photos and community labels; it is not a
certification authority. Official results with conflicting statuses stay UNKNOWN.
Known results survive unrelated service failures. Source evidence remains internal
and the simple Hebrew result presentation and three statuses are preserved.

These are live public-site integrations, not guaranteed official open APIs for
every source. Strict identity and condition checks can leave products UNKNOWN,
particularly without manufacturer metadata, with unmatched names, dated/batch
conditions or country-specific variants. Adding sources does not establish
universal barcode coverage.

Version 1.4.7 identifies a retail product line when its complete branded name
matches OU's product name, even if OU lists a different parent/licensing brand.
The retail brand must explicitly start the official product name; extra flavor,
sugar-free, ingredient mentions, substring brands and brand-only names cannot
match. Name-only queries are also included before broad brand searches.
OU's structured `Yoshon Always (Made with Winter Wheat)` annotation is recognized
only when it exactly agrees with the displayed status. Other restrictions are
retained. Restricted identity-equivalent rows cannot be hidden by a positive row,
including rows from earlier queries. A failed query no longer aborts all later
queries; the existing 8/12-second and 12-request budgets remain. Transport,
timeout and malformed-response issues are classified separately. Debug logs
report request/row/identity/eligibility counts, without queries or barcodes.
No GitHub mirror or barcode verdict table was added: hosting the same name-based
records elsewhere does not fix missing product identity, and the public search
does not provide a verified complete catalogue export or barcode mapping.

Version 1.4.6 fixes OU catalogue coverage without barcode overrides. The parser
keeps certification conditions separate from OU's display status and accepts only
recognized, matching structured DE/Yoshon annotations. Revocation, lot/date-based
certification restrictions and unrecognized status text still fail closed. The
observed OU-Fish symbol is supported. Search can read five 100-row pages per query,
with at most 12 requests inside the existing time budgets; incomplete pages cannot
certify. Name matching tolerates word order, apostrophes, composite whole-token
brand fields and packaging quantities, while retaining flavor/variant tokens.
The generic descriptor `cereal` is ignored only with the actual breakfast-cereals
category. Related suggestions, missing records and wrong variants stay UNKNOWN.

Version 1.4.5 restores all matching/status rules from 1.4.3. The stricter policy
introduced in 1.4.4 was withdrawn after the user clarified that only the package
check sentence should be removed. Name/brand matching, community labels, the
water rule and exact-barcode approvals again produce the same statuses as 1.4.3.
Only display copy changes: positive cards no longer ask to verify certification
on the package. Dairy and Passover details remain. Missing evidence stays UNKNOWN.

Version 1.4.1 runs OU fallback in parallel with the barcode source as soon as OFF
metadata arrives. The normal lookup has a 12-second overall deadline, a five-second
metadata budget, and an eight-second OU budget. An exact authority verdict waits
at most another 1.5 seconds for optional OFF metadata, then cancels unused work.
Missing slow metadata may mean a missing photo; source evidence remains internal.
Completed OU verdicts survive a timeout in the other source. Available conflicting
explicit labels still produce UNKNOWN. Matching requirements are unchanged.
The loading message is simply `בודק במאגרי כשרות` as of version 1.4.2, with no
time estimate. TIMEOUT offers an explicit extended
search, with a 35-second overall / 20-second OU budget, so slow source coverage
remains available without imposing that wait on every scan. No verdict caching
or barcode shortcuts were added.

Version 1.4.0 removes Open Food Facts as a prerequisite for certification.
An independent exact-barcode lookup checks [Kosharot's product catalogue](https://www.ikr.org.il/index2.php?id=2&lang=HEB)
in parallel with OFF. A matching structured product record must contain the
requested barcode, a recognized explicit status and, for a positive/negative
verdict, named certification agencies. Its relevant conditions are shown
on the result card. Missing entries, unapproved entries, mismatched barcodes,
duplicate fields, missing agencies and malformed pages cannot certify a product.
OFF failure or a missing OFF record no longer prevents a positive authority result.
Conflicting explicit positive/negative evidence produces UNKNOWN.

Kosharot's public scanner endpoint and product page are website integrations,
not contracted third-party APIs. Lookup is bounded to 12 seconds and responses
to 2 MB. No barcode, brand or product-specific verdict overrides are present.

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
* The UI shows applicable dairy/Passover details without package-check instructions.
  Name/brand matching cannot validate a physical package,
  factory, batch, region, or future changes to certification.
* Only an exact explicit negative OFF label or an explicit negative exact-barcode
  authority record can yield `NOT_KOSHER`, with its source retained internally.
  Missing OU results, errors, ingredients, and
  “not kosher for Passover” never imply a year-round negative verdict.
* An exact explicit `kosher` OFF label can produce a green result when OU has
  no exact match. The UI uses a short positive message.
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
* OU searches try English and display-language names, multiple declared brands
  and a brand-only fallback. Flavors and variants remain significant. The search
  has at most six requests and two pages per query, within the selected time budget.
  Incomplete/truncated results cannot certify because unseen rows might conflict.
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

Version 1.4.2 adds 12,000 individually registered, uniquely named JUnit cases:
4,000 OU identity/condition cases, 4,000 structured authority parsing cases,
2,000 community-label cases and 2,000 plain-water boundary cases. Each family
crosses 20 explicit adversarial mutations with different product identities,
brands and/or valid generated EAN-13 barcodes. Expected verdicts are declared
independently of the production matcher; malformed authority rows must be
rejected rather than turned into certification. Duplicate case identifiers are
rejected at generation. These are synthetic regression/property cases, not
12,000 real products, live source queries or real-device camera scans.
Together with the 110 existing JVM cases this yields 12,110 JVM tests; five
instrumented tests run on the emulator. The 33 recorded real catalogue records
remain a separate source-coverage sample.

Unit tests cover matching safety and real HTTP parsing/error classification via
MockWebServer. Device tests exercise the bundled ML Kit decoder with a generated
EAN-13 image, all three status cards, Activity recreation and scan-again.
These do not replace camera testing with physical packages on a real phone.

The recorded coverage corpus contains 33 public records across 14 categories:
28 approved records, three unapproved records and two invalid internal SKUs.
On those same snapshots the previous OFF/first-OU-query path returned one green
result; version 1.4 returns 28 and leaves the five controls UNKNOWN. Nine approved
records were absent from OFF. This is a focused catalogue sample, not a random
market survey or a claim to cover millions of products. Fixtures, source URLs
and per-record before/after reports are included in the tests/CI reports.


Version 1.4.3 removes source names, diagnostic matching text and the source button from result cards. Separate plain Hebrew copy retains package checks, water ingredient conditions, dairy designation and Passover exclusions. Evidence remains internal; matching and status rules are unchanged.
