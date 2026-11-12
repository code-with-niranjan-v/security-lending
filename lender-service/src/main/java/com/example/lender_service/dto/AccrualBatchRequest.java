package com.example.lender_service.dto;

import jakarta.validation.constraints.*;
import java.util.List;

public record AccrualBatchRequest(
    @NotEmpty @Size(max = 100) List<@NotNull @Positive Long> loanIds,
    @NotBlank @Size(max = 80) String clientRequestId) {}
