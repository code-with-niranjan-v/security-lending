package com.example.lender_service.dto;

import jakarta.validation.constraints.*;

public record ReturnCommand(
    @Positive long loanId,
    @Positive long quantity,
    @PositiveOrZero long recallId,
    @PositiveOrZero long marginCallId) {
  public ReturnCommand(long loanId, long quantity, long recallId) {
    this(loanId, quantity, recallId, 0L);
  }
}
