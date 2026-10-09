package com.navan.travel;

import com.navan.travel.models.TransactionModels.Fee;
import com.navan.travel.models.TransactionModels.Transaction;
import com.navan.travel.models.TransactionModels.TransactionInput;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@AutoConfigureMockMvc
class TravelApiTest {
    @Autowired
    MockMvc http;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    JdbcTemplate db;
    @Autowired
    org.springframework.transaction.support.TransactionTemplate tx;
    @Autowired
    TravelRepository repository;
    @Autowired
    TransactionValidator validator;
    JsonNode seeds;
    private static final String TEST_DB = "jdbc:h2:file:./target/test-db-" + UUID.randomUUID() + ";DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=10000";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> TEST_DB);
    }

    @BeforeEach
    void setup() throws Exception {
        db.update("DELETE FROM invoices");
        db.update("DELETE FROM transactions");
        seeds = mapper.readTree(Files.readString(Path.of("fixtures/task-b/seed-payloads.json")));
    }

    ResponseEntity<JsonNode> call(HttpMethod method, String path, Object body) {
        try {
            var request = MockMvcRequestBuilders.request(method, path);
            if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
            var response = http.perform(request).andReturn().getResponse();
            String content = response.getContentAsString();
            return ResponseEntity.status(response.getStatus()).body(content.isEmpty() ? null : mapper.readTree(content));
        } catch (Exception e) {
            throw new AssertionError("Request failed: " + method + " " + path, e);
        }
    }

    ResponseEntity<JsonNode> post(String path, Object body) {
        return call(HttpMethod.POST, path, body);
    }

    JsonNode get(String path) {
        var r = call(HttpMethod.GET, path, null);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        return r.getBody();
    }

    JsonNode create(String name) {
        var r = post("/transactions", seeds.get(name));
        assertThat(r.getStatusCode().value()).isEqualTo(201);
        return r.getBody();
    }

    String id(JsonNode node) {
        return node.get("id").asText();
    }

    JsonNode invoice(List<String> ids) {
        var r = post("/invoices", Map.of("transaction_ids", ids));
        assertThat(r.getStatusCode().value()).isEqualTo(201);
        return r.getBody();
    }

    List<String> trip() {
        return List.of("flight", "hotel", "rail", "trip_fee", "agent_call_fee").stream().map(this::create).map(this::id).toList();
    }

    long count(String external) {
        return db.queryForObject("SELECT COUNT(*) FROM transactions WHERE external_id=?", Long.class, external);
    }

    ObjectNode altered(String name, String reference) {
        ObjectNode n = seeds.get(name).deepCopy();
        n.put("external_id", reference);
        return n;
    }

    void amount(JsonNode node, String field, String expected) {
        assertThat(node.get(field).decimalValue()).isEqualByComparingTo(expected);
    }

    @Test
    void createsAllSeedTypesAndRoundTripsStructuredMetadata() {
        JsonNode flight = create("flight");
        assertThat(get("/transactions/" + id(flight)).at("/metadata/coupons/0/flight_number").asText()).isEqualTo("BA982");
        assertThat(get("/transactions/" + id(create("hotel"))).at("/metadata/address/street").asText()).isEqualTo("12 Kingsway");
        assertThat(get("/transactions/" + id(create("rail"))).at("/metadata/legs").size()).isEqualTo(2);
        assertThat(get("/transactions/" + id(create("trip_fee"))).at("/metadata/fee_kind").asText()).isEqualTo("trip_fee");
        assertThat(get("/transactions/" + id(create("agent_call_fee"))).at("/metadata/fee_kind").asText()).isEqualTo("agent_call_fee");
        assertThat(db.queryForObject("SELECT total_minor FROM transactions WHERE id=?", Long.class, id(flight))).isEqualTo(22000);
        String stored = db.queryForObject("SELECT metadata FROM transactions WHERE id=?", String.class, id(flight));
        assertThat(stored).contains("fare_minor", "18000").doesNotContain("\"fare\":");
        assertThat(db.queryForObject("SELECT amount_minor FROM transaction_taxes WHERE transaction_id=?", Long.class, id(flight))).isEqualTo(1500);
    }

    @Test
    void duplicateReturnsExistingIdWithoutInsertingOrChangingMoney() {
        JsonNode first = create("flight");
        var duplicate = post("/transactions", seeds.get("duplicate_flight"));
        assertThat(duplicate.getStatusCode().value()).isEqualTo(409);
        assertThat(id(duplicate.getBody())).isEqualTo(id(first));
        ObjectNode changed = seeds.get("flight").deepCopy();
        changed.put("total", 999);
        assertThat(post("/transactions", changed).getStatusCode().value()).isEqualTo(409);
        assertThat(count(first.get("external_id").asText())).isEqualTo(1);
        amount(get("/transactions/" + id(first)), "total", "220.00");
    }

    @Test
    void tripInvoiceMatchesFixtureAndRollups() throws Exception {
        JsonNode inv = invoice(trip());
        JsonNode expected = mapper.readTree(Files.readString(Path.of("fixtures/task-b/expected-invoice.json")));
        assertThat(inv.at("/totals/grand_total").decimalValue()).isEqualByComparingTo(expected.at("/totals/grand_total").decimalValue());
        assertThat(inv.get("currency").asText()).isEqualTo("EUR");
        assertThat(inv.get("transactions").size()).isEqualTo(5);
        assertThat(inv.get("tax_rollup").size()).isEqualTo(2);
        assertThat(inv.get("fee_rollup").size()).isEqualTo(2);
        amount(inv.at("/tax_rollup/0"), "amount", "15.00");
        amount(inv.at("/tax_rollup/1"), "amount", "21.70");
        amount(inv.at("/fee_rollup/0"), "amount", "25.00");
        amount(inv.at("/fee_rollup/1"), "amount", "4.00");
        assertThat(get("/invoices/" + id(inv))).isEqualTo(inv);
    }

    @Test
    void invoiceSnapshotSurvivesDirectDatabaseMutationOfHeaderTaxesFeesAndMetadata() {
        String flight = id(create("flight"));
        JsonNode inv = invoice(List.of(flight));
        db.update("UPDATE transactions SET total_minor=99900,metadata=? FORMAT JSON WHERE id=?",
                "{\"origin\":\"XYZ\",\"destination\":\"LHR\",\"fare_minor\":99000,\"coupons\":[]}", flight);
        db.update("UPDATE transaction_taxes SET amount_minor=1 WHERE transaction_id=?", flight);
        db.update("DELETE FROM transaction_fees WHERE transaction_id=?", flight);
        JsonNode frozen = get("/invoices/" + id(inv));
        amount(frozen.at("/transactions/0"), "total", "220.00");
        amount(frozen.at("/transactions/0/tax_lines/0"), "amount", "15.00");
        assertThat(frozen.at("/transactions/0/metadata/origin").asText()).isEqualTo("TXL");
        assertThat(frozen).isEqualTo(inv);
    }

    @Test
    void billedTransactionsRejectPatchAndDeleteWithoutAnyChanges() {
        String flight = id(create("flight"));
        JsonNode inv = invoice(List.of(flight));
        assertThat(call(HttpMethod.PATCH, "/transactions/" + flight, Map.of("total", 999)).getStatusCode().value()).isEqualTo(409);
        assertThat(call(HttpMethod.DELETE, "/transactions/" + flight, null).getStatusCode().value()).isEqualTo(409);
        amount(get("/transactions/" + flight), "total", "220.00");
        assertThat(get("/invoices/" + id(inv))).isEqualTo(inv);
    }

    @Test
    void mixedCurrencyAndRepeatedIdsRejectWithoutCreatingInvoice() {
        String eur = id(create("flight"));
        ObjectNode usd = altered("hotel", "usd-hotel");
        usd.put("currency", "USD");
        String usdId = id(post("/transactions", usd).getBody());
        assertThat(post("/invoices", Map.of("transaction_ids", List.of(eur, usdId))).getStatusCode().value()).isEqualTo(409);
        assertThat(post("/invoices", Map.of("transaction_ids", List.of(eur, eur))).getStatusCode().value()).isEqualTo(409);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM invoices", Long.class)).isZero();
    }

    @Test
    void supportsSingleIdAndDeletingInvoicePreservesAndUnblocksTransactions() {
        String flight = id(create("flight"));
        var response = post("/invoices", Map.of("transaction_id", flight));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        String invoiceId = id(response.getBody());
        assertThat(call(HttpMethod.DELETE, "/invoices/" + invoiceId, null).getStatusCode().value()).isEqualTo(204);
        assertThat(call(HttpMethod.GET, "/invoices/" + invoiceId, null).getStatusCode().value()).isEqualTo(404);
        get("/transactions/" + flight);
        assertThat(call(HttpMethod.PATCH, "/transactions/" + flight, Map.of("total", 230)).getStatusCode().value()).isEqualTo(200);
        assertThat(call(HttpMethod.DELETE, "/transactions/" + flight, null).getStatusCode().value()).isEqualTo(204);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM transaction_taxes", Long.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM transaction_fees", Long.class)).isZero();
    }

    @Test
    void patchReplacesChargeListsAndValidationRollsBack() {
        String flight = id(create("flight"));
        var patch = Map.of("total", 225, "tax_lines", List.of(Map.of("name", "New tax", "amount", 5)), "fee_lines", List.of());
        var r = call(HttpMethod.PATCH, "/transactions/" + flight, patch);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        amount(r.getBody(), "total", "225.00");
        assertThat(r.getBody().at("/tax_lines/0/name").asText()).isEqualTo("New tax");
        assertThat(r.getBody().get("fee_lines").size()).isZero();
        assertThat(call(HttpMethod.PATCH, "/transactions/" + flight, Map.of("total", 250, "metadata", Map.of())).getStatusCode().value()).isEqualTo(400);
        assertThat(get("/transactions/" + flight)).isEqualTo(r.getBody());
        assertThat(call(HttpMethod.PATCH, "/transactions/" + flight, Map.of("external_id", "other")).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void invoicePatchRebuildsSnapshotAndFailedReplacementLeavesItUnchanged() {
        String flight = id(create("flight")), hotel = id(create("hotel"));
        JsonNode original = invoice(List.of(flight));
        call(HttpMethod.PATCH, "/transactions/" + hotel, Map.of("total", 350));
        var replacement = call(HttpMethod.PATCH, "/invoices/" + id(original), Map.of("transaction_ids", List.of(hotel)));
        assertThat(replacement.getStatusCode().value()).isEqualTo(200);
        amount(replacement.getBody().get("totals"), "grand_total", "350.00");
        assertThat(call(HttpMethod.PATCH, "/transactions/" + flight, Map.of("total", 230)).getStatusCode().value()).isEqualTo(200);
        ObjectNode usd = altered("rail", "usd-rail");
        usd.put("currency", "USD");
        String usdId = id(post("/transactions", usd).getBody());
        assertThat(call(HttpMethod.PATCH, "/invoices/" + id(original), Map.of("transaction_ids", List.of(hotel, usdId))).getStatusCode().value()).isEqualTo(409);
        assertThat(call(HttpMethod.PATCH, "/invoices/" + id(original), Map.of("transaction_id", UUID.randomUUID())).getStatusCode().value()).isEqualTo(404);
        assertThat(get("/invoices/" + id(original))).isEqualTo(replacement.getBody());
    }

    @Test
    void listsUseDefaultAndBoundedPaginationForBothResources() {
        for (int i = 0; i < 55; i++)
            assertThat(post("/transactions", altered("trip_fee", "fee-" + i)).getStatusCode().value()).isEqualTo(201);
        JsonNode page = get("/transactions");
        assertThat(page.get("limit").asInt()).isEqualTo(50);
        assertThat(page.get("items").size()).isEqualTo(50);
        assertThat(get("/transactions?limit=10&offset=50").get("items").size()).isEqualTo(5);
        assertThat(call(HttpMethod.GET, "/transactions?limit=1000", null).getStatusCode().value()).isEqualTo(400);
        assertThat(call(HttpMethod.GET, "/invoices?limit=1000", null).getStatusCode().value()).isEqualTo(400);
        String txn = id(page.at("/items/0"));
        for (int i = 0; i < 51; i++) invoice(List.of(txn));
        assertThat(get("/invoices").get("items").size()).isEqualTo(50);
        assertThat(get("/invoices?limit=10&offset=50").get("items").size()).isEqualTo(1);
    }

    @Test
    void missingAndInvalidRequestsAreRejected() {
        String missing = UUID.randomUUID().toString();
        for (String resource : List.of("transactions", "invoices")) {
            assertThat(call(HttpMethod.GET, "/" + resource + "/" + missing, null).getStatusCode().value()).isEqualTo(404);
            assertThat(call(HttpMethod.DELETE, "/" + resource + "/" + missing, null).getStatusCode().value()).isEqualTo(404);
        }
        assertThat(call(HttpMethod.PATCH, "/transactions/" + missing, Map.of("total", 2)).getStatusCode().value()).isEqualTo(404);
        assertThat(call(HttpMethod.PATCH, "/invoices/" + missing, Map.of("transaction_id", missing)).getStatusCode().value()).isEqualTo(404);
        assertThat(post("/invoices", Map.of("transaction_id", missing)).getStatusCode().value()).isEqualTo(404);
        ObjectNode bad = altered("flight", "bad");
        bad.put("type", "boat");
        assertThat(post("/transactions", bad).getStatusCode().value()).isEqualTo(400);
        bad = altered("trip_fee", "bad-fee");
        ((ObjectNode) bad.get("metadata")).put("fee_kind", "unknown");
        assertThat(post("/transactions", bad).getStatusCode().value()).isEqualTo(400);
        bad = altered("hotel", "bad-hotel");
        ((ObjectNode) bad.get("metadata")).remove("address");
        assertThat(post("/transactions", bad).getStatusCode().value()).isEqualTo(400);
        bad = altered("flight", "bad-money");
        bad.put("total", new BigDecimal("1.001"));
        assertThat(post("/transactions", bad).getStatusCode().value()).isEqualTo(400);
        bad.put("total", new BigDecimal("92233720368547758.08"));
        assertThat(post("/transactions", bad).getStatusCode().value()).isEqualTo(400);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM transactions", Long.class)).isZero();
    }

    @Test
    void invoiceTotalOverflowIsRejectedAtomically() {
        ObjectNode a = altered("trip_fee", "large-a"), b = altered("trip_fee", "large-b");
        a.put("total", new BigDecimal("92233720368547758.07"));
        b.put("total", new BigDecimal("0.01"));
        String aid = id(post("/transactions", a).getBody()), bid = id(post("/transactions", b).getBody());
        assertThat(post("/invoices", Map.of("transaction_ids", List.of(aid, bid))).getStatusCode().value()).isEqualTo(400);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM invoices", Long.class)).isZero();
    }

    @Test
    void concurrentDuplicateCreatesLeaveExactlyOneCompleteTransaction() throws Exception {
        List<ResponseEntity<JsonNode>> results = race(
                () -> post("/transactions", seeds.get("flight")), () -> post("/transactions", seeds.get("flight")));
        assertThat(results.stream().map(r -> r.getStatusCode().value()).sorted().toList()).containsExactly(201, 409);
        assertThat(id(results.get(0).getBody())).isEqualTo(id(results.get(1).getBody()));
        assertThat(count(seeds.at("/flight/external_id").asText())).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM transaction_taxes", Long.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM transaction_fees", Long.class)).isEqualTo(1);
    }

    @Test
    void concurrentPatchesNeverMixHeadersTaxesAndFees() throws Exception {
        String flight = id(create("flight"));
        var a = Map.of("total", 100, "tax_lines", List.of(Map.of("name", "A", "amount", 10)), "fee_lines", List.of(Map.of("name", "A", "amount", 20)));
        var b = Map.of("total", 200, "tax_lines", List.of(Map.of("name", "B", "amount", 30)), "fee_lines", List.of(Map.of("name", "B", "amount", 40)));
        var results = race(() -> call(HttpMethod.PATCH, "/transactions/" + flight, a), () -> call(HttpMethod.PATCH, "/transactions/" + flight, b));
        assertThat(results).allSatisfy(r -> assertThat(r.getStatusCode().value()).isEqualTo(200));
        JsonNode stored = get("/transactions/" + flight);
        String name = stored.at("/tax_lines/0/name").asText();
        assertThat(stored.at("/fee_lines/0/name").asText()).isEqualTo(name);
        amount(stored, "total", name.equals("A") ? "100" : "200");
        amount(stored.at("/tax_lines/0"), "amount", name.equals("A") ? "10" : "30");
    }

    @Test
    void invoiceCreationRacingTransactionPatchUsesOneConsistentVersion() throws Exception {
        String flight = id(create("flight"));
        var results = race(() -> post("/invoices", Map.of("transaction_id", flight)),
                () -> call(HttpMethod.PATCH, "/transactions/" + flight, Map.of("total", 250)));
        assertThat(results.get(0).getStatusCode().value()).isEqualTo(201);
        int patchStatus = results.get(1).getStatusCode().value();
        assertThat(patchStatus).isIn(200, 409);
        amount(results.get(0).getBody().get("totals"), "grand_total", patchStatus == 200 ? "250" : "220");
    }

    @Test
    void concurrentInvoicePatchAndDeleteLeavesNoPartialSnapshot() throws Exception {
        String flight = id(create("flight")), hotel = id(create("hotel"));
        String inv = id(invoice(List.of(flight)));
        var results = race(() -> call(HttpMethod.PATCH, "/invoices/" + inv, Map.of("transaction_id", hotel)),
                () -> call(HttpMethod.DELETE, "/invoices/" + inv, null));
        assertThat(results.get(0).getStatusCode().value()).isIn(200, 404);
        assertThat(results.get(1).getStatusCode().value()).isEqualTo(204);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM invoice_lines", Long.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM invoice_taxes", Long.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM invoice_fees", Long.class)).isZero();
        get("/transactions/" + flight);
        get("/transactions/" + hotel);
    }

    @Test
    void databaseUniqueConstraintProtectsIdentityEvenWithoutApiPrecheck() {
        JsonNode original = create("flight");
        assertThatThrownBy(() -> db.update("INSERT INTO transactions(id,external_id,type,currency,occurred_at,total_minor,metadata) VALUES(?,?,?,?,?,?,? FORMAT JSON)",
                UUID.randomUUID(), original.get("external_id").asText(), "flight", "EUR", "2026-03-11T06:40:00Z", 22000, "{}"))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(count(original.get("external_id").asText())).isEqualTo(1);
    }

    @Test
    void databaseFailureAfterHeaderAndTaxInsertRollsBackAllRows() throws Exception {
        var input = mapper.treeToValue(seeds.get("flight"), TransactionInput.class);
        var valid = validator.validate(UUID.randomUUID(), input);
        var invalid = new Transaction(valid.id(), valid.externalId(), valid.type(), valid.currency(), valid.occurredAt(), valid.totalMinor(),
                valid.taxes(), List.of(new Fee("x".repeat(201), 2500)), valid.metadata());
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> repository.insert(invalid)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM transactions", Long.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM transaction_taxes", Long.class)).isZero();
    }

    @Test
    void rollupsGroupTaxesByNameAndNumericRateAndFeesByName() {
        ObjectNode a = altered("trip_fee", "rollup-a"), b = altered("trip_fee", "rollup-b");
        a.set("tax_lines", mapper.valueToTree(List.of(Map.of("name", "VAT", "rate", new BigDecimal("0.07"), "amount", 1), Map.of("name", "VAT", "rate", new BigDecimal("0.10"), "amount", 2))));
        b.set("tax_lines", mapper.valueToTree(List.of(Map.of("name", "VAT", "rate", new BigDecimal("0.070"), "amount", 3))));
        a.set("fee_lines", mapper.valueToTree(List.of(Map.of("name", "Booking", "amount", 2))));
        b.set("fee_lines", mapper.valueToTree(List.of(Map.of("name", "Booking", "amount", 3))));
        JsonNode inv = invoice(List.of(id(post("/transactions", a).getBody()), id(post("/transactions", b).getBody())));
        assertThat(inv.get("tax_rollup").size()).isEqualTo(2);
        amount(inv.at("/tax_rollup/0"), "amount", "4");
        amount(inv.at("/tax_rollup/1"), "amount", "2");
        assertThat(inv.get("fee_rollup").size()).isEqualTo(1);
        amount(inv.at("/fee_rollup/0"), "amount", "5");
    }

    @Test
    void concurrentTransactionPatchAndDeleteLeavesNoOrphanCharges() throws Exception {
        String flight = id(create("flight"));
        var results = race(() -> call(HttpMethod.PATCH, "/transactions/" + flight, Map.of("total", 250)),
                () -> call(HttpMethod.DELETE, "/transactions/" + flight, null));
        assertThat(results.get(0).getStatusCode().value()).isIn(200, 404);
        assertThat(results.get(1).getStatusCode().value()).isEqualTo(204);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM transactions", Long.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM transaction_taxes", Long.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM transaction_fees", Long.class)).isZero();
    }

    @Test
    void simultaneousInvoicePatchesKeepOneCompleteSetOfLines() throws Exception {
        String flight = id(create("flight")), hotel = id(create("hotel")), rail = id(create("rail"));
        String inv = id(invoice(List.of(flight)));
        var results = race(() -> call(HttpMethod.PATCH, "/invoices/" + inv, Map.of("transaction_ids", List.of(flight, hotel))),
                () -> call(HttpMethod.PATCH, "/invoices/" + inv, Map.of("transaction_id", rail)));
        assertThat(results).allSatisfy(r -> assertThat(r.getStatusCode().value()).isEqualTo(200));
        JsonNode stored = get("/invoices/" + inv);
        if (stored.get("transactions").size() == 1) {
            assertThat(id(stored.at("/transactions/0"))).isEqualTo(rail);
            amount(stored.get("totals"), "grand_total", "94");
        } else {
            assertThat(stored.get("transactions").size()).isEqualTo(2);
            amount(stored.get("totals"), "grand_total", "551.70");
        }
    }

    @SafeVarargs
    final <T> List<T> race(Callable<T>... actions) throws Exception {
        var pool = Executors.newFixedThreadPool(actions.length);
        var ready = new CountDownLatch(actions.length);
        var start = new CountDownLatch(1);
        try {
            var futures = java.util.Arrays.stream(actions).map(action -> pool.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Race start timed out");
                return action.call();
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var out = new java.util.ArrayList<T>();
            for (var f : futures) out.add(f.get(20, TimeUnit.SECONDS));
            return out;
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }
}
