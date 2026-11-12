package com.example.lender_service.dto;

import java.math.BigDecimal;
import java.util.List;

public record AgreementExpanded(
    AgreementSummary agreement,
    Object borrower,
    List<LoanSummary> loans,
    Long activeLoanCount,
    Long closedLoanCount,
    BigDecimal totalExposure) {

  public record LoanSummary(
      Long id,
      Long borrowerLoanId,
      String status,
      Long securityId,
      String securitySymbol,
      String securityName,
      Long quantity,
      Long outstandingQuantity,
      BigDecimal marketPrice,
      BigDecimal marketValue,
      BigDecimal coverageRatio,
      BigDecimal collateralValue,
      BigDecimal requiredCollateral,
      Long collateralCount,
      java.time.Instant settledAt,
      java.time.Instant closedAt,
      List<CollateralSummary> collateral) {}

  public record CollateralSummary(
      Long id,
      String status,
      String assetType,
      Long securityId,
      String securitySymbol,
      Long quantity,
      BigDecimal cashAmount,
      BigDecimal haircut,
      BigDecimal marketPrice,
      BigDecimal marketValue) {}
}
