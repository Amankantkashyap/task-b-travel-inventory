package com.navan.travel;

import java.util.UUID;

public class ApiException extends RuntimeException {
    final int status;
    final UUID existingId;
    public ApiException(int status, String message) { this(status, message, null); }
    public ApiException(int status, String message, UUID existingId) {
        super(message); this.status = status; this.existingId = existingId;
    }
    public static ApiException bad(String message) { return new ApiException(400, message); }
    public static ApiException missing(String resource) { return new ApiException(404, resource + " not found"); }
    public static ApiException conflict(String message) { return new ApiException(409, message); }
}
