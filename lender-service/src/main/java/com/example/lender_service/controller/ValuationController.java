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
public class ValuationController {
  private final ValuationScreenService screens;
  private final LifecycleRequestService requests;

  @GetMapping("/lenddesk/valuations/overview")
  public ApiResponse<?> overview(
      @PathVariable long org,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size,
      @RequestParam(required = false) Long connectionId,
      @RequestParam(required = false) Long mslaId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String coverage,
      @RequestParam(required = false) String q) {
    return ApiResponse.ok(
        screens.overview(org, page, size, connectionId, mslaId, status, coverage, q));
  }

  @GetMapping("/lenddesk/valuations/loans/{id}/screen")
  public ApiResponse<?> detail(
      @PathVariable long org,
      @PathVariable long id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return ApiResponse.ok(screens.detail(org, id, page, size));
  }

  @PostMapping("/loans/{id}/valuation-runs")
  public ApiResponse<?> run(
      @PathVariable long org, @PathVariable long id, @Valid @RequestBody LifecycleActionRequest r) {
    return ApiResponse.ok(screens.run(requests.valuation(org, id, r)));
  }

  @PostMapping("/valuation-runs")
  public ApiResponse<?> batch(@PathVariable long org, @Valid @RequestBody ValuationBatchRequest r) {
    return ApiResponse.ok(requests.valuationBatch(org, r).stream().map(screens::run).toList());
  }

  @GetMapping("/valuation-runs/{id}")
  public ApiResponse<?> status(@PathVariable long org, @PathVariable long id) {
    return ApiResponse.ok(screens.run(org, id));
  }
}
