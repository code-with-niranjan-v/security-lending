package com.example.lender_service.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record InventorySummary(
    Long id,
    Long organizationId,
    Long securityId,
    String symbol,
    String name,
    String isin,
    String assetClass,
    Long totalQuantity,
    Long onLoanQuantity,
    Long reservedQuantity,
    Long availableQuantity,
    BigDecimal marketPrice,
    BigDecimal marketValue,
    BigDecimal indicativeRateBps,
    Integer utilisation,
    String availability,
    Instant quotedAt) {}
