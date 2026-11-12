package com.example.lender_service.controller;

import com.example.lender_service.dto.*;
import com.example.lender_service.response.ApiResponse;
import com.example.lender_service.service.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/organizations/{org}")
@RequiredArgsConstructor
public class LoanController {
  private final LoanService loans;
  private final ReadService read;
  private final CollateralService collateral;

  @GetMapping("/loans/{id}")
  public ApiResponse<?> detail(@PathVariable long org, @PathVariable long id) {
    return ApiResponse.ok(read.loan(org, id));
  }

  @GetMapping("/loans/{id}/collateral-preview")
  public ApiResponse<?> preview(@PathVariable long org, @PathVariable long id) {
    return ApiResponse.ok(collateral.preview(org, id, true, null));
  }

  @GetMapping("/loans/{id}/margin-preview")
  public ApiResponse<?> marginPreview(@PathVariable long org,@PathVariable long id){
    return ApiResponse.ok(collateral.preview(org,id,false,null));
  }

  @PostMapping("/loans/{id}/approve")
  public ApiResponse<?> approve(@PathVariable long org, @PathVariable long id) {
    return ApiResponse.ok(loans.decide(org, id, true, "Approved"));
  }

  @PostMapping("/loans/{id}/reject")
  public ApiResponse<?> reject(
      @PathVariable long org, @PathVariable long id, @Valid @RequestBody RejectionRequest r) {
    return ApiResponse.ok(loans.decide(org, id, false, r.reason()));
  }

  @PostMapping("/loans/{id}/settle")
  public ApiResponse<?> settle(
      @PathVariable long org, @PathVariable long id, @Valid @RequestBody SettlementRequest r) {
    return ApiResponse.ok(loans.settle(org, id, r.reference()));
  }

  @PostMapping("/returns/{id}/settle")
  public ApiResponse<?> returned(
      @PathVariable long org, @PathVariable long id, @Valid @RequestBody SettlementRequest r) {
    return ApiResponse.ok(loans.returnDecision(org, id, true, r.reference()));
  }

  @PostMapping("/returns/{id}/reject")
  public ApiResponse<?> rejectReturn(
      @PathVariable long org, @PathVariable long id, @Valid @RequestBody RejectionRequest r) {
    return ApiResponse.ok(loans.returnDecision(org, id, false, r.reason()));
  }
}
