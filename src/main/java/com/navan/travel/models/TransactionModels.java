package com.navan.travel.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Transaction storage, request, response, and type-specific metadata records. */
public final class TransactionModels {
    private TransactionModels() {
    }

    public record Tax(String name, BigDecimal rate, long amountMinor) {
    }

    public record Fee(String name, long amountMinor) {
    }

    public record Transaction(UUID id, String externalId, String type, String currency,
                              String occurredAt, long totalMinor, List<Tax> taxes, List<Fee> fees, JsonNode metadata) {
    }

    public record TaxInput(String name, BigDecimal rate, BigDecimal amount) {
    }

    public record FeeInput(String name, BigDecimal amount) {
    }

    @JsonIgnoreProperties({"comment"})
    public record TransactionInput(String type, @JsonProperty("external_id") String externalId,
                                   String currency, @JsonProperty("occurred_at") String occurredAt, BigDecimal total,
                                   @JsonProperty("tax_lines") List<TaxInput> taxes,
                                   @JsonProperty("fee_lines") List<FeeInput> fees, JsonNode metadata) {
    }

    public record TaxView(String name, BigDecimal rate, BigDecimal amount) {
    }

    public record FeeView(String name, BigDecimal amount) {
    }

    public record TransactionView(UUID id, @JsonProperty("external_id") String externalId, String type,
                                  String currency, @JsonProperty("occurred_at") String occurredAt, BigDecimal total,
                                  @JsonProperty("tax_lines") List<TaxView> taxes,
                                  @JsonProperty("fee_lines") List<FeeView> fees, JsonNode metadata) {
    }

    public record TransactionSummaryView(UUID id, @JsonProperty("external_id") String externalId,
                                         String type, String currency, @JsonProperty("occurred_at") String occurredAt,
                                         BigDecimal total) {
    }

    public sealed interface Metadata permits Flight, Hotel, Rail, NavanFee {
    }

    public record Flight(String origin, String destination,
                         @JsonProperty("fare_minor") long fareMinor,
                         List<Coupon> coupons) implements Metadata {
    }

    public record Coupon(@JsonProperty("flight_number") String flightNumber, String carrier,
                         String from, String to, String departure, String arrival) {
    }

    public record Hotel(String name, Address address,
                        @JsonProperty("check_in") String checkIn,
                        @JsonProperty("check_out") String checkOut, int nights,
                        @JsonProperty("room_rate_per_night_minor") long roomRateMinor,
                        List<Extra> extras) implements Metadata {
    }

    public record Address(String street, String city, String country) {
    }

    public record Extra(String name, @JsonProperty("amount_minor") long amountMinor) {
    }

    public record Rail(List<Leg> legs) implements Metadata {
    }

    public record Leg(String origin, String destination, String departure, String arrival,
                      @JsonProperty("train_number") String trainNumber,
                      @JsonProperty("class") String travelClass) {
    }

    public record NavanFee(@JsonProperty("fee_kind") String feeKind, String description,
                           @JsonProperty("related_external_id") String relatedExternalId) implements Metadata {
    }
}
