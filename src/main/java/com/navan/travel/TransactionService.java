package com.navan.travel;

import com.navan.travel.models.PaginationModels.Page;
import com.navan.travel.models.TransactionModels.Transaction;
import com.navan.travel.models.TransactionModels.TransactionInput;
import com.navan.travel.models.TransactionModels.TransactionSummaryView;
import com.navan.travel.models.TransactionModels.TransactionView;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TransactionService {
    private final TravelRepository repository;
    private final TransactionValidator validator;
    private final TransactionTemplate tx;
    private final ApiViews views;
    private final ObjectMapper mapper;
    public TransactionService(TravelRepository repository, TransactionValidator validator, TransactionTemplate tx,
                              ApiViews views, ObjectMapper mapper) {
        this.repository = repository; this.validator = validator; this.tx = tx; this.views = views; this.mapper = mapper;
    }
    public TransactionView create(TransactionInput input) {
        String externalId = TransactionValidator.required(input.externalId(),"external_id");
        repository.existing(externalId).ifPresent(existingTransactionId -> { throw duplicate(existingTransactionId); });
        Transaction candidate = validator.validate(UUID.randomUUID(),input);
        try { return tx.execute(s -> { repository.insert(candidate); return views.transaction(candidate); }); }
        catch (DuplicateKeyException e) {
            // Resolve the winner only AFTER the failed write transaction has rolled back.
            UUID existingTransactionId = repository.existing(externalId).orElseThrow(() -> e);
            throw duplicate(existingTransactionId);
        }
    }
    public TransactionView get(UUID id) { return tx.execute(s -> views.transaction(repository.transaction(id))); }
    public Page<TransactionSummaryView> list(int limit, long offset) {
        return new Page<>(repository.listTransactions(limit,offset),limit,offset);
    }
    public TransactionView patch(UUID id, JsonNode patch) {
        return tx.execute(s -> {
            Transaction current = repository.transaction(id);
            requireUnbilled(id);
            if (patch == null || !patch.isObject() || patch.isEmpty()) throw ApiException.bad("PATCH must be a nonempty object");
            Set<String> allowed = Set.of("total","tax_lines","fee_lines","metadata","occurred_at");
            patch.fieldNames().forEachRemaining(field -> { if (!allowed.contains(field)) throw ApiException.bad("Field is immutable or unknown: " + field); });
            ObjectNode body = mapper.valueToTree(views.transaction(current)); body.remove("id");
            patch.fields().forEachRemaining(e -> body.set(e.getKey(),e.getValue()));
            TransactionInput input;
            try { input = mapper.treeToValue(body,TransactionInput.class); }
            catch (Exception e) { throw ApiException.bad("Invalid transaction patch"); }
            Transaction updated = validator.validate(id,input);
            repository.update(updated);
            return views.transaction(updated);
        });
    }
    public void delete(UUID id) {
        tx.executeWithoutResult(s -> { repository.transactionHeader(id); requireUnbilled(id); repository.deleteTransaction(id); });
    }
    private void requireUnbilled(UUID id) {
        if (repository.billed(id)) throw ApiException.conflict("Transaction is already on an invoice");
    }
    private ApiException duplicate(UUID existingTransactionId) {
        return new ApiException(409,"external_id already exists",existingTransactionId);
    }
}
