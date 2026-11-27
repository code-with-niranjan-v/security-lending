package com.example.borrower_service.controller;

import com.example.borrower_service.dto.*;
import com.example.borrower_service.repository.LenderReferenceRepository;
import com.example.borrower_service.response.ApiResponse;
import com.example.borrower_service.service.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/organizations/{org}")
@RequiredArgsConstructor
public class LendingController {
    private final LoanCollateralPreviewService loanCollateralPreview;
    private final LendingService lending;private final BorrowerReadService read;private final PortfolioService portfolio;private final LenderReferenceRepository lenders;
    @GetMapping("/lenders") public ApiResponse<?> lenders(@PathVariable long org) {return ApiResponse.ok(portfolio.lenders(org));}
    @GetMapping("/agreements") public ApiResponse<?> agreements(@PathVariable long org,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size) {return ApiResponse.ok(read.list(org,"msla",page,size));}
    @PostMapping("/agreements/{id}/accept") public ApiResponse<?> acceptAgreement(@PathVariable long org,@PathVariable long id,@Valid @RequestBody(required=false) AgreementDecisionRequest r) {return ApiResponse.ok(lending.decideAgreement(org,id,true,r));}
    @PostMapping("/agreements/{id}/reject") public ApiResponse<?> rejectAgreement(@PathVariable long org,@PathVariable long id,@Valid @RequestBody AgreementDecisionRequest r) {return ApiResponse.ok(lending.decideAgreement(org,id,false,r));}
    @GetMapping("/agreements/{id}") public ApiResponse<?> agreement(@PathVariable long org,@PathVariable long id) {return ApiResponse.ok(portfolio.agreement(org,id));}
    @GetMapping("/locates") public ApiResponse<?> locates(@PathVariable long org,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size) {return ApiResponse.ok(read.list(org,"locate_request",page,size));}
    @PostMapping("/locates") public ApiResponse<?> locate(@PathVariable long org,@Valid @RequestBody LocateRequest r) {return ApiResponse.ok(lending.locate(org,r));}
    @GetMapping("/reservations") public ApiResponse<?> reservations(@PathVariable long org,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size) {return ApiResponse.ok(read.list(org,"reservation",page,size));}
    @PostMapping("/locates/{id}/reserve") public ApiResponse<?> reserve(@PathVariable long org,@PathVariable long id,@Valid @RequestBody ReservationRequest r) {return ApiResponse.ok(lending.reserve(org,id,r));}
    @PostMapping("/reservations/{id}/release") public ApiResponse<?> release(@PathVariable long org,@PathVariable long id) {return ApiResponse.ok(lending.releaseReservation(org,id));}
    @GetMapping("/loans") public ApiResponse<?> loans(@PathVariable long org,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size) {return ApiResponse.ok(read.list(org,"loan",page,size));}
    @PostMapping("/loans/collateral-preview") public ApiResponse<?> loanCollateralPreview(@PathVariable long org,@Valid @RequestBody LoanCollateralPreviewRequest r) {return ApiResponse.ok(loanCollateralPreview.preview(org,r));}
    @PostMapping("/loans") public ApiResponse<?> create(@PathVariable long org,@Valid @RequestBody LoanRequest r) {return ApiResponse.ok(lending.createLoan(org,r));}
    @GetMapping("/loans/{id}") public ApiResponse<?> detail(@PathVariable long org,@PathVariable long id) {return ApiResponse.ok(portfolio.loan(org,id));}
    @PostMapping("/margin-calls/{id}/reduce-exposure") public ApiResponse<?> reduceExposure(@PathVariable long org,@PathVariable long id) {return ApiResponse.ok(lending.reduceExposure(org,id));}
    @PostMapping("/loans/{id}/returns") public ApiResponse<?> requestReturn(@PathVariable long org,@PathVariable long id,@Valid @RequestBody ReturnRequest r) {return ApiResponse.ok(lending.requestReturn(org,id,r));}
    @PostMapping("/recalls/{id}/returns") public ApiResponse<?> respondToRecall(@PathVariable long org,@PathVariable long id,@Valid @RequestBody ReturnRequest r) {return ApiResponse.ok(lending.respondToRecall(org,id,r));}
    @GetMapping("/returns") public ApiResponse<?> returns(@PathVariable long org,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size) {return ApiResponse.ok(read.list(org,"loan_return",page,size));}
    @GetMapping("/credit-history") public ApiResponse<?> credit(@PathVariable long org,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size) {return ApiResponse.ok(read.list(org,"credit_history",page,size));}
}
