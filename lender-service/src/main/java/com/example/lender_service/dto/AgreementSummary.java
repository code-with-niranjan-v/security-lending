package com.example.lender_service.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record AgreementSummary(
    Long id,
    Long organizationId,
    Long connectionId,
    Long borrowerOrganizationId,
    String borrowerName,
    String borrowerExternalId,
    String number,
    String status,
    LocalDate effectiveDate,
    LocalDate expiryDate,
    BigDecimal creditLimit,
    BigDecimal feeRate,
    BigDecimal rebateRate,
    BigDecimal coverageRatio,
    String billingFrequency,
    String rebateSettlementFrequency,
    Integer dayBasis,
    Boolean recallAllowed,
    Long activeLoanCount,
    Long totalLoanCount,
    BigDecimal totalExposure) {}
