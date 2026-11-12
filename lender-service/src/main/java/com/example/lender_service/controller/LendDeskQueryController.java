package com.example.lender_service.controller;

import com.example.lender_service.response.ApiResponse;
import com.example.lender_service.service.ReadService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only API contract for the LendDesk lender operations frontend. */
@RestController
@RequestMapping("/api/v1/organizations/{org}/lenddesk")
@RequiredArgsConstructor
public class LendDeskQueryController {
  private final ReadService read;
  private final com.example.lender_service.service.AgreementScreenService agreementScreen;
  private final com.example.lender_service.service.LocateScreenService locateScreen;

  @GetMapping("/dashboard")
  public ApiResponse<?> dashboard(@PathVariable long org) { return ApiResponse.ok(read.dashboard(org)); }

  @GetMapping("/{resource:users|borrowers|inventory|inventory-history|agreements|locates|reservations|loans|collateral|collateral-history|returns|lifecycle-requests|valuations|margin-calls|recalls|fees|rebates|accruals|defaults|buy-ins|rebate-payments|ledger|notifications|audit}")
  public ApiResponse<?> list(@PathVariable long org, @PathVariable String resource, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "200") int size) {
    return ApiResponse.ok(read.list(org, resource, page, size));
  }

  @GetMapping("/locates/overview")
  public ApiResponse<?> locateOverview(@PathVariable long org,
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size,
      @RequestParam(required = false) Long connectionId, @RequestParam(required = false) Long mslaId,
      @RequestParam(required = false) Long securityId, @RequestParam(required = false) String status,
      @RequestParam(required = false) String expiry, @RequestParam(required = false) String q) {
    return ApiResponse.ok(locateScreen.overview(org, page, size, connectionId, mslaId, securityId, status, expiry, q));
  }

  @GetMapping("/locates/{id}/screen")
  public ApiResponse<?> locateScreen(@PathVariable long org, @PathVariable long id) {
    return ApiResponse.ok(locateScreen.detail(org, id));
  }

  @GetMapping("/agreements")
  public ApiResponse<?> agreementSummaries(
      @PathVariable long org,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "200") int size) {
    return ApiResponse.ok(read.agreementSummaries(org, page, size));
  }

  @GetMapping("/agreements/overview")
  public ApiResponse<?> agreementOverview(@PathVariable long org,
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size,
      @RequestParam(required = false) Long connectionId, @RequestParam(required = false) String status,
      @RequestParam(required = false) Boolean recallAllowed,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) java.time.LocalDate activeOn,
      @RequestParam(required = false) String q) {
    return ApiResponse.ok(agreementScreen.overview(org, page, size, connectionId, status, recallAllowed, activeOn, q));
  }

  @GetMapping("/agreements/{id}/screen")
  public ApiResponse<?> agreementScreen(@PathVariable long org, @PathVariable long id) {
    return ApiResponse.ok(agreementScreen.detail(org, id));
  }

  @GetMapping("/agreements/{id}")
  public ApiResponse<?> agreement(@PathVariable long org, @PathVariable long id) {
    return ApiResponse.ok(read.agreement(org, id));
  }

  @GetMapping("/agreements/{id}/summary")
  public ApiResponse<?> agreementSummary(@PathVariable long org, @PathVariable long id) {
    return ApiResponse.ok(read.agreementSummary(org, id));
  }

  @GetMapping("/agreements/{id}/expanded")
  public ApiResponse<?> agreementExpanded(@PathVariable long org, @PathVariable long id) {
    return ApiResponse.ok(read.agreementExpanded(org, id));
  }

  @GetMapping("/inventory")
  public ApiResponse<?> inventorySummaries(
      @PathVariable long org,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "200") int size) {
    return ApiResponse.ok(read.inventorySummaries(org, page, size));
  }

  @GetMapping("/inventory/{id}")
  public ApiResponse<?> inventoryDetail(@PathVariable long org, @PathVariable long id) {
    return ApiResponse.ok(read.inventoryDetail(org, id));
  }

  @GetMapping("/loans/{id}")
  public ApiResponse<?> loan(@PathVariable long org, @PathVariable long id) { return ApiResponse.ok(read.loan(org, id)); }

  @GetMapping("/loans/{loanId}/{resource:rebate-accruals|rebate-settlements|rebate-payments}")
  public ApiResponse<?> loanRebates(@PathVariable long org, @PathVariable long loanId, @PathVariable String resource, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "200") int size) {
    return ApiResponse.ok(read.loanRebates(org, loanId, resource, page, size));
  }

  @GetMapping("/account")
  public ApiResponse<?> account(@PathVariable long org) { return ApiResponse.ok(read.account(org)); }
}
