package com.navan.travel;

import com.navan.travel.models.InvoiceModels.Invoice;
import com.navan.travel.models.InvoiceModels.InvoiceInput;
import com.navan.travel.models.InvoiceModels.InvoiceSummaryView;
import com.navan.travel.models.InvoiceModels.InvoiceView;
import com.navan.travel.models.PaginationModels.Page;
import com.navan.travel.models.TransactionModels.Transaction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class InvoiceService {
    private final TravelRepository repository;
    private final TransactionTemplate tx;
    private final ApiViews views;
    public InvoiceService(TravelRepository repository, TransactionTemplate tx, ApiViews views) {
        this.repository = repository; this.tx = tx; this.views = views;
    }
    public InvoiceView create(InvoiceInput input) {
        List<UUID> ids = ids(input);
        return tx.execute(s -> save(UUID.randomUUID(),ids,false));
    }
    public InvoiceView patch(UUID id, InvoiceInput input) {
        List<UUID> ids = ids(input);
        return tx.execute(s -> { repository.invoiceHeader(id); return save(id,ids,true); });
    }
    public InvoiceView get(UUID id) {
        return tx.execute(s -> views.invoice(repository.invoice(id)));
    }
    public Page<InvoiceSummaryView> list(int limit, long offset) {
        return new Page<>(repository.listInvoices(limit,offset),limit,offset);
    }
    public void delete(UUID id) {
        tx.executeWithoutResult(s -> { repository.invoiceHeader(id); repository.deleteInvoice(id); });
    }
    private InvoiceView save(UUID id, List<UUID> ids, boolean replace) {
        // A stable lock order avoids deadlocks between invoices containing the same transactions.
        var loaded = new HashMap<UUID,Transaction>();
        ids.stream().sorted(java.util.Comparator.comparing(UUID::toString)).forEach(key -> loaded.put(key,repository.transaction(key)));
        List<Transaction> lines = ids.stream().map(loaded::get).toList();
        String currency = lines.get(0).currency();
        long total = 0;
        for (Transaction t : lines) {
            if (!currency.equals(t.currency())) throw ApiException.conflict("Cannot invoice mixed currencies");
            total = Money.add(total,t.totalMinor());
        }
        Invoice invoice = new Invoice(id,currency,total,lines);
        // Validate rollup overflow before any snapshot changes.
        InvoiceView view = views.invoice(invoice);
        repository.saveInvoice(invoice,replace);
        return view;
    }
    private List<UUID> ids(InvoiceInput input) {
        if (input == null || (input.transactionId() != null && input.transactionIds() != null))
            throw ApiException.bad("Supply transaction_id or transaction_ids, not both");
        List<UUID> ids = input.transactionId() != null ? List.of(input.transactionId()) : input.transactionIds();
        if (ids == null || ids.isEmpty() || ids.size() > 100 || ids.stream().anyMatch(java.util.Objects::isNull))
            throw ApiException.bad("Invoice requires 1–100 non-null transaction IDs");
        if (new HashSet<>(ids).size() != ids.size()) throw ApiException.conflict("Repeated transaction ID");
        return new ArrayList<>(ids);
    }
}
