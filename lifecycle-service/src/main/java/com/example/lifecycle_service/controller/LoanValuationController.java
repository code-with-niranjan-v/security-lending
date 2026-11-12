package com.example.lifecycle_service.controller;

import com.example.lifecycle_service.service.LoanValuationService;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/lifecycle")
@RequiredArgsConstructor
public class LoanValuationController {
  private final LoanValuationService loanValuationService;

  @PostMapping("/loans/{loanId}/valuation/run")
  public Map<String, Object> runValuation(@PathVariable long loanId) {
    loanValuationService.requestValuationRun(loanId);
    return Map.of(
        "success",
        true,
        "data",
        Map.of("loanId", loanId, "status", "VALUATION_REQUEST_ACCEPTED"),
        "timestamp",
        Instant.now());
  }

  @GetMapping("/loans/{loanId}/valuation")
  public Map<String, Object> readValuation(@PathVariable long loanId) {
    return Map.of("success", true, "data", loanValuationService.latestForLoan(loanId), "timestamp", Instant.now());
  }
}
