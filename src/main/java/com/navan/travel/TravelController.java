package com.navan.travel;

import com.navan.travel.models.InvoiceModels.InvoiceInput;
import com.navan.travel.models.InvoiceModels.InvoiceSummaryView;
import com.navan.travel.models.InvoiceModels.InvoiceView;
import com.navan.travel.models.PaginationModels.Page;
import com.navan.travel.models.TransactionModels.TransactionInput;
import com.navan.travel.models.TransactionModels.TransactionSummaryView;
import com.navan.travel.models.TransactionModels.TransactionView;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
public class TravelController {
    private final TransactionService transactions;
    private final InvoiceService invoices;
    public TravelController(TransactionService transactions, InvoiceService invoices) {
        this.transactions = transactions; this.invoices = invoices;
    }
    @GetMapping("/health")
    public Map<String,String> health() {
        return Map.of("status","UP");
    }
    @PostMapping("/transactions") @ResponseStatus(HttpStatus.CREATED)
    public TransactionView createTransaction(@RequestBody TransactionInput input) {
        return transactions.create(input);
    }
    @GetMapping("/transactions/{id}")
    public TransactionView getTransaction(@PathVariable UUID id) {
        return transactions.get(id);
    }
    @PatchMapping("/transactions/{id}")
    public TransactionView patchTransaction(@PathVariable UUID id, @RequestBody JsonNode patch) {
        return transactions.patch(id,patch);
    }
    @DeleteMapping("/transactions/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTransaction(@PathVariable UUID id) {
        transactions.delete(id);
    }
    @GetMapping("/transactions")
    public Page<TransactionSummaryView> listTransactions(@RequestParam(defaultValue="50") int limit,
            @RequestParam(defaultValue="0") long offset) {
        page(limit,offset); return transactions.list(limit,offset);
    }
    @PostMapping("/invoices") @ResponseStatus(HttpStatus.CREATED)
    public InvoiceView createInvoice(@RequestBody InvoiceInput input) {
        return invoices.create(input);
    }
    @GetMapping("/invoices/{id}")
    public InvoiceView getInvoice(@PathVariable UUID id) {
        return invoices.get(id);
    }
    @PatchMapping("/invoices/{id}")
    public InvoiceView patchInvoice(@PathVariable UUID id, @RequestBody InvoiceInput input) {
        return invoices.patch(id,input);
    }
    @DeleteMapping("/invoices/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteInvoice(@PathVariable UUID id) {
        invoices.delete(id);
    }
    @GetMapping("/invoices")
    public Page<InvoiceSummaryView> listInvoices(@RequestParam(defaultValue="50") int limit,
            @RequestParam(defaultValue="0") long offset) {
        page(limit,offset); return invoices.list(limit,offset);
    }
    private void page(int limit, long offset) {
        if (limit < 1 || limit > 100 || offset < 0) throw ApiException.bad("limit must be 1–100 and offset must be non-negative");
    }
}
