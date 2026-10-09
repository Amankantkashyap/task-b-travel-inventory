package com.navan.travel;

import com.navan.travel.models.InvoiceModels.Invoice;
import com.navan.travel.models.InvoiceModels.InvoiceSummaryView;
import com.navan.travel.models.InvoiceModels.Snapshot;
import com.navan.travel.models.InvoiceModels.Totals;
import com.navan.travel.models.TransactionModels.Fee;
import com.navan.travel.models.TransactionModels.Tax;
import com.navan.travel.models.TransactionModels.Transaction;
import com.navan.travel.models.TransactionModels.TransactionSummaryView;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TravelRepository {
    private final JdbcTemplate db;
    private final ObjectMapper mapper;

    public TravelRepository(JdbcTemplate db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    public Optional<UUID> existing(String externalId) {
        return db.query("SELECT id FROM transactions WHERE external_id = ?", (rs, n) -> uuid(rs, "id"), externalId).stream().findFirst();
    }

    public Transaction transactionHeader(UUID id) {
        var rows = db.query("SELECT * FROM transactions WHERE id = ? FOR UPDATE", (rs, n) -> header(rs, "id"), id);
        if (rows.isEmpty()) throw ApiException.missing("Transaction");
        return rows.get(0);
    }

    public Transaction transaction(UUID id) {
        return charges(transactionHeader(id), "transaction_taxes", "transaction_fees", "transaction_id", id);
    }

    public List<TransactionSummaryView> listTransactions(int limit, long offset) {
        return db.query("SELECT id,external_id,type,currency,occurred_at,total_minor FROM transactions ORDER BY id LIMIT ? OFFSET ?",
                (rs, n) -> new TransactionSummaryView(uuid(rs, "id"), rs.getString("external_id"), rs.getString("type"),
                        rs.getString("currency"), rs.getString("occurred_at"), Money.decimal(rs.getLong("total_minor"))), limit, offset);
    }

    public void insert(Transaction t) {
        db.update("INSERT INTO transactions(id,external_id,type,currency,occurred_at,total_minor,metadata) VALUES(?,?,?,?,?,?,? FORMAT JSON)",
                t.id(), t.externalId(), t.type(), t.currency(), t.occurredAt(), t.totalMinor(), json(t.metadata()));
        saveCharges(t, "transaction_taxes", "transaction_fees", "transaction_id", t.id());
    }

    public void update(Transaction t) {
        db.update("UPDATE transactions SET occurred_at=?,total_minor=?,metadata=? FORMAT JSON WHERE id=?",
                t.occurredAt(), t.totalMinor(), json(t.metadata()), t.id());
        db.update("DELETE FROM transaction_taxes WHERE transaction_id=?", t.id());
        db.update("DELETE FROM transaction_fees WHERE transaction_id=?", t.id());
        saveCharges(t, "transaction_taxes", "transaction_fees", "transaction_id", t.id());
    }

    public boolean billed(UUID id) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM invoice_lines WHERE transaction_id=?)", Boolean.class, id));
    }

    public void deleteTransaction(UUID id) {
        db.update("DELETE FROM transactions WHERE id=?", id);
    }

    public Invoice invoiceHeader(UUID id) {
        var headers = db.query("SELECT currency,total_minor FROM invoices WHERE id=? FOR UPDATE",
                (rs, n) -> new Invoice(id, rs.getString("currency"), rs.getLong("total_minor"), List.of()), id);
        if (headers.isEmpty()) throw ApiException.missing("Invoice");
        return headers.get(0);
    }

    public Invoice invoice(UUID id) {
        Invoice h = invoiceHeader(id);
        var lines = db.query("SELECT * FROM invoice_lines WHERE invoice_id=? ORDER BY position", (rs, n) ->
                new Snapshot(uuid(rs, "id"), header(rs, "transaction_id")), id);
        var transactions = lines.stream().map(l -> charges(l.transaction(), "invoice_taxes", "invoice_fees", "line_id", l.lineId())).toList();
        return new Invoice(id, h.currency(), h.totalMinor(), transactions);
    }

    public List<InvoiceSummaryView> listInvoices(int limit, long offset) {
        return db.query("SELECT id,currency,total_minor FROM invoices ORDER BY id LIMIT ? OFFSET ?",
                (rs, n) -> new InvoiceSummaryView(uuid(rs, "id"), rs.getString("currency"), new Totals(Money.decimal(rs.getLong("total_minor")))), limit, offset);
    }

    public void saveInvoice(Invoice invoice, boolean replace) {
        if (replace) {
            db.update("UPDATE invoices SET currency=?,total_minor=? WHERE id=?", invoice.currency(), invoice.totalMinor(), invoice.id());
            db.update("DELETE FROM invoice_lines WHERE invoice_id=?", invoice.id());
        } else
            db.update("INSERT INTO invoices(id,currency,total_minor) VALUES(?,?,?)", invoice.id(), invoice.currency(), invoice.totalMinor());
        int position = 0;
        for (Transaction t : invoice.lines()) {
            UUID lineId = UUID.randomUUID();
            db.update("INSERT INTO invoice_lines(id,invoice_id,transaction_id,position,external_id,type,currency,occurred_at,total_minor,metadata) VALUES(?,?,?,?,?,?,?,?,?,? FORMAT JSON)",
                    lineId, invoice.id(), t.id(), position++, t.externalId(), t.type(), t.currency(), t.occurredAt(), t.totalMinor(), json(t.metadata()));
            saveCharges(t, "invoice_taxes", "invoice_fees", "line_id", lineId);
        }
    }

    public void deleteInvoice(UUID id) {
        db.update("DELETE FROM invoices WHERE id=?", id);
    }

    private void saveCharges(Transaction t, String taxTable, String feeTable, String parent, UUID id) {
        // Table/column names are internal constants, never request input.
        for (Tax tax : t.taxes())
            db.update("INSERT INTO " + taxTable + "(" + parent + ",name,rate,amount_minor) VALUES(?,?,?,?)", id, tax.name(), tax.rate(), tax.amountMinor());
        for (Fee fee : t.fees())
            db.update("INSERT INTO " + feeTable + "(" + parent + ",name,amount_minor) VALUES(?,?,?)", id, fee.name(), fee.amountMinor());
    }

    private Transaction charges(Transaction h, String taxTable, String feeTable, String parent, UUID id) {
        var taxes = db.query("SELECT name,rate,amount_minor FROM " + taxTable + " WHERE " + parent + "=? ORDER BY id",
                (rs, n) -> new Tax(rs.getString("name"), rs.getBigDecimal("rate"), rs.getLong("amount_minor")), id);
        var fees = db.query("SELECT name,amount_minor FROM " + feeTable + " WHERE " + parent + "=? ORDER BY id",
                (rs, n) -> new Fee(rs.getString("name"), rs.getLong("amount_minor")), id);
        return new Transaction(h.id(), h.externalId(), h.type(), h.currency(), h.occurredAt(), h.totalMinor(), taxes, fees, h.metadata());
    }

    private Transaction header(ResultSet rs, String idColumn) throws SQLException {
        return new Transaction(uuid(rs, idColumn), rs.getString("external_id"), rs.getString("type"), rs.getString("currency"),
                rs.getString("occurred_at"), rs.getLong("total_minor"), List.of(), List.of(), parse(rs.getString("metadata")));
    }

    private UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    private JsonNode parse(String value) {
        try {
            return mapper.readTree(value);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid persisted metadata", e);
        }
    }

    private String json(JsonNode value) {
        return value.toString();
    }
}
