package com.example.borrower_service.dto;

import jakarta.validation.constraints.Positive;

public record ReturnRequest(@Positive long quantity, @Positive Long recallId, @Positive Long marginCallId) {
    public ReturnRequest(long quantity) { this(quantity, null, null); }
    public ReturnRequest(long quantity, Long recallId) { this(quantity, recallId, null); }
}