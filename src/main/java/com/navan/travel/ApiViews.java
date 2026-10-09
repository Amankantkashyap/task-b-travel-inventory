package com.navan.travel;

import com.navan.travel.models.InvoiceModels.FeeRollup;
import com.navan.travel.models.InvoiceModels.Invoice;
import com.navan.travel.models.InvoiceModels.InvoiceView;
import com.navan.travel.models.InvoiceModels.TaxRollup;
import com.navan.travel.models.InvoiceModels.TaxRollupKey;
import com.navan.travel.models.InvoiceModels.Totals;
import com.navan.travel.models.TransactionModels.Fee;
import com.navan.travel.models.TransactionModels.FeeView;
import com.navan.travel.models.TransactionModels.Tax;
import com.navan.travel.models.TransactionModels.TaxView;
import com.navan.travel.models.TransactionModels.Transaction;
import com.navan.travel.models.TransactionModels.TransactionView;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ApiViews {
    private final MetadataCodec metadata;
    public ApiViews(MetadataCodec metadata) { this.metadata = metadata; }
    public TransactionView transaction(Transaction t) {
        return new TransactionView(t.id(),t.externalId(),t.type(),t.currency(),t.occurredAt(),Money.decimal(t.totalMinor()),
            t.taxes().stream().map(x -> new TaxView(x.name(),x.rate(),Money.decimal(x.amountMinor()))).toList(),
            t.fees().stream().map(x -> new FeeView(x.name(),Money.decimal(x.amountMinor()))).toList(),metadata.view(t.type(),t.metadata()));
    }
    public InvoiceView invoice(Invoice invoice) {
        Map<TaxRollupKey,Long> taxes = new LinkedHashMap<>();
        Map<String,Long> fees = new LinkedHashMap<>();
        for (Transaction t : invoice.lines()) {
            for (Tax tax : t.taxes()) {
                BigDecimal rate = tax.rate() == null ? null : tax.rate().stripTrailingZeros();
                taxes.merge(new TaxRollupKey(tax.name(),rate),tax.amountMinor(),Money::add);
            }
            for (Fee fee : t.fees()) fees.merge(fee.name(),fee.amountMinor(),Money::add);
        }
        return new InvoiceView(invoice.id(),invoice.currency(),invoice.lines().stream().map(this::transaction).toList(),
            taxes.entrySet().stream().map(e -> new TaxRollup(e.getKey().name(),e.getKey().rate(),Money.decimal(e.getValue()))).toList(),
            fees.entrySet().stream().map(e -> new FeeRollup(e.getKey(),Money.decimal(e.getValue()))).toList(),new Totals(Money.decimal(invoice.totalMinor())));
    }
}
