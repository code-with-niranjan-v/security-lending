package com.example.lender_service.dto;

import com.example.lender_service.response.PageResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Read models for the agreements register. Rates/ratios carry explicit display units. */
public final class AgreementScreen {
  private AgreementScreen() {}
  public record BorrowerOption(Long connectionId, String name, String externalId) {}
  public record Overview(PageResponse<Row> agreements, long activeAgreementCount,
      List<BorrowerOption> borrowers, List<String> statuses, List<String> unavailableFields) {}
  public record Row(Long id, String number, Long connectionId, String borrowerName,
      String borrowerExternalId, String status, LocalDate effectiveDate, LocalDate expiryDate,
      BigDecimal feeRateBps, String rebateSettlementFrequency, BigDecimal coveragePercent,
      boolean recallAllowed, long activeLoanCount, long totalLoanCount, BigDecimal totalExposure,
      long unpricedLoanCount, String currency, BigDecimal creditLimit, BigDecimal rebateRateBps,
      String billingFrequency, int dayBasis) {}
  public record Rule(Long id, String assetType, Long securityId, String securitySymbol,
      boolean eligible, BigDecimal haircutPercent, BigDecimal advancePercent,
      BigDecimal concentrationLimitPercent) {}
  public record LoanRow(Long id, Long securityId, String securitySymbol, String status,
      long originalQuantity, long outstandingQuantity, BigDecimal marketPrice,
      BigDecimal marketValue, Instant priceAt, Instant settledAt) {}
  public record Activity(Long id, String type, Long securityId, String securitySymbol,
      long quantity, String status, Instant createdAt, Instant expiresAt) {}
  public record Billing(Long id, Long loanId, String type, BigDecimal amount, String status,
      LocalDate periodStart, LocalDate periodEnd, LocalDate dueDate) {}
  public record Audit(Long id, String type, String reference, Instant createdAt) {}
  public record Detail(Row agreement, Object terms, List<Rule> collateralRules,
      List<LoanRow> activeLoans, long activeLoanCount, List<Activity> recentActivity,
      long recentActivityCount, List<Billing> billing, long billingCount,
      List<Audit> audit, long auditCount, List<String> unavailableFields) {}
}
