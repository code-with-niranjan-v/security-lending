package com.example.lender_service.controller;

import com.example.lender_service.dto.*;
import com.example.lender_service.response.ApiResponse;
import com.example.lender_service.service.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/organizations/{org}")
public class FeeRebateScreenController {
  private final FeeRebateScreenService screens;
  private final LifecycleRequestService requests;

  @GetMapping("/lenddesk/fees-rebates/overview")
  public ApiResponse<?> overview(
      @PathVariable long org,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size,
      @RequestParam(defaultValue = "ALL") String tab,
      @RequestParam(required = false) Long loanId,
      @RequestParam(required = false) String q) {
    return ApiResponse.ok(screens.overview(org, page, size, tab, loanId, q));
  }

  @PostMapping("/{kind:fees|rebates}/accrual-runs")
  public ApiResponse<?> batch(
      @PathVariable long org,
      @PathVariable String kind,
      @Valid @RequestBody AccrualBatchRequest request) {
    return ApiResponse.ok(
        requests.accrualBatch(org, kind.equals("fees") ? "FEE" : "REBATE", request).stream()
            .map(screens::run)
            .toList());
  }

  @PostMapping("/loans/{id}/{kind:fees|rebates}/accrual-runs")
  public ApiResponse<?> single(
      @PathVariable long org,
      @PathVariable long id,
      @PathVariable String kind,
      @Valid @RequestBody LifecycleActionRequest r) {
    return ApiResponse.ok(
        screens.run(requests.accrual(org, id, kind.equals("fees") ? "FEE" : "REBATE", r)));
  }

  @GetMapping("/accrual-runs/{id}")
  public ApiResponse<?> status(@PathVariable long org, @PathVariable long id) {
    return ApiResponse.ok(screens.run(org, id));
  }
}
