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
  @Autowired RecallRepository recalls;
  @Autowired LoanDefaultRepository defaults;
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
        .andExpect(jsonPath("$.data.totals[0].missingValuations").value(1));
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
}
