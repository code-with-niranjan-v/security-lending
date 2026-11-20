package com.example.lender_service;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.lender_service.dto.*;
import com.example.lender_service.model.*;
import com.example.lender_service.repository.*;
import com.example.lender_service.service.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class LenderWorkflowTests {
  @Autowired DirectoryService directory;
  @Autowired InventoryService inventory;
  @Autowired MarketService market;
  @Autowired BorrowerCommandService commands;
  @Autowired AgreementService agreementService;
  @Autowired ReservationService reservationService;
  @Autowired LoanService loanService;
  @Autowired CollateralService collateral;
  @Autowired LifecycleRequestService lifecycleRequests;
  @Autowired LifecycleEventService lifecycle;
  @Autowired ReadService read;
  @Autowired LoanScreenService loanScreen;
  @Autowired ReturnCancellationService returnCancellations;
  @Autowired ValuationScreenService valuationScreens;
  @Autowired jakarta.persistence.EntityManager valuationEm;
  @Autowired FeeRebateScreenService feeScreens;
  @Autowired CollateralScreenService collateralScreens;
  @Autowired OutboxService outbox;
  @Autowired JsonService json;
  @Autowired OrganizationRepository organizations;
  @Autowired UserRepository users;
  @Autowired MslaRepository agreements;
  @Autowired LocateRequestRepository locates;
  @Autowired ReservationRepository reservations;
  @Autowired LoanRepository loans;
  @Autowired LoanReturnRepository returns;
  @Autowired CollateralPositionRepository positions;
  @Autowired InventoryPositionRepository stock;
  @Autowired OutboxEventRepository events;
  @Autowired InboxEventRepository inbox;
  @Autowired RebateRepository rebates;
  @Autowired LenderAccountRepository accounts;
  @Autowired MarginCallRepository calls;
  @Autowired MarginCallService marginCallService;
  @Autowired RecallRepository recalls;
  @Autowired LoanDefaultRepository defaults;
  @Autowired BuyinOrderService buyinOrders;
  @Autowired CollateralLiquidationService liquidation;
  @Autowired BuyinOrderRepository buyinOrderRows;
  @Autowired BuyinInventoryService buyinReceipts;
  @Autowired BuyinLoanCloseoutService buyinCloseouts;
  @Autowired BuyinLoanCloseoutRepository buyinCloseoutRows;
  @Autowired BuyInRepository buyinReports;
  @Autowired InventoryHistoryRepository inventoryHistory;
  @Autowired LenderAccountLedgerRepository purchaseLedger;
  @Autowired MockMvc mvc;
  @Autowired LocateScreenService locateScreenForTests;
  @Autowired SecurityMasterRepository securityMasterForScreenTest;
  @Autowired LendingFeeRepository feesForScreenTest;
  static final AtomicLong ids = new AtomicLong(10000);

  static BigDecimal bd(String s) {
    return new BigDecimal(s);
  }

  Organization organization() {
    long id = ids.incrementAndGet();
    return directory.create(new OrganizationRequest("Demo fund " + id, "LENDER_" + id, id));
  }

  BorrowerConnection connection(Organization o) {
    return directory.connect(
        o.getId(), new BorrowerConnectionRequest("BORROWER_DEMO", 1, "Borrower firm"));
  }

  long security(String price) {
    long id = ids.incrementAndGet();
    market.register(new SecurityRequest(id, "EQ" + id, "NSE", "Demo equity", "INE" + id, "INR"));
    market.quote(id, bd(price), Instant.now().minusSeconds(120));
    return id;
  }

  String body(Organization o, String type, long reference, long msla, Object data) {
    return json.write(
        new BorrowerCommand(
            UUID.randomUUID().toString(),
            type,
            1,
            reference,
            Instant.now(),
            "COUNTERPARTY",
            msla,
            o.getExternalId(),
            json.tree(json.write(data))));
  }

  void command(Organization o, String type, long reference, long msla, Object data) {
    commands.receive(body(o, type, reference, msla, data), "BORROWER_DEMO");
  }

  Msla agreement(Organization o, BorrowerConnection c, long pledgeSecurity, boolean recallAllowed) {
    var rules = new ArrayList<RuleRequest>();
    rules.add(new RuleRequest("CASH", null, BigDecimal.ZERO));
    if (pledgeSecurity > 0) rules.add(new RuleRequest("SECURITY", pledgeSecurity, bd("0.10")));
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
    var proposal =
        new AgreementProposal(
            o.getPublicReferenceId(),
            "MSLA-" + UUID.randomUUID(),
            today.minusDays(1),
            today.plusYears(1),
            bd("1000000"),
            bd("0.03"),
            bd("0.01"),
            bd("1.05"),
            "MONTHLY",
            365,
            recallAllowed,
            24,
            48,
            rules);
    command(o, "AGREEMENT_PROPOSED", 10, 10, proposal);
    var a = agreements.findByConnectionIdAndBorrowerMslaId(c.getId(), 10L).orElseThrow();
    agreementService.decide(o.getId(), a.getId(), true, "Approved");
    return a;
  }

  Reservation reservation(Organization o, BorrowerConnection c, long security, long quantity) {
    command(
        o,
        "LOCATE_REQUESTED",
        100,
        10,
        new LocateCommand(10, security, quantity, Instant.now().plusSeconds(3600)));
    var locate = locates.findByConnectionIdAndBorrowerLocateId(c.getId(), 100L).orElseThrow();
    reservationService.decideLocate(o.getId(), locate.getId(), quantity, true, "Available");
    command(o, "RESERVATION_REQUESTED", 200, 10, new ReservationCommand(100, quantity));
    var r = reservations.findByConnectionIdAndBorrowerReservationId(c.getId(), 200L).orElseThrow();
    reservationService.decide(o.getId(), r.getId(), quantity, true, "Reserved");
    return r;
  }

  Loan pendingLoan(Organization o, BorrowerConnection c, long quantity) {
    var allocation = new AllocationRequest("CASH", null, 0, bd("11000"), null);
    command(o, "COLLATERAL_PLEDGE_REQUESTED", 300, 10, new PledgeCommand(400, allocation));
    command(o, "LOAN_REQUESTED", 300, 10, new LoanCommand(200, quantity, List.of(allocation)));
    return loans.findByConnectionIdAndBorrowerLoanId(c.getId(), 300L).orElseThrow();
  }

  @Test
  void loanMaturitySurvivesBorrowerCommandAndAppearsInScreenAndRawApis() throws Exception {
    var org = organization();
    var c = connection(org);
    long sec = security("100");
    inventory.create(org.getId(), new InventoryRequest(sec, 1000, "Initial custody"));
    agreement(org, c, 0, true);
    reservation(org, c, sec, 100);
    var maturity = Instant.now().plusSeconds(86400).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    command(org, "LOAN_REQUESTED", 300, 10,
        new LoanCommand(200, 100, List.of(new AllocationRequest("CASH", null, 0, bd("11000"), null)), maturity));
    var loan = loans.findByConnectionIdAndBorrowerLoanId(c.getId(), 300L).orElseThrow();
    assertEquals(maturity, loan.getMaturityAt());
    var row = (Map<?, ?>) loanScreen.detail(org.getId(), loan.getId()).get("loan");
    assertEquals(maturity, row.get("maturityAt"));
    assertEquals(maturity, row.get("maturity"));
    mvc.perform(get("/api/v1/organizations/" + org.getId() + "/loans"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].maturityAt").value(maturity.toString()));
  }

  Loan activeLoan(Organization o, BorrowerConnection c, long security, boolean recallAllowed) {
    inventory.create(o.getId(), new InventoryRequest(security, 1000, "Initial custody"));
    agreement(o, c, 0, recallAllowed);
    reservation(o, c, security, 100);
    var l = pendingLoan(o, c, 100);
    var p = positions.findByConnectionIdAndBorrowerPositionId(c.getId(), 400L).orElseThrow();
    collateral.confirm(o.getId(), p.getId(), "COLLATERAL-RECEIPT");
    loanService.decide(o.getId(), l.getId(), true, "Approved");
    loanService.settle(o.getId(), l.getId(), "LOAN-SETTLEMENT");
    return l;
  }

  void lifecycle(
      Organization o, String type, long reference, long version, Map<String, Object> data) {
    lifecycle.receive(
        json.write(
            new LifecycleEvent(
                UUID.randomUUID().toString(),
                type,
                o.getId(),
                reference,
                version,
                Instant.now(),
                json.tree(json.write(data)))));
  }

  @Test
  void liveFeesAndRebatesMapRealTypesBorrowersTotalsAndSettlementRecords() throws Exception {
    var o = organization();
    var c = connection(o);
    long sec = security("100");
    var l = activeLoan(o, c, sec, true);
    String day = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString();
    lifecycle(
        o,
        "ACCRUAL_REPORTED",
        991001L,
        1,
        Map.of(
            "loanId",
            l.getId(),
            "type",
            "FEE",
            "date",
            day,
            "baseValue",
            "10000",
            "rate",
            "0.03",
            "amount",
            "0.821918"));
    lifecycle(
        o,
        "ACCRUAL_REPORTED",
        991002L,
        1,
        Map.of(
            "loanId",
            l.getId(),
            "type",
            "REBATE",
            "date",
            day,
            "baseValue",
            "11000",
            "rate",
            "0.01",
            "amount",
            "0.301370"));
    String path = "/api/v1/organizations/" + o.getId() + "/lenddesk/fees-rebates/overview";
    mvc.perform(get(path).param("tab", "FEE"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.records.totalElements").value(1))
        .andExpect(jsonPath("$.data.records.content[0].type").value("FEE"))
        .andExpect(jsonPath("$.data.records.content[0].borrower").value(c.getName()))
        .andExpect(jsonPath("$.data.records.content[0].rateBps").value(300))
        .andExpect(jsonPath("$.data.records.content[0].accruedTotal").value(0.821918))
        .andExpect(jsonPath("$.data.counts.REBATE").value(1));
    mvc.perform(get(path).param("tab", "REBATE"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.records.content[0].type").value("REBATE"))
        .andExpect(jsonPath("$.data.records.content[0].settlementRule").value("AT_CLOSE"))
        .andExpect(jsonPath("$.data.totals[0].rebateToday").value(0.301370));
    lifecycle(
        o,
        "FEE_BILL_ISSUED",
        991003L,
        1,
        Map.of(
            "loanId",
            l.getId(),
            "amount",
            "0.821918",
            "rate",
            "0.03",
            "periodStart",
            day,
            "periodEnd",
            day,
            "dueDate",
            day));
    mvc.perform(get(path).param("tab", "DUE"))
        .andExpect(jsonPath("$.data.records.totalElements").value(1))
        .andExpect(jsonPath("$.data.records.content[0].category").value("fees"))
        .andExpect(jsonPath("$.data.records.content[0].status").value("DUE"));
    // Daily rows must reflect the actual linked settlement, not their original accrual status.
    command(o, "FEE_PAYMENT_RECEIVED", 991003L, 10,
        new FeePaymentCommand(l.getBorrowerLoanId(), bd("0.821918"), 771L));
    mvc.perform(get(path).param("tab", "FEE"))
        .andExpect(jsonPath("$.data.records.content[0].status").value("PAID"))
        .andExpect(jsonPath("$.data.records.content[0].settlementStatus").value("PAID"));
    lifecycle(o, "REBATE_ACCRUED", 991004L, 1, Map.of("loanId", l.getId(), "amount", "0.301370",
        "rate", "0.01", "date", day, "paymentDate", day));
    lifecycle(o, "REBATE_PAID", 991004L, 2, Map.of("loanId", l.getId(), "amount", "0.301370"));
    mvc.perform(get(path).param("tab", "REBATE"))
        .andExpect(jsonPath("$.data.records.content[0].status").value("PAID"))
        .andExpect(jsonPath("$.data.records.content[0].settlementStatus").value("PAID"));
    mvc.perform(get(path).param("tab", "DUE"))
        .andExpect(jsonPath("$.data.records.totalElements").value(0));
    mvc.perform(get(path).param("tab", "INVALID")).andExpect(status().isBadRequest());
    var other = organization();
    mvc.perform(get("/api/v1/organizations/" + other.getId() + "/lenddesk/fees-rebates/overview"))
        .andExpect(jsonPath("$.data.records.totalElements").value(0));
  }

  @Test
  void accrualRunsAreScopedIdempotentAndExposeOwnerResultsAndFailures() throws Exception {
    var o = organization();
    var c = connection(o);
    long sec = security("100");
    var l = activeLoan(o, c, sec, true);
    var r =
        lifecycleRequests.accrual(
            o.getId(), l.getId(), "FEE", new LifecycleActionRequest("fee-run"));
    assertEquals(
        r.getId(),
        lifecycleRequests
            .accrual(o.getId(), l.getId(), "FEE", new LifecycleActionRequest("fee-run"))
            .getId());
    assertEquals(
        r.getId(),
        lifecycleRequests
            .accrual(o.getId(), l.getId(), "FEE", new LifecycleActionRequest("second-pending-key"))
            .getId());
    assertThrows(
        ResponseStatusException.class,
        () ->
            lifecycleRequests.accrual(
                o.getId(), l.getId(), "REBATE", new LifecycleActionRequest("fee-run")));
    lifecycle(
        o,
        "ACCRUAL_RUN_COMPLETED",
        r.getId(),
        1,
        Map.of(
            "loanId",
            l.getId(),
            "kind",
            "FEE",
            "result",
            Map.of(
                "loanId",
                l.getId(),
                "daysRecorded",
                1,
                "billsQueued",
                0,
                "billedAmount",
                0,
                "processedThrough",
                LocalDate.now().toString())));
    assertEquals("COMPLETED", feeScreens.run(o.getId(), r.getId()).get("status"));
    var next =
        lifecycleRequests.accrual(
            o.getId(), l.getId(), "REBATE", new LifecycleActionRequest("rebate-run"));
    lifecycle(
        o,
        "LIFECYCLE_REQUEST_REJECTED",
        next.getId(),
        1,
        Map.of("reason", "Market data unavailable"));
    assertEquals(
        "Market data unavailable", feeScreens.run(o.getId(), next.getId()).get("failureReason"));
    var other = organization();
    mvc.perform(get("/api/v1/organizations/" + other.getId() + "/accrual-runs/" + r.getId()))
        .andExpect(status().isNotFound());
    mvc.perform(
            post("/api/v1/organizations/" + o.getId() + "/fees/accrual-runs")
                .contentType("application/json")
                .content("{\"loanIds\":[],\"clientRequestId\":\"empty\"}"))
        .andExpect(status().isBadRequest());
    assertEquals(100, l.getOutstandingQuantity());
  }

  @Test
  void failedValuationStopsBeingPendingAndANewRunRetiresItsBlockedDelivery() {
    var o=organization();var c=connection(o);long sec=security("100");var loan=activeLoan(o,c,sec,true);
    long loanId=loan.getId();
    var request=lifecycleRequests.valuation(o.getId(),loanId,new LifecycleActionRequest("failed-run"));
    long requestId=request.getId();
    var event=events.findByOrganizationId(o.getId(),Pageable.unpaged()).getContent().stream()
        .filter(e->json.tree(e.getPayload()).path("type").asText().equals("VALUATION_REQUESTED"))
        .filter(e->json.tree(e.getPayload()).path("referenceId").asLong()==requestId).findFirst().orElseThrow();
    long eventId=event.getId();event.setStatus("FAILED");valuationEm.flush();
    ValuationDeliveryState.fail(valuationEm,event,"Kafka unavailable");valuationEm.clear();
    assertEquals("FAILED",valuationScreens.run(o.getId(),requestId).get("status"));
    assertEquals("Kafka unavailable",valuationScreens.run(o.getId(),requestId).get("failureReason"));
    var row=(Map<?,?>)loanScreen.detail(o.getId(),loanId).get("loan");
    assertNull(row.get("pendingValuationRunId"));assertEquals(false,row.get("valuationRunPending"));
    var next=lifecycleRequests.valuation(o.getId(),loanId,new LifecycleActionRequest("new-run"));
    assertNotEquals(requestId,next.getId());assertEquals("PENDING",next.getStatus());
    assertEquals("CLOSED",valuationScreens.run(o.getId(),requestId).get("status"));
    assertEquals("CLOSED",events.findById(eventId).orElseThrow().getStatus());
  }

  @Test
  void valuationRequestsAreTenantScopedIdempotentAndExposeFailures() throws Exception {
    var o = organization();
    var c = connection(o);
    long sec = security("100");
    var l = activeLoan(o, c, sec, true);
    var request =
        lifecycleRequests.valuation(o.getId(), l.getId(), new LifecycleActionRequest("run-one"));
    assertEquals(
        request.getId(),
        lifecycleRequests
            .valuation(o.getId(), l.getId(), new LifecycleActionRequest("run-one"))
            .getId());
    assertEquals(
        request.getId(),
        lifecycleRequests
            .valuation(o.getId(), l.getId(), new LifecycleActionRequest("run-another-key"))
            .getId());
    assertEquals("PENDING", request.getStatus());
    lifecycle(
        o,
        "LIFECYCLE_REQUEST_REJECTED",
        request.getId(),
        1,
        Map.of("reason", "Missing cached price for security 42"));
    assertEquals(
        "Missing cached price for security 42",
        valuationScreens.run(o.getId(), request.getId()).get("failureReason"));
    assertEquals(
        request.getId(),
        lifecycleRequests
            .valuation(o.getId(), l.getId(), new LifecycleActionRequest("run-one"))
            .getId());
    var next =
        lifecycleRequests.valuation(o.getId(), l.getId(), new LifecycleActionRequest("run-two"));
    lifecycle(
        o,
        "VALUATION_REPORTED",
        8800000L,
        1,
        Map.of(
            "loanId",
            l.getId(),
            "lifecycleRequestId",
            next.getId(),
            "loanMarketValue",
            "10000",
            "requiredCollateral",
            "10500",
            "recognizedCollateral",
            "11000",
            "coverageRatio",
            "1.1",
            "shortfall",
            "0",
            "status",
            "SUFFICIENT"));
    assertEquals("COMPLETED", next.getStatus());
    var other = organization();
    mvc.perform(get("/api/v1/organizations/" + other.getId() + "/valuation-runs/" + next.getId()))
        .andExpect(status().isNotFound());
    mvc.perform(
            get(
                "/api/v1/organizations/"
                    + o.getId()
                    + "/lenddesk/valuations/loans/"
                    + l.getId()
                    + "/screen"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.history.totalElements").value(1));
    mvc.perform(
            post("/api/v1/organizations/" + o.getId() + "/valuation-runs")
                .contentType("application/json")
                .content("{\"loanIds\":[],\"clientRequestId\":\"empty\"}"))
        .andExpect(status().isBadRequest());
    l.setStatus("CLOSED");
    l.setOutstandingQuantity(0L);
    assertThrows(
        ResponseStatusException.class,
        () ->
            lifecycleRequests.valuation(
                o.getId(), l.getId(), new LifecycleActionRequest("closed")));
  }

  @Test
  void collateralReadsUseActualConfirmedValuesAndRecordedMovements() throws Exception {
    var o = organization();
    var c = connection(o);
    long sec = security("100");
    var l = activeLoan(o, c, sec, true);
    var position = positions.findByLoanId(l.getId()).get(0);
    var d = collateralScreens.detail(o.getId(), position.getId());
    var row = (Map<?, ?>) d.get("position");
    assertEquals(0, bd("11000").compareTo((BigDecimal) row.get("eligibleValue")));
    assertEquals(true, row.get("includedInCoverage"));
    assertFalse(((List<?>) d.get("movements")).isEmpty());
    assertTrue(((List<?>) d.get("valuations")).isEmpty());
    var root = "/api/v1/organizations/" + o.getId() + "/collateral";
    mvc.perform(get(root + "/overview").param("status", "POSTED"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.positions.totalElements").value(1))
        .andExpect(jsonPath("$.data.totals[0].eligibleValue").value(11000));
    mvc.perform(get(root + "/overview").param("status", "INVALID"))
        .andExpect(status().isBadRequest());
    var other = organization();
    mvc.perform(
            get(
                "/api/v1/organizations/"
                    + other.getId()
                    + "/collateral/"
                    + position.getId()
                    + "/screen"))
        .andExpect(status().isNotFound());
    position.setStatus("RELEASED");
    row = (Map<?, ?>) collateralScreens.detail(o.getId(), position.getId()).get("position");
    assertEquals(false, row.get("includedInCoverage"));
  }

  @Test
  void liveLoanRegisterHasNoFabricatedValuationsAndIsTenantScoped() throws Exception {
    var org = organization();
    var c = connection(org);
    long sec = security("100");
    var loan = activeLoan(org, c, sec, true);
    var root = "/api/v1/organizations/" + org.getId() + "/lenddesk/loans";
    mvc.perform(get(root + "/overview"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.activeLoanCount").value(1))
        .andExpect(jsonPath("$.data.loans.content[0].id").value(loan.getId()))
        .andExpect(jsonPath("$.data.loans.content[0].coverageStatus").value("UNKNOWN"))
        .andExpect(jsonPath("$.data.loans.content[0].marketValue").isEmpty())
        .andExpect(jsonPath("$.data.loans.content[0].maturity").isEmpty())
        .andExpect(jsonPath("$.data.totals[0].missingValuations").value(1))
      .andExpect(jsonPath("$.data.totals[0].marketValue").isEmpty());
    mvc.perform(get(root + "/overview").param("coverage", "UNKNOWN"))
        .andExpect(jsonPath("$.data.loans.totalElements").value(1));
    mvc.perform(get(root + "/overview").param("size", "201")).andExpect(status().isBadRequest());
    mvc.perform(get(root + "/overview").param("status", "BOGUS"))
        .andExpect(status().isBadRequest());
    mvc.perform(get(root + "/overview").param("coverage", "BOGUS"))
        .andExpect(status().isBadRequest());
    var other = organization();
    mvc.perform(
            get(
                "/api/v1/organizations/"
                    + other.getId()
                    + "/lenddesk/loans/"
                    + loan.getId()
                    + "/screen"))
        .andExpect(status().isNotFound());
    mvc.perform(get(root + "/" + loan.getId() + "/screen"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.collateral.length()").value(1))
        .andExpect(jsonPath("$.data.activity.length()").value(2))
        .andExpect(jsonPath("$.data.loan.returnedQuantity").value(0));
    lifecycle(
        org,
        "VALUATION_REPORTED",
        loan.getId(),
        1,
        Map.of(
            "loanId",
            loan.getId(),
            "loanMarketValue",
            "10000",
            "requiredCollateral",
            "10500",
            "recognizedCollateral",
            "11000",
            "coverageRatio",
            "1.1",
            "shortfall",
            "0",
            "status",
            "HEALTHY"));
    mvc.perform(get(root + "/overview").param("coverage", "SUFFICIENT"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.loans.content[0].marketValue").value(10000))
        .andExpect(jsonPath("$.data.loans.content[0].coverage").value(110))
        .andExpect(jsonPath("$.data.totals[0].currency").value("INR"));
    mvc.perform(get(root + "/overview").param("coverage", "SHORTFALL"))
        .andExpect(jsonPath("$.data.loans.totalElements").value(0));
  }

  @Test
  void cancelledUndeliveredReturnCannotBeCreatedByADelayedCommand() {
    var org = organization();
    var c = connection(org);
    long sec = security("100");
    var loan = activeLoan(org, c, sec, true);
    String original = body(
      org,
      "RETURN_REQUESTED",
      501,
      10,
      new ReturnCommand(300, 10, 0)
    );
    var result = json.tree(
      json.write(returnCancellations.cancel(original, "BORROWER_DEMO"))
    );
    assertEquals("RETURN_REJECTED", result.path("type").asText());
    assertEquals(
      result,
      json.tree(
        json.write(returnCancellations.cancel(original, "BORROWER_DEMO"))
      )
    );
    commands.receive(original, "BORROWER_DEMO");
    assertTrue(
      returns.findByConnectionIdAndBorrowerReturnId(c.getId(), 501L).isEmpty()
    );
    assertEquals(100, loan.getOutstandingQuantity());
    command(org, "RETURN_REQUESTED", 502, 10, new ReturnCommand(300, 10, 0));
    assertEquals(
      "PENDING",
      returns
        .findByConnectionIdAndBorrowerReturnId(c.getId(), 502L)
        .orElseThrow()
        .getStatus()
    );
  }

  @Test
  void returnCancellationRejectsADifferentCounterparty() {
    var org=organization();var c=connection(org);long sec=security("100");activeLoan(org,c,sec,true);
    String original=body(org,"RETURN_REQUESTED",501,10,new ReturnCommand(300,10,0));
    assertThrows(ResponseStatusException.class,()->returnCancellations.cancel(original,"ANOTHER_BORROWER"));
    assertTrue(returns.findByConnectionIdAndBorrowerReturnId(c.getId(),501L).isEmpty());
  }

  @Test
  void cancellationSerializesWithPendingAndAlreadySettledReturns() {
    for (boolean settleFirst : List.of(false, true)) {
      var org = organization();
      var c = connection(org);
      long sec = security("100");
      var loan = activeLoan(org, c, sec, true);
      String original = body(
        org,
        "RETURN_REQUESTED",
        501,
        10,
        new ReturnCommand(300, 10, 0)
      );
      commands.receive(original, "BORROWER_DEMO");
      var returned = returns
        .findByConnectionIdAndBorrowerReturnId(c.getId(), 501L)
        .orElseThrow();
      if (settleFirst) loanService.returnDecision(
        org.getId(),
        returned.getId(),
        true,
        "received"
      );
      var confirmation = json.tree(
        json.write(returnCancellations.cancel(original, "BORROWER_DEMO"))
      );
      assertEquals(
        settleFirst ? "RETURN_SETTLED" : "RETURN_REJECTED",
        confirmation.path("type").asText()
      );
      assertEquals(settleFirst ? "SETTLED" : "CANCELLED", returned.getStatus());
      assertEquals(settleFirst ? 90 : 100, loan.getOutstandingQuantity());
      if (!settleFirst) assertThrows(ResponseStatusException.class, () ->
        loanService.returnDecision(
          org.getId(),
          returned.getId(),
          true,
          "late-receipt"
        )
      );
    }
  }

  @Test
  void marginRequestsAreValidatedIdempotentAndCompletedOnlyByOwner() throws Exception {
    var org = organization();
    var c = connection(org);
    long sec = security("100");
    var loan = activeLoan(org, c, sec, true);
    var due = Instant.now().plusSeconds(86400);
    var request = new MarginIssueRequest(bd("1600"), due, "Market move", "live-margin-test");
    assertThrows(
        ResponseStatusException.class,
        () -> lifecycleRequests.margin(org.getId(), loan.getId(), request));
    market.quote(sec, bd("120"), Instant.now()); // Required 12,600, confirmed cash 11,000.
    var pending = lifecycleRequests.margin(org.getId(), loan.getId(), request);
    assertEquals("PENDING", pending.getStatus());
    assertEquals(0, calls.findByOrganizationId(org.getId(), Pageable.unpaged()).getTotalElements());
    assertEquals(
        pending.getId(), lifecycleRequests.margin(org.getId(), loan.getId(), request).getId());
    assertThrows(
        ResponseStatusException.class,
        () ->
            lifecycleRequests.margin(
                org.getId(),
                loan.getId(),
                new MarginIssueRequest(bd("1700"), due, "Changed", request.clientRequestId())));
    assertThrows(
        ResponseStatusException.class,
        () ->
            lifecycleRequests.margin(
                org.getId(),
                loan.getId(),
                new MarginIssueRequest(bd("1600"), due, "Duplicate", "another-id")));
    lifecycle(
        org,
        "MARGIN_CALL_UPDATED",
        loan.getId(),
        1,
        Map.of(
            "loanId",
            loan.getId(),
            "lifecycleRequestId",
            pending.getId(),
            "externalId",
            "MARGIN-" + pending.getId(),
            "amount",
            "1600",
            "fulfilledAmount",
            "0",
            "dueAt",
            due.toString(),
            "reason",
            "Market move",
            "status",
            "OPEN"));
    assertEquals("COMPLETED", pending.getStatus());
    assertEquals(1, calls.findByOrganizationId(org.getId(), Pageable.unpaged()).getTotalElements());
    mvc.perform(
            post("/api/v1/organizations/"
                    + org.getId()
                    + "/loans/"
                    + loan.getId()
                    + "/margin-call-requests")
                .contentType("application/json")
                .content(
                    json.write(
                        new MarginIssueRequest(
                            bd("1"), Instant.now().minusSeconds(1), "Invalid", "bad"))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void marginCancellationWaitsForBorrowerRejectsSettlementsAndAllowsNewCall() throws Exception {
    var org = organization();
    var conn = connection(org);
    var loan = activeLoan(org, conn, security("100"), true);
    lifecycle(org, "MARGIN_CALL_UPDATED", loan.getId(), 1, Map.of(
        "loanId", loan.getId(), "externalId", "AUTO-MARGIN-1", "amount", "300",
        "fulfilledAmount", "0", "status", "OPEN", "reason", "Shortfall",
        "dueAt", Instant.now().plusSeconds(86400).toString()));
    var call = calls.findByOrganizationIdAndOwnerReferenceId(org.getId(), loan.getId()).orElseThrow();
    var service = marginCallService;
    service.cancel(org.getId(), call.getId(), new SettlementRequest("cancel-1"));
    assertEquals("CANCELLATION_PENDING", call.getStatus());
    assertThrows(ResponseStatusException.class, () -> lifecycleRequests.margin(org.getId(), loan.getId(),
        new MarginIssueRequest(bd("300"), Instant.now().plusSeconds(86400), "Again", "blocked")));
    command(org, "MARGIN_CALL_CANCELLATION_RESPONDED", loan.getBorrowerLoanId(), 10,
        Map.of("marginCallId", loan.getId(), "cancellationReference", "cancel-1", "accepted", false, "reason", "Borrower pledge is in transit"));
    assertEquals("OPEN", call.getStatus());
    assertEquals("Borrower pledge is in transit", call.getCancellationFailureReason());
    service.cancel(org.getId(), call.getId(), new SettlementRequest("cancel-2"));
    command(org, "MARGIN_CALL_CANCELLATION_RESPONDED", loan.getBorrowerLoanId(), 10,
        Map.of("marginCallId", loan.getId(), "cancellationReference", "cancel-2", "accepted", true));
    assertEquals("CANCELLED", call.getStatus());
    // A delayed old owner snapshot must not reopen the cancelled call.
    lifecycle(org, "MARGIN_CALL_UPDATED", loan.getId(), 2, Map.of(
        "loanId", loan.getId(), "externalId", "AUTO-MARGIN-1", "amount", "300",
        "fulfilledAmount", "0", "status", "OPEN", "reason", "Shortfall",
        "dueAt", Instant.now().plusSeconds(86400).toString()));
    assertEquals("CANCELLED", call.getStatus());
    lifecycleRequests.setMarginGuardrail(org.getId(), false);
    var nextDue = Instant.now().plusSeconds(86400);
    var next = lifecycleRequests.margin(org.getId(), loan.getId(),
        new MarginIssueRequest(bd("300"), nextDue, "Again", "new-call"));
    assertEquals("PENDING", next.getStatus());
    var previousIssueTime = call.getCreatedAt();
    lifecycle(org, "MARGIN_CALL_UPDATED", loan.getId(), call.getSourceVersion() + 1, Map.of(
        "loanId", loan.getId(), "lifecycleRequestId", next.getId(), "externalId", "MARGIN-" + next.getId(),
        "amount", "300", "fulfilledAmount", "0", "status", "OPEN", "reason", "Again", "dueAt", nextDue.toString()));
    assertEquals("OPEN", call.getStatus());
    assertTrue(call.getCreatedAt().isAfter(previousIssueTime));
    assertNull(call.getCancellationReference());
    service.cancel(org.getId(), call.getId(), new SettlementRequest("cancel-3"));
    // A pledge already in transit can arrive and settle during cancellation verification.
    command(org, "COLLATERAL_PLEDGE_REQUESTED", loan.getBorrowerLoanId(), 10,
        new PledgeCommand(499, new AllocationRequest("CASH", null, 0, bd("100"), loan.getId())));
    var received = positions.findByConnectionIdAndBorrowerPositionId(conn.getId(), 499L).orElseThrow();
    collateral.confirm(org.getId(), received.getId(), "IN-TRANSIT-RECEIPT");
    assertEquals("CANCELLATION_PENDING", call.getStatus());
    command(org, "MARGIN_CALL_CANCELLATION_RESPONDED", loan.getBorrowerLoanId(), 10,
        Map.of("marginCallId", loan.getId(), "cancellationReference", "cancel-3", "accepted", false, "reason", "Collateral already submitted"));
    assertEquals("PARTIALLY_FULFILLED", call.getStatus());
    assertEquals(0, bd("100").compareTo(call.getFulfilledAmount()));
    assertThrows(ResponseStatusException.class, () -> service.cancel(org.getId(), call.getId(), new SettlementRequest("cancel-4")));
    mvc.perform(post("/api/v1/organizations/" + organization().getId() + "/margin-calls/" + call.getId() + "/cancel")
        .contentType("application/json").content(json.write(new SettlementRequest("cross-tenant"))))
        .andExpect(status().isNotFound());
  }

  @Test
  void marginDeliveryRepairForLegacyAutomaticCallQueuesExactlyOnce() {
    var org = organization(); var conn = connection(org);
    var loan = activeLoan(org, conn, security("100"), true);
    var call = new MarginCall();
    call.setOrganizationId(org.getId()); call.setConnectionId(conn.getId()); call.setLoanId(loan.getId());
    call.setOwnerReferenceId(loan.getId()); call.setSourceVersion(1L); call.setSourceData("{}");
    call.setStatus("OPEN"); call.setCreatedAt(Instant.now()); call.setAmount(bd("300"));
    call.setFulfilledAmount(BigDecimal.ZERO); call.setDueAt(Instant.now().plusSeconds(86400)); call.setReason("Automatic shortfall");
    calls.saveAndFlush(call);
    var service = marginCallService;
    service.repairMissingDelivery(org.getId(), call.getId());
    service.repairMissingDelivery(org.getId(), call.getId());
    var deliveries = events.findByOrganizationId(org.getId(), Pageable.unpaged()).stream()
        .filter(e -> "lender.confirmations.v1".equals(e.getTopic()))
        .map(e -> json.tree(e.getPayload()))
        .filter(e -> "MARGIN_CALL_UPDATED".equals(e.path("type").asText())).toList();
    assertEquals(1, deliveries.size());
    assertEquals(loan.getBorrowerLoanId().longValue(), deliveries.get(0).path("data").path("loanId").asLong());
  }

  @Test
  void demoMarginGuardrailAllowsAPositiveCallWithoutShortfallAndCanBeRestored() throws Exception {
    var org = organization();
    var connection = connection(org);
    long security = security("100");
    var loan = activeLoan(org, connection, security, true);
    var due = Instant.now().plusSeconds(86400);
    var call =
        new MarginIssueRequest(bd("100"), due, "Demonstration margin call", "demo-margin-guard");

    assertThrows(
        ResponseStatusException.class,
        () -> lifecycleRequests.margin(org.getId(), loan.getId(), call));

    mvc.perform(
            put("/api/v1/organizations/" + org.getId() + "/settings/margin-shortfall-guardrail")
                .contentType("application/json")
                .content(json.write(new MarginGuardrailRequest(false))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.enabled").value(false));

    var pending = lifecycleRequests.margin(org.getId(), loan.getId(), call);
    assertEquals("PENDING", pending.getStatus());

    mvc.perform(get("/api/v1/organizations/" + org.getId() + "/settings/margin-shortfall-guardrail"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.enabled").value(false));

    mvc.perform(
            put("/api/v1/organizations/" + org.getId() + "/settings/margin-shortfall-guardrail")
                .contentType("application/json")
                .content(json.write(new MarginGuardrailRequest(true))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.enabled").value(true));
  }

  @Test
  void marginGuardrailControlsBothSubmissionAndOwnerConfirmation() {
    for (boolean shortfall : List.of(false, true)) {
      for (boolean enableBeforeConfirmation : List.of(false, true)) {
        var org = organization();
        var c = connection(org);
        long sec = security("100");
        var loan = activeLoan(org, c, sec, true);
        if (shortfall) market.quote(sec, bd("120"), Instant.now());
        lifecycleRequests.setMarginGuardrail(org.getId(), false);
        var due = Instant.now().plusSeconds(86400);
        var pending = lifecycleRequests.margin(org.getId(), loan.getId(),
            new MarginIssueRequest(bd("100"), due, "Demo", "guard-confirmation"));
        if (enableBeforeConfirmation) lifecycleRequests.setMarginGuardrail(org.getId(), true);
        lifecycle(org, "MARGIN_CALL_UPDATED", loan.getId(), 1,
            Map.of("loanId", loan.getId(), "lifecycleRequestId", pending.getId(),
                "externalId", "MARGIN-" + pending.getId(), "amount", "100",
                "fulfilledAmount", "0", "dueAt", due.toString(), "reason", "Demo", "status", "OPEN"));
        assertEquals(enableBeforeConfirmation ? "REJECTED" : "COMPLETED", pending.getStatus());
        assertEquals(enableBeforeConfirmation ? 0 : 1,
            calls.findByOrganizationId(org.getId(), Pageable.unpaged()).getTotalElements());
      }
    }
  }

  @Test
  void recallCapacityIncludesPendingRequestsWithoutChangingOutstanding() {
    var org = organization();
    var c = connection(org);
    long sec = security("100");
    var loan = activeLoan(org, c, sec, true);
    var request = new RecallRequest(30, "Portfolio sale", "live-recall-test");
    var pending=lifecycleRequests.recall(org.getId(),loan.getId(),request);
    assertEquals(pending.getId(),lifecycleRequests.recall(org.getId(),loan.getId(),request).getId());
    assertEquals(100,loan.getOutstandingQuantity());
    var row=(Map<?,?>)loanScreen.detail(org.getId(),loan.getId()).get("loan");
    assertEquals(70L,row.get("recallAvailableQuantity"));
    assertThrows(
        ResponseStatusException.class,
        () ->
            lifecycleRequests.recall(
                org.getId(), loan.getId(), new RecallRequest(71, "Too much", "over-capacity")));
  }

  @Test
  void agreementsScreenOverviewAndPanelsAreOrganizationScoped() throws Exception {
    var o = organization();
    var c = connection(o);
    long securityId = security("100");
    inventory.create(o.getId(), new InventoryRequest(securityId, 1000, "Custody"));
    var a = agreement(o, c, securityId, true);
    reservation(o, c, securityId, 100);
    var l = pendingLoan(o, c, 100);
    var pledge = positions.findByConnectionIdAndBorrowerPositionId(c.getId(), 400L).orElseThrow();
    collateral.confirm(o.getId(), pledge.getId(), "SCREEN-COLLATERAL");
    loanService.decide(o.getId(), l.getId(), true, "Approved");
    loanService.settle(o.getId(), l.getId(), "SCREEN-SETTLEMENT");
    var fee = new LendingFee();
    fee.setOrganizationId(o.getId()); fee.setConnectionId(c.getId()); fee.setLoanId(l.getId());
    fee.setOwnerReferenceId(901L); fee.setSourceVersion(1L);
    fee.setSourceData("{}");
    fee.setStatus("DUE");
    fee.setCreatedAt(Instant.now()); fee.setAmount(bd("12.34")); fee.setRate(bd("0.03"));
    fee.setPeriodStart(LocalDate.now()); fee.setPeriodEnd(LocalDate.now()); fee.setDueDate(LocalDate.now().plusDays(10));
    feesForScreenTest.saveAndFlush(fee);
    var rebate = new Rebate();
    rebate.setOrganizationId(o.getId()); rebate.setConnectionId(c.getId()); rebate.setLoanId(l.getId());
    rebate.setOwnerReferenceId(902L); rebate.setSourceVersion(1L);
    rebate.setSourceData("{}");
    rebate.setStatus("PAID");
    rebate.setCreatedAt(Instant.now()); rebate.setAmount(bd("1.23")); rebate.setRate(bd("0.01"));
    rebate.setAccrualDate(LocalDate.now()); rebate.setPaymentDate(LocalDate.now());
    rebates.saveAndFlush(rebate);
    String base = "/api/v1/organizations/" + o.getId() + "/lenddesk/agreements";
    mvc.perform(get(base + "/overview").param("size", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.activeAgreementCount").value(1))
        .andExpect(jsonPath("$.data.agreements.totalElements").value(1))
        .andExpect(jsonPath("$.data.agreements.content[0].borrowerName").value("Borrower firm"))
        .andExpect(jsonPath("$.data.agreements.content[0].feeRateBps").value(300.0))
        .andExpect(jsonPath("$.data.agreements.content[0].coveragePercent").value(105.0))
        .andExpect(jsonPath("$.data.agreements.content[0].totalExposure").value(10000.0))
        .andExpect(jsonPath("$.data.agreements.content[0].currency").value("INR"))
        .andExpect(jsonPath("$.data.agreements.content[0].activeLoanCount").value(1));
    mvc.perform(get(base + "/" + a.getId() + "/screen"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.agreement.id").value(a.getId()))
        .andExpect(jsonPath("$.data.collateralRules[0].eligible").value(true))
        .andExpect(jsonPath("$.data.collateralRules[0].concentrationLimitPercent").isEmpty())
        .andExpect(jsonPath("$.data.activeLoans[0].id").value(l.getId()))
        .andExpect(jsonPath("$.data.recentActivityCount").value(2))
        .andExpect(jsonPath("$.data.billingCount").value(2))
        .andExpect(jsonPath("$.data.billing[0].amount").value(12.34))
        .andExpect(jsonPath("$.data.billing[1].type").value("REBATE"))
        .andExpect(jsonPath("$.data.audit").isNotEmpty());
    mvc.perform(get(base + "/overview").param("recallAllowed", "false"))
        .andExpect(jsonPath("$.data.agreements.totalElements").value(0));
    mvc.perform(
            get(base + "/overview")
                .param("connectionId", String.valueOf(c.getId()))
                .param("q", "BORROWER"))
        .andExpect(jsonPath("$.data.agreements.totalElements").value(1));
    mvc.perform(get(base + "/overview").param("activeOn", a.getExpiryDate().plusDays(1).toString()))
        .andExpect(jsonPath("$.data.agreements.totalElements").value(0));
    var other = organization();
    mvc.perform(
            get(
                "/api/v1/organizations/"
                    + other.getId()
                    + "/lenddesk/agreements/"
                    + a.getId()
                    + "/screen"))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/organizations/" + other.getId() + "/lenddesk/agreements/overview"))
        .andExpect(jsonPath("$.data.agreements.totalElements").value(0))
        .andExpect(jsonPath("$.data.borrowers").isEmpty());
    mvc.perform(get(base + "/overview").param("page", "-1")).andExpect(status().isBadRequest());
    mvc.perform(get(base + "/overview").param("size", "201")).andExpect(status().isBadRequest());
  }

  @Test
  void agreementsScreenMissingPriceIsUnknownAndLiteralSearchIsNotAWildcard() throws Exception {
    var o = organization();
    var c = connection(o);
    long securityId = security("100");
    inventory.create(o.getId(), new InventoryRequest(securityId, 1000, "Custody"));
    var a = agreement(o, c, securityId, false);
    reservation(o, c, securityId, 100);
    var l = pendingLoan(o, c, 100);
    l.setStatus("ACTIVE");
    loans.saveAndFlush(l);
    var security = securityMasterForScreenTest.findById(securityId).orElseThrow();
    security.setPrice(null);
    securityMasterForScreenTest.saveAndFlush(security);
    String base = "/api/v1/organizations/" + o.getId() + "/lenddesk/agreements";
    mvc.perform(get(base + "/overview"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.agreements.content[0].unpricedLoanCount").value(1))
        .andExpect(jsonPath("$.data.agreements.content[0].totalExposure").isEmpty());
    mvc.perform(get(base + "/" + a.getId() + "/screen"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.activeLoans[0].marketValue").isEmpty());
    mvc.perform(get(base + "/overview").param("q", "%"))
        .andExpect(jsonPath("$.data.agreements.totalElements").value(0));
    a.setNumber("MSLA-%-literal");
    agreements.saveAndFlush(a);
    mvc.perform(get(base + "/overview").param("q", "%"))
        .andExpect(jsonPath("$.data.agreements.totalElements").value(1));
  }

  @Test
  void agreementsScreenPaginationAndStatusCountRemainConsistent() throws Exception {
    var o = organization();
    var c = connection(o);
    var a = agreement(o, c, 0, true);
    a.setNumber("SECONDZAGREEMENT");
    agreements.saveAndFlush(a);
    var proposed = new Msla();
    org.springframework.beans.BeanUtils.copyProperties(a, proposed, "id");
    proposed.setBorrowerMslaId(11L); proposed.setNumber("SECOND_AGREEMENT");
    proposed.setStatus("PROPOSED"); proposed.setRecallAllowed(false);
    agreements.saveAndFlush(proposed);
    String base = "/api/v1/organizations/" + o.getId() + "/lenddesk/agreements/overview";
    mvc.perform(get(base).param("size", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.agreements.totalPages").value(2))
        .andExpect(jsonPath("$.data.agreements.content[0].id").value(proposed.getId()));
    mvc.perform(get(base).param("size", "1").param("page", "1"))
        .andExpect(jsonPath("$.data.agreements.content[0].id").value(a.getId()));
    mvc.perform(get(base).param("status", "PROPOSED"))
        .andExpect(jsonPath("$.data.activeAgreementCount").value(1))
        .andExpect(jsonPath("$.data.agreements.totalElements").value(1));
    mvc.perform(get(base).param("q", "SECOND_"))
        .andExpect(jsonPath("$.data.agreements.totalElements").value(1));
    mvc.perform(get(base).param("size", "1").param("page", "2"))
        .andExpect(jsonPath("$.data.agreements.content").isEmpty())
        .andExpect(jsonPath("$.data.agreements.totalElements").value(2));
  }

  @Test
  void locateScreenSeparatesReservationsConsumedCapacityAndPhysicalInventory() throws Exception {
    var o = organization(); var c = connection(o); long securityId = security("100");
    inventory.create(o.getId(), new InventoryRequest(securityId, 1000, "Custody"));
    var a = agreement(o, c, securityId, true);
    var r = reservation(o, c, securityId, 100);
    var l = pendingLoan(o, c, 30);
    var locate = locates.findById(r.getLocateId()).orElseThrow();
    locate.setExpiresAt(Instant.now().plusSeconds(7200)); locates.saveAndFlush(locate);
    String base = "/api/v1/organizations/" + o.getId() + "/lenddesk/locates";
    mvc.perform(
            get(base + "/overview")
                .param("mslaId", String.valueOf(a.getId()))
                .param("expiry", "UPCOMING"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.pendingCount").value(0))
        .andExpect(jsonPath("$.data.activeReservationCount").value(1))
        .andExpect(jsonPath("$.data.locates.content[0].reservedQuantity").value(70))
        .andExpect(jsonPath("$.data.locates.content[0].consumedQuantity").value(30))
        .andExpect(jsonPath("$.data.locates.content[0].availableQuantity").value(0))
        .andExpect(jsonPath("$.data.locates.content[0].inventoryAvailableQuantity").value(900));
    mvc.perform(get(base + "/" + locate.getId() + "/screen"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.reservations[0].remainingQuantity").value(70))
        .andExpect(jsonPath("$.data.reservations[0].linkedLoans[0].id").value(l.getId()))
        .andExpect(jsonPath("$.data.locate.canApprove").value(false))
        .andExpect(jsonPath("$.data.activity").isNotEmpty());
    mvc.perform(get(base + "/overview").param("q", "EQ" + securityId))
        .andExpect(jsonPath("$.data.locates.totalElements").value(1));
    mvc.perform(get(base + "/overview").param("q", "LOC-" + locate.getId()))
        .andExpect(jsonPath("$.data.locates.totalElements").value(1));
    var other = organization();
    mvc.perform(
            get(
                "/api/v1/organizations/"
                    + other.getId()
                    + "/lenddesk/locates/"
                    + locate.getId()
                    + "/screen"))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/organizations/" + other.getId() + "/lenddesk/locates/overview"))
        .andExpect(jsonPath("$.data.locates.totalElements").value(0))
        .andExpect(jsonPath("$.data.securities").isEmpty());
  }

  @Test
  void liveLocateDecisionsAreGuardedAndDoNotReserveInventory() throws Exception {
    var o = organization(); var c = connection(o); long securityId = security("100");
    inventory.create(o.getId(), new InventoryRequest(securityId, 1000, "Custody"));
    agreement(o, c, securityId, true);
    command(
        o,
        "LOCATE_REQUESTED",
        101,
        10,
        new LocateCommand(10, securityId, 200, Instant.now().plusSeconds(7200)));
    var locate = locates.findByConnectionIdAndBorrowerLocateId(c.getId(), 101L).orElseThrow();
    String decision = "/api/v1/organizations/" + o.getId() + "/locates/" + locate.getId();
    String screen =
        "/api/v1/organizations/" + o.getId() + "/lenddesk/locates/" + locate.getId() + "/screen";
    mvc.perform(get(screen)).andExpect(jsonPath("$.data.locate.canApprove").value(true));
    mvc.perform(
            post(decision + "/approve")
                .contentType("application/json")
                .content("{\"quantity\":201,\"reason\":\"Too much\"}"))
        .andExpect(status().isConflict());
    mvc.perform(
            post(decision + "/approve")
                .contentType("application/json")
                .content("{\"quantity\":150,\"reason\":\"Approved partial\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("PARTIALLY_APPROVED"));
    mvc.perform(get(screen))
        .andExpect(jsonPath("$.data.locate.availableQuantity").value(150))
        .andExpect(jsonPath("$.data.locate.inventoryAvailableQuantity").value(1000))
        .andExpect(jsonPath("$.data.reservations").isEmpty())
        .andExpect(jsonPath("$.data.locate.canApprove").value(false));
    long eventCount = events.count();
    mvc.perform(
            post(decision + "/approve")
                .contentType("application/json")
                .content("{\"quantity\":150,\"reason\":\"Retry\"}"))
        .andExpect(status().isConflict());
    assertEquals(eventCount, events.count());
    command(
        o,
        "LOCATE_REQUESTED",
        102,
        10,
        new LocateCommand(10, securityId, 100, Instant.now().plusSeconds(7200)));
    var rejected = locates.findByConnectionIdAndBorrowerLocateId(c.getId(), 102L).orElseThrow();
    mvc.perform(
            post("/api/v1/organizations/" + o.getId() + "/locates/" + rejected.getId() + "/reject")
                .contentType("application/json")
                .content("{\"reason\":\"Outside mandate\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("REJECTED"));
    var trail = locateScreenForTests.detail(o.getId(), rejected.getId());
    assertTrue(
        trail.activity().stream()
            .anyMatch(event -> event.type().equals("LOCATE_DECISION_REJECTED")));
  }

  @Test
  void locateExpiryFiltersAndInvalidParametersRespectStoredState() throws Exception {
    var o = organization(); var c = connection(o); long securityId = security("100");
    inventory.create(o.getId(), new InventoryRequest(securityId, 1000, "Custody"));
    agreement(o, c, securityId, true); var r = reservation(o, c, securityId, 100);
    var locate = locates.findById(r.getLocateId()).orElseThrow();
    locate.setExpiresAt(Instant.now().minusSeconds(60)); locates.saveAndFlush(locate);
    String base = "/api/v1/organizations/" + o.getId() + "/lenddesk/locates";
    mvc.perform(get(base + "/overview").param("expiry", "EXPIRED"))
        .andExpect(jsonPath("$.data.locates.content[0].status").value("APPROVED"))
        .andExpect(jsonPath("$.data.locates.content[0].expiryBucket").value("EXPIRED"))
        .andExpect(jsonPath("$.data.locates.content[0].availableQuantity").value(0))
        .andExpect(jsonPath("$.data.locates.content[0].reservedQuantity").value(100))
        .andExpect(jsonPath("$.data.activeReservationCount").value(1));
    mvc.perform(get(base + "/overview").param("expiry", "URGENT"))
        .andExpect(jsonPath("$.data.locates.content").isEmpty());
    mvc.perform(get(base + "/overview").param("expiry", "invalid"))
        .andExpect(status().isBadRequest());
    mvc.perform(get(base + "/overview").param("page", "-1")).andExpect(status().isBadRequest());
    mvc.perform(get(base + "/overview").param("size", "201")).andExpect(status().isBadRequest());
  }

  @Test
  void organizationSupportsMultipleUsersAndProtectsLastAdministrator() {
    var o = organization();
    var admin =
        directory.member(o.getId(), new MemberRequest("Admin", "admin@example.com", "ADMIN"));
    directory.member(o.getId(), new MemberRequest("Desk", "desk@example.com", "LENDING_DESK"));
    assertEquals(2, users.countByOrganizationId(o.getId()));
    assertThrows(
        ResponseStatusException.class,
        () ->
            directory.updateMember(
                o.getId(), admin.getId(), new MemberUpdateRequest("READ_ONLY", false)));
  }

  @Test
  void expandedAgreementIncludesLoanAndCollateralDetailForTheUi() {
    var o = organization();
    var c = connection(o);
    long securityId = security("100");
    inventory.create(o.getId(), new InventoryRequest(securityId, 1000, "Custody"));
    var msla = agreement(o, c, securityId, true);
    reservation(o, c, securityId, 100);
    var loan = pendingLoan(o, c, 100);
    var position = positions.findByConnectionIdAndBorrowerPositionId(c.getId(), 400L).orElseThrow();
    collateral.confirm(o.getId(), position.getId(), "COLLATERAL-RECEIPT");
    loanService.decide(o.getId(), loan.getId(), true, "Approved");
    loanService.settle(o.getId(), loan.getId(), "LOAN-SETTLEMENT");

    var expanded = read.agreementExpanded(o.getId(), msla.getId());
    assertNotNull(expanded);
    assertFalse(expanded.loans().isEmpty());
    var firstLoan = expanded.loans().get(0);
    assertNotNull(firstLoan.securitySymbol());
    assertNotNull(firstLoan.marketValue());
    assertNotNull(firstLoan.collateralValue());
    assertFalse(firstLoan.collateral().isEmpty());
    assertNotNull(firstLoan.collateral().get(0).securitySymbol());
  }

  @Test
  void incomingProposalsAreDeduplicatedAndRequireTheRegisteredSender() {
    var o = organization();
    var c = connection(o);
    var today = LocalDate.now();
    var p =
        new AgreementProposal(
            o.getPublicReferenceId(),
            "MSLA",
            today,
            today.plusYears(1),
            bd("1000"),
            bd("0.03"),
            bd("0.01"),
            bd("1.05"),
            "MONTHLY",
            365,
            true,
            24,
            48,
            List.of(new RuleRequest("CASH", null, BigDecimal.ZERO)));
    String payload = body(o, "AGREEMENT_PROPOSED", 10, 10, p);
    commands.receive(payload, "BORROWER_DEMO");
    commands.receive(payload, "BORROWER_DEMO");
    assertEquals(
        1,
        agreements
            .findByOrganizationId(o.getId(), org.springframework.data.domain.Pageable.unpaged())
            .getTotalElements());
    assertThrows(ResponseStatusException.class, () -> commands.receive(payload, "OTHER_BORROWER"));
    assertEquals(1, inbox.count());
  }

  @Test
  void locateApprovalDoesNotReserveButReservationApprovalDoes() {
    var o = organization();
    var c = connection(o);
    long s = security("100");
    inventory.create(o.getId(), new InventoryRequest(s, 1000, "Custody"));
    agreement(o, c, 0, true);
    command(
        o,
        "LOCATE_REQUESTED",
        100,
        10,
        new LocateCommand(10, s, 100, Instant.now().plusSeconds(3600)));
    var locate = locates.findByConnectionIdAndBorrowerLocateId(c.getId(), 100L).orElseThrow();
    reservationService.decideLocate(o.getId(), locate.getId(), 100, true, "Available");
    assertEquals(0, inventory.position(o.getId(), s).getReservedQuantity());
    command(o, "RESERVATION_REQUESTED", 200, 10, new ReservationCommand(100, 100));
    var r = reservations.findByConnectionIdAndBorrowerReservationId(c.getId(), 200L).orElseThrow();
    assertEquals(0, inventory.position(o.getId(), s).getReservedQuantity());
    reservationService.decide(o.getId(), r.getId(), 80, true, "Partial allocation");
    assertEquals(80, inventory.position(o.getId(), s).getReservedQuantity());
    assertEquals(80, r.getRemainingQuantity());
  }

  @Test
  void stockCannotBeReducedBelowExistingReservations() {
    var o = organization();
    var c = connection(o);
    long s = security("100");
    var p = inventory.create(o.getId(), new InventoryRequest(s, 100, "Custody"));
    agreement(o, c, 0, true);
    reservation(o, c, s, 80);
    assertThrows(
        ResponseStatusException.class,
        () ->
            inventory.adjust(
                o.getId(), p.getId(), new InventoryAdjustmentRequest(-30, "Withdraw")));
    assertEquals(100, p.getTotalQuantity());
  }

  @Test
  void collateralCanArriveBeforeLoanWithoutBecomingConfirmed() {
    var o = organization();
    var c = connection(o);
    long s = security("100");
    inventory.create(o.getId(), new InventoryRequest(s, 1000, "Custody"));
    agreement(o, c, 0, true);
    reservation(o, c, s, 100);
    var allocation = new AllocationRequest("CASH", null, 0, bd("11000"), null);
    command(o, "COLLATERAL_PLEDGE_REQUESTED", 300, 10, new PledgeCommand(400, allocation));
    var p = positions.findByConnectionIdAndBorrowerPositionId(c.getId(), 400L).orElseThrow();
    assertNull(p.getLoanId());
    assertEquals("PLEDGE_PENDING", p.getStatus());
    command(o, "LOAN_REQUESTED", 300, 10, new LoanCommand(200, 100, List.of(allocation)));
    assertNotNull(p.getLoanId());
    assertEquals(
        0, accounts.findByOrganizationId(o.getId()).orElseThrow().getCashCollateralHeld().signum());
  }

  @Test
  void approvalAndSettlementAreSeparateAndSettlementIsIdempotent() {
    var o = organization();
    var c = connection(o);
    long s = security("100");
    inventory.create(o.getId(), new InventoryRequest(s, 1000, "Custody"));
    agreement(o, c, 0, true);
    reservation(o, c, s, 100);
    var loan = pendingLoan(o, c, 100);
    var position = positions.findByLoanId(loan.getId()).get(0);
    loanService.decide(o.getId(), loan.getId(), true, "Approved");
    assertEquals(0, inventory.position(o.getId(), s).getLoanedQuantity());
    assertThrows(
        ResponseStatusException.class,
        () -> loanService.settle(o.getId(), loan.getId(), "BEFORE-COLLATERAL"));
    collateral.confirm(o.getId(), position.getId(), "CASH");
    loanService.settle(o.getId(), loan.getId(), "SETTLED");
    loanService.settle(o.getId(), loan.getId(), "SETTLED");
    assertEquals(100, inventory.position(o.getId(), s).getLoanedQuantity());
    assertEquals(0, inventory.position(o.getId(), s).getReservedQuantity());
    assertEquals("ACTIVE", loan.getStatus());
  }

  @Test
  void lenderAgreementSummaryReturnsBorrowerContextAndStatus() {
    var o = organization();
    var c = connection(o);
    agreement(o, c, 0, true);
    var summaries = (com.example.lender_service.response.PageResponse<?>) read.agreementSummaries(o.getId(), 0, 20);
    assertFalse(summaries.content().isEmpty());
    var summary = (com.example.lender_service.dto.AgreementSummary) summaries.content().get(0);
    assertEquals(c.getName(), summary.borrowerName());
    assertEquals(o.getId(), summary.organizationId());
    assertEquals("ACTIVE", summary.status());
  }

  @Test
  void rejectionRestoresReservationAndRejectsUnsettledCollateral() {
    var o = organization();
    var c = connection(o);
    long s = security("100");
    inventory.create(o.getId(), new InventoryRequest(s, 1000, "Custody"));
    agreement(o, c, 0, true);
    var reservation = reservation(o, c, s, 100);
    var loan = pendingLoan(o, c, 100);
    loanService.decide(o.getId(), loan.getId(), false, "Rejected");
    assertEquals(100, reservation.getRemainingQuantity());
    assertEquals(100, inventory.position(o.getId(), s).getReservedQuantity());
    assertEquals("REJECTED", positions.findByLoanId(loan.getId()).get(0).getStatus());
  }

  @Test
  void partialAndFullReturnsUpdateInventoryOnlyAfterSettlement() {
    var o = organization();
    var c = connection(o);
    long s = security("100");
    var loan = activeLoan(o, c, s, true);
    command(o, "RETURN_REQUESTED", 500, 10, new ReturnCommand(300, 40, 0));
    var r = returns.findByConnectionIdAndBorrowerReturnId(c.getId(), 500L).orElseThrow();
    assertEquals(100, inventory.position(o.getId(), s).getLoanedQuantity());
    loanService.returnDecision(o.getId(), r.getId(), true, "RETURN40");
    assertEquals(60, loan.getOutstandingQuantity());
    command(o, "RETURN_REQUESTED", 501, 10, new ReturnCommand(300, 60, 0));
    loanService.returnDecision(
        o.getId(),
        returns.findByConnectionIdAndBorrowerReturnId(c.getId(), 501L).orElseThrow().getId(),
        true,
        "RETURN60");
    assertEquals("CLOSED", loan.getStatus());
    assertEquals(0, inventory.position(o.getId(), s).getLoanedQuantity());
    assertEquals(1000, inventory.position(o.getId(), s).getAvailableQuantity());
  }

  @Test
  void fullReturnAutomaticallyReleasesPostedCollateral() {
    var o = organization();
    var c = connection(o);
    long s = security("100");
    var loan = activeLoan(o, c, s, true);
    var p = positions.findByLoanId(loan.getId()).get(0);
    command(o, "RETURN_REQUESTED", 500, 10, new ReturnCommand(300, 100, 0));
    var r = returns.findByConnectionIdAndBorrowerReturnId(c.getId(), 500L).orElseThrow();
    loanService.returnDecision(o.getId(), r.getId(), true, "FULL-RETURN");
    assertEquals("CLOSED", loans.findById(loan.getId()).orElseThrow().getStatus());
    assertEquals("RELEASED", positions.findById(p.getId()).orElseThrow().getStatus());
  }

  @Test
  void partialReleaseRechecksMarketCoverageAndPreservesImmutableCashMovements() throws Exception {
    var org = organization();
    var c = connection(org);
    long sec = security("100");
    var loan = activeLoan(org, c, sec, true);
    var p = positions.findByLoanId(loan.getId()).get(0);
    command(
      org,
      "COLLATERAL_RELEASE_REQUESTED",
      400,
      10,
      Map.of("cashAmount", bd("400"), "quantity", 0)
    );
    var row = (Map<?, ?>) (
      (List<?>) loanScreen.detail(org.getId(), loan.getId()).get("collateral")
    ).get(0);
    assertEquals(
      0,
      bd("400").compareTo((BigDecimal) row.get("requestedReleaseCash"))
    );
    // The loan price rises after the request: acceptance must use today's coverage, not submission's.
    market.quote(sec, bd("110"), Instant.now().minusSeconds(30));
    assertThrows(ResponseStatusException.class, () ->
      collateral.release(org.getId(), p.getId(), "BLOCKED", true)
    );
    assertEquals("RELEASE_REQUESTED", p.getStatus());
    market.quote(sec, bd("100"), Instant.now().minusSeconds(10));
    mvc
      .perform(
        post(
          "/api/v1/organizations/" +
            org.getId() +
            "/collateral/" +
            p.getId() +
            "/release-confirmations"
        )
          .contentType("application/json")
          .content("{\"reference\":\"PARTIAL-RELEASE\"}")
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.status").value("POSTED"));
    assertEquals(0, bd("10600").compareTo(p.getCashAmount()));
    assertEquals(
      0,
      bd("10600").compareTo(
        accounts
          .findByOrganizationId(org.getId())
          .orElseThrow()
          .getCashCollateralHeld()
      )
    );
    var movements = valuationEm
      .createQuery(
        "select h from CollateralHistory h where h.positionId=:id order by h.id",
        CollateralHistory.class
      )
      .setParameter("id", p.getId())
      .getResultList();
    assertEquals(0, bd("11000").compareTo(movements.get(0).getCashAmount()));
    assertEquals(0, bd("400").compareTo(movements.get(1).getCashAmount()));
    // A full return while another partial release is pending must still release all remaining cash.
    command(
      org,
      "COLLATERAL_RELEASE_REQUESTED",
      400,
      10,
      Map.of("cashAmount", bd("100"), "quantity", 0)
    );
    command(org, "RETURN_REQUESTED", 500, 10, new ReturnCommand(300, 100, 0));
    var returned = returns
      .findByConnectionIdAndBorrowerReturnId(c.getId(), 500L)
      .orElseThrow();
    loanService.returnDecision(
      org.getId(),
      returned.getId(),
      true,
      "CLOSING-RETURN"
    );
    assertEquals("RELEASED", p.getStatus());
    assertEquals(
      0,
      accounts
        .findByOrganizationId(org.getId())
        .orElseThrow()
        .getCashCollateralHeld()
        .signum()
    );
  }

  @Test
  void ledgerPagingFiltersTotalsAndSortingCoverTheWholeOrganization() throws Exception {
    var org = organization();
    var c = connection(org);
    var linked = activeLoan(org, c, security("100"), true);
    for (int i = 0; i < 230; i++) {
      var row = new LenderAccountLedger();
      row.setOrganizationId(org.getId());
      row.setLoanId(linked.getId());
      row.setType("TEST_ENTRY");
      row.setAmount(bd("2"));
      row.setReference(i == 0 ? "needle" : i == 5 ? "literal%" : "entry-" + i);
      row.setCreatedAt(Instant.now());
      valuationEm.persist(row);
    }
    valuationEm.flush();
    String path =
      "/api/v1/organizations/" + org.getId() + "/lenddesk/ledger/overview";
    mvc
      .perform(get(path).param("page", "0").param("size", "25"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.totalElements").value(231))
      .andExpect(jsonPath("$.data.totalPages").value(10))
      .andExpect(jsonPath("$.data.content.length()").value(25))
      .andExpect(jsonPath("$.data.content[0].reference").value("entry-229"))
      .andExpect(jsonPath("$.data.totalAmount").value(11460));
    mvc
      .perform(get(path).param("page", "9").param("size", "25"))
      .andExpect(jsonPath("$.data.content.length()").value(6))
      .andExpect(jsonPath("$.data.options.borrowers[0].id").value(c.getId()));
    mvc
      .perform(get(path).param("type", "TEST_ENTRY").param("q", "needle"))
      .andExpect(jsonPath("$.data.totalElements").value(1))
      .andExpect(jsonPath("$.data.content[0].reference").value("needle"))
      .andExpect(jsonPath("$.data.totalAmount").value(2));
    mvc
      .perform(get(path).param("q", "%"))
      .andExpect(jsonPath("$.data.totalElements").value(1));
    mvc
      .perform(
        get(path)
          .param("type", "TEST_ENTRY")
          .param("sort", "id")
          .param("direction", "asc")
      )
      .andExpect(jsonPath("$.data.content[0].reference").value("needle"));
    mvc
      .perform(
        get(path)
          .param("q", "LOAN-000" + linked.getId())
          .param("connectionId", c.getId().toString())
      )
      .andExpect(jsonPath("$.data.totalElements").value(231));
    mvc
      .perform(get(path).param("size", "201"))
      .andExpect(status().isBadRequest());
    mvc
      .perform(get(path).param("page", "-1"))
      .andExpect(status().isBadRequest());
    mvc
      .perform(get(path).param("sort", "invalid"))
      .andExpect(status().isBadRequest());
    var other = organization();
    mvc
      .perform(
        get(
          "/api/v1/organizations/" + other.getId() + "/lenddesk/ledger/overview"
        )
      )
      .andExpect(jsonPath("$.data.totalElements").value(0))
      .andExpect(jsonPath("$.data.options.borrowers.length()").value(0));
  }

  @Test
  void settlementLedgerIncludesLocalLoanAndBorrowerAndKeepsTenantBoundaries() throws Exception {
    var org = organization();
    var c = connection(org);
    var loan = activeLoan(org, c, security("100"), true);
    for (String prefix : List.of("/ledger", "/lenddesk/ledger")) {
      mvc
        .perform(get("/api/v1/organizations/" + org.getId() + prefix))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].loanId").value(loan.getId()))
        .andExpect(jsonPath("$.data.content[0].borrowerLoanId").value(300))
        .andExpect(jsonPath("$.data.content[0].connectionId").value(c.getId()))
        .andExpect(
          jsonPath("$.data.content[0].borrowerName").value(c.getName())
        );
    }
    var other = organization();
    var legacy = new LenderAccountLedger();
    legacy.setOrganizationId(other.getId());
    legacy.setType("LEGACY");
    legacy.setAmount(bd("1"));
    legacy.setReference("Unlinked historical posting");
    legacy.setCreatedAt(Instant.now());
    valuationEm.persist(legacy);
    valuationEm.flush();
    mvc
      .perform(
        get("/api/v1/organizations/" + other.getId() + "/lenddesk/ledger")
      )
      .andExpect(jsonPath("$.data.totalElements").value(1))
      .andExpect(jsonPath("$.data.content[0].loanId").isEmpty())
      .andExpect(jsonPath("$.data.content[0].borrowerName").isEmpty());
    // Corrupt references must not disclose a different tenant's borrower details.
    legacy.setLoanId(loan.getId());
    valuationEm.flush();
    mvc
      .perform(get("/api/v1/organizations/" + other.getId() + "/ledger"))
      .andExpect(jsonPath("$.data.content[0].loanId").isEmpty())
      .andExpect(jsonPath("$.data.content[0].borrowerName").isEmpty());
  }

  @Test
  void bothFeePaymentSourcesPreserveTheLedgerLoanContext() {
    var org = organization();
    var c = connection(org);
    var loan = activeLoan(org, c, security("100"), true);
    for (long bill : List.of(991101L, 991102L))
      lifecycle(
        org,
        "FEE_BILL_ISSUED",
        bill,
        1,
        Map.of(
          "loanId",
          loan.getId(),
          "amount",
          "10",
          "rate",
          "0.03",
          "periodStart",
          "2026-11-01",
          "periodEnd",
          "2026-11-02",
          "dueDate",
          "2026-11-30"
        )
      );
    command(
      org,
      "FEE_PAYMENT_RECEIVED",
      991101L,
      10,
      new FeePaymentCommand(300, bd("10"), 77)
    );
    lifecycle(
      org,
      "FEE_PAYMENT_RECEIVED",
      991102L,
      2,
      Map.of(
        "loanId",
        loan.getId(),
        "amount",
        "10",
        "borrowerPaymentRequestId",
        88
      )
    );
    var postings = valuationEm
      .createQuery(
        "select e from LenderAccountLedger e where e.organizationId=:org and e.type='FEE_PAYMENT_RECEIVED'",
        LenderAccountLedger.class
      )
      .setParameter("org", org.getId())
      .getResultList();
    assertEquals(2, postings.size());
    assertTrue(
      postings.stream().allMatch(e -> loan.getId().equals(e.getLoanId()))
    );
  }

  @Test
  void collateralReleaseWaitsForRequestAndCannotUndercollateralizeAnActiveLoan() {
    var o = organization();
    var c = connection(o);
    long s = security("100");
    var loan = activeLoan(o, c, s, true);
    var p = positions.findByLoanId(loan.getId()).get(0);
    command(o, "COLLATERAL_RELEASE_REQUESTED", 400, 10, Map.of("loanId", 300));
    assertEquals("RELEASE_REQUESTED", p.getStatus());
    assertThrows(
        ResponseStatusException.class,
        () -> collateral.release(o.getId(), p.getId(), "TOO-EARLY", true));
    collateral.release(o.getId(), p.getId(), "Not enough cover", false);
    assertEquals("POSTED", p.getStatus());
    var rejection=events.findByOrganizationId(o.getId(),Pageable.unpaged()).getContent().stream()
        .map(e->json.tree(e.getPayload())).filter(e->"COLLATERAL_RELEASE_REJECTED".equals(e.path("type").asText())).findFirst().orElseThrow();
    assertEquals("Not enough cover",rejection.path("data").path("reason").asText());
  }

  @Test
  void onlyLifecycleExpiryInstructionsUnlockExpiredReservations() {
    var o = organization();
    var c = connection(o);
    long s = security("100");
    inventory.create(o.getId(), new InventoryRequest(s, 1000, "Custody"));
    agreement(o, c, 0, true);
    var r = reservation(o, c, s, 100);
    r.setExpiresAt(
        Instant.now().minusSeconds(10).truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    assertEquals(100, inventory.position(o.getId(), s).getReservedQuantity());
    lifecycle(
        o, "RESERVATION_EXPIRED", r.getId(), 1, Map.of("expectedExpiresAt", r.getExpiresAt()));
    assertEquals(0, inventory.position(o.getId(), s).getReservedQuantity());
    assertEquals("EXPIRED", r.getStatus());
  }

  @Test
  void lateLoanRequestsReceiveBusinessRejectionInsteadOfAllocatingExpiredStock() {
    var o = organization();
    var c = connection(o);
    long s = security("100");
    inventory.create(o.getId(), new InventoryRequest(s, 1000, "Custody"));
    agreement(o, c, 0, true);
    var r = reservation(o, c, s, 100);
    reservationService.release(o.getId(), r, false);
    var loan = pendingLoan(o, c, 100);
    assertEquals("REJECTED", loan.getStatus());
    assertEquals(0, inventory.position(o.getId(), s).getReservedQuantity());
  }

  @Test
  void recallsAreDelegatedAndRequireAgreementPermission() {
    var o = organization();
    var c = connection(o);
    long s = security("100");
    var loan = activeLoan(o, c, s, false);
    assertThrows(
        ResponseStatusException.class,
        () ->
            lifecycleRequests.recall(
                o.getId(), loan.getId(), new RecallRequest(10, "Need shares", "recall-one")));
    assertEquals(0, readCount(o, "recalls"));
  }

  long readCount(Organization o, String resource) {
    var page =
        (com.example.lender_service.response.PageResponse<?>) read.list(o.getId(), resource, 0, 50);
    return page.totalElements();
  }

  @Test
  void permittedRecallRequestDoesNotCreateRecallOrDeadlineLocally() {
    var o = organization();
    var c = connection(o);
    long s = security("100");
    var loan = activeLoan(o, c, s, true);
    var r =
        lifecycleRequests.recall(
            o.getId(), loan.getId(), new RecallRequest(30, "Need shares", "recall-one"));
    assertEquals("PENDING", r.getStatus());
    assertEquals(0, readCount(o, "recalls"));
    assertEquals(
        r.getId(),
        lifecycleRequests
            .recall(o.getId(), loan.getId(), new RecallRequest(30, "Need shares", "recall-one"))
            .getId());
  }

  @Test
  void recallIssuanceDeadlineAndReturnSettlementReachTheBorrowerOutbox() {
    var o = organization();
    var c = connection(o);
    var loan = activeLoan(o, c, security("100"), true);
    var request =
        lifecycleRequests.recall(
            o.getId(), loan.getId(), new RecallRequest(60, "Need shares", "recall-flow"));
    long reference = request.getId();
    lifecycle(
        o,
        "RECALL_UPDATED",
        reference,
        1,
        Map.of(
            "loanId",
            loan.getId(),
            "lifecycleRequestId",
            reference,
            "externalId",
            "RECALL-" + reference,
            "quantity",
            60,
            "fulfilledQuantity",
            0,
            "reason",
            "Need shares",
            "status",
            "OPEN",
            "dueAt",
            Instant.now().plusSeconds(3600).toString()));
    var recall = read.list(o.getId(), "recalls", 0, 50);
    assertEquals(1, ((com.example.lender_service.response.PageResponse<?>) recall).totalElements());
    assertEquals("COMPLETED", request.getStatus());

    command(o, "RETURN_REQUESTED", 500, 10, new ReturnCommand(300, 20, reference));
    var first = returns.findByConnectionIdAndBorrowerReturnId(c.getId(), 500L).orElseThrow();
    command(o, "RETURN_REQUESTED", 501, 10, new ReturnCommand(300, 41, reference));
    assertTrue(returns.findByConnectionIdAndBorrowerReturnId(c.getId(), 501L).isEmpty());
    loanService.returnDecision(o.getId(), first.getId(), true, "RETURN-1");
    loanService.returnDecision(o.getId(), first.getId(), true, "RETURN-1");

    var current =
        recalls.findByOrganizationIdAndOwnerReferenceId(o.getId(), reference).orElseThrow();
    assertEquals("PARTIALLY_FULFILLED", current.getStatus());
    assertEquals(20L, current.getFulfilledQuantity().longValue());
    current.setDueAt(Instant.now().minusSeconds(60));
    var deadline = Map.<String, Object>of("expectedDueAt", current.getDueAt().toString());
    lifecycle(o, "RECALL_DEADLINE_REACHED", reference, 1, deadline);
    assertEquals("OVERDUE", current.getStatus());
    command(o, "RETURN_REQUESTED", 502, 10, new ReturnCommand(300, 40, reference));
    var last = returns.findByConnectionIdAndBorrowerReturnId(c.getId(), 502L).orElseThrow();
    loanService.returnDecision(o.getId(), last.getId(), true, "RETURN-2");
    lifecycle(o, "RECALL_DEADLINE_REACHED", reference, 1, deadline);
    assertEquals("FULFILLED", current.getStatus());
    assertEquals(60L, current.getFulfilledQuantity().longValue());
    assertEquals(40L, loan.getOutstandingQuantity().longValue());
    var updates =
        events.findByOrganizationId(o.getId(), Pageable.unpaged()).getContent().stream()
            .filter(row -> "lender.confirmations.v1".equals(row.getTopic()))
            .map(row -> json.tree(row.getPayload()))
            .filter(event -> "RECALL_UPDATED".equals(event.path("type").asText()))
            .sorted(Comparator.comparingLong(event -> event.path("version").asLong()))
            .toList();
    assertEquals(
        List.of("OPEN", "PARTIALLY_FULFILLED", "OVERDUE", "FULFILLED"),
        updates.stream().map(event -> event.path("data").path("status").asText()).toList());
    assertEquals(300L, updates.get(3).path("data").path("loanId").asLong());
  }

  @Test
  void externallyReportedRebatesAreNotRecalculatedAndPaymentIsDeduplicated() throws Exception {
    var o = organization();
    var c = connection(o);
    long s = security("100");
    var loan = activeLoan(o, c, s, true);
    long id = ids.incrementAndGet();
    lifecycle(
        o,
        "ACCRUAL_REPORTED",
        id,
        1,
        Map.of("loanId", loan.getId(), "type", "REBATE", "date", "2026-11-08",
            "baseValue", "2000", "rate", "0.0123456789", "amount", "12.345678"));
    lifecycle(
        o,
        "REBATE_ACCRUED",
        id,
        1,
        Map.of(
            "loanId",
            loan.getId(),
            "rate",
            "0.0123456789",
            "amount",
            "12.345678",
            "date",
            "2026-11-08",
            "paymentDate",
            "2026-12-01"));
    var rebate = rebates.findByOrganizationIdAndOwnerReferenceId(o.getId(), id).orElseThrow();
    assertEquals(0, bd("0.0123456789").compareTo(rebate.getRate()));
    String body =
        json.write(
            new LifecycleEvent(
                UUID.randomUUID().toString(),
                "REBATE_PAID",
                o.getId(),
                id,
                2,
                Instant.now(),
                json.tree(json.write(Map.of("loanId", loan.getId(), "amount", "12.345678")))));
    lifecycle.receive(body);
    lifecycle.receive(body);
    assertEquals(
        0,
        bd("12.345678")
            .compareTo(accounts.findByOrganizationId(o.getId()).orElseThrow().getRebatePaid()));
    mvc.perform(get("/api/v1/organizations/"+o.getId()+"/lenddesk/ledger"))
        .andExpect(jsonPath("$.data.content[0].loanId").value(loan.getId()))
        .andExpect(jsonPath("$.data.content[0].borrowerName").value(c.getName()));
    String path = "/api/v1/organizations/" + o.getId() + "/loans/" + loan.getId();
    for (String resource : List.of("rebate-accruals", "rebate-settlements", "rebate-payments")) {
      mvc.perform(get(path + "/" + resource))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.totalElements").value(1));
    }
  }

  @Test
  void marginCallSnapshotsDoNotTriggerLocalDefaultsAndStaleVersionsAreIgnored() {
    var o = organization();
    var c = connection(o);
    long s = security("100");
    var loan = activeLoan(o, c, s, true);
    long id = ids.incrementAndGet();
    var data =
        new LinkedHashMap<String, Object>(
            Map.of(
                "loanId",
                loan.getId(),
                "externalId",
                "MC-1",
                "amount",
                "100",
                "fulfilledAmount",
                "0",
                "reason",
                "Owner valuation",
                "status",
                "OPEN",
                "dueAt",
                Instant.now().minusSeconds(3600)));
    lifecycle(o, "MARGIN_CALL_UPDATED", id, 1, data);
    assertEquals(0, defaults.count());
    data.put("status", "OVERDUE");
    lifecycle(o, "MARGIN_CALL_UPDATED", id, 2, data);
    data.put("status", "OPEN");
    lifecycle(o, "MARGIN_CALL_UPDATED", id, 1, data);
    assertEquals(
        "OVERDUE",
        calls.findByOrganizationIdAndOwnerReferenceId(o.getId(), id).orElseThrow().getStatus());
  }

  @Test
  void confirmingMarginCollateralPublishesPartialAndFulfilledSnapshotsOnce() throws Exception {
    var o = organization();
    var c = connection(o);
    long borrowedSecurity = security("100");
    long pledgedSecurity = security("50");
    inventory.create(o.getId(), new InventoryRequest(borrowedSecurity, 1000, "Initial custody"));
    agreement(o, c, pledgedSecurity, true);
    reservation(o, c, borrowedSecurity, 100);
    var loan = pendingLoan(o, c, 100);
    collateral.confirm(o.getId(), positions.findByLoanId(loan.getId()).get(0).getId(), "INITIAL");
    loanService.decide(o.getId(), loan.getId(), true, "Approved");
    loanService.settle(o.getId(), loan.getId(), "SETTLED");

    long callReference = ids.incrementAndGet();
    lifecycle(
        o,
        "MARGIN_CALL_UPDATED",
        callReference,
        1,
        Map.of(
            "loanId",
            loan.getId(),
            "externalId",
            "MC-" + callReference,
            "amount",
            "200",
            "fulfilledAmount",
            "0",
            "reason",
            "Coverage shortfall",
            "status",
            "OPEN",
            "dueAt",
            Instant.now().plusSeconds(3600)));
    var call =
        calls.findByOrganizationIdAndOwnerReferenceId(o.getId(), callReference).orElseThrow();

    command(
        o,
        "COLLATERAL_PLEDGE_REQUESTED",
        300,
        10,
        new PledgeCommand(401, new AllocationRequest("CASH", null, 0, bd("120"), callReference)));
    var cash = positions.findByConnectionIdAndBorrowerPositionId(c.getId(), 401L).orElseThrow();
    // Simulate a receipt posted by the old code, before it also updated the call.
    cash.setStatus("POSTED");
    cash.setSettlementReference("OLD-CASH-RECEIPT");
    collateral.confirm(o.getId(), cash.getId(), "OLD-CASH-RECEIPT");
    assertEquals("PARTIALLY_FULFILLED", call.getStatus());
    assertEquals(0, bd("120").compareTo(call.getFulfilledAmount()));

    command(
        o,
        "COLLATERAL_PLEDGE_REQUESTED",
        300,
        10,
        new PledgeCommand(
            402,
            new AllocationRequest("SECURITY", pledgedSecurity, 2, BigDecimal.ZERO, callReference)));
    var shares = positions.findByConnectionIdAndBorrowerPositionId(c.getId(), 402L).orElseThrow();
    mvc.perform(
            post("/api/v1/organizations/"
                    + o.getId()
                    + "/collateral/"
                    + shares.getId()
                    + "/confirm")
                .contentType("application/json")
                .content(json.write(new SettlementRequest("SHARES-RECEIPT"))))
        .andExpect(status().isOk());
    collateral.confirm(o.getId(), shares.getId(), "SHARES-RECEIPT");

    assertEquals("FULFILLED", call.getStatus());
    assertEquals(0, bd("200").compareTo(call.getFulfilledAmount()));
    var updates =
        events
            .findByOrganizationId(o.getId(), org.springframework.data.domain.Pageable.unpaged())
            .getContent()
            .stream()
            .filter(row -> row.getTopic().equals("lender.confirmations.v1"))
            .map(row -> json.tree(row.getPayload()))
            .filter(
                event ->
                    "MARGIN_CALL_UPDATED".equals(event.path("type").asText())
                        && event.path("referenceId").asLong() == callReference)
            .sorted(Comparator.comparingLong(event -> event.path("version").asLong()))
            .toList();
    assertEquals(3, updates.size());
    assertEquals("PARTIALLY_FULFILLED", updates.get(1).path("data").path("status").asText());
    var finalUpdate = updates.get(2);
    assertEquals(3L, finalUpdate.path("version").asLong());
    assertEquals(
        loan.getBorrowerLoanId().longValue(), finalUpdate.path("data").path("loanId").asLong());
    assertEquals("FULFILLED", finalUpdate.path("data").path("status").asText());
  }

  @Test
  void confirmationsUseBorrowerIdsAndRemoteOrganizationWithMonotonicResourceVersions() {
    var o = organization();
    var c = connection(o);
    long s = security("100");
    inventory.create(o.getId(), new InventoryRequest(s, 1000, "Custody"));
    agreement(o, c, 0, true);
    var r = reservation(o, c, s, 100);
    reservationService.release(o.getId(), r, false);
    var confirmations =
        events
            .findByOrganizationId(o.getId(), org.springframework.data.domain.Pageable.unpaged())
            .getContent()
            .stream()
            .sorted(Comparator.comparing(OutboxEvent::getId))
            .filter(e -> e.getTopic().equals("lender.confirmations.v1"))
            .map(e -> json.tree(e.getPayload()))
            .filter(e -> e.get("type").asText().startsWith("RESERVATION_"))
            .toList();
    assertEquals(2, confirmations.size());
    assertEquals(200, confirmations.get(0).get("referenceId").asLong());
    assertEquals(1, confirmations.get(0).get("organizationId").asLong());
    assertEquals("BORROWER_DEMO", confirmations.get(0).get("borrowerExternalId").asText());
    assertEquals(1, confirmations.get(0).get("version").asLong());
    assertEquals(2, confirmations.get(1).get("version").asLong());
  }

  @Test
  void tenantScopeAndHttpValidationPreventInvalidOperations() throws Exception {
    var o = organization();
    var second = organization();
    long s = security("100");
    var p = inventory.create(o.getId(), new InventoryRequest(s, 100, "Custody"));
    assertThrows(
        ResponseStatusException.class,
        () ->
            inventory.adjust(
                second.getId(), p.getId(), new InventoryAdjustmentRequest(1, "Wrong tenant")));
    mvc.perform(
            post("/api/v1/organizations/" + o.getId() + "/inventory")
                .contentType("application/json")
                .content("{\"securityId\":1,\"quantity\":-1,\"reference\":\"invalid\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/organizations/" + o.getId() + "/loans?size=201"))
        .andExpect(status().isBadRequest());
    mvc.perform(post("/api/v1/organizations/" + o.getId() + "/margin-calls"))
        .andExpect(status().isMethodNotAllowed());
    mvc.perform(post("/api/v1/organizations/" + o.getId() + "/loans/1/accrue"))
        .andExpect(status().isNotFound());
  }

  @Test
  void offlineOutboxStillDeliversAllWebsocketInvalidationsWithoutMarkingKafkaPublished() {
    var o = organization();
    for (int i = 0; i < 10 && !outbox.pending().isEmpty(); i++)
      for (var key : outbox.pending()) outbox.publishNext(key);
    var rows =
        events
            .findByOrganizationId(o.getId(), org.springframework.data.domain.Pageable.unpaged())
            .getContent();
    assertTrue(rows.stream().allMatch(OutboxEvent::getWsSent));
    assertTrue(rows.stream().noneMatch(OutboxEvent::getPublished));
  }

  @Test
  void ownerRequestRetriesIgnoreObjectOrderButCannotChangeTargetLoan() {
    var o = organization();
    var c = connection(o);
    long security = security("100");
    inventory.create(o.getId(), new InventoryRequest(security, 1000, "Custody"));
    agreement(o, c, 0, true);
    reservation(o, c, security, 200);
    var first = pendingLoan(o, c, 100);
    collateral.confirm(
        o.getId(), positions.findByLoanId(first.getId()).get(0).getId(), "CASH-FIRST");
    loanService.decide(o.getId(), first.getId(), true, "Approved");
    loanService.settle(o.getId(), first.getId(), "FIRST");
    var allocation = new AllocationRequest("CASH", null, 0, bd("11000"), null);
    command(o, "COLLATERAL_PLEDGE_REQUESTED", 301, 10, new PledgeCommand(401, allocation));
    command(o, "LOAN_REQUESTED", 301, 10, new LoanCommand(200, 100, List.of(allocation)));
    var second = loans.findByConnectionIdAndBorrowerLoanId(c.getId(), 301L).orElseThrow();
    collateral.confirm(
        o.getId(), positions.findByLoanId(second.getId()).get(0).getId(), "CASH-SECOND");
    loanService.decide(o.getId(), second.getId(), true, "Approved");
    loanService.settle(o.getId(), second.getId(), "SECOND");
    var body = new RecallRequest(10, "Portfolio sale", "stable-client-id");
    var request = lifecycleRequests.recall(o.getId(), first.getId(), body);
    var fields = new ArrayList<>(json.map(json.tree(request.getPayload())).entrySet());
    Collections.reverse(fields);
    var reordered = new LinkedHashMap<String, Object>();
    fields.forEach(e -> reordered.put(e.getKey(), e.getValue()));
    request.setPayload(json.write(reordered));
    assertEquals(request.getId(), lifecycleRequests.recall(o.getId(), first.getId(), body).getId());
    assertThrows(
        ResponseStatusException.class,
        () -> lifecycleRequests.recall(o.getId(), second.getId(), body));
  }

  LoanDefault reportDefaultForBuyinTest(Organization org, Loan loan, long ownerId, long obligationId) {
    lifecycle(org, "DEFAULT_REPORTED", ownerId, 1, Map.of(
        "loanId", loan.getId(), "obligationId", obligationId, "obligationType", "RECALL",
        "reason", "Recall deadline elapsed", "status", "OPEN"));
    return defaults.findByOrganizationIdAndOwnerReferenceId(org.getId(), ownerId).orElseThrow();
  }

  @Test
  void buyinScreenKeepsOwnerReferencesAndRequestsSeparate() throws Exception {
    var org = organization(); var connection = connection(org);
    var loan = activeLoan(org, connection, security("100"), true);
    long recallOwner = ids.incrementAndGet();
    lifecycle(org, "RECALL_UPDATED", recallOwner, 1, Map.of("loanId", loan.getId(), "quantity", 20,
        "fulfilledQuantity", 5, "dueAt", Instant.now().minusSeconds(3600), "reason", "Recall", "status", "OVERDUE", "externalId", "REC-SCREEN"));
    var first = reportDefaultForBuyinTest(org, loan, ids.incrementAndGet(), recallOwner);
    var second = reportDefaultForBuyinTest(org, loan, ids.incrementAndGet(), ids.incrementAndGet());
    var request = lifecycleRequests.buyIn(org.getId(), first.getId(), new LifecycleActionRequest("screen-buyin"));
    var response = mvc.perform(get("/api/v1/organizations/" + org.getId() + "/lenddesk/defaults-buyins"))
        .andExpect(status().isOk()).andReturn();
    var rows = json.tree(response.getResponse().getContentAsString()).path("data");
    assertEquals(2, rows.size());
    for (var row : rows) {
      if (row.path("default").path("id").asLong() == first.getId()) {
        assertEquals(request.getId().longValue(), row.path("requests").get(0).path("id").asLong());
        assertEquals(recallOwner, row.path("recall").path("ownerReferenceId").asLong());
        assertEquals(first.getOwnerReferenceId().longValue(), row.path("default").path("ownerReferenceId").asLong());
      } else {
        assertEquals(second.getId().longValue(), row.path("default").path("id").asLong());
        assertEquals(0, row.path("requests").size());
        assertTrue(row.path("recall").isNull());
      }
    }
    var other = organization();
    mvc.perform(get("/api/v1/organizations/" + other.getId() + "/lenddesk/defaults-buyins"))
        .andExpect(jsonPath("$.data.length()").value(0));
    mvc.perform(post("/api/v1/organizations/" + other.getId() + "/defaults/" + first.getId() + "/buy-in-requests")
        .contentType("application/json").content("{\"clientRequestId\":\"foreign\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void buyinRequestsAreIdempotentAndRejectedRequestsCanBeRetried() {
    var org = organization(); var connection = connection(org);
    var loan = activeLoan(org, connection, security("100"), true);
    var def = reportDefaultForBuyinTest(org, loan, ids.incrementAndGet(), ids.incrementAndGet());
    var request = lifecycleRequests.buyIn(org.getId(), def.getId(), new LifecycleActionRequest("buyin-first"));
    assertEquals("PENDING", request.getStatus());
    assertEquals(request.getId(), lifecycleRequests.buyIn(org.getId(), def.getId(), new LifecycleActionRequest("buyin-first")).getId());
    assertEquals(request.getId(), lifecycleRequests.buyIn(org.getId(), def.getId(), new LifecycleActionRequest("buyin-second-click")).getId());
    var second = reportDefaultForBuyinTest(org, loan, ids.incrementAndGet(), ids.incrementAndGet());
    assertThrows(ResponseStatusException.class, () -> lifecycleRequests.buyIn(org.getId(), second.getId(), new LifecycleActionRequest("buyin-first")));
    lifecycle(org, "LIFECYCLE_REQUEST_REJECTED", request.getId(), 1, Map.of("reason", "No execution venue available"));
    assertEquals("REJECTED", request.getStatus()); assertEquals("No execution venue available", request.getFailureReason());
    var retry = lifecycleRequests.buyIn(org.getId(), def.getId(), new LifecycleActionRequest("buyin-retry"));
    assertNotEquals(request.getId(), retry.getId()); assertEquals("PENDING", retry.getStatus());
    def.setStatus("RESOLVED");
    assertEquals(retry.getId(), lifecycleRequests.buyIn(org.getId(), def.getId(), new LifecycleActionRequest("buyin-retry")).getId());
    assertThrows(ResponseStatusException.class, () -> lifecycleRequests.buyIn(org.getId(), def.getId(), new LifecycleActionRequest("buyin-closed")));
  }

  @Test
  void buyinReportsCompleteOnlyTheirDefaultAndDoNotSettleLoanOrCash() throws Exception {
    var org = organization(); var connection = connection(org);
    var loan = activeLoan(org, connection, security("100"), true);
    var def = reportDefaultForBuyinTest(org, loan, ids.incrementAndGet(), ids.incrementAndGet());
    var request = lifecycleRequests.buyIn(org.getId(), def.getId(), new LifecycleActionRequest("buyin-report"));
    var initialCash = accounts.findByOrganizationId(org.getId()).orElseThrow().getCashCollateralHeld();
    var data = new LinkedHashMap<String, Object>(Map.of("loanId", loan.getId(), "defaultId", def.getOwnerReferenceId(),
        "quantity", 20, "price", "101", "costs", "5", "collateralApplied", "2025", "residualBalance", "0", "status", "COMPLETED", "lifecycleRequestId", request.getId()));
    lifecycle(org, "BUY_IN_REPORTED", ids.incrementAndGet(), 1, data);
    assertEquals("COMPLETED", request.getStatus());
    assertEquals("ACTIVE", loans.findById(loan.getId()).orElseThrow().getStatus());
    assertEquals(initialCash, accounts.findByOrganizationId(org.getId()).orElseThrow().getCashCollateralHeld());
    assertThrows(ResponseStatusException.class, () -> lifecycleRequests.buyIn(org.getId(), def.getId(), new LifecycleActionRequest("duplicate-after-report")));
    var response = mvc.perform(get("/api/v1/organizations/" + org.getId() + "/lenddesk/defaults-buyins")).andExpect(status().isOk()).andReturn();
    assertEquals(20L, json.tree(response.getResponse().getContentAsString()).path("data").get(0).path("buyIns").get(0).path("quantity").asLong());
    data.put("defaultId", def.getOwnerReferenceId() + 1);
    assertThrows(ResponseStatusException.class, () -> lifecycle(org, "BUY_IN_REPORTED", ids.incrementAndGet(), 1, data));
  }

  @Test
  void exhaustedBuyinDeliveryFailsRequestAndNewAttemptRetiresFailedCommand() {
    var org = organization(); var connection = connection(org);
    var loan = activeLoan(org, connection, security("100"), true);
    var def = reportDefaultForBuyinTest(org, loan, ids.incrementAndGet(), ids.incrementAndGet());
    var request = lifecycleRequests.buyIn(org.getId(), def.getId(), new LifecycleActionRequest("buyin-delivery-first"));
    var event = events.findAll().stream().filter(e -> e.getOrganizationId().equals(org.getId()) && json.tree(e.getPayload()).path("type").asText().equals("BUY_IN_REQUESTED")).findFirst().orElseThrow();
    event.setStatus("FAILED");
    BuyinDeliveryState.fail(valuationEm, event, "Kafka unavailable after retries");
    valuationEm.refresh(request);
    assertEquals("FAILED", request.getStatus()); assertEquals("Kafka unavailable after retries", request.getFailureReason());
    var retry = lifecycleRequests.buyIn(org.getId(), def.getId(), new LifecycleActionRequest("buyin-delivery-retry"));
    assertEquals("PENDING", retry.getStatus()); assertNotEquals(request.getId(), retry.getId());
    assertEquals("CLOSED", request.getStatus()); assertEquals("CLOSED", event.getStatus());
    assertEquals("OPEN", def.getStatus());
  }

  @Test
  void executionEndpointIsTenantScopedIdempotentAndCompletesBothRequests() throws Exception {
    var org = organization();
    var connection = connection(org);
    var loan = activeLoan(org, connection, security("100"), true);
    var def = reportDefaultForBuyinTest(
      org,
      loan,
      ids.incrementAndGet(),
      ids.incrementAndGet()
    );
    var parent = lifecycleRequests.buyIn(
      org.getId(),
      def.getId(),
      new LifecycleActionRequest("parent-execution")
    );
    var request = new BuyinExecutionRequest(
      "execution-one",
      parent.getId(),
      20,
      bd("101"),
      bd("5"),
      "TRADE-42"
    );
    assertThrows(ResponseStatusException.class, () ->
      lifecycleRequests.reportBuyin(org.getId(), def.getId(), request)
    );
    lifecycle(
      org,
      "LIFECYCLE_REQUEST_ACCEPTED",
      parent.getId(),
      1,
      Map.of("loanId", loan.getId())
    );
    String path =
      "/api/v1/organizations/" +
      org.getId() +
      "/defaults/" +
      def.getId() +
      "/buy-in-executions";
    var response = mvc
      .perform(
        post(path).contentType("application/json").content(json.write(request))
      )
      .andExpect(status().isOk())
      .andReturn();
    long executionId = json
      .tree(response.getResponse().getContentAsString())
      .path("data")
      .path("id")
      .asLong();
    assertEquals(
      executionId,
      lifecycleRequests.reportBuyin(org.getId(), def.getId(), request).getId()
    );
    assertEquals(
      executionId,
      lifecycleRequests
        .reportBuyin(
          org.getId(),
          def.getId(),
          new BuyinExecutionRequest(
            "execution-double-click",
            parent.getId(),
            20,
            bd("101"),
            bd("5"),
            "TRADE-42"
          )
        )
        .getId()
    );
    var other = organization();
    mvc
      .perform(
        post(
          "/api/v1/organizations/" +
            other.getId() +
            "/defaults/" +
            def.getId() +
            "/buy-in-executions"
        )
          .contentType("application/json")
          .content(json.write(request))
      )
      .andExpect(status().isNotFound());
    mvc
      .perform(
        post(path)
          .contentType("application/json")
          .content(
            json.write(
              new BuyinExecutionRequest(
                "negative",
                parent.getId(),
                20,
                bd("-1"),
                bd("0"),
                "x"
              )
            )
          )
      )
      .andExpect(status().isBadRequest());
    var executionEvent = events
      .findAll()
      .stream()
      .filter(
        e ->
          json
            .tree(e.getPayload())
            .path("type")
            .asText()
            .equals("BUY_IN_EXECUTION_REQUESTED") &&
          json.tree(e.getPayload()).path("referenceId").asLong() == executionId
      )
      .findFirst()
      .orElseThrow();
    executionEvent.setStatus("FAILED");
    BuyinDeliveryState.fail(valuationEm, executionEvent, "Kafka unavailable");
    valuationEm.refresh(valuationEm.find(LifecycleRequest.class, executionId));
    assertEquals(
      "FAILED",
      valuationEm.find(LifecycleRequest.class, executionId).getStatus()
    );
    assertEquals("ACCEPTED", parent.getStatus());
    BuyinDeliveryState.decide(valuationEm, executionEvent, true);
    valuationEm.refresh(valuationEm.find(LifecycleRequest.class, executionId));
    assertEquals(
      "PENDING",
      valuationEm.find(LifecycleRequest.class, executionId).getStatus()
    );
    var data = new LinkedHashMap<String, Object>();
    data.put("loanId", loan.getId());
    data.put("defaultId", def.getOwnerReferenceId());
    data.put("lifecycleRequestId", parent.getId());
    data.put("executionRequestId", executionId);
    data.put("quantity", 20);
    data.put("price", "101");
    data.put("costs", "5");
    data.put("collateralApplied", "0");
    data.put("residualBalance", "2025");
    data.put("status", "EXECUTED");
    data.put("executionReference", "TRADE-42");
    long report = ids.incrementAndGet();
    lifecycle(org, "BUY_IN_REPORTED", report, 1, data);
    lifecycle(org, "BUY_IN_REPORTED", report, 1, data);
    assertEquals("COMPLETED", parent.getStatus());
    assertEquals(
      "COMPLETED",
      valuationEm.find(LifecycleRequest.class, executionId).getStatus()
    );
    var snapshot = mvc
      .perform(
        get(
          "/api/v1/organizations/" + org.getId() + "/lenddesk/defaults-buyins"
        )
      )
      .andExpect(status().isOk())
      .andReturn();
    var row = json
      .tree(snapshot.getResponse().getContentAsString())
      .path("data")
      .get(0);
    assertEquals(2, row.path("requests").size());
    assertEquals(1, row.path("buyIns").size());
    assertEquals(
      "TRADE-42",
      row.path("buyIns").get(0).path("executionReference").asText()
    );
    assertEquals("ACTIVE", loan.getStatus());
    assertEquals("OPEN", def.getStatus());
    assertThrows(ResponseStatusException.class, () ->
      lifecycleRequests.reportBuyin(
        org.getId(),
        def.getId(),
        new BuyinExecutionRequest(
          "another",
          parent.getId(),
          20,
          bd("101"),
          bd("5"),
          "TRADE-43"
        )
      )
    );
  }

  Map<String, Object> orderFill(BuyinOrder order, LoanDefault def) {
    var data = new LinkedHashMap<String, Object>();
    data.put("loanId", order.getLoanId());
    data.put("defaultId", def.getOwnerReferenceId());
    data.put("lifecycleRequestId", order.getBuyinRequestId());
    data.put("executionRequestId", order.getExecutionRequestId());
    data.put("quantity", order.getQuantity());
    data.put("price", order.getExecutionPrice());
    data.put("costs", order.getCosts());
    data.put("collateralApplied", "0");
    data.put(
      "residualBalance",
      order
        .getExecutionPrice()
        .multiply(BigDecimal.valueOf(order.getQuantity()))
        .add(order.getCosts())
    );
    data.put("status", "EXECUTED");
    data.put("executionReference", order.getExecutionReference());
    return data;
  }

  @Test
  void securitiesCollateralMustBeSoldBeforeBuyinAndProceedsSettleClosureExactlyOnce() throws Exception {
    var org=organization();var conn=connection(org);
    long security=security("100"), collateralSecurity=security("200");
    inventory.create(org.getId(),new InventoryRequest(security,1000,"Custody"));
    agreement(org,conn,collateralSecurity,true);reservation(org,conn,security,100);
    var allocation=new AllocationRequest("SECURITY",collateralSecurity,60,BigDecimal.ZERO,null);
    command(org,"COLLATERAL_PLEDGE_REQUESTED",300,10,new PledgeCommand(400,allocation));
    command(org,"LOAN_REQUESTED",300,10,new LoanCommand(200,100,List.of(allocation)));
    var loan=loans.findByConnectionIdAndBorrowerLoanId(conn.getId(),300L).orElseThrow();
    var lot=positions.findByConnectionIdAndBorrowerPositionId(conn.getId(),400L).orElseThrow();
    collateral.confirm(org.getId(),lot.getId(),"SECURITY-CUSTODY");loanService.decide(org.getId(),loan.getId(),true,"Approved");loanService.settle(org.getId(),loan.getId(),"SETTLED");
    var def=reportDefaultForBuyinTest(org,loan,ids.incrementAndGet(),ids.incrementAndGet());
    var parent=lifecycleRequests.buyIn(org.getId(),def.getId(),new LifecycleActionRequest("security-buyin"));
    lifecycle(org,"LIFECYCLE_REQUEST_ACCEPTED",parent.getId(),1,Map.of("loanId",loan.getId()));
    var request=new BuyinOrderRequest("funded-order",parent.getId(),"MARKET",100,null,bd("5"));
    assertThrows(ResponseStatusException.class,()->buyinOrders.place(org.getId(),def.getId(),request));
    String saleScope=(String)liquidation.preview(org.getId(),def.getId()).get("saleScope");
    lot.setQuantity(61L);
    assertThrows(ResponseStatusException.class,()->liquidation.sell(org.getId(),def.getId(),"sell-once",saleScope));
    lot.setQuantity(60L);
    // Missing market data must not sell stock or change its cash ledger.
    var securityRow=securityMasterForScreenTest.findById(collateralSecurity).orElseThrow();
    var quoteAt=securityRow.getPriceAt();securityRow.setPriceAt(null);
    assertThrows(ResponseStatusException.class,()->liquidation.sell(org.getId(),def.getId(),"sell-once",saleScope));
    assertEquals("SECURITY",lot.getAssetType());assertEquals(0,liquidation.cash(loan.getId()).signum());
    securityRow.setPriceAt(quoteAt);
    var sale=liquidation.sell(org.getId(),def.getId(),"sell-once",saleScope);
    assertEquals(0,bd("12000").compareTo(sale.getProceeds()));
    assertEquals(sale.getId(),liquidation.sell(org.getId(),def.getId(),"sell-once",saleScope).getId());
    assertEquals("CASH",lot.getAssetType());assertNull(lot.getSecurityId());assertEquals(0,lot.getQuantity());
    assertEquals(0,bd("12000").compareTo(accounts.findByOrganizationId(org.getId()).orElseThrow().getCashCollateralHeld()));
    assertThrows(ResponseStatusException.class,()->liquidation.requireCashFunding(loan.getId(),100,bd("200"),bd("5")));
    var order=buyinOrders.place(org.getId(),def.getId(),request);
    assertEquals("EXECUTION_PENDING",order.getStatus());
    lifecycle(org,"BUY_IN_REPORTED",ids.incrementAndGet(),1,orderFill(order,def));
    assertEquals("FILLED",order.getStatus());
    var closed=buyinCloseouts.close(org.getId(),def.getId(),"SECURITY-CLOSE");
    assertEquals("CLOSED",loan.getStatus());assertEquals(0,bd("1995").compareTo(closed.getCashCollateralReturned()));
    assertEquals(0,accounts.findByOrganizationId(org.getId()).orElseThrow().getCashCollateralHeld().signum());
    assertEquals(sale.getId(),liquidation.sell(org.getId(),def.getId(),"sell-once",saleScope).getId());
    assertEquals(1,events.findByOrganizationId(org.getId(),Pageable.unpaged()).stream().map(e->json.tree(e.getPayload()))
        .filter(e->"COLLATERAL_LIQUIDATED".equals(e.path("type").asText())).count());
    mvc.perform(post("/api/v1/organizations/"+organization().getId()+"/defaults/"+def.getId()+"/sell-collateral")
        .contentType("application/json").content("{\"reference\":\"cross-tenant\",\"saleScope\":\"scope\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void buyinMarketOrderUsesOlderQuoteCreditsInventoryAndAuditsExactlyOnce() throws Exception {
    var org = organization();
    var conn = connection(org);
    var loan = activeLoan(org, conn, security("100"), true);
    var def = reportDefaultForBuyinTest(
      org,
      loan,
      ids.incrementAndGet(),
      ids.incrementAndGet()
    );
    var parent = lifecycleRequests.buyIn(
      org.getId(),
      def.getId(),
      new LifecycleActionRequest("market-parent")
    );
    lifecycle(
      org,
      "LIFECYCLE_REQUEST_ACCEPTED",
      parent.getId(),
      1,
      Map.of("loanId", loan.getId())
    );
    var cachedQuote = securityMasterForScreenTest
      .findById(loan.getSecurityId())
      .orElseThrow();
    cachedQuote.setPriceAt(Instant.now().minusSeconds(86400));
    var stockBefore = inventory.position(org.getId(), loan.getSecurityId());
    long total = stockBefore.getTotalQuantity(),
      available = stockBefore.getAvailableQuantity(),
      loaned = stockBefore.getLoanedQuantity(),
      outstanding = loan.getOutstandingQuantity();
    var request = new BuyinOrderRequest(
      "market-order",
      parent.getId(),
      "MARKET",
      20,
      null,
      bd("5")
    );
    var order = buyinOrders.place(org.getId(), def.getId(), request);
    var http = mvc
      .perform(
        post(
          "/api/v1/organizations/" +
            org.getId() +
            "/defaults/" +
            def.getId() +
            "/buy-in-orders"
        )
          .contentType("application/json")
          .content(json.write(request))
      )
      .andExpect(status().isOk())
      .andReturn();
    assertEquals(
      order.getId().longValue(),
      json
        .tree(http.getResponse().getContentAsString())
        .path("data")
        .path("id")
        .asLong()
    );
    assertEquals("EXECUTION_PENDING", order.getStatus());
    assertEquals(cachedQuote.getPriceAt(), order.getQuoteAt());
    assertNull(order.getFailureReason());
    assertEquals(
      order.getId(),
      buyinOrders.place(org.getId(), def.getId(), request).getId()
    );
    assertEquals(total, stockBefore.getTotalQuantity());
    assertThrows(ResponseStatusException.class, () ->
      lifecycleRequests.reportBuyin(
        org.getId(),
        def.getId(),
        new BuyinExecutionRequest(
          "manual-overlap",
          parent.getId(),
          20,
          bd("101"),
          bd("5"),
          "OTHER"
        )
      )
    );
    long reportId = ids.incrementAndGet();
    var payload = orderFill(order, def);
    lifecycle(org, "BUY_IN_REPORTED", reportId, 1, payload);
    lifecycle(org, "BUY_IN_REPORTED", reportId, 1, payload);
    assertEquals("FILLED", order.getStatus());
    assertEquals(total + 20, stockBefore.getTotalQuantity());
    assertEquals(available + 20, stockBefore.getAvailableQuantity());
    assertEquals(loaned, stockBefore.getLoanedQuantity());
    assertEquals(outstanding, loan.getOutstandingQuantity());
    assertEquals("ACTIVE", loan.getStatus());
    var report = buyinReports
      .findByOrganizationIdAndOwnerReferenceId(org.getId(), reportId)
      .orElseThrow();
    assertNotNull(report.getInventoryReceivedAt());
    buyinReceipts.receive(
      org.getId(),
      report.getId(),
      order.getExecutionReference()
    );
    assertEquals(
      1,
      inventoryHistory
        .findByOrganizationId(org.getId(), Pageable.unpaged())
        .stream()
        .filter(h -> h.getType().equals("BUY_IN_SECURITIES_RECEIVED"))
        .count()
    );
    var entries = purchaseLedger
      .findByOrganizationId(org.getId(), Pageable.unpaged())
      .stream()
      .filter(l -> l.getType().equals("BUY_IN_PURCHASE_PAYABLE"))
      .toList();
    assertEquals(1, entries.size());
    assertEquals(0, bd("2005").compareTo(entries.get(0).getAmount()));
    assertEquals(0, bd("5").compareTo(entries.get(0).getExecutionFeeAmount()));
    assertTrue(
      events
        .findByOrganizationId(org.getId(), Pageable.unpaged())
        .stream()
        .anyMatch(e ->
          json
            .tree(e.getPayload())
            .path("type")
            .asText()
            .equals("BUY_IN_ORDER_FILLED")
        )
    );
    assertEquals(
      1L,
      valuationEm
        .createQuery(
          "select count(a) from AuditEvent a where a.organizationId=:org and a.type='BUY_IN_ORDER_FILLED'",
          Long.class
        )
        .setParameter("org", org.getId())
        .getSingleResult()
    );
    mvc
      .perform(
        post(
          "/api/v1/organizations/" +
            org.getId() +
            "/buy-ins/" +
            report.getId() +
            "/inventory-receipts"
        )
          .contentType("application/json")
          .content(
            json.write(Map.of("reference", order.getExecutionReference()))
          )
      )
      .andExpect(status().isOk());
    assertThrows(ResponseStatusException.class, () ->
      buyinOrders.cancel(org.getId(), order.getId())
    );
    var other = organization();
    mvc
      .perform(
        post(
          "/api/v1/organizations/" +
            other.getId() +
            "/buy-in-orders/" +
            order.getId() +
            "/cancel"
        )
      )
      .andExpect(status().isNotFound());
    var response = mvc
      .perform(
        get(
          "/api/v1/organizations/" + org.getId() + "/lenddesk/defaults-buyins"
        )
      )
      .andExpect(status().isOk())
      .andReturn();
    var row = json
      .tree(response.getResponse().getContentAsString())
      .path("data")
      .get(0);
    assertEquals("FILLED", row.path("orders").get(0).path("status").asText());
    assertFalse(row.path("buyIns").get(0).path("inventoryReceivedAt").isNull());
  }

  @Test
  void buyinLimitOrderWaitsCancelsAndMatchesOnlyAtOrBelowLimit() {
    var org = organization();
    var conn = connection(org);
    var loan = activeLoan(org, conn, security("100"), true);
    var def = reportDefaultForBuyinTest(
      org,
      loan,
      ids.incrementAndGet(),
      ids.incrementAndGet()
    );
    var parent = lifecycleRequests.buyIn(
      org.getId(),
      def.getId(),
      new LifecycleActionRequest("limit-parent")
    );
    lifecycle(
      org,
      "LIFECYCLE_REQUEST_ACCEPTED",
      parent.getId(),
      1,
      Map.of("loanId", loan.getId())
    );
    var order = buyinOrders.place(
      org.getId(),
      def.getId(),
      new BuyinOrderRequest(
        "limit-cancel",
        parent.getId(),
        "LIMIT",
        20,
        bd("90"),
        bd("0")
      )
    );
    assertEquals("PENDING", order.getStatus());
    assertNull(order.getExecutionRequestId());
    assertThrows(ResponseStatusException.class, () ->
      buyinOrders.place(
        org.getId(),
        def.getId(),
        new BuyinOrderRequest(
          "duplicate-pending",
          parent.getId(),
          "MARKET",
          20,
          null,
          bd("0")
        )
      )
    );
    buyinOrders.cancel(org.getId(), order.getId());
    buyinOrders.cancel(org.getId(), order.getId());
    assertEquals("CANCELLED", order.getStatus());
    var replacement = buyinOrders.place(
      org.getId(),
      def.getId(),
      new BuyinOrderRequest(
        "limit-fill",
        parent.getId(),
        "LIMIT",
        20,
        bd("90"),
        bd("0")
      )
    );
    assertEquals("PENDING", replacement.getStatus());
    market.quote(loan.getSecurityId(), bd("89"), Instant.now());
    buyinOrders.match(org.getId(), replacement.getId());
    assertEquals("EXECUTION_PENDING", replacement.getStatus());
    assertEquals(0, bd("89").compareTo(replacement.getExecutionPrice()));
    assertThrows(ResponseStatusException.class, () ->
      buyinOrders.cancel(org.getId(), replacement.getId())
    );
  }

  @Test
  void buyinLimitUsesOlderQuoteAndRejectsChangedObligationsWithoutCreditingInventory() {
    var org = organization();
    var conn = connection(org);
    var loan = activeLoan(org, conn, security("100"), true);
    var def = reportDefaultForBuyinTest(
      org,
      loan,
      ids.incrementAndGet(),
      ids.incrementAndGet()
    );
    var parent = lifecycleRequests.buyIn(
      org.getId(),
      def.getId(),
      new LifecycleActionRequest("stale-parent")
    );
    lifecycle(
      org,
      "LIFECYCLE_REQUEST_ACCEPTED",
      parent.getId(),
      1,
      Map.of("loanId", loan.getId())
    );
    var sec = securityMasterForScreenTest
      .findById(loan.getSecurityId())
      .orElseThrow();
    sec.setPriceAt(Instant.now().minusSeconds(600));
    long total = inventory
      .position(org.getId(), loan.getSecurityId())
      .getTotalQuantity();
    var order = buyinOrders.place(
      org.getId(),
      def.getId(),
      new BuyinOrderRequest(
        "stale-order",
        parent.getId(),
        "LIMIT",
        20,
        bd("90"),
        bd("0")
      )
    );
    assertEquals("PENDING", order.getStatus());
    assertNull(order.getFailureReason());
    assertNull(order.getExecutionRequestId());
    loan.setOutstandingQuantity(10L);
    market.quote(loan.getSecurityId(), bd("100"), Instant.now());
    buyinOrders.match(org.getId(), order.getId());
    assertEquals("REJECTED", order.getStatus());
    assertEquals(
      total,
      inventory.position(org.getId(), loan.getSecurityId()).getTotalQuantity()
    );
  }

  @Test
  void externalBuyinReceiptRequiresConfirmationAndCannotCreditTwice() {
    var org = organization();
    var conn = connection(org);
    var loan = activeLoan(org, conn, security("100"), true);
    var def = reportDefaultForBuyinTest(
      org,
      loan,
      ids.incrementAndGet(),
      ids.incrementAndGet()
    );
    var parent = lifecycleRequests.buyIn(
      org.getId(),
      def.getId(),
      new LifecycleActionRequest("external-parent")
    );
    long total = inventory
      .position(org.getId(), loan.getSecurityId())
      .getTotalQuantity();
    var payload = new LinkedHashMap<String, Object>(
      Map.of(
        "loanId",
        loan.getId(),
        "defaultId",
        def.getOwnerReferenceId(),
        "quantity",
        20,
        "price",
        "100",
        "costs",
        "0",
        "collateralApplied",
        "0",
        "residualBalance",
        "2000",
        "status",
        "EXECUTED",
        "lifecycleRequestId",
        parent.getId()
      )
    );
    payload.put("executionReference", "EXTERNAL-TRADE");
    long owner = ids.incrementAndGet();
    lifecycle(org, "BUY_IN_REPORTED", owner, 1, payload);
    var report = buyinReports
      .findByOrganizationIdAndOwnerReferenceId(org.getId(), owner)
      .orElseThrow();
    assertEquals(
      total,
      inventory.position(org.getId(), loan.getSecurityId()).getTotalQuantity()
    );
    buyinReceipts.receive(org.getId(), report.getId(), "CUSTODY-42");
    buyinReceipts.receive(org.getId(), report.getId(), "CUSTODY-42");
    assertEquals(
      total + 20,
      inventory.position(org.getId(), loan.getSecurityId()).getTotalQuantity()
    );
    assertThrows(ResponseStatusException.class, () ->
      buyinReceipts.receive(org.getId(), report.getId(), "OTHER-CUSTODY")
    );
    var other = organization();
    assertThrows(ResponseStatusException.class, () ->
      buyinReceipts.receive(other.getId(), report.getId(), "CUSTODY-42")
    );
  }

  BuyIn receivedBuyinForCloseout(
    Organization org,
    Loan loan,
    LoanDefault def,
    long quantity,
    String price
  ) {
    var parent = lifecycleRequests.buyIn(
      org.getId(),
      def.getId(),
      new LifecycleActionRequest("close-parent-" + ids.incrementAndGet())
    );
    var data = new LinkedHashMap<String, Object>(
      Map.of(
        "loanId",
        loan.getId(),
        "defaultId",
        def.getOwnerReferenceId(),
        "quantity",
        quantity,
        "price",
        price,
        "costs",
        "5",
        "collateralApplied",
        "0",
        "residualBalance",
        "0",
        "status",
        "EXECUTED",
        "lifecycleRequestId",
        parent.getId()
      )
    );
    long owner = ids.incrementAndGet();
    lifecycle(org, "BUY_IN_REPORTED", owner, 1, data);
    var report = buyinReports
      .findByOrganizationIdAndOwnerReferenceId(org.getId(), owner)
      .orElseThrow();
    buyinReceipts.receive(org.getId(), report.getId(), "receipt-" + owner);
    return report;
  }

  @Test
  void buyinLoanCloseoutCanApplyAnEntireCashCollateralLot() throws Exception {
    var org = organization();
    var conn = connection(org);
    var loan = activeLoan(org, conn, security("100"), true);
    var lot = positions.findByConnectionIdAndBorrowerPositionId(conn.getId(), 400L)
        .orElseThrow();
    var def = reportDefaultForBuyinTest(org, loan, ids.incrementAndGet(), ids.incrementAndGet());
    receivedBuyinForCloseout(org, loan, def, 100, "109.95");

    String path = "/api/v1/organizations/" + org.getId()
        + "/defaults/" + def.getId() + "/close-loan";
    mvc.perform(post(path)
        .contentType("application/json")
        .content("{\"reference\":\"FULL-CASH-CLOSE\"}"))
        .andExpect(status().isOk());

    assertEquals("CLOSED", loan.getStatus());
    assertEquals("APPLIED", lot.getStatus());
    assertEquals(0, lot.getCashAmount().signum());
    assertEquals(0, accounts.findByOrganizationId(org.getId()).orElseThrow()
        .getCashCollateralHeld().signum());
  }

  @Test
  void buyinLoanCloseoutAppliesCostReleasesSurplusAndQueuesFinalBillingExactlyOnce() throws Exception {
    var org = organization();
    var conn = connection(org);
    var loan = activeLoan(org, conn, security("100"), true);
    var def = reportDefaultForBuyinTest(
      org,
      loan,
      ids.incrementAndGet(),
      ids.incrementAndGet()
    );
    var receipt = receivedBuyinForCloseout(org, loan, def, 100, "100");
    assertThrows(ResponseStatusException.class, () ->
      lifecycle(
        org,
        "BUY_IN_REPORTED",
        receipt.getOwnerReferenceId(),
        2,
        Map.of(
          "loanId",
          loan.getId(),
          "defaultId",
          def.getOwnerReferenceId(),
          "quantity",
          100,
          "price",
          "200",
          "costs",
          "5",
          "collateralApplied",
          "0",
          "residualBalance",
          "0",
          "status",
          "EXECUTED"
        )
      )
    );
    var stockRow = inventory.position(org.getId(), loan.getSecurityId());
    assertEquals(1100, stockRow.getTotalQuantity());
    long available = stockRow.getAvailableQuantity();
    assertEquals(
      true,
      buyinCloseouts.preview(org.getId(), def.getId()).get("eligible")
    );
    String path =
      "/api/v1/organizations/" +
      org.getId() +
      "/defaults/" +
      def.getId() +
      "/close-loan";
    mvc
      .perform(
        post(path)
          .contentType("application/json")
          .content("{\"reference\":\"CLOSE-42\"}")
      )
      .andExpect(status().isOk());
    var closed = buyinCloseouts.close(org.getId(), def.getId(), "CLOSE-42");
    assertEquals(
      1,
      buyinCloseoutRows
        .findAll()
        .stream()
        .filter(c -> c.getLoanId().equals(loan.getId()))
        .count()
    );
    assertEquals("CLOSED", loan.getStatus());
    assertEquals(0, loan.getOutstandingQuantity());
    assertNotNull(loan.getClosedAt());
    assertEquals(100, receipt.getCloseoutQuantity());
    assertEquals(1000, stockRow.getTotalQuantity());
    assertEquals(0, stockRow.getLoanedQuantity());
    assertEquals(available, stockRow.getAvailableQuantity());
    assertEquals(0, bd("10005").compareTo(closed.getCashCollateralApplied()));
    assertEquals(0, bd("995").compareTo(closed.getCashCollateralReturned()));
    var settlement = purchaseLedger.findByOrganizationId(org.getId(), Pageable.unpaged()).stream()
        .filter(e -> "BUY_IN_PAYABLE_SETTLED".equals(e.getType())).findFirst().orElseThrow();
    assertEquals(0, bd("5").compareTo(settlement.getExecutionFeeAmount()));
    mvc.perform(get("/api/v1/organizations/" + org.getId() + "/lenddesk/ledger/overview")
        .param("type", "BUY_IN_PAYABLE_SETTLED"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].executionFeeAmount").value(5))
        .andExpect(jsonPath("$.data.content[0].amount").value(-10005));
    valuationEm.flush();
    valuationEm.createQuery("update LenderAccountLedger e set e.executionFeeAmount=null where e.organizationId=:org")
        .setParameter("org", org.getId()).executeUpdate();
    String migration = new org.springframework.core.io.ClassPathResource("db/migration/V20__buyin_execution_fee_ledger.sql")
        .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    for (int attempt = 0; attempt < 2; attempt++) for (String statement : migration.split(";"))
        if (statement.contains("UPDATE lender_account_ledger")) valuationEm.createNativeQuery(statement).executeUpdate();
    valuationEm.refresh(settlement);
    var repaired = settlement;
    assertEquals(0, bd("5").compareTo(repaired.getExecutionFeeAmount()));
    assertEquals(0, bd("-10005").compareTo(repaired.getAmount()));

    assertEquals(
      0,
      accounts
        .findByOrganizationId(org.getId())
        .orElseThrow()
        .getCashCollateralHeld()
        .signum()
    );
    assertEquals("RESOLVED", def.getStatus());
    assertTrue(
      positions
        .findByLoanId(loan.getId())
        .stream()
        .allMatch(p -> Set.of("APPLIED", "RELEASED").contains(p.getStatus()))
    );
    var all = events
      .findByOrganizationId(org.getId(), Pageable.unpaged())
      .getContent();
    assertEquals(
      1,
      all
        .stream()
        .filter(e ->
          json.tree(e.getPayload()).path("type").asText().equals("LOAN_CLOSED")
        )
        .count()
    );
    assertEquals(
      1,
      all
        .stream()
        .filter(e ->
          json
            .tree(e.getPayload())
            .path("type")
            .asText()
            .equals("COLLATERAL_APPLIED")
        )
        .count()
    );
    assertTrue(
      all
        .stream()
        .anyMatch(
          e ->
            json
              .tree(e.getPayload())
              .path("type")
              .asText()
              .equals("LOAN_STATUS_UPDATED") &&
            json
              .tree(e.getPayload())
              .path("data")
              .path("buyinCloseout")
              .asBoolean()
        )
    );
    var caseSnapshot=mvc.perform(get("/api/v1/organizations/"+org.getId()+"/lenddesk/defaults-buyins")).andExpect(status().isOk()).andReturn();assertEquals(0,bd("10005").compareTo(new BigDecimal(json.tree(caseSnapshot.getResponse().getContentAsString()).path("data").get(0).path("closeout").path("cashCollateralApplied").asText())));assertEquals(0,bd("10000").compareTo(new BigDecimal(json.tree(caseSnapshot.getResponse().getContentAsString()).path("data").get(0).path("closeout").path("purchaseValue").asText())));assertEquals(0,bd("5").compareTo(new BigDecimal(json.tree(caseSnapshot.getResponse().getContentAsString()).path("data").get(0).path("closeout").path("executionCosts").asText())));
    var summaries=all.stream().filter(e->json.tree(e.getPayload()).path("type").asText().equals("BUY_IN_CLOSEOUT_REPORTED")).toList();assertEquals(1,summaries.size());var summary=json.tree(summaries.get(0).getPayload()).path("data");assertEquals(loan.getBorrowerLoanId().longValue(),summary.path("loanId").asLong());assertEquals(0,bd("10000").compareTo(new BigDecimal(summary.path("purchaseValue").asText())));assertEquals(0,bd("5").compareTo(new BigDecimal(summary.path("executionCosts").asText())));assertEquals(0,bd("995").compareTo(new BigDecimal(summary.path("cashCollateralReturned").asText())));assertTrue(closed.isBorrowerSummaryQueued());
    closed.setBorrowerSummaryQueued(false);assertTrue(buyinCloseouts.pendingSummaries().stream().anyMatch(c->c.getId().equals(closed.getId())));buyinCloseouts.publishSummary(org.getId(),closed.getId());buyinCloseouts.publishSummary(org.getId(),closed.getId());assertEquals(2,events.findByOrganizationId(org.getId(),Pageable.unpaged()).stream().filter(e->json.tree(e.getPayload()).path("type").asText().equals("BUY_IN_CLOSEOUT_REPORTED")).count());assertEquals(1000,stockRow.getTotalQuantity());assertEquals(0,accounts.findByOrganizationId(org.getId()).orElseThrow().getCashCollateralHeld().signum());
    assertThrows(ResponseStatusException.class, () ->
      buyinCloseouts.close(org.getId(), def.getId(), "ANOTHER")
    );
    var other = organization();
    mvc
      .perform(
        post(
          "/api/v1/organizations/" +
            other.getId() +
            "/defaults/" +
            def.getId() +
            "/close-loan"
        )
          .contentType("application/json")
          .content("{\"reference\":\"CLOSE-42\"}")
      )
      .andExpect(status().isNotFound());
  }

  @Test
  void buyinLoanCloseoutBlocksPartialReceiptAndInsufficientCashWithoutChangingBalances() {
    var org = organization();
    var conn = connection(org);
    var loan = activeLoan(org, conn, security("100"), true);
    var def = reportDefaultForBuyinTest(
      org,
      loan,
      ids.incrementAndGet(),
      ids.incrementAndGet()
    );
    receivedBuyinForCloseout(org, loan, def, 20, "100");
    assertEquals(
      false,
      buyinCloseouts.preview(org.getId(), def.getId()).get("eligible")
    );
    assertThrows(ResponseStatusException.class, () ->
      buyinCloseouts.close(org.getId(), def.getId(), "partial")
    );
    assertEquals("ACTIVE", loan.getStatus());
    assertEquals(100, loan.getOutstandingQuantity());
    var another = reportDefaultForBuyinTest(
      org,
      loan,
      ids.incrementAndGet(),
      ids.incrementAndGet()
    );
    receivedBuyinForCloseout(org, loan, another, 80, "200");
    assertEquals(
      false,
      buyinCloseouts.preview(org.getId(), another.getId()).get("eligible")
    );
    assertThrows(ResponseStatusException.class, () ->
      buyinCloseouts.close(org.getId(), another.getId(), "cash-shortfall")
    );
    assertEquals(
      0,
      accounts
        .findByOrganizationId(org.getId())
        .orElseThrow()
        .getCashCollateralHeld()
        .compareTo(bd("11000"))
    );
    assertEquals(
      0,
      buyinCloseoutRows
        .findAll()
        .stream()
        .filter(c -> c.getLoanId().equals(loan.getId()))
        .count()
    );
  }

  @Test
  void lenderOfferPublishesExactlyTheSelectedCollateralTypesAndHaircuts() {
    var org = organization(); var connection = connection(org); long sec = security("100");
    var securityOnly = new AgreementOfferRequest(connection.getId(), "MSLA-SECURITY-ONLY", LocalDate.now(), LocalDate.now().plusYears(1),
        bd("100000"), bd("0.03"), bd("0.01"), bd("1.05"), "MONTHLY", 365, true, 24, 48,
        List.of(new RuleRequest("SECURITY", sec, bd("0.15"))), "AT_CLOSE");
    var agreement = agreementService.offer(org.getId(), securityOnly);
    var offered = events.findAll().stream().filter(e -> e.getOrganizationId().equals(org.getId()) && json.tree(e.getPayload()).path("type").asText().equals("AGREEMENT_OFFERED")).findFirst().orElseThrow();
    var rules = json.tree(offered.getPayload()).path("data").path("collateralRules");
    assertEquals(1, rules.size()); assertEquals("SECURITY", rules.get(0).path("assetType").asText());
    assertEquals(0, bd("0.15").compareTo(new BigDecimal(rules.get(0).path("haircut").asText())));
    assertEquals("OFFERED", agreement.getStatus());
    assertEquals(connection.getBorrowerOrganizationId().longValue(), json.tree(offered.getPayload()).path("organizationId").asLong());
    long before = events.count();
    assertEquals(true, agreementService.resend(org.getId(), agreement.getId()).get("alreadyQueued"));
    assertEquals(before, events.count());
    offered.setPublished(true); offered.setStatus("PUBLISHED");
    assertEquals(false, agreementService.resend(org.getId(), agreement.getId()).get("alreadyQueued"));
    assertEquals(1, agreements.findByOrganizationId(org.getId(), Pageable.unpaged()).getTotalElements());
    var replay = events.findAll().stream().filter(e -> e.getOrganizationId().equals(org.getId()) && json.tree(e.getPayload()).path("type").asText().equals("AGREEMENT_OFFERED") && !e.getId().equals(offered.getId())).findFirst().orElseThrow();
    assertEquals(agreement.getId().longValue(), json.tree(replay.getPayload()).path("referenceId").asLong());
    assertEquals(1, json.tree(replay.getPayload()).path("version").asLong());
    assertEquals(json.tree(offered.getPayload()).path("data"), json.tree(replay.getPayload()).path("data"));
    assertThrows(ResponseStatusException.class, () -> agreementService.resend(organization().getId(), agreement.getId()));
    agreement.setStatus("ACTIVE");
    assertThrows(ResponseStatusException.class, () -> agreementService.resend(org.getId(), agreement.getId()));
  }
}
