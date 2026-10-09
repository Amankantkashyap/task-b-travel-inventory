package com.navan.travel;

import com.navan.travel.models.TransactionModels.Address;
import com.navan.travel.models.TransactionModels.Coupon;
import com.navan.travel.models.TransactionModels.Extra;
import com.navan.travel.models.TransactionModels.Flight;
import com.navan.travel.models.TransactionModels.Hotel;
import com.navan.travel.models.TransactionModels.Leg;
import com.navan.travel.models.TransactionModels.Metadata;
import com.navan.travel.models.TransactionModels.NavanFee;
import com.navan.travel.models.TransactionModels.Rail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * Validates metadata and converts between API amounts and stored integer minor units.
 */
@Component
public class MetadataCodec {
    private final ObjectMapper mapper;

    public MetadataCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public JsonNode store(String type, JsonNode node) {
        if (node == null || !node.isObject()) throw ApiException.bad("metadata must be an object");
        Metadata metadata = switch (type) {
            case "flight" -> new Flight(text(node, "origin"), text(node, "destination"), money(node, "fare"),
                    array(node, "coupons", true).stream().map(c -> {
                        times(c, "departure", "arrival");
                        return new Coupon(text(c, "flight_number"), optional(c, "carrier"), text(c, "from"), text(c, "to"),
                                text(c, "departure"), text(c, "arrival"));
                    }).toList());
            case "hotel" -> {
                JsonNode address = node.get("address");
                if (address == null || !address.isObject()) throw ApiException.bad("hotel address is required");
                String in = text(node, "check_in"), out = text(node, "check_out");
                try {
                    if (!LocalDate.parse(out).isAfter(LocalDate.parse(in)))
                        throw ApiException.bad("check_out must follow check_in");
                } catch (java.time.format.DateTimeParseException e) {
                    throw ApiException.bad("Hotel dates must be ISO dates");
                }
                JsonNode nights = node.get("nights");
                if (nights == null || !nights.isIntegralNumber() || !nights.canConvertToInt() || nights.intValue() < 1)
                    throw ApiException.bad("nights must be a positive integer");
                List<Extra> extras = node.has("extras") ? array(node, "extras", false).stream()
                        .map(e -> new Extra(text(e, "name"), money(e, "amount"))).toList() : List.of();
                yield new Hotel(text(node, "name"), new Address(text(address, "street"), text(address, "city"), text(address, "country")),
                        in, out, nights.intValue(), money(node, "room_rate_per_night"), extras);
            }
            case "rail" -> new Rail(array(node, "legs", true).stream().map(l -> {
                times(l, "departure", "arrival");
                return new Leg(text(l, "origin"), text(l, "destination"), text(l, "departure"), text(l, "arrival"),
                        optional(l, "train_number"), optional(l, "class"));
            }).toList());
            case "navan_fee" -> {
                String kind = text(node, "fee_kind");
                if (!List.of("trip_fee", "agent_call_fee").contains(kind))
                    throw ApiException.bad("Unknown navan_fee fee_kind");
                yield new NavanFee(kind, optional(node, "description"), optional(node, "related_external_id"));
            }
            default -> throw ApiException.bad("Unknown transaction type");
        };
        return mapper.valueToTree(metadata);
    }

    public JsonNode view(String type, JsonNode stored) {
        ObjectNode out = stored.deepCopy();
        if (type.equals("flight")) decimal(out, "fare_minor", "fare");
        if (type.equals("hotel")) {
            decimal(out, "room_rate_per_night_minor", "room_rate_per_night");
            for (JsonNode extra : out.withArray("extras")) decimal((ObjectNode) extra, "amount_minor", "amount");
        }
        return out;
    }

    private void decimal(ObjectNode node, String internal, String external) {
        node.put(external, Money.decimal(node.remove(internal).longValue()));
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank() || value.textValue().length() > 200)
            throw ApiException.bad(field + " must be a nonblank string of at most 200 characters");
        return value.textValue();
    }

    private static String optional(JsonNode node, String field) {
        return !node.hasNonNull(field) ? null : text(node, field);
    }

    private static long money(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()) throw ApiException.bad(field + " must be an amount");
        return Money.minor(value.decimalValue());
    }

    private static List<JsonNode> array(JsonNode node, String field, boolean nonempty) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray() || (nonempty && value.isEmpty()) || value.size() > 100)
            throw ApiException.bad(field + " must be an array" + (nonempty ? " with 1–100 entries" : " with at most 100 entries"));
        java.util.ArrayList<JsonNode> result = new java.util.ArrayList<>();
        value.forEach(v -> {
            if (!v.isObject()) throw ApiException.bad(field + " entries must be objects");
            result.add(v);
        });
        return result;
    }

    static void timestamp(String value) {
        try {
            OffsetDateTime.parse(value);
        } catch (RuntimeException e) {
            throw ApiException.bad("Timestamp must be ISO-8601 with a timezone");
        }
    }

    private static void times(JsonNode node, String start, String end) {
        String a = text(node, start), b = text(node, end);
        timestamp(a);
        timestamp(b);
        if (!OffsetDateTime.parse(b).toInstant().isAfter(OffsetDateTime.parse(a).toInstant()))
            throw ApiException.bad(end + " must follow " + start);
    }
}
