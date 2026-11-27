package com.example.lender_service.dto;

import java.time.Instant;

/** Enriched return request row used by the LendDesk live returns register. */
public record ReturnSummary(
    Long id,
    Long loanId,
    Long recallId,
    Long borrowerReturnId,
    Long quantity,
    String status,
    String settlementReference,
    Instant createdAt,
    Instant settledAt,
    Long securityId,
    String securitySymbol,
    String securityName,
    String loanStatus,
    Instant loanClosedAt,
    Long connectionId,
    String borrowerName,
    String collateralReleaseState,
    Long marginCallId) {}
