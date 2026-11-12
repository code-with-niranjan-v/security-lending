package com.example.lender_service.controller;

import com.example.lender_service.dto.*;
import com.example.lender_service.response.ApiResponse;
import com.example.lender_service.service.LifecycleRequestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** Delegates owner actions; this service neither schedules nor executes these workflows. */
@RestController
@RequestMapping("/api/v1/organizations/{org}")
@RequiredArgsConstructor
public class LifecycleRequestController {
  private final LifecycleRequestService requests;

  @PostMapping("/loans/{id}/recall-requests")
  public ApiResponse<?> recall(
      @PathVariable long org, @PathVariable long id, @Valid @RequestBody RecallRequest r) {
    return ApiResponse.ok(requests.recall(org, id, r));
  }

  @PostMapping("/loans/{id}/margin-call-requests")
  public ApiResponse<?> margin(@PathVariable long org,@PathVariable long id,@Valid @RequestBody MarginIssueRequest r){
    return ApiResponse.ok(requests.margin(org,id,r));
  }

  @PostMapping("/rebates/{id}/payment-requests")
  public ApiResponse<?> rebate(
      @PathVariable long org, @PathVariable long id, @Valid @RequestBody LifecycleActionRequest r) {
    return ApiResponse.ok(requests.rebatePayment(org, id, r));
  }

  @PostMapping("/defaults/{id}/buy-in-requests")
  public ApiResponse<?> buyIn(
      @PathVariable long org, @PathVariable long id, @Valid @RequestBody LifecycleActionRequest r) {
    return ApiResponse.ok(requests.buyIn(org, id, r));
  }
}
