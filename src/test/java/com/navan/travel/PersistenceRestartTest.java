package com.navan.travel;

import com.navan.travel.models.InvoiceModels.InvoiceInput;
import com.navan.travel.models.TransactionModels.TransactionInput;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import static org.assertj.core.api.Assertions.assertThat;

class PersistenceRestartTest {
    @Test void transactionsAndFrozenInvoicesSurviveApplicationRestart() throws Exception {
        String url = "jdbc:h2:file:./target/restart-db-" + UUID.randomUUID() + ";DB_CLOSE_ON_EXIT=FALSE";
        UUID transactionId, invoiceId;
        try (var app = start(url)) {
            var mapper = app.getBean(ObjectMapper.class);
            var flight = mapper.readTree(Files.readString(Path.of("fixtures/task-b/seed-payloads.json"))).get("flight");
            var transaction = app.getBean(TransactionService.class).create(mapper.treeToValue(flight,TransactionInput.class));
            transactionId = transaction.id();
            invoiceId = app.getBean(InvoiceService.class).create(new InvoiceInput(transactionId,null)).id();
        }
        try (var app = start(url)) {
            var transaction = app.getBean(TransactionService.class).get(transactionId);
            var invoice = app.getBean(InvoiceService.class).get(invoiceId);
            assertThat(transaction.total()).isEqualByComparingTo("220.00");
            assertThat(invoice.totals().grandTotal()).isEqualByComparingTo("220.00");
            assertThat(invoice.transactions().get(0).metadata().at("/coupons/0/flight_number").asText()).isEqualTo("BA982");
        }
    }
    private ConfigurableApplicationContext start(String url) {
        var app = new SpringApplication(TravelApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        return app.run("--spring.datasource.url=" + url,"--spring.main.banner-mode=off");
    }
}
