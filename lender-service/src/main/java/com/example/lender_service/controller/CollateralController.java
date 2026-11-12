package com.example.lender_service.controller;

import com.example.lender_service.dto.*;
import com.example.lender_service.response.ApiResponse;
import com.example.lender_service.service.CollateralService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/organizations/{org}/collateral")
@RequiredArgsConstructor
public class CollateralController {
  private final CollateralService collateral;
  private final com.example.lender_service.service.CollateralScreenService screens;

  @GetMapping("/overview")
  public ApiResponse<?> overview(
      @PathVariable long org,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size,
      @RequestParam(required = false) Long connectionId,
      @RequestParam(required = false) Long loanId,
      @RequestParam(required = false) String assetType,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String q) {
    return ApiResponse.ok(
        screens.overview(org, page, size, connectionId, loanId, assetType, status, q));
  }

  @GetMapping("/{id}/screen")
  public ApiResponse<?> screen(@PathVariable long org, @PathVariable long id) {
    return ApiResponse.ok(screens.detail(org, id));
  }

  @PostMapping("/{id}/confirm")
  public ApiResponse<?> confirm(
      @PathVariable long org, @PathVariable long id, @Valid @RequestBody SettlementRequest r) {
    return ApiResponse.ok(collateral.confirm(org, id, r.reference()));
  }

  @PostMapping("/{id}/reject")
  public ApiResponse<?> reject(
      @PathVariable long org, @PathVariable long id, @Valid @RequestBody RejectionRequest r) {
    return ApiResponse.ok(collateral.reject(org, id, r.reason()));
  }

  @PostMapping("/{id}/release-confirmations")
  public ApiResponse<?> release(
      @PathVariable long org, @PathVariable long id, @Valid @RequestBody SettlementRequest r) {
    return ApiResponse.ok(collateral.release(org, id, r.reference(), true));
  }

  @PostMapping("/{id}/release-rejections")
  public ApiResponse<?> refuse(
      @PathVariable long org, @PathVariable long id, @Valid @RequestBody RejectionRequest r) {
    return ApiResponse.ok(collateral.release(org, id, r.reason(), false));
  }
}
