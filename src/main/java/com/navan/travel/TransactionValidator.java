package com.navan.travel;

import com.navan.travel.models.TransactionModels.Fee;
import com.navan.travel.models.TransactionModels.Tax;
import com.navan.travel.models.TransactionModels.Transaction;
import com.navan.travel.models.TransactionModels.TransactionInput;

import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class TransactionValidator {
    private final MetadataCodec metadata;
    public TransactionValidator(MetadataCodec metadata) { this.metadata = metadata; }
    public Transaction validate(UUID id, TransactionInput input) {
        if (input == null) throw ApiException.bad("Transaction body is required");

        String external = required(input.externalId(),"external_id");
        String type = required(input.type(),"type");
        String currency = required(input.currency(),"currency");
        try {
            if (!currency.matches("[A-Z]{3}") || Currency.getInstance(currency).getDefaultFractionDigits() != 2)
                throw ApiException.bad("Only ISO currencies with two decimal places are supported");
        } catch (IllegalArgumentException e) {
            throw ApiException.bad("Invalid currency");
        }
        String occurred = required(input.occurredAt(),"occurred_at"); MetadataCodec.timestamp(occurred);
        if (input.taxes() == null || input.fees() == null) throw ApiException.bad("tax_lines and fee_lines are required");
        if (input.taxes().size() > 100 || input.fees().size() > 100) throw ApiException.bad("At most 100 taxes and fees are supported");
        List<Tax> taxes = input.taxes().stream().map(t -> {
            if (t == null) throw ApiException.bad("Tax must be an object");
            if (t.rate() != null && (t.rate().signum() < 0 || t.rate().compareTo(java.math.BigDecimal.ONE) > 0 || t.rate().stripTrailingZeros().scale() > 10))
                throw ApiException.bad("Tax rate must be between 0 and 1 with at most 10 decimal places");
            return new Tax(required(t.name(),"Tax name"),t.rate(),Money.minor(t.amount()));
        }).toList();
        List<Fee> fees = input.fees().stream().map(f -> {
            if (f == null) throw ApiException.bad("Fee must be an object");
            return new Fee(required(f.name(),"Fee name"),Money.minor(f.amount()));
        }).toList();
        return new Transaction(id,external,type,currency,occurred,Money.minor(input.total()),taxes,fees,metadata.store(type,input.metadata()));
    }
    static String required(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 200) throw ApiException.bad(field + " must be nonblank and at most 200 characters");
        return value;
    }
}
