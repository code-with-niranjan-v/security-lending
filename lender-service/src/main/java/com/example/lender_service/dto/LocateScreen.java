package com.example.lender_service.dto;

import com.example.lender_service.response.PageResponse;
import java.time.Instant;
import java.util.List;

public final class LocateScreen {
  private LocateScreen() {}
  public record Option(Long id, String label) {}
  public record Overview(PageResponse<Row> locates, long pendingCount, long activeReservationCount,
      List<Option> borrowers, List<Option> agreements, List<Option> securities,
      List<String> statuses, Instant asOf) {}
  public record Row(Long id, Long borrowerLocateId, Long connectionId, String borrowerName,
      String borrowerExternalId, Long mslaId, String agreementNumber, Long securityId,
      String securitySymbol, long requestedQuantity, long approvedQuantity,
      long reservedQuantity, long consumedQuantity, long unallocatedQuantity, long availableQuantity,
      Long inventoryAvailableQuantity, String status, Instant createdAt, Instant expiresAt,
      long remainingSeconds, String expiryBucket, boolean canApprove, boolean canReject) {}
  public record LinkedLoan(Long id, long quantity, long outstandingQuantity, String status) {}
  public record ReservationRow(Long id, Long borrowerReservationId, long quantity, long remainingQuantity,
      String status, Instant expiresAt, long remainingSeconds, List<LinkedLoan> linkedLoans) {}
  public record Activity(Long id, String resourceType, String type, String reference, Instant createdAt) {}
  public record Detail(Row locate, List<ReservationRow> reservations, List<Activity> activity,
      long activityCount, Instant asOf, List<String> unavailableFields) {}
}
