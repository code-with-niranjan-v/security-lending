package com.example.lender_service.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;

public record MarginIssueRequest(
    @NotNull @DecimalMin("0.01") @Digits(integer = 18, fraction = 6) BigDecimal amount,
    @NotNull @Future Instant dueAt,
    @NotBlank @Size(max = 500) String reason,
    @NotBlank @Size(max = 120) String clientRequestId) {}
