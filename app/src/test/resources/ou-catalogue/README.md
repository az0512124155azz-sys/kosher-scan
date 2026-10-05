# OU public catalogue snapshots

Captured 2026-10-05 from the same endpoint used by the official OU search page:
`https://productsearch-v2.oukosher.org/api/v1/product?query=BRAND&limit=100&page=1`.
Brands: Oreo, Heinz, Kellogg, Barilla, Quaker, Twinings. The six first pages contain
459 actual records across cookies, sauces, cereal, pasta and tea. Additional files
capture Kellogg page 2 and the specific `Kellogg's Corn Flakes` query, plus OFF's
API v2 product metadata for 5050083393693. The snapshots are test data only and
are not bundled as a certification database or barcode overrides.

The official frontend `ou-search.js` appends DE and Yoshon information to the
display status while returning the underlying conditions in a separate field.
Tests distinguish these supplemental classifications from revoked, lot-limited
and unrecognized certification conditions. Yoshon eligibility itself is not
determined by the app's year-round kosher status.

Catalogue matching is tested with product identity from the row and generated
adversarial variations. This is not 459 live scanned barcodes and not a random
market coverage estimate. Actual recorded OFF→OU metadata is tested separately.
