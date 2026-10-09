# Travel transactions and invoice API

Java 17+ / Spring Boot 3.5, Spring JDBC, and **file-backed H2**. Stores flight, hotel, rail, trip-fee, and agent-call-fee transactions and creates independent invoice snapshots. No vendor keys or external services are needed.

## Run

From the repository root, with a JDK 17 or later:

```sh
./mvnw spring-boot:run
```

The Maven wrapper downloads Maven/dependencies on the first run (internet required unless cached). The API listens on `http://localhost:8080`. Stop with Ctrl+C. Records survive restart in `data/travel.mv.db`; `data/` is gitignored. Run only one service process against this embedded database file. An optional `DB_URL` overrides the JDBC URL, `DB_PASSWORD` sets its password, and `SERVER_PORT` changes the HTTP port.

```sh
curl -sS http://localhost:8080/health
```

## Test

```sh
./mvnw test
```

Tests use Spring MockMvc to exercise JSON parsing, HTTP routing/status codes, validation, services, and the actual file-backed database together. No repositories are mocked. Tests use isolated databases under `target/`, never `data/travel`. Coverage includes all five fixtures, duplicate identity, the EUR 685.70 trip invoice, snapshots after direct database mutation, billed-transaction guards, invoice replacement/deletion, pagination, concurrent creates/patches/deletes, unique-constraint enforcement, failed-write rollback, overflow, rollup grouping, and persistence after application restart.

Package an executable jar with `./mvnw package`, then run `java -jar target/travel-invoice-1.0.0.jar`.

## Behavior and decisions

- Every create returns **201** and a UUID `id`. Unknown types/fee kinds, invalid metadata, malformed requests, and invalid amounts return **400**. Missing resources return **404**.
- `external_id` is globally unique and case-sensitive across transaction types. A duplicate returns **409** with `{ "error": "external_id already exists", "id": "<existing UUID>" }`. It never updates the original, even if the request's amounts differ. For a valid request envelope, existing identity is resolved before detailed metadata validation; the fixture's descriptive `comment` field is accepted and ignored. Deleting an unbilled transaction frees its reference for reuse.
- All stored monetary amounts use Java `long` / database `BIGINT` **minor units**, including tax/fee amounts, flight fares, hotel room rates, and extras. INR 220.00 is 22000 paise; EUR 220.00 is 22000 cents. API amounts use decimal major units to match the supplied fixtures. Boundary conversion is exact with `BigDecimal`; negative amounts, fractional minor units, and overflow return **400**. Only ISO currencies with two fractional digits are supported. Tax rates are decimal fractions (0.07 means 7%), not money; supported precision is ten decimal places.
- `total` is the supplied **gross amount**, already including applicable taxes/fees/extras. Invoice totals sum transaction totals without adding charges again. We do not invent a base-price formula for rail or Navan fees.
- `PATCH /transactions/{id}` supports `total`, `tax_lines`, `fee_lines`, `metadata`, and `occurred_at`. Supplied lists and metadata **replace** their previous values; omitted fields stay unchanged. Null required fields are invalid. `id`, `type`, `external_id`, and `currency` are immutable. Transactions on any invoice reject PATCH/DELETE with **409** and remain unchanged.
- Invoice create/PATCH accepts either `transaction_id` or `transaction_ids`, never both. An invoice contains 1–100 transactions. Repeated IDs and mixed currencies return **409**. Any missing transaction returns **404** and nothing changes. Multiple different invoices may reference the same transaction: the brief only prohibits repeated IDs within one invoice.
- Invoice PATCH explicitly replaces its complete transaction set and rebuilds snapshots from the current stored records, atomically. Invoice DELETE returns **204**, removes only its snapshots, and keeps transactions. A transaction becomes editable/deletable once no remaining invoice references it.
- Tax rollups group by exact name and numeric rate; absent rates form a separate group from zero. Fee rollups group by exact name. Names are case-sensitive. Summary ordering follows first appearance in the requested transaction order. GET computes summaries from **persisted snapshots**, never from live transactions.
- Lists return `{ "items": [...], "limit": 50, "offset": 0 }` with compact headers; use GET by ID for full metadata/charges/snapshots. Default limit is 50, maximum 100; out-of-range limits or negative offsets return **400**. SQL performs `ORDER BY id LIMIT … OFFSET …`. Inserts/deletes between requests may shift offset pages; no cross-request snapshot guarantee is promised.
- Metadata is validated with typed flight/hotel/rail/Navan-fee models; supported properties from the brief round-trip, while unrecognized metadata extensions are omitted. Timestamp fields require ISO-8601 with an explicit offset, hotel dates require ISO dates, and segment arrival/check-out must follow departure/check-in. Coupons/legs must contain 1–100 entries; tax/fee/extras lists allow up to 100.

## Example requests

These commands require `curl` and `jq` and should be run from the repository root against a fresh database. The existing seed payloads are the request source; IDs below are captured from actual responses.

```sh
BASE=http://localhost:8080
SEEDS=fixtures/task-b/seed-payloads.json

# Create each type, including both Navan fee kinds.
FLIGHT=$(jq '.flight' "$SEEDS" | curl -fsS -H 'Content-Type: application/json' -d @- "$BASE/transactions" | jq -r '.id')
HOTEL=$(jq '.hotel' "$SEEDS" | curl -fsS -H 'Content-Type: application/json' -d @- "$BASE/transactions" | jq -r '.id')
RAIL=$(jq '.rail' "$SEEDS" | curl -fsS -H 'Content-Type: application/json' -d @- "$BASE/transactions" | jq -r '.id')
TRIP_FEE=$(jq '.trip_fee' "$SEEDS" | curl -fsS -H 'Content-Type: application/json' -d @- "$BASE/transactions" | jq -r '.id')
AGENT_FEE=$(jq '.agent_call_fee' "$SEEDS" | curl -fsS -H 'Content-Type: application/json' -d @- "$BASE/transactions" | jq -r '.id')

# Repeat the same external_id: HTTP 409 with the original FLIGHT id.
jq '.flight' "$SEEDS" | curl -sS -i -H 'Content-Type: application/json' -d @- "$BASE/transactions"
# On this fresh five-transaction dataset, exactly one matching row remains.
curl -fsS "$BASE/transactions?limit=100" | jq '[.items[] | select(.external_id == "flight-TXL-LHR-2026-03-11")] | length'

# Read and list transactions.
curl -fsS "$BASE/transactions/$FLIGHT" | jq
curl -fsS "$BASE/transactions?limit=10&offset=0" | jq

# Update an unbilled transaction (the fixture's gross amount is unchanged here).
curl -fsS -X PATCH -H 'Content-Type: application/json' -d '{"total":220.00}' "$BASE/transactions/$FLIGHT" | jq

# Create the complete trip invoice. Expected totals.grand_total: 685.70 EUR.
INVOICE=$(jq -n --arg f "$FLIGHT" --arg h "$HOTEL" --arg r "$RAIL" --arg t "$TRIP_FEE" --arg a "$AGENT_FEE" \
  '{transaction_ids:[$f,$h,$r,$t,$a]}' | curl -fsS -H 'Content-Type: application/json' -d @- "$BASE/invoices" | jq -r '.id')
curl -fsS "$BASE/invoices/$INVOICE" | jq
curl -fsS "$BASE/invoices?limit=10&offset=0" | jq

# These both return 409 now that the flight is invoiced.
curl -sS -i -X PATCH -H 'Content-Type: application/json' -d '{"total":999.00}' "$BASE/transactions/$FLIGHT"
curl -sS -i -X DELETE "$BASE/transactions/$FLIGHT"

# Replace the invoice's transaction set; single-ID input is supported too.
jq -n --arg id "$FLIGHT" '{transaction_id:$id}' | curl -fsS -X PATCH -H 'Content-Type: application/json' -d @- "$BASE/invoices/$INVOICE" | jq

# Delete the invoice (204). The flight still exists, and can now be deleted.
curl -sS -i -X DELETE "$BASE/invoices/$INVOICE"
curl -fsS "$BASE/transactions/$FLIGHT" | jq
curl -sS -i -X DELETE "$BASE/transactions/$FLIGHT"
```

A runnable version with checks is also available: `bash scripts/demo.sh`. It uses unique client references so it can be rerun without clearing the database, and covers successful writes plus the expected conflicts.

## Submission

Push this project to your chosen Git repository and supply its URL plus the commit SHA (`git rev-parse HEAD`). See [ARCHITECTURE.md](ARCHITECTURE.md) for schema and concurrency choices. Do not commit database files, credentials, or generated build output.
