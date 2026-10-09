package com.navan.travel.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.navan.travel.models.TransactionModels.Transaction;
import com.navan.travel.models.TransactionModels.TransactionView;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Invoice storage, request, response, snapshot, and rollup records. */
public final class InvoiceModels {
    private InvoiceModels() {
    }

    public record Invoice(UUID id, String currency, long totalMinor, List<Transaction> lines) {
    }

    public record InvoiceInput(@JsonProperty("transaction_id") UUID transactionId,
                               @JsonProperty("transaction_ids") List<UUID> transactionIds) {
    }

    public record InvoiceView(UUID id, String currency, List<TransactionView> transactions,
                              @JsonProperty("tax_rollup") List<TaxRollup> taxes,
                              @JsonProperty("fee_rollup") List<FeeRollup> fees, Totals totals) {
    }

    public record Totals(@JsonProperty("grand_total") BigDecimal grandTotal) {
    }

    public record InvoiceSummaryView(UUID id, String currency, Totals totals) {
    }

    public record TaxRollup(String name, BigDecimal rate, BigDecimal amount) {
    }

    public record FeeRollup(String name, BigDecimal amount) {
    }

    public record TaxRollupKey(String name, BigDecimal rate) {
    }

    public record Snapshot(UUID lineId, Transaction transaction) {
    }
}
