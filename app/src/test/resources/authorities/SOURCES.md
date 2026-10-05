Recorded 2026-10-05 from public official endpoints for parser regression tests:

- government-package.json: https://data.gov.il/api/3/action/package_show?id=mazon
- government.json: https://data.gov.il/api/3/action/datastore_search?resource_id=4cc6c561-5975-4bac-904f-c06489ceeb6d&limit=3
- ok.html: https://www.ok.org/product-search/?term=Guylian
- klbd.json: https://isitkosher.uk/api/query?q=Weetabix&grouped=false&cat=false
- star-listing.html: POST https://www.star-k.org/listings/star-k with form q=Graeter
- star-text.txt: text extracted from https://apiservice.star-k.org/api/Loc/LoadLoc/ZGCOQH37
- ../../../../androidTest/assets/star-certificate.pdf: that same public certificate.

The fixtures are test-only snapshots, never bundled as current certification
data in the application. Expiry tests use a fixed date and do not assert that
these records remain valid forever. Live lookups use the current clock.
