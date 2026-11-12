package com.example.lender_service.dto;

import java.util.List;

public record InventoryExpanded(
    InventorySummary inventory,
    List<InventoryLot> lots,
    Long totalLotCount,
    Long availableQuantity) {

  public record InventoryLot(
      String lotId,
      String custodianAccount,
      Long settledBalance,
      Long onLoan,
      Long availableToLend,
      String availability) {}
}
