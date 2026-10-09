package com.navan.travel.models;

import java.util.List;

/** Pagination response shared by transaction and invoice lists. */
public final class PaginationModels {
    private PaginationModels() {
    }

    public record Page<T>(List<T> items, int limit, long offset) {
    }
}
