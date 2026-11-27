package com.example.borrower_service;

import static com.example.borrower_service.repository.BorrowerRepository.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.borrower_service.dto.*;
import com.example.borrower_service.dto.LocateRequest;
import com.example.borrower_service.model.*;
import com.example.borrower_service.repository.BorrowerRepository;
import com.example.borrower_service.service.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class BorrowerWorkflowTests {
    @Autowired DirectoryService directory;
    @Autowired MarketService market;
    @Autowired TradingService trading;
    @Autowired ReturnFailureRecoveryService returnRecovery;
    @Autowired LendingService lending;
    @Autowired LoanCollateralPreviewService loanCollateralPreview;
    @Autowired CollateralService collateral;
    @Autowired PaymentService payments;
    @Autowired BorrowerEventProjectionService projections;
    @Autowired BorrowerReadService read;
    @Autowired PortfolioService portfolio;
    @Autowired BorrowerRepository db;
    @Autowired MockMvc mvc;
    @Autowired ApplicationContext context;
    @Autowired jakarta.persistence.EntityManager billingEm;
    static final AtomicLong sequence = new AtomicLong(1000);

    static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    long org() {
        return n(
                directory.organization(
                        new OrganizationRequest(
                                "Borrower " + UUID.randomUUID(),
                                UUID.randomUUID().toString(),
                                "INR")),
                "id");
    }

    BorrowerEvent event(long org, String type, long ref, long version, Map<String, Object> data) {
        return new BorrowerEvent(
                UUID.randomUUID().toString(), type, org, ref, version, Instant.now(), data);
    }

    void apply(long org, String type, long ref, long version, Map<String, Object> data) {
        projections.apply(event(org, type, ref, version, data));
    }

    void fund(long org, String amount) {
        long id =
                n(
                        payments.funding(
                                org,
                                new FundingRequest(bd(amount), UUID.randomUUID().toString()),
                                true),
                        "id");
        apply(org, "PAYMENT_CONFIRMED", id, 1, Map.of("amount", amount));
    }

    long security(String price) {
        long id = sequence.incrementAndGet();
        market.register(
                new SecurityRequest(id, "DEMO" + id, "NSE", "Demo equity", "INEDEMO" + id, "INR"));
        market.quote(id, new QuoteRequest(bd(price), Instant.now().minusSeconds(120)));
        return id;
    }

    long agreement(long org, long collateralSecurity) {
        long lender = sequence.incrementAndGet();
        apply(
                org,
                "LENDER_REFERENCE_UPDATED",
                lender,
                1,
                Map.of("name", "Demo lender", "externalId", "LENDER_" + lender));
        var rules = new ArrayList<RuleRequest>();
        rules.add(new RuleRequest("CASH", null, BigDecimal.ZERO));
        if (collateralSecurity > 0)
            rules.add(new RuleRequest("SECURITY", collateralSecurity, bd("0.10")));
        long id =
                n(
                        lending.propose(
                                org,
                                new AgreementRequest(
                                        lender,
                                        UUID.randomUUID().toString(),
                                        LocalDate.now().minusDays(1),
                                        LocalDate.now().plusYears(1),
                                        bd("1000000"),
                                        bd("0.03"),
                                        bd("0.01"),
                                        bd("1.05"),
                                        "MONTHLY",
                                        365,
                                        true,
                                        24,
                                        48,
                                        rules)),
                        "id");
        apply(org, "AGREEMENT_ACCEPTED", id, 1, Map.of());
        return id;
    }

    long reservation(long org, long security, long agreement, long quantity) {
        long locate =
                n(
                        lending.locate(
                                org,
                                new LocateRequest(
                                        agreement,
                                        security,
                                        quantity,
                                        Instant.now().plusSeconds(3600))),
                        "id");
        apply(org, "LOCATE_APPROVED", locate, 1, Map.of("approvedQuantity", quantity));
        long id = n(lending.reserve(org, locate, new ReservationRequest(quantity)), "id");
        apply(org, "RESERVATION_CONFIRMED", id, 1, Map.of("quantity", quantity));
        return id;
    }

    long loan(long org, long security, long agreement, long quantity) {
        long reservation = reservation(org, security, agreement, quantity);
        return n(
                lending.createLoan(
                        org,
                        new LoanRequest(
                                reservation,
                                quantity,
                                List.of(new AllocationRequest("CASH", null, 0, bd("2000"))))),
                "id");
    }

    @Test
    void multiCollateralDraftUsesCurrentHaircutsAndCreatesAllAllocations() throws Exception {
        long org=org(), loanSecurity=security("100"), first=security("100"), second=security("200");
        fund(org,"2000");
        long agreement=agreement(org,first);
        db.create(CollateralHaircut::new,r->{r.setMslaId(agreement);r.setAssetType("SECURITY");r.setSecurityId(second);r.setHaircut(bd("0.20"));});
        db.holding(org,first);db.holding(org,second);
        db.edit(Holding.class,new HoldingId(org,first),h->{h.setOwnedQuantity(10L);h.setPledgedQuantity(3L);h.setBlockedOwned(2L);});
        db.edit(Holding.class,new HoldingId(org,second),h->{h.setOwnedQuantity(5L);h.setBlockedOwned(1L);});
        long reservation=reservation(org,loanSecurity,agreement,10);
        var allocations=List.of(new AllocationRequest("CASH",null,0,bd("300")),
            new AllocationRequest("SECURITY",first,5,BigDecimal.ZERO),new AllocationRequest("SECURITY",second,2,BigDecimal.ZERO));
        var preview=new LoanCollateralPreviewRequest(reservation,10,allocations);
        var result=loanCollateralPreview.preview(org,preview);
        assertEquals(true,result.get("eligible"));
        assertEquals(0,bd("1200").compareTo((BigDecimal)result.get("marketValue")));
        assertEquals(0,bd("1070").compareTo((BigDecimal)result.get("eligibleValue")));
        assertEquals(0,bd("1050").compareTo((BigDecimal)result.get("requiredCollateral")));
        assertEquals(0,bd("20").compareTo((BigDecimal)result.get("surplus")));
        assertEquals(0,bd("2000").compareTo(money(account(org),"available")));
        assertEquals(0,db.rows(Loan.class,eq("organizationId",org)).size());
        mvc.perform(post("/api/v1/organizations/"+org+"/loans/collateral-preview")
            .contentType("application/json").content(toJson(preview)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.rows[1].haircut").value(0.1));
        long loan=n(lending.createLoan(org,new LoanRequest(reservation,10,allocations,Instant.now().plusSeconds(86400))),"id");
        assertEquals(3,db.rows(CollateralPosition.class,eq("loanId",loan)).size());
        var outgoing=db.rows(OutboxEvent.class,eq("organizationId",org),eq("eventType","LOAN_REQUESTED")).get(0);
        assertEquals(3,tools.jackson.databind.json.JsonMapper.builder().build().readTree(outgoing.get("payload").toString()).path("data").path("collateral").size());
        assertEquals(0,bd("1700").compareTo(money(account(org),"available")));
        assertEquals(8,db.find(Holding.class,new HoldingId(org,first)).getPledgedQuantity());
        assertEquals(2,db.find(Holding.class,new HoldingId(org,second)).getPledgedQuantity());
        assertEquals(0,n(db.owned(Reservation.class,org,reservation),"remaining_quantity"));
        mvc.perform(post("/api/v1/organizations/"+org()+"/loans/collateral-preview")
            .contentType("application/json").content(toJson(preview))).andExpect(status().isNotFound());
    }

    private String toJson(Object value) {return tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(value);}

    @Test
    void multiCollateralDraftRejectsAggregateOverAllocationAndMissingPrices() {
        long org=org(), loanSecurity=security("100"), pledged=security("100");
        fund(org,"2000");long agreement=agreement(org,pledged),reservation=reservation(org,loanSecurity,agreement,10);
        db.holding(org,pledged);db.edit(Holding.class,new HoldingId(org,pledged),h->h.setOwnedQuantity(5L));
        var duplicates=new LoanCollateralPreviewRequest(reservation,10,List.of(new AllocationRequest("CASH",null,0,bd("600")),
            new AllocationRequest("SECURITY",pledged,3,BigDecimal.ZERO),new AllocationRequest("SECURITY",pledged,3,BigDecimal.ZERO)));
        var result=loanCollateralPreview.preview(org,duplicates);
        assertEquals(false,result.get("eligible"));assertTrue(result.get("errors").toString().contains("Combined allocations"));
        var tooMuchCash=new LoanCollateralPreviewRequest(reservation,10,List.of(new AllocationRequest("CASH",null,0,bd("1200")),new AllocationRequest("CASH",null,0,bd("1200"))));
        assertEquals(false,loanCollateralPreview.preview(org,tooMuchCash).get("eligible"));
        db.edit(SecurityMaster.class,pledged,s->s.setPriceAt(null));
        var unknown=loanCollateralPreview.preview(org,duplicates);
        assertEquals(false,unknown.get("eligible"));assertNull(unknown.get("eligibleValue"));
        var cashOnly=new LoanCollateralPreviewRequest(reservation,10,List.of(new AllocationRequest("CASH",null,0,bd("1100"))));
        assertEquals(true,loanCollateralPreview.preview(org,cashOnly).get("eligible"));
        assertEquals(0,db.rows(Loan.class,eq("organizationId",org)).size());
        assertEquals(0,bd("2000").compareTo(money(account(org),"available")));
    }

    @Test
    void buyinClosureAppliesCashAndClosesLoanWithoutCreditingOrDebitingCashTwice() {
      long org = org(),
        security = security("100");
      fund(org, "10000");
      long loan = loan(org, security, agreement(org, 0), 10);
      long position = n(
        db.rows(CollateralPosition.class, eq("loanId", loan)).get(0),
        "id"
      );
      apply(org, "COLLATERAL_PLEDGED", position, 1, Map.of());
      apply(org, "LOAN_SETTLED", loan, 1, Map.of());
      var application = event(
        org,
        "COLLATERAL_APPLIED",
        position,
        2,
        Map.of("amount", "1500", "reference", "BUYIN-CLOSE")
      );
      projections.apply(application);
      projections.apply(application);
      assertEquals(
        0,
        bd("500").compareTo(db.find(TraderAccount.class, org).getPledged())
      );
      assertEquals(
        0,
        bd("8000").compareTo(db.find(TraderAccount.class, org).getAvailable())
      );
      apply(
        org,
        "COLLATERAL_RELEASED",
        position,
        3,
        Map.of("cashAmount", "500", "quantity", 0, "reference", "BUYIN-CLOSE")
      );
      var close = event(
        org,
        "LOAN_STATUS_UPDATED",
        loan,
        2,
        Map.of(
          "status",
          "CLOSED",
          "outstandingQuantity",
          0,
          "buyinCloseout",
          true,
          "closedQuantity",
          10,
          "buyinPrice",
          "150",
          "closedAt",
          Instant.now().toString(),
          "reference",
          "BUYIN-CLOSE"
        )
      );
      projections.apply(close);
      projections.apply(close);
      assertEquals("CLOSED", db.find(Loan.class, loan).getStatus());
      assertEquals(0, db.find(Loan.class, loan).getOutstandingQuantity());
      assertNotNull(db.find(Loan.class, loan).getClosedAt());
      var holding = db.find(Holding.class, new HoldingId(org, security));
      assertEquals(0, holding.getBorrowedQuantity());
      assertEquals(10, holding.getOwnedQuantity());
      assertEquals(0, bd("150").compareTo(holding.getAverageCost()));
      assertEquals(0, db.find(TraderAccount.class, org).getPledged().signum());
      assertEquals(
        0,
        bd("8500").compareTo(db.find(TraderAccount.class, org).getAvailable())
      );
    }

    @Test
    void buyinClosureCoversSoldShortLotsAndCancelsPendingBorrowedOrders() {
      long org = org(),
        security = security("100");
      fund(org, "10000");
      long loan = loan(org, security, agreement(org, 0), 10);
      long position = n(
        db.rows(CollateralPosition.class, eq("loanId", loan)).get(0),
        "id"
      );
      apply(org, "COLLATERAL_PLEDGED", position, 1, Map.of());
      apply(org, "LOAN_SETTLED", loan, 1, Map.of());
      trading.order(
        org,
        new OrderRequest(security, "SELL", "MARKET", "BORROWED", 6, null)
      );
      long pending = n(
        trading.order(
          org,
          new OrderRequest(security, "SELL", "LIMIT", "BORROWED", 4, bd("200"))
        ),
        "id"
      );
      apply(
        org,
        "LOAN_STATUS_UPDATED",
        loan,
        2,
        Map.of(
          "status",
          "CLOSED",
          "outstandingQuantity",
          0,
          "buyinCloseout",
          true,
          "closedQuantity",
          10,
          "buyinPrice",
          "110",
          "reference",
          "SHORT-CLOSE"
        )
      );
      assertEquals("CANCELLED", db.find(TradeOrder.class, pending).getStatus());
      assertEquals(
        0L,
        db.sum(
          ShortLot.class,
          "openQuantity",
          Long.class,
          eq("organizationId", org),
          eq("securityId", security)
        )
      );
      var holding = db.find(Holding.class, new HoldingId(org, security));
      assertEquals(0, holding.getBorrowedQuantity());
      assertEquals(4, holding.getOwnedQuantity());
      assertEquals(0, bd("-60").compareTo(holding.getRealizedPnl()));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void buyinSummaryPersistsAcrossApiReadsAndReplayWithoutTouchingHoldingsOrCash(boolean lenderFunded) throws Exception {
      long org = org(),
        security = security("100");
      fund(org, "10000");
      long loan = loan(org, security, agreement(org, 0), 10);
      long position = n(
        db.rows(CollateralPosition.class, eq("loanId", loan)).get(0),
        "id"
      );
      apply(org, "COLLATERAL_PLEDGED", position, 1, Map.of());
      apply(org, "LOAN_SETTLED", loan, 1, Map.of());
      apply(
        org,
        "LOAN_STATUS_UPDATED",
        loan,
        2,
        Map.of(
          "status",
          "CLOSED",
          "outstandingQuantity",
          0,
          "buyinCloseout",
          true,
          "closedQuantity",
          10,
          "buyinPrice",
          "100",
          "reference",
          "SUM-CLOSE"
        )
      );
      var before = mvc
        .perform(get("/api/v1/organizations/" + org + "/loans/" + loan))
        .andExpect(status().isOk())
        .andReturn();
      assertTrue(
        before
          .getResponse()
          .getContentAsString()
          .contains("\"buyinClosurePending\":true")
      );
      var data = new LinkedHashMap<String, Object>();
      data.put("loanId", loan);
      data.put("quantity", 10);
      data.put("purchaseValue", "1000");
      data.put("executionCosts", "5");
      data.put("purchaseCost", "1005");
      data.put("cashCollateralApplied", lenderFunded ? "805" : "1005");
      data.put("cashCollateralReturned", lenderFunded ? "0" : "995");
      if (lenderFunded) data.put("lenderFundsApplied", "200");
      data.put("averagePrice", "100");
      data.put("closedAt", Instant.now().toString());
      data.put("reference", "SUM-CLOSE");
      long id = sequence.incrementAndGet();
      var summary = event(org, "BUY_IN_CLOSEOUT_REPORTED", id, 1, data);
      projections.apply(summary);
      projections.apply(summary);
      apply(org, "BUY_IN_CLOSEOUT_REPORTED", id, 2, data);
      mvc
        .perform(get("/api/v1/organizations/" + org + "/loans/" + loan))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.buyinClosure.reference").value("SUM-CLOSE"))
        .andExpect(jsonPath("$.data.buyinClosure.purchaseCost").value(1005))
        .andExpect(jsonPath("$.data.buyinClosure.lenderFundsApplied").value(lenderFunded ? 200 : 0))
        .andExpect(
          jsonPath("$.data.buyinClosure.cashCollateralReturned").value(lenderFunded ? 0 : 995)
        )
        .andExpect(jsonPath("$.data.buyinClosurePending").value(false));
      assertEquals(
        1,
        db
          .rows(
            BuyinClosureSummary.class,
            eq("organizationId", org),
            eq("loanId", loan)
          )
          .size()
      );
      assertEquals(
        10,
        db.find(Holding.class, new HoldingId(org, security)).getOwnedQuantity()
      );
      assertEquals(
        0,
        bd("8000").compareTo(db.find(TraderAccount.class, org).getAvailable())
      );
      var feeEntries = db.rows(TraderAccountTransaction.class, eq("organizationId", org), eq("type", "BUY_IN_EXECUTION_FEE"));
      assertEquals(1, feeEntries.size());
      assertEquals(0, money(feeEntries.get(0), "amount").signum());
      assertEquals(0, bd("5").compareTo(money(feeEntries.get(0), "execution_fee_amount")));
      assertEquals(loan, n(feeEntries.get(0), "loan_id"));
      mvc.perform(get("/api/v1/organizations/" + org + "/transactions"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content[0].executionFeeAmount").value(5));
      assertTrue(portfolio.statement(org).contains("execution_fee_amount"));
      // Reconstruct a pre-upgrade closure with no fee allocation, then backfill twice.
      billingEm.flush();
      billingEm.createQuery("delete from TraderAccountTransaction t where t.organizationId=:org and t.type='BUY_IN_EXECUTION_FEE'")
          .setParameter("org", org).executeUpdate();
      String migration = new org.springframework.core.io.ClassPathResource("db/migration/V20__buyin_execution_fee_ledger.sql")
          .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
      for (int attempt = 0; attempt < 2; attempt++) for (String statement : migration.split(";"))
          if (statement.contains("INSERT INTO")) billingEm.createNativeQuery(statement).executeUpdate();
      billingEm.clear();
      assertEquals(1, db.rows(TraderAccountTransaction.class, eq("organizationId", org), eq("type", "BUY_IN_EXECUTION_FEE")).size());
      assertEquals(0, bd("8000").compareTo(money(account(org), "available")));
      long other = org();
      mvc
        .perform(get("/api/v1/organizations/" + other + "/loans/" + loan))
        .andExpect(status().isNotFound());
      data.put("purchaseCost", "2000");
      assertThrows(ResponseStatusException.class, () ->
        apply(org, "BUY_IN_CLOSEOUT_REPORTED", id, 3, data)
      );
    }

    @Test
    void loanMaturityIsPersistedPublishedAndExposedInBothReadEndpoints() throws Exception {
        long org = org(), security = security("100"), agreement = agreement(org, 0);
        fund(org, "10000");
        long reservation = reservation(org, security, agreement, 10);
        Instant maturity = Instant.now().plusSeconds(86400).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        var request = new LoanRequest(reservation, 10,
                List.of(new AllocationRequest("CASH", null, 0, bd("2000"))), maturity);
        long id = n(lending.createLoan(org, request), "id");
        assertEquals(maturity, db.find(Loan.class, id).getMaturityAt());
        var event = db.rows(OutboxEvent.class, eq("organizationId", org),
                eq("eventType", "LOAN_REQUESTED"), eq("aggregateId", String.valueOf(id))).get(0);
        assertTrue(event.get("payload").toString().contains(maturity.toString()));
        mvc.perform(get("/api/v1/organizations/" + org + "/loans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].maturityAt").value(maturity.toString()));
        mvc.perform(get("/api/v1/organizations/" + org + "/loans/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.maturityAt").value(maturity.toString()));
    }

    @Test
    void pastLoanMaturityDoesNotConsumeReservation() {
        long org = org(), security = security("100"), agreement = agreement(org, 0);
        long reservation = reservation(org, security, agreement, 10);
        assertThrows(ResponseStatusException.class, () -> lending.createLoan(org,
                new LoanRequest(reservation, 10,
                    List.of(new AllocationRequest("CASH", null, 0, bd("2000"))),
                    Instant.now().minusSeconds(60))));
        assertEquals(10, n(db.owned(Reservation.class, org, reservation), "remaining_quantity"));
    }

    Map<String, Object> account(long org) {
        return db.matchingRow(TraderAccount.class, eq("organizationId", org));
    }

    @Test
    void organizationHasMultipleUsersAndProtectsLastAdmin() {
        long org = org();
        long admin =
                n(
                        directory.member(
                                org, new MemberRequest("Admin", "admin@example.com", "ADMIN")),
                        "id");
        directory.member(org, new MemberRequest("Trader", "trader@example.com", "TRADER"));
        assertEquals(2, db.rows(User.class, eq("organizationId", org)).size());
        assertThrows(
                ResponseStatusException.class,
                () ->
                        directory.updateMember(
                                org, admin, new MemberUpdateRequest("READ_ONLY", false)));
    }

    @Test
    void fundingWaitsForExternalConfirmationAndRetriesDoNotDoubleCredit() {
        long org = org();
        var request = payments.funding(org, new FundingRequest(bd("5000"), "fund-1"), true);
        assertEquals(0, money(account(org), "available").signum());
        var event = event(org, "PAYMENT_CONFIRMED", n(request, "id"), 1, Map.of("amount", "5000"));
        projections.apply(event);
        projections.apply(event);
        assertEquals(0, bd("5000").compareTo(money(account(org), "available")));
        assertEquals(
                n(request, "id"),
                n(payments.funding(org, new FundingRequest(bd("5000"), "fund-1"), true), "id"));
    }

    @Test
    void depositApprovalRequiresAnActiveAdminAndCreditsFunds() {
        long org = org();
        long admin =
                n(
                        directory.member(
                                org, new MemberRequest("Admin", "admin@example.com", "ADMIN")),
                        "id");
        long trader =
                n(
                        directory.member(
                                org, new MemberRequest("Trader", "trader@example.com", "TRADER")),
                        "id");
        long request =
                n(
                        payments.funding(org, new FundingRequest(bd("7500"), "fund-approve"), true),
                        "id");
        assertThrows(
                ResponseStatusException.class, () -> payments.approveDeposit(org, request, trader));
        var approved = payments.approveDeposit(org, request, admin);
        assertEquals(0, bd("7500").compareTo(money(account(org), "available")));
        assertEquals("CONFIRMED", approved.get("status"));
    }

    @Test
    void depositListEndpointReturnsDepositRequests() throws Exception {
        long org = org();
        payments.funding(org, new FundingRequest(bd("2500"), "fund-list-1"), true);
        payments.funding(org, new FundingRequest(bd("1000"), "fund-list-2"), true);

        mvc.perform(get("/api/v1/organizations/{org}/deposits", org).param("page", "0").param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.content[0].type").value("DEPOSIT"));
    }

    @Test
    void marketBuyAndOwnedSellUpdateHoldingsCashAndRealizedPnl() {
        long org = org(), security = security("100");
        fund(org, "10000");
        var buy =
                trading.order(org, new OrderRequest(security, "BUY", "MARKET", "OWNED", 10, null));
        assertEquals("FILLED", buy.get("status"));
        market.quote(security, new QuoteRequest(bd("120"), Instant.now().minusSeconds(60)));
        trading.order(org, new OrderRequest(security, "SELL", "MARKET", "OWNED", 5, null));
        var h =
                db.matchingRow(
                        Holding.class, eq("organizationId", org), eq("securityId", security));
        assertEquals(5, n(h, "owned_quantity"));
        assertEquals(0, bd("100").compareTo(money(h, "realized_pnl")));
        assertEquals(0, bd("9600").compareTo(money(account(org), "available")));
    }

    @Test
    void limitOrdersBlockFundsAndCancellationReleasesThem() {
        long org = org(), security = security("100");
        fund(org, "10000");
        long id =
                n(
                        trading.order(
                                org,
                                new OrderRequest(security, "BUY", "LIMIT", "OWNED", 10, bd("90"))),
                        "id");
        assertEquals(0, bd("900").compareTo(money(account(org), "blocked")));
        trading.cancel(org, id);
        assertEquals(0, money(account(org), "blocked").signum());
        assertEquals(0, bd("10000").compareTo(money(account(org), "available")));
    }

    @Test
    void updatingPendingBuyRetainsIdentityAndReplacesCashReservation() {
        long org = org(), security = security("100");
        fund(org, "10000");
        long id = n(trading.order(org,
                new OrderRequest(security, "BUY", "LIMIT", "OWNED", 10, bd("90"))), "id");
        var updated = trading.update(org, id, new OrderUpdateRequest("LIMIT", 20, bd("80")));
        assertEquals(id, n(updated, "id"));
        assertEquals(20, n(updated, "quantity"));
        assertEquals("PENDING", updated.get("status"));
        assertEquals(0, bd("1600").compareTo(money(account(org), "blocked")));
        assertEquals(0, bd("8400").compareTo(money(account(org), "available")));
        trading.cancel(org, id);
        assertEquals(0, money(account(org), "blocked").signum());
        assertEquals(0, bd("10000").compareTo(money(account(org), "available")));
    }

    @Test
    void updatingPendingSellReplacesShareReservationAndCanFillImmediately() {
        long org = org(), security = security("100");
        fund(org, "10000");
        trading.order(org, new OrderRequest(security, "BUY", "MARKET", "OWNED", 30, null));
        long id = n(trading.order(org,
                new OrderRequest(security, "SELL", "LIMIT", "OWNED", 10, bd("120"))), "id");
        trading.update(org, id, new OrderUpdateRequest("LIMIT", 20, bd("130")));
        assertEquals(20, n(db.matchingRow(Holding.class,
                eq("organizationId", org), eq("securityId", security)), "blocked_owned"));
        var filled = trading.update(org, id, new OrderUpdateRequest("MARKET", 15, null));
        assertEquals("FILLED", filled.get("status"));
        var holding = db.matchingRow(Holding.class,
                eq("organizationId", org), eq("securityId", security));
        assertEquals(0, n(holding, "blocked_owned"));
        assertEquals(15, n(holding, "owned_quantity"));
        assertEquals(1, db.rows(TradeHistory.class, eq("orderId", id)).size());
    }

    @Test
    void borrowedReservationsAndBuyToCoverUpdatesExcludeTheReplacedOrder() {
        long org = org(), security = security("100");
        fund(org, "10000");
        db.holding(org, security);
        db.edit(Holding.class, new HoldingId(org, security), h -> h.setBorrowedQuantity(20L));
        long sell = n(trading.order(org,
                new OrderRequest(security, "SELL", "LIMIT", "BORROWED", 5, bd("120"))), "id");
        trading.update(org, sell, new OrderUpdateRequest("LIMIT", 10, bd("120")));
        assertEquals(10, n(db.matchingRow(Holding.class,
                eq("organizationId", org), eq("securityId", security)), "blocked_borrowed"));
        trading.cancel(org, sell);
        assertEquals(0, n(db.matchingRow(Holding.class,
                eq("organizationId", org), eq("securityId", security)), "blocked_borrowed"));
        trading.order(org, new OrderRequest(security, "SELL", "MARKET", "BORROWED", 10, null));
        long cover = n(trading.order(org,
                new OrderRequest(security, "BUY", "LIMIT", "BORROWED", 6, bd("90"))), "id");
        trading.order(org, new OrderRequest(security, "BUY", "LIMIT", "BORROWED", 4, bd("90")));
        var updated = trading.update(org, cover, new OrderUpdateRequest("LIMIT", 6, bd("80")));
        assertEquals("PENDING", updated.get("status"));
        assertEquals(6, n(updated, "quantity"));
        assertEquals(0, bd("480").compareTo(money(updated, "reserved_cash")));
    }

    @Test
    void orderUpdateEndpointValidatesPayloadAndTenant() throws Exception {
        long org = org(), security = security("100"), other = org();
        fund(org, "10000");
        long id = n(trading.order(org,
                new OrderRequest(security, "BUY", "LIMIT", "OWNED", 10, bd("90"))), "id");
        String path = "/api/v1/organizations/" + org + "/orders/" + id;
        mvc.perform(put(path).contentType("application/json")
                .content("{\"type\":\"LIMIT\",\"quantity\":0,\"limitPrice\":80}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/organizations/" + other + "/orders/" + id)
                .contentType("application/json")
                .content("{\"type\":\"LIMIT\",\"quantity\":5,\"limitPrice\":80}"))
                .andExpect(status().isNotFound());
        mvc.perform(put(path).contentType("application/json")
                .content("{\"type\":\"LIMIT\",\"quantity\":5,\"limitPrice\":80}"))
                .andExpect(status().isOk());
        assertEquals(5, n(db.owned(TradeOrder.class, org, id), "quantity"));
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void failedUpdateRollsBackReservationsAndTerminalOrdersRejectChanges() {
        long org = org(), security = security("100");
        fund(org, "1000");
        long id = n(trading.order(org,
                new OrderRequest(security, "BUY", "LIMIT", "OWNED", 10, bd("90"))), "id");
        assertThrows(ResponseStatusException.class,
                () -> trading.update(org, id, new OrderUpdateRequest("LIMIT", 100, bd("90"))));
        assertEquals("PENDING", db.owned(TradeOrder.class, org, id).get("status"));
        assertEquals(10, n(db.owned(TradeOrder.class, org, id), "quantity"));
        assertEquals(0, bd("900").compareTo(money(account(org), "blocked")));
        assertEquals(0, bd("100").compareTo(money(account(org), "available")));
        assertThrows(ResponseStatusException.class,
                () -> trading.update(org, id, new OrderUpdateRequest("LIMIT", 5, null)));
        trading.cancel(org, id);
        assertThrows(ResponseStatusException.class,
                () -> trading.update(org, id, new OrderUpdateRequest("LIMIT", 5, bd("80"))));
        assertThrows(ResponseStatusException.class, () -> trading.cancel(org, id));
        long filled = n(trading.order(org,
                new OrderRequest(security, "BUY", "MARKET", "OWNED", 1, null)), "id");
        assertThrows(ResponseStatusException.class,
                () -> trading.update(org, filled, new OrderUpdateRequest("LIMIT", 1, bd("80"))));
        assertThrows(ResponseStatusException.class, () -> trading.cancel(org, filled));
    }

    @Test
    void limitFillUsesImprovedPriceAndIsIdempotent() {
        long org = org(), security = security("100");
        fund(org, "10000");
        long id =
                n(
                        trading.order(
                                org,
                                new OrderRequest(security, "BUY", "LIMIT", "OWNED", 10, bd("90"))),
                        "id");
        market.quote(security, new QuoteRequest(bd("80"), Instant.now().minusSeconds(60)));
        trading.match(org, id);
        trading.match(org, id);
        assertEquals(1, db.rows(TradeHistory.class, eq("orderId", id)).size());
        assertEquals(0, bd("9200").compareTo(money(account(org), "available")));
        assertEquals(0, money(account(org), "blocked").signum());
    }

    @Test
    void cachedPriceCanFillBuyButInsufficientInventoryPreventsSell() {
        long org = org(), security = security("100");
        fund(org, "10000");
        assertThrows(
                ResponseStatusException.class,
                () ->
                        trading.order(
                                org,
                                new OrderRequest(security, "SELL", "MARKET", "BORROWED", 1, null)));
        db.edit(
                SecurityMaster.class,
                security,
                entity -> {
                    entity.setPriceAt(OffsetDateTime.now().minusHours(2).toInstant());
                });
        assertEquals(
                "FILLED",
                trading.order(org, new OrderRequest(security, "BUY", "MARKET", "OWNED", 1, null))
                        .get("status"));
        assertEquals(1, db.rows(TradeOrder.class, eq("organizationId", org)).size());
    }

    @Test
    void locateListIncludesTheReasonSentWithEachLenderDecision() throws Exception {
        long org = org(), security = security("100"), agreement = agreement(org, 0);
        var request = new LocateRequest(agreement, security, 10, Instant.now().plusSeconds(3600));
        long approved = n(lending.locate(org, request), "id");
        long rejected = n(lending.locate(org, request), "id");

        apply(org, "LOCATE_APPROVED", approved, 1, Map.of(
                "approvedQuantity", 8, "reason", "Eight shares are available."));
        apply(org, "LOCATE_REJECTED", rejected, 1, Map.of("reason", "Inventory is committed."));

        mvc.perform(get("/api/v1/organizations/" + org + "/locates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].id").value(rejected))
                .andExpect(jsonPath("$.data.content[0].decisionReason").value("Inventory is committed."))
                .andExpect(jsonPath("$.data.content[1].id").value(approved))
                .andExpect(jsonPath("$.data.content[1].decisionReason").value("Eight shares are available."));
    }

    @Test
    void locateApprovalDoesNotAutomaticallyReserveAndLoanDoesNotAutomaticallySettle() {
        long org = org(), security = security("100");
        fund(org, "10000");
        long agreement = agreement(org, 0);
        long locate =
                n(
                        lending.locate(
                                org,
                                new LocateRequest(
                                        agreement, security, 10, Instant.now().plusSeconds(3600))),
                        "id");
        apply(org, "LOCATE_APPROVED", locate, 1, Map.of("approvedQuantity", 10));
        assertTrue(db.rows(Reservation.class, eq("locateId", locate)).isEmpty());
        long reservation = n(lending.reserve(org, locate, new ReservationRequest(10)), "id");
        assertEquals("REQUESTED", db.owned(Reservation.class, org, reservation).get("status"));
        apply(org, "RESERVATION_CONFIRMED", reservation, 1, Map.of("quantity", 10));
        long loan =
                n(
                        lending.createLoan(
                                org,
                                new LoanRequest(
                                        reservation,
                                        10,
                                        List.of(
                                                new AllocationRequest(
                                                        "CASH", null, 0, bd("1100"))))),
                        "id");
        assertEquals("PENDING_SETTLEMENT", db.owned(Loan.class, org, loan).get("status"));
        assertEquals(
                "PLEDGE_PENDING",
                db.rows(CollateralPosition.class, eq("loanId", loan)).get(0).get("status"));
        assertThrows(
                ResponseStatusException.class,
                () ->
                        trading.order(
                                org,
                                new OrderRequest(security, "SELL", "MARKET", "BORROWED", 1, null)));
    }

    @Test
    void fullBorrowSellCoverReturnFlowWaitsForConfirmations() {
        long org = org(), security = security("100");
        fund(org, "10000");
        long loan = loan(org, security, agreement(org, 0), 10);
        long position = n(db.rows(CollateralPosition.class, eq("loanId", loan)).get(0), "id");
        apply(org, "COLLATERAL_PLEDGED", position, 1, Map.of());
        apply(org, "LOAN_SETTLED", loan, 1, Map.of());
        trading.order(org, new OrderRequest(security, "SELL", "MARKET", "BORROWED", 10, null));
        market.quote(security, new QuoteRequest(bd("90"), Instant.now().minusSeconds(60)));
        trading.order(org, new OrderRequest(security, "BUY", "MARKET", "BORROWED", 10, null));
        long ret = n(lending.requestReturn(org, loan, new ReturnRequest(10)), "id");
        assertEquals(10, n(db.owned(Loan.class, org, loan), "outstanding_quantity"));
        apply(org, "RETURN_SETTLED", ret, 1, Map.of());
        assertEquals("CLOSED", db.owned(Loan.class, org, loan).get("status"));
        apply(org, "COLLATERAL_RELEASED", position, 2, Map.of());
        assertEquals(0, money(account(org), "pledged").signum());
        assertEquals(0, bd("10100").compareTo(money(account(org), "available")));
    }

    @Test
    void securityCollateralLinksSecurityAndCannotBeSoldWhilePledged() {
        long org = org(), security = security("100"), pledge = security("200");
        fund(org, "10000");
        apply(
                org,
                "HOLDING_IMPORTED",
                pledge,
                1,
                Map.of("ownedQuantity", 20, "borrowedQuantity", 0, "averageCost", "180"));
        long reservation = reservation(org, security, agreement(org, pledge), 10);
        long loan =
                n(
                        lending.createLoan(
                                org,
                                new LoanRequest(
                                        reservation,
                                        10,
                                        List.of(
                                                new AllocationRequest(
                                                        "SECURITY", pledge, 10, BigDecimal.ZERO)))),
                        "id");
        var p = db.rows(CollateralPosition.class, eq("loanId", loan)).get(0);
        assertEquals(pledge, n(p, "security_id"));
        assertThrows(
                ResponseStatusException.class,
                () ->
                        trading.order(
                                org,
                                new OrderRequest(pledge, "SELL", "MARKET", "OWNED", 11, null)));
    }

    @Test
    void reservationsAreUnlockedOnlyByExternalEventNotByBorrowerReleaseRequest() {
        long org = org(),
                security = security("100"),
                reservation = reservation(org, security, agreement(org, 0), 10);
        lending.releaseReservation(org, reservation);
        assertEquals(
                "RELEASE_REQUESTED", db.owned(Reservation.class, org, reservation).get("status"));
        assertEquals(10, n(db.owned(Reservation.class, org, reservation), "remaining_quantity"));
        apply(org, "RESERVATION_RELEASED", reservation, 2, Map.of());
        assertEquals(0, n(db.owned(Reservation.class, org, reservation), "remaining_quantity"));
    }

    @Test
    void loanReturnRejectsWhenOutstandingFeeBillExceedsAvailableCash() {
        long org = org(), security = security("100");
        fund(org, "2500");
        long loan = loan(org, security, agreement(org, 0), 10), fee = sequence.incrementAndGet();
        long position = n(db.rows(CollateralPosition.class, eq("loanId", loan)).get(0), "id");
        apply(org, "COLLATERAL_PLEDGED", position, 1, Map.of());
        apply(org, "LOAN_SETTLED", loan, 1, Map.of());
        apply(
                org,
                "FEE_BILL_ISSUED",
                fee,
                1,
                Map.of(
                        "loanId",
                        loan,
                        "periodStart",
                        "2026-11-01",
                        "periodEnd",
                        "2026-11-30",
                        "rate",
                        "0.03",
                        "amount",
                        "600",
                        "dueDate",
                        "2026-12-01"));
        assertThrows(
                ResponseStatusException.class,
                () -> lending.requestReturn(org, loan, new ReturnRequest(10)));
        assertEquals("DUE", db.owned(LendingFee.class, org, fee).get("status"));
    }

    void loanReturnBlocksDueFeesUntilTheLenderSettlesIt() {
        long org = org(), security = security("100");
        fund(org, "1000");
        long loan = loan(org, security, agreement(org, 0), 10), fee = sequence.incrementAndGet();
        long position = n(db.rows(CollateralPosition.class, eq("loanId", loan)).get(0), "id");
        apply(org, "COLLATERAL_PLEDGED", position, 1, Map.of());
        apply(org, "LOAN_SETTLED", loan, 1, Map.of());
        apply(
                org,
                "FEE_BILL_ISSUED",
                fee,
                1,
                Map.of(
                        "loanId",
                        loan,
                        "periodStart",
                        "2026-11-01",
                        "periodEnd",
                        "2026-11-30",
                        "rate",
                        "0.03",
                        "amount",
                        "250",
                        "dueDate",
                        "2026-12-01"));
        long ret = n(lending.requestReturn(org, loan, new ReturnRequest(10)), "id");
        assertEquals("PAYMENT_PENDING", db.owned(LendingFee.class, org, fee).get("status"));
        assertEquals(0, bd("750").compareTo(money(account(org), "available")));
        assertEquals(0, bd("250").compareTo(money(account(org), "blocked")));
        assertEquals("PENDING", db.owned(LoanReturn.class, org, ret).get("status"));
    }

    @Test
    void externalFeeBillsArePaidOnceWithoutCalculatingFees() throws Exception {
        long org = org(), security = security("100");
        fund(org, "10000");
        long loan = loan(org, security, agreement(org, 0), 10), fee = sequence.incrementAndGet();
        apply(org, "ACCRUAL_REPORTED", fee, 1, Map.of(
                "loanId", loan, "type", "FEE", "date", "2026-11-01",
                "baseValue", "1000", "rate", "0.03", "amount", "0.082192"));
        apply(
                org,
                "FEE_BILL_ISSUED",
                fee,
                1,
                Map.of(
                        "loanId",
                        loan,
                        "periodStart",
                        "2026-11-01",
                        "periodEnd",
                        "2026-11-30",
                        "rate",
                        "0.03",
                        "amount",
                        "123.456789",
                        "dueDate",
                        "2026-12-01"));
        long request = n(payments.payFee(org, fee, "fee-payment"), "id");
        payments.payFee(org, fee, "fee-payment");
        assertEquals("DUE", db.owned(LendingFee.class, org, fee).get("status"));
        assertEquals(0, bd("123.456789").compareTo(money(account(org), "blocked")));
        apply(org, "PAYMENT_CONFIRMED", request, 1, Map.of("amount", "123.456789"));
        assertEquals("PAID", db.owned(LendingFee.class, org, fee).get("status"));
        assertTrue(
                db.rows(OutboxEvent.class, eq("organizationId", org), eq("eventType", "FEE_PAYMENT_RECEIVED"))
                        .stream()
                        .anyMatch(
                                row ->
                                        Objects.equals(
                                                String.valueOf(row.get("aggregate_id")),
                                                String.valueOf(fee))));
        String path = "/api/v1/organizations/" + org + "/loans/" + loan;
        mvc.perform(get(path + "/fee-accruals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].type").value("FEE"));
        mvc.perform(get(path + "/fees"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].status").value("PAID"));
    }

    @Test
    void returnAndLateClosureFeeConfirmationsMarkBillsPaidWithoutDuplicateReceipts() throws Exception {
        for (int scenario = 0; scenario < 3; scenario++) {
            long org = org(), security = security("100");
            fund(org, "10000");
            long loan = loan(org, security, agreement(org, 0), 10), fee = sequence.incrementAndGet();
            apply(org, "LOAN_SETTLED", loan, 1, Map.of());
            var bill = Map.<String,Object>of("loanId", loan, "periodStart", "2026-11-01", "periodEnd", "2026-11-02",
                "rate", "0.03", "amount", "100", "dueDate", "2026-11-03");
            if (scenario != 2) apply(org, "FEE_BILL_ISSUED", fee, 1, bill);
            long ret = n(lending.requestReturn(org, loan, new ReturnRequest(scenario == 0 ? 4 : 10)), "id");
            apply(org, "RETURN_SETTLED", ret, 1, Map.of("reference", "return-paid"));
            if (scenario == 2) apply(org, "FEE_BILL_ISSUED", fee, 1, bill);
            var payment = db.rows(PaymentRequest.class, eq("feeId", fee)).get(0);
            assertEquals("SETTLEMENT_SUBMITTED", payment.get("status"));
            assertEquals("PAYMENT_PENDING", db.owned(LendingFee.class, org, fee).get("status"));
            var cash = money(account(org), "available");
            apply(org, "PAYMENT_CONFIRMED", n(payment, "id"), 1, Map.of("amount", "100"));
            var paidAt = db.owned(LendingFee.class, org, fee).get("paid_at");
            assertNotNull(paidAt);
            assertEquals("PAID", db.owned(LendingFee.class, org, fee).get("status"));
            // A repeated lender acknowledgement must not debit or release cash a second time.
            apply(org, "PAYMENT_CONFIRMED", n(payment, "id"), 2, Map.of("amount", "100"));
            assertEquals(paidAt, db.owned(LendingFee.class, org, fee).get("paid_at"));
            assertEquals(0, cash.compareTo(money(account(org), "available")));
            assertEquals(0, money(account(org), "blocked").signum());
            assertEquals(1, db.rows(OutboxEvent.class, eq("organizationId", org), eq("eventType", "FEE_PAYMENT_RECEIVED")).size());
            mvc.perform(get("/api/v1/organizations/" + org + "/fees"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].status").value("PAID"));
        }
    }

    @Test
    void confirmedBillingMigrationRepairsOnlyProvenPaymentsWithoutCashMovements() throws Exception {
        long org = org(), security = security("100");
        fund(org, "10000");
        long loan = loan(org, security, agreement(org, 0), 10);
        long paidFee = sequence.incrementAndGet(), pendingFee = sequence.incrementAndGet();
        for (int i = 0; i < 2; i++) {
            String day = "2026-11-0" + (i + 1);
            apply(org, "FEE_BILL_ISSUED", i == 0 ? paidFee : pendingFee, 1, Map.of("loanId", loan,
                "periodStart", day, "periodEnd", day, "rate", "0.03", "amount", "100", "dueDate", "2026-12-01"));
        }
        long paid = n(payments.payFee(org, paidFee, "paid"), "id");
        apply(org, "PAYMENT_CONFIRMED", paid, 1, Map.of("amount", "100"));
        payments.payFee(org, pendingFee, "still-pending");
        db.edit(LendingFee.class, paidFee, f -> { f.setStatus("PAYMENT_PENDING"); f.setPaidAt(null); });
        long rebate = sequence.incrementAndGet();
        apply(org, "REBATE_ACCRUED", rebate, 1, Map.of("loanId", loan, "rate", "0.01", "amount", "10", "date", "2026-11-01", "paymentDate", "2026-11-02"));
        apply(org, "REBATE_PAID", rebate, 2, Map.of("amount", "10"));
        db.edit(Rebate.class, rebate, r -> r.setStatus("ACCRUED"));
        var cash = money(account(org), "available");
        var blocked = money(account(org), "blocked");
        String sql = new org.springframework.core.io.ClassPathResource("db/migration/V19__reconcile_confirmed_billing_status.sql")
            .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        billingEm.flush();
        for (int attempt = 0; attempt < 2; attempt++) for (String statement : sql.split(";"))
            if (!statement.isBlank()) billingEm.createNativeQuery(statement).executeUpdate();
        billingEm.clear();
        assertEquals("PAID", db.owned(LendingFee.class, org, paidFee).get("status"));
        assertNotNull(db.owned(LendingFee.class, org, paidFee).get("paid_at"));
        assertEquals("DUE", db.owned(LendingFee.class, org, pendingFee).get("status"));
        assertEquals("PAID", db.owned(Rebate.class, org, rebate).get("status"));
        assertEquals(0, cash.compareTo(money(account(org), "available")));
        assertEquals(0, blocked.compareTo(money(account(org), "blocked")));
        assertEquals(1, db.rows(RebatePayment.class, eq("organizationId", org)).size());
    }

    @Test
    void legacySettledReturnFeeRepairSubmitsReservedCashOnce() {
        long org = org(), security = security("100");
        fund(org, "10000");
        long loan = loan(org, security, agreement(org, 0), 10), fee = sequence.incrementAndGet();
        apply(org, "LOAN_SETTLED", loan, 1, Map.of());
        apply(org, "FEE_BILL_ISSUED", fee, 1, Map.of("loanId", loan, "periodStart", "2026-11-01", "periodEnd", "2026-11-02",
            "rate", "0.03", "amount", "100", "dueDate", "2026-11-03"));
        long ret = n(lending.requestReturn(org, loan, new ReturnRequest(4)), "id");
        var request = db.rows(PaymentRequest.class, eq("feeId", fee)).get(0);
        var available = money(account(org), "available");
        payments.resumeSettledReturnFee(org, n(request, "id"));
        assertEquals("PENDING", db.owned(PaymentRequest.class, org, n(request, "id")).get("status"));
        // Reproduce the old partial-return state, before the fee receipt was ever queued.
        db.edit(LoanReturn.class, ret, e -> { e.setStatus("SETTLED"); e.setSettledAt(Instant.now()); });
        db.edit(Loan.class, loan, e -> { e.setOutstandingQuantity(6L); e.setStatus("ACTIVE"); });
        payments.resumeSettledReturnFee(org, n(request, "id"));
        payments.resumeSettledReturnFee(org, n(request, "id"));
        assertEquals("SETTLEMENT_SUBMITTED", db.owned(PaymentRequest.class, org, n(request, "id")).get("status"));
        assertEquals(0, available.compareTo(money(account(org), "available")));
        assertEquals(0, bd("100").compareTo(money(account(org), "blocked")));
        assertEquals(1, db.rows(OutboxEvent.class, eq("organizationId", org), eq("eventType", "FEE_PAYMENT_RECEIVED")).size());
    }

    @Test
    void fullLoanReturnReservesFeesThenSettlesThemAndReleasesCollateral() throws Exception {
        long org = org(), security = security("100");
        fund(org, "20000");
        long loanId = loan(org, security, agreement(org, security), 10);
        long fee = sequence.incrementAndGet();
        apply(
                org,
                "FEE_BILL_ISSUED",
                fee,
                1,
                Map.of(
                        "loanId",
                        loanId,
                        "periodStart",
                        "2026-11-01",
                        "periodEnd",
                        "2026-11-30",
                        "rate",
                        "0.03",
                        "amount",
                        "123.456789",
                        "dueDate",
                        "2026-12-01"));
        var position =
                db.rows(CollateralPosition.class, eq("organizationId", org), eq("loanId", loanId)).get(0);
        apply(org, "COLLATERAL_PLEDGED", n(position, "id"), 1, Map.of());
        apply(org, "LOAN_SETTLED", loanId, 1, Map.of());
        long returnId = n(lending.requestReturn(org, loanId, new ReturnRequest(10)), "id");
        var positions =
                db.rows(CollateralPosition.class, eq("organizationId", org), eq("loanId", loanId));
        assertFalse(positions.isEmpty());
        assertEquals("POSTED", positions.get(0).get("status"));
        assertEquals("PAYMENT_PENDING", db.owned(LendingFee.class, org, fee).get("status"));
        apply(org, "RETURN_SETTLED", returnId, 1, Map.of("reference", "return-1"));
        assertTrue(
                db.rows(OutboxEvent.class, eq("organizationId", org), eq("eventType", "FEE_PAYMENT_RECEIVED"))
                        .stream()
                        .anyMatch(row -> Objects.equals(String.valueOf(row.get("aggregate_id")), String.valueOf(fee))));
    }

  @Test
  void exhaustedReturnRestoresExactReservationsOnceAndRetryReservesAFreshRequest() throws Exception {
    long org = org(),
      security = security("100"),
      agreement = agreement(org, 0);
    fund(org, "10000");
    long loan = loan(org, security, agreement, 10);
    var position = db
      .rows(
        CollateralPosition.class,
        eq("organizationId", org),
        eq("loanId", loan)
      )
      .get(0);
    apply(org, "COLLATERAL_PLEDGED", n(position, "id"), 1, Map.of());
    apply(org, "LOAN_SETTLED", loan, 1, Map.of());
    db.edit(Holding.class, new HoldingId(org, security), h -> {
      h.setBorrowedQuantity(4L);
      h.setOwnedQuantity(6L);
      h.setAverageCost(bd("50"));
    });
    long fee = sequence.incrementAndGet();
    apply(
      org,
      "FEE_BILL_ISSUED",
      fee,
      1,
      Map.of(
        "loanId",
        loan,
        "periodStart",
        "2026-11-01",
        "periodEnd",
        "2026-11-02",
        "rate",
        "0.03",
        "amount",
        "100",
        "dueDate",
        "2026-11-03"
      )
    );
    BigDecimal availableBefore = money(account(org), "available"),
      blockedBefore = money(account(org), "blocked");
    long ret = n(lending.requestReturn(org, loan, new ReturnRequest(10)), "id");
    var outbound = db
      .entities(
        OutboxEvent.class,
        Sort.unsorted(),
        1,
        eq("organizationId", org),
        eq("eventType", "RETURN_REQUESTED"),
        eq("aggregateId", String.valueOf(ret))
      )
      .get(0);
    var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
    String eventId = mapper
      .readTree(outbound.getPayload())
      .path("eventId")
      .asText();
    returnRecovery.markFailed(org, ret, eventId, "Kafka retries exhausted");
    assertEquals("FAILED", db.find(LoanReturn.class, ret).getStatus());
    assertFalse(db.find(LoanReturn.class, ret).isAssetsReleased());
    // Owned inventory purchased after the return was reserved must keep its correct cost.
    db.edit(Holding.class, new HoldingId(org, security), h -> {
      h.setOwnedQuantity(2L);
      h.setAverageCost(bd("100"));
    });
    var confirmation = event(
      org,
      "RETURN_REJECTED",
      ret,
      1,
      Map.of("recoveryEventId", eventId)
    );
    byte[] response = mapper
      .writeValueAsString(Map.of("success",true,"data",confirmation))
      .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var server = com.sun.net.httpserver.HttpServer.create(
      new java.net.InetSocketAddress("127.0.0.1", 0),
      0
    );
    server.createContext("/api/v1/transport/returns/cancel", exchange -> {
      exchange.getRequestBody().readAllBytes();
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    });
    server.start();
    try {
      ReflectionTestUtils.setField(
        returnRecovery,
        "lenderUrl",
        "http://127.0.0.1:" + server.getAddress().getPort()
      );
      returnRecovery.reconcile(outbound);
      returnRecovery.reconcile(outbound);
    } finally {
      server.stop(0);
      ReflectionTestUtils.setField(
        returnRecovery,
        "lenderUrl",
        "http://localhost:8084"
      );
    }
    assertEquals("FAILED", db.find(LoanReturn.class, ret).getStatus());
    assertTrue(db.find(LoanReturn.class, ret).isAssetsReleased());
    var holding = db.find(Holding.class, new HoldingId(org, security));
    assertEquals(4, holding.getBorrowedQuantity());
    assertEquals(8, holding.getOwnedQuantity());
    assertEquals(0, bd("62.5").compareTo(holding.getAverageCost()));
    assertEquals(
      0,
      availableBefore.compareTo(money(account(org), "available"))
    );
    assertEquals(0, blockedBefore.compareTo(money(account(org), "blocked")));
    assertEquals("DUE", db.find(LendingFee.class, fee).getStatus());
    assertEquals("ACTIVE", db.find(Loan.class, loan).getStatus());
    assertEquals(
      "POSTED",
      db.find(CollateralPosition.class, n(position, "id")).getStatus()
    );
    apply(org, "RETURN_REJECTED", ret, 2, Map.of());
    assertEquals(8, holding.getOwnedQuantity());
    long replacement = returnRecovery.retry(org, ret);
    assertNotEquals(ret, replacement);
    assertEquals(replacement, returnRecovery.retry(org, ret));
    assertEquals("PENDING", db.find(LoanReturn.class, replacement).getStatus());
    assertEquals(2, holding.getOwnedQuantity());
    assertEquals(0, holding.getBorrowedQuantity());
    assertEquals(
      0,
      blockedBefore.add(bd("100")).compareTo(money(account(org), "blocked"))
    );
  }

  @Test
  void failedReturnKeepsAssetsReservedUntilCancellationIsAcknowledged() {
    long org = org(),
      sec = security("100"),
      agreement = agreement(org, 0);
    fund(org, "10000");
    long loan = loan(org, sec, agreement, 10);
    apply(org, "LOAN_SETTLED", loan, 1, Map.of());
    long ret = n(lending.requestReturn(org, loan, new ReturnRequest(10)), "id");
    returnRecovery.markFailed(
      org,
      ret,
      "failed-event",
      "FIX attempts exhausted"
    );
    assertEquals("FAILED", db.find(LoanReturn.class, ret).getStatus());
    assertFalse(db.find(LoanReturn.class, ret).isAssetsReleased());
    assertEquals(
      0,
      db.find(Holding.class, new HoldingId(org, sec)).getBorrowedQuantity()
    );
    assertThrows(ResponseStatusException.class, () ->
      lending.requestReturn(org, loan, new ReturnRequest(10))
    );
    assertThrows(ResponseStatusException.class, () ->
      returnRecovery.retry(org, ret)
    );
  }

  @Test
  void aReturnSettledBeforeFailureReconciliationNeverRestoresReturnedSecurities() {
    long org = org(),
      sec = security("100"),
      agreement = agreement(org, 0);
    fund(org, "10000");
    long loan = loan(org, sec, agreement, 10);
    apply(org, "LOAN_SETTLED", loan, 1, Map.of());
    long ret = n(lending.requestReturn(org, loan, new ReturnRequest(10)), "id");
    returnRecovery.markFailed(org, ret, "failed-event", "receipt lost");
    apply(org, "RETURN_SETTLED", ret, 1, Map.of());
    assertEquals("SETTLED", db.find(LoanReturn.class, ret).getStatus());
    assertEquals("CLOSED", db.find(Loan.class, loan).getStatus());
    assertEquals(
      0,
      db.find(Holding.class, new HoldingId(org, sec)).getBorrowedQuantity()
    );
    assertFalse(
      returnRecovery.markFailed(org, ret, "failed-event", "receipt lost")
    );
  }

    @Test
    void partialCashReleaseIsLimitedToExcessAndCreditsOnlyTheConfirmedAmount() throws Exception {
      long org = org(),
        sec = security("100");
      fund(org, "10000");
      long loanId = loan(org, sec, agreement(org, 0), 10);
      var p = db.rows(CollateralPosition.class, eq("loanId", loanId)).get(0);
      long id = n(p, "id");
      apply(org, "COLLATERAL_PLEDGED", id, 1, Map.of());
      apply(org, "LOAN_SETTLED", loanId, 1, Map.of());
      assertEquals(
        0,
        bd("950").compareTo(
          (BigDecimal) collateral
            .releasePreview(org, loanId)
            .get("availableExcess")
        )
      );
      assertThrows(ResponseStatusException.class, () ->
        collateral.requestRelease(
          org,
          id,
          new CollateralReleaseRequest(bd("951"), null)
        )
      );
      assertThrows(ResponseStatusException.class, () ->
        collateral.requestRelease(org, id, new CollateralReleaseRequest(null, 1L))
      );
      BigDecimal cashBefore = money(
        db.matchingRow(TraderAccount.class, eq("organizationId", org)),
        "available"
      );
      mvc
        .perform(
          post("/api/v1/organizations/" + org + "/collateral/" + id + "/release")
            .contentType("application/json")
            .content("{\"cashAmount\":500}")
        )
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.requested_release_cash").value(500));
      assertEquals(
        0,
        bd("450").compareTo(
          (BigDecimal) collateral
            .releasePreview(org, loanId)
            .get("availableExcess")
        )
      );
      assertEquals(
        0,
        cashBefore.compareTo(
          money(
            db.matchingRow(TraderAccount.class, eq("organizationId", org)),
            "available"
          )
        )
      );
      var confirmation = event(
        org,
        "COLLATERAL_RELEASED",
        id,
        2,
        Map.of("cashAmount", bd("500"), "quantity", 0)
      );
      projections.apply(confirmation);
      projections.apply(confirmation);
      var after = db.owned(CollateralPosition.class, org, id);
      assertEquals("POSTED", after.get("status"));
      assertEquals(0, bd("1500").compareTo(money(after, "cash_amount")));
      var account = db.matchingRow(
        TraderAccount.class,
        eq("organizationId", org)
      );
      assertEquals(
        0,
        cashBefore.add(bd("500")).compareTo(money(account, "available"))
      );
      assertEquals(0, bd("1500").compareTo(money(account, "pledged")));
      collateral.requestRelease(
        org,
        id,
        new CollateralReleaseRequest(bd("450"), null)
      );
      apply(
        org,
        "COLLATERAL_RELEASE_REJECTED",
        id,
        3,
        Map.of("reason", "Keep coverage buffer")
      );
      assertNull(
        db.owned(CollateralPosition.class, org, id).get("requested_release_cash")
      );
      assertEquals(
        "POSTED",
        db.owned(CollateralPosition.class, org, id).get("status")
      );
      assertEquals(
        "REJECTED",
        db.owned(CollateralPosition.class, org, id).get("release_request_status")
      );
      assertEquals(
        "Keep coverage buffer",
        db
          .owned(CollateralPosition.class, org, id)
          .get("release_rejection_reason")
      );
      mvc
        .perform(get("/api/v1/organizations/" + org + "/collateral"))
        .andExpect(status().isOk())
        .andExpect(
          jsonPath("$.data.content[0].release_request_status").value("REJECTED")
        )
        .andExpect(
          jsonPath("$.data.content[0].release_rejection_reason").value(
            "Keep coverage buffer"
          )
        );
      assertEquals(
        0,
        bd("1500").compareTo(
          money(
            db.matchingRow(TraderAccount.class, eq("organizationId", org)),
            "pledged"
          )
        )
      );
      collateral.requestRelease(
        org,
        id,
        new CollateralReleaseRequest(bd("450"), null)
      );
      assertEquals(
        "PENDING",
        db.owned(CollateralPosition.class, org, id).get("release_request_status")
      );
      assertNull(
        db
          .owned(CollateralPosition.class, org, id)
          .get("release_rejection_reason")
      );
      // Older queued rejection envelopes stored their reason in reference.
      apply(
        org,
        "COLLATERAL_RELEASE_REJECTED",
        id,
        4,
        Map.of("reference", "Legacy rejection reason")
      );
      assertEquals(
        "Legacy rejection reason",
        db
          .owned(CollateralPosition.class, org, id)
          .get("release_rejection_reason")
      );
    }

    @Test
    void securityReleaseUsesHaircutAdjustedWholeUnitsAndUnpledgesOnlyConfirmedUnits() {
      long org = org(),
        sec = security("100");
      fund(org, "10000");
      long loanId = loan(org, sec, agreement(org, sec), 10);
      var cash = db.rows(CollateralPosition.class, eq("loanId", loanId)).get(0);
      apply(org, "COLLATERAL_PLEDGED", n(cash, "id"), 1, Map.of());
      apply(org, "LOAN_SETTLED", loanId, 1, Map.of());
      db.holding(org, sec);
      db.editMatching(
        Holding.class,
        h -> h.setOwnedQuantity(10L),
        eq("organizationId", org),
        eq("securityId", sec)
      );
      var securities = collateral.post(
        org,
        loanId,
        new AllocationRequest("SECURITY", sec, 10, BigDecimal.ZERO)
      );
      long positionId = n(securities, "id");
      apply(org, "COLLATERAL_PLEDGED", positionId, 1, Map.of());
      collateral.requestRelease(
        org,
        n(cash, "id"),
        new CollateralReleaseRequest(bd("1700"), null)
      );
      @SuppressWarnings("unchecked")
      var releasePositions = (List<Map<String, Object>>) collateral
        .releasePreview(org, loanId)
        .get("positions");
      var releaseSecurity = releasePositions
        .stream()
        .filter(x -> n(x, "id") == positionId)
        .findFirst()
        .orElseThrow();
      assertEquals("DEMO" + sec, releaseSecurity.get("securitySymbol"));
      assertEquals("NSE", releaseSecurity.get("exchange"));
      assertEquals(1L, releaseSecurity.get("maxQuantity"));
      assertThrows(ResponseStatusException.class, () ->
        collateral.requestRelease(
          org,
          positionId,
          new CollateralReleaseRequest(null, 2L)
        )
      );
      collateral.requestRelease(
        org,
        positionId,
        new CollateralReleaseRequest(null, 1L)
      );
      apply(
        org,
        "COLLATERAL_RELEASED",
        positionId,
        2,
        Map.of("cashAmount", BigDecimal.ZERO, "quantity", 1)
      );
      assertEquals(
        9,
        n(db.owned(CollateralPosition.class, org, positionId), "quantity")
      );
      assertEquals(
        9,
        n(
          db.matchingRow(
            Holding.class,
            eq("organizationId", org),
            eq("securityId", sec)
          ),
          "pledged_quantity"
        )
      );
    }

    @Test
    void pendingReleasesConsumeExcessAcrossLotsAndUnconfirmedPledgesDoNotCount() {
      long org = org(),
        sec = security("100");
      fund(org, "10000");
      long loanId = loan(org, sec, agreement(org, 0), 10);
      var initial = db
        .rows(CollateralPosition.class, eq("loanId", loanId))
        .get(0);
      apply(org, "COLLATERAL_PLEDGED", n(initial, "id"), 1, Map.of());
      apply(org, "LOAN_SETTLED", loanId, 1, Map.of());
      var second = collateral.post(
        org,
        loanId,
        new AllocationRequest("CASH", null, 0, bd("500"))
      );
      assertEquals(
        0,
        bd("950").compareTo(
          (BigDecimal) collateral
            .releasePreview(org, loanId)
            .get("availableExcess")
        )
      );
      apply(org, "COLLATERAL_PLEDGED", n(second, "id"), 1, Map.of());
      collateral.requestRelease(
        org,
        n(initial, "id"),
        new CollateralReleaseRequest(bd("1200"), null)
      );
      assertThrows(ResponseStatusException.class, () ->
        collateral.requestRelease(
          org,
          n(second, "id"),
          new CollateralReleaseRequest(bd("251"), null)
        )
      );
      collateral.requestRelease(
        org,
        n(second, "id"),
        new CollateralReleaseRequest(bd("250"), null)
      );
      assertEquals(
        0,
        (
          (BigDecimal) collateral
            .releasePreview(org, loanId)
            .get("availableExcess")
        ).signum()
      );
    }

    @Test
    void returnSettlementClosesLoanAndAcceptsTheLenderCollateralReleaseConfirmation() {
        long org = org(), security = security("100");
        fund(org, "20000");
        long loanId = loan(org, security, agreement(org, security), 10);
        var position = db.rows(CollateralPosition.class, eq("organizationId", org), eq("loanId", loanId)).get(0);
        apply(org, "COLLATERAL_PLEDGED", n(position, "id"), 1, Map.of());
        long returnId = db.create(LoanReturn::new, entity -> {
            entity.setOrganizationId(org);
            entity.setLoanId(loanId);
            entity.setQuantity(10L);
            entity.setOwnedQuantity(10L);
            entity.setBorrowedQuantity(0L);
            entity.setStatus("PENDING");
            entity.setOwnedCost(BigDecimal.ZERO);
        });
        db.edit(Loan.class, loanId, entity -> {
            entity.setStatus("RETURN_PENDING");
            entity.setOutstandingQuantity(10L);
        });

        apply(org, "RETURN_SETTLED", returnId, 1, Map.of("reference", "return-1"));
        apply(org, "COLLATERAL_RELEASED", n(position, "id"), 2, Map.of("reference", "release-1"));

        assertEquals("CLOSED", db.owned(Loan.class, org, loanId).get("status"));
        assertEquals("RELEASED", db.owned(CollateralPosition.class, org, n(position, "id")).get("status"));
    }

    @Test
    void externalMarginUpdatesAreProjectedWithoutCreatingCallsOrDefaults() {
        long org = org(), security = security("100");
        fund(org, "10000");
        long loan = loan(org, security, agreement(org, 0), 10), call = sequence.incrementAndGet();
        var fields = new HashMap<String, Object>();
        fields.put("loanId", loan);
        fields.put("externalId", "margin-" + call);
        fields.put("amount", "300");
        fields.put("fulfilledAmount", "0");
        fields.put("reason", "End-of-day valuation");
        fields.put("status", "OPEN");
        fields.put("dueAt", Instant.now().minusSeconds(3600).toString());
        apply(org, "MARGIN_CALL_UPDATED", call, 1, fields);
        assertEquals("OPEN", db.owned(MarginCall.class, org, call).get("status"));
        assertTrue(db.rows(LoanDefault.class, eq("organizationId", org)).isEmpty());
        fields.put("status", "OVERDUE");
        apply(org, "MARGIN_CALL_UPDATED", call, 2, fields);
        fields.put("status", "OPEN");
        apply(org, "MARGIN_CALL_UPDATED", call, 1, fields);
        assertEquals("OVERDUE", db.owned(MarginCall.class, org, call).get("status"));
    }

    @Test
    void lifecycleCreatedPendingMarginCallIsProjectedAsOpen() {
        long org = org(), security = security("100");
        fund(org, "10000");
        long loan = loan(org, security, agreement(org, 0), 10), call = sequence.incrementAndGet();
        var fields = new HashMap<String, Object>();
        fields.put("loanId", loan);
        fields.put("externalId", "margin-" + call);
        fields.put("amount", "300");
        fields.put("fulfilledAmount", "0");
        fields.put("reason", "Coverage ratio below the MSLA threshold.");
        fields.put("status", "PENDING");
        fields.put("dueAt", Instant.now().plusSeconds(3600).toString());
        apply(org, "MARGIN_CALL_UPDATED", call, 1, fields);
        assertEquals("OPEN", db.owned(MarginCall.class, org, call).get("status"));
    }

    @Test
    void organizationIsolationPreventsCrossOrganizationOperations() {
        long first = org(), second = org(), security = security("100");
        fund(first, "10000");
        long loan = loan(first, security, agreement(first, 0), 10);
        assertThrows(
                ResponseStatusException.class,
                () -> lending.requestReturn(second, loan, new ReturnRequest(1)));
        assertThrows(ResponseStatusException.class, () -> portfolio.loan(second, loan));
    }

    @Test
    void marginCallResponseAllocatesCollateralButDoesNotFulfillTheCallLocally() {
        long org = org(), security = security("100");
        fund(org, "10000");
        long loan = loan(org, security, agreement(org, 0), 10), call = sequence.incrementAndGet();
        apply(org, "LOAN_SETTLED", loan, 1, Map.of());
        var fields = new HashMap<String, Object>();
        fields.put("loanId", loan);
        fields.put("externalId", "call-" + call);
        fields.put("amount", "300");
        fields.put("fulfilledAmount", "0");
        fields.put("reason", "External EOD check");
        fields.put("status", "OPEN");
        fields.put("dueAt", Instant.now().plusSeconds(3600).toString());
        apply(org, "MARGIN_CALL_UPDATED", call, 1, fields);
        long position =
                n(
                        collateral.respondToMarginCall(
                                org, call, new AllocationRequest("CASH", null, 0, bd("300"))),
                        "id");
        assertEquals(call, n(db.owned(CollateralPosition.class, org, position), "margin_call_id"));
        apply(org, "COLLATERAL_PLEDGED", position, 1, Map.of());
        assertEquals("OPEN", db.owned(MarginCall.class, org, call).get("status"));
        fields.put("fulfilledAmount", "300");
        fields.put("status", "FULFILLED");
        apply(org, "MARGIN_CALL_UPDATED", call, 2, fields);
        assertEquals("FULFILLED", db.owned(MarginCall.class, org, call).get("status"));
    }

    @Test
    void reduceExposureCreatesASettlementGatedReturnForMarginShortfall() throws Exception {
        long org = org(), security = security("100");
        fund(org, "10000");
        long loan = loan(org, security, agreement(org, 0), 10), call = sequence.incrementAndGet();
        apply(org, "LOAN_SETTLED", loan, 1, Map.of());
        db.holding(org, security);
        db.edit(Holding.class, new HoldingId(org, security), h -> h.setBorrowedQuantity(10L));
        var fields = new HashMap<String, Object>();
        fields.put("loanId", loan);
        fields.put("externalId", "reduce-exposure-" + call);
        fields.put("amount", "250");
        fields.put("fulfilledAmount", "0");
        fields.put("reason", "Collateral shortfall");
        fields.put("status", "OPEN");
        fields.put("dueAt", Instant.now().plusSeconds(3600).toString());
        apply(org, "MARGIN_CALL_UPDATED", call, 1, fields);

        var result = lending.reduceExposure(org, call);
        long returnId = n(result, "id");
        var returned = db.owned(LoanReturn.class, org, returnId);
        assertEquals(3L, n(returned, "quantity"));
        assertEquals(call, n(returned, "margin_call_id"));
        assertEquals("PENDING", returned.get("status"));
        assertEquals("OPEN", db.owned(MarginCall.class, org, call).get("status"));
        assertEquals(7L, n(db.owned(Loan.class, org, loan), "outstanding_quantity"));

        var outbox = db.rows(OutboxEvent.class, eq("organizationId", org), eq("eventType", "RETURN_REQUESTED"));
        var payload = tools.jackson.databind.json.JsonMapper.builder().build().readTree(outbox.get(outbox.size()-1).get("payload").toString());
        assertEquals(call, payload.path("data").path("marginCallId").asLong());
        assertEquals(3L, payload.path("data").path("quantity").asLong());

        apply(org, "RETURN_SETTLED", returnId, 1, Map.of());
        assertEquals("OPEN", db.owned(MarginCall.class, org, call).get("status"));
        var updated = new HashMap<>(fields);
        updated.put("fulfilledAmount", "250");
        updated.put("status", "FULFILLED");
        apply(org, "MARGIN_CALL_UPDATED", call, 2, updated);
        assertEquals("FULFILLED", db.owned(MarginCall.class, org, call).get("status"));
    }

    @Test
    void marginCancellationChecksUnconfirmedAndConfirmedPledgesAndFencesNewResponses() {
        for (int response = 0; response < 3; response++) {
            long org = org(), security = security("100");
            fund(org, "10000");
            long loan = loan(org, security, agreement(org, 0), 10), call = sequence.incrementAndGet();
            apply(org, "LOAN_SETTLED", loan, 1, Map.of());
            var fields = new HashMap<String, Object>();
            fields.put("loanId", loan); fields.put("externalId", "call-" + call);
            fields.put("amount", "300"); fields.put("fulfilledAmount", "0");
            fields.put("reason", "Shortfall"); fields.put("status", "OPEN");
            fields.put("issuedAt", Instant.now().minusSeconds(60).toString());
            fields.put("dueAt", Instant.now().plusSeconds(86400).toString());
            if (response > 0) apply(org, "MARGIN_CALL_UPDATED", call, 1, fields);
            if (response > 0) {
                long position = n(collateral.respondToMarginCall(org, call,
                    new AllocationRequest("CASH", null, 0, bd("100"))), "id");
                if (response == 2) apply(org, "COLLATERAL_PLEDGED", position, 1, Map.of());
            }
            fields.put("cancellationRequested", true); fields.put("cancellationReference", "cancel-" + call);
            var cancellation = event(org, "MARGIN_CALL_UPDATED", call, 2, fields);
            projections.apply(cancellation); projections.apply(cancellation);
            assertEquals(response == 0 ? "CANCELLED" : "OPEN", db.owned(MarginCall.class, org, call).get("status"));
            var decisions = db.rows(OutboxEvent.class, eq("organizationId", org), eq("eventType", "MARGIN_CALL_CANCELLATION_RESPONDED"));
            assertEquals(1, decisions.size());
            var payload = tools.jackson.databind.json.JsonMapper.builder().build().readTree(decisions.get(0).get("payload").toString());
            assertEquals(response == 0, payload.path("data").path("accepted").asBoolean());
            assertEquals("COUNTERPARTY", payload.path("destination").asText());
            assertTrue(payload.hasNonNull("mslaId")); assertTrue(payload.hasNonNull("lenderExternalId"));
            if (response == 0) {
                assertThrows(ResponseStatusException.class, () -> collateral.respondToMarginCall(org, call,
                    new AllocationRequest("CASH", null, 0, bd("100"))));
                fields.remove("cancellationRequested");
                apply(org, "MARGIN_CALL_UPDATED", call, 1, fields);
                assertEquals("CANCELLED", db.owned(MarginCall.class, org, call).get("status"));
            }
        }
    }

    @Test
    void recallResponseWaitsForExternalRecallStatusUpdates() {
        long org = org(), security = security("100");
        fund(org, "10000");
        long loan = loan(org, security, agreement(org, 0), 10), recall = sequence.incrementAndGet();
        apply(org, "LOAN_SETTLED", loan, 1, Map.of());
        var fields =
                Map.<String, Object>of(
                        "loanId",
                        loan,
                        "externalId",
                        "recall-" + recall,
                        "quantity",
                        5,
                        "fulfilledQuantity",
                        0,
                        "reason",
                        "Portfolio sale",
                        "status",
                        "OPEN",
                        "dueAt",
                        Instant.now().plusSeconds(3600).toString());
        apply(org, "RECALL_UPDATED", recall, 1, fields);
        long ret = n(lending.respondToRecall(org, recall, new ReturnRequest(5)), "id");
        assertEquals(recall, n(db.owned(LoanReturn.class, org, ret), "recall_id"));
        apply(org, "RETURN_SETTLED", ret, 1, Map.of());
        assertEquals("OPEN", db.owned(Recall.class, org, recall).get("status"));
        assertEquals(5, n(db.owned(Loan.class, org, loan), "outstanding_quantity"));
        var fulfilled = new HashMap<>(fields);
        fulfilled.put("fulfilledQuantity", 5);
        fulfilled.put("status", "FULFILLED");
        apply(org, "RECALL_UPDATED", recall, 2, fulfilled);
        apply(org, "RECALL_UPDATED", recall, 1, fields);
        assertEquals("FULFILLED", db.owned(Recall.class, org, recall).get("status"));
        assertEquals(5, n(db.owned(Recall.class, org, recall), "fulfilled_quantity"));
    }

    @Test
    void securitiesCollateralLiquidationRemovesSoldHoldingsAndHoldsCashUntilSettlement() {
        long org=org(), security=security("100"), pledgedSecurity=security("200");
        fund(org,"20000");
        long loan=loan(org,security,agreement(org,pledgedSecurity),10);
        apply(org,"LOAN_SETTLED",loan,1,Map.of());
        db.holding(org,pledgedSecurity);
        db.edit(Holding.class,new HoldingId(org,pledgedSecurity),h->{h.setOwnedQuantity(100L);h.setAverageCost(bd("100"));});
        long position=n(collateral.post(org,loan,new AllocationRequest("SECURITY",pledgedSecurity,60,BigDecimal.ZERO)),"id");
        apply(org,"COLLATERAL_PLEDGED",position,1,Map.of());
        var available=money(account(org),"available");var pledged=money(account(org),"pledged");
        var data=Map.<String,Object>of("loanId",loan,"securityId",pledgedSecurity,"quantity",60,"price","200","proceeds","12000","reference","SECURITY-SALE");
        var sale=event(org,"COLLATERAL_LIQUIDATED",position,2,data);
        projections.apply(sale);projections.apply(sale);
        var holding=db.find(Holding.class,new HoldingId(org,pledgedSecurity));
        assertEquals(40,holding.getOwnedQuantity());assertEquals(0,holding.getPledgedQuantity());
        assertEquals(0,bd("6000").compareTo(holding.getRealizedPnl()));
        var lot=db.owned(CollateralPosition.class,org,position);
        assertEquals("CASH",lot.get("asset_type"));assertEquals(0,bd("12000").compareTo(money(lot,"cash_amount")));
        assertEquals(0,available.compareTo(money(account(org),"available")));
        assertEquals(0,pledged.add(bd("12000")).compareTo(money(account(org),"pledged")));
        assertEquals(1,db.rows(TraderAccountTransaction.class,eq("organizationId",org),eq("type","COLLATERAL_SECURITY_SOLD")).size());
        apply(org,"COLLATERAL_APPLIED",position,3,Map.of("amount","10005","reference","SECURITY-CLOSE"));
        apply(org,"COLLATERAL_RELEASED",position,4,Map.of("cashAmount","1995","quantity",0));
        assertEquals(0,available.add(bd("1995")).compareTo(money(account(org),"available")));
        assertEquals(0,pledged.compareTo(money(account(org),"pledged")));
        assertEquals(40,holding.getOwnedQuantity());
    }

    @Test
    void externallyCalculatedRebatesCreditExactlyTheReportedAmount() throws Exception {
        long org = org(), security = security("100");
        fund(org, "10000");
        long loan = loan(org, security, agreement(org, 0), 10), rebate = sequence.incrementAndGet();
        apply(
                org,
                "ACCRUAL_REPORTED",
                rebate,
                1,
                Map.of("loanId", loan, "type", "REBATE", "date", "2026-11-08",
                        "baseValue", "2000", "rate", "0.0123456789", "amount", "12.345678"));
        apply(
                org,
                "REBATE_ACCRUED",
                rebate,
                1,
                Map.of(
                        "loanId",
                        loan,
                        "rate",
                        "0.0123456789",
                        "amount",
                        "12.345678",
                        "date",
                        "2026-11-08",
                        "paymentDate",
                        "2026-12-01",
                        "periodStart",
                        "2026-11-08",
                        "periodEnd",
                        "2026-11-30",
                        "settlementFrequency",
                        "MONTHLY"));
        assertEquals(
                0,
                bd("0.0123456789")
                        .compareTo(money(db.owned(Rebate.class, org, rebate), "rebate_rate")));
        var paid = event(org, "REBATE_PAID", rebate, 2, Map.of("amount", "12.345678"));
        projections.apply(paid);
        projections.apply(paid);
        assertEquals(0, bd("8012.345678").compareTo(money(account(org), "available")));
        String path = "/api/v1/organizations/" + org + "/loans/" + loan;
        mvc.perform(get(path + "/rebate-accruals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1));
        mvc.perform(get(path + "/rebate-settlements"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].settlementFrequency").value("MONTHLY"));
        mvc.perform(get(path + "/rebate-payments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void kafkaConfirmationContractDeserializesAndAppliesWithoutAnyFixEngine() {
        long org = org();
        long request =
                n(payments.funding(org, new FundingRequest(bd("123"), "json-funding"), true), "id");
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        new KafkaInboundService(projections, market)
                .confirmation(
                        json.writeValueAsString(
                                event(
                                        org,
                                        "PAYMENT_CONFIRMED",
                                        request,
                                        1,
                                        Map.of("amount", "123"))));
        assertEquals(0, bd("123").compareTo(money(account(org), "available")));
    }

    @Test
    void invalidRequestsReturnProblemsAndNoLenderManagementRoutesExist() throws Exception {
        long org = org();
        mvc.perform(
                        post("/api/v1/organizations/" + org + "/orders")
                                .contentType("application/json")
                                .content(
                                        "{\"securityId\":1,\"side\":\"BUY\",\"type\":\"MARKET\",\"source\":\"OWNED\",\"quantity\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("detail").value("Request validation failed"));
        for (String path : List.of("defaults", "buy-ins", "recalls", "margin-calls"))
            mvc.perform(post("/api/v1/organizations/" + org + "/" + path))
                    .andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/api/v1/organizations/" + org + "/loans/1/accrue"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/organizations/" + org + "/loans/1/risk-review"))
                .andExpect(status().isNotFound());
        assertFalse(context.containsBean("fixAdapter"));
        assertFalse(context.containsBean("riskService"));
        assertFalse(context.containsBean("accountingService"));
    }

    @Test
    void paginatedReadsAndLoanViewsAreAvailableForFrontend() throws Exception {
        long org = org();
        mvc.perform(get("/api/v1/organizations/" + org + "/orders?page=0&size=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.totalElements").value(0));
        mvc.perform(get("/api/v1/organizations/" + org + "/orders?size=201"))
                .andExpect(status().isBadRequest());
        assertNotNull(portfolio.dashboard(org));
    }

    @Test
    void lenderOffersPreserveCashOnlySecurityOnlyAndMixedCollateralRules() {
        long org = org(), sec = security("100"), lender = 1000000000001L;
        apply(org, "LENDER_REFERENCE_UPDATED", lender, 1, Map.of("name", "Registered lender", "externalId", "LENDORA_REGISTERED"));
        var cash = new LinkedHashMap<String,Object>(); cash.put("assetType", "CASH"); cash.put("securityId", null); cash.put("haircut", "0");
        var security = Map.<String,Object>of("assetType", "SECURITY", "securityId", sec, "haircut", "0.15");
        for (var rules : List.of(List.of(cash), List.of(security), List.of(cash, security))) {
            long ownerId = sequence.incrementAndGet();
            var data = new LinkedHashMap<String,Object>();
            data.put("lenderReferenceId", lender); data.put("lenderExternalId", "LENDORA_REGISTERED"); data.put("number", "OFFER-" + ownerId);
            data.put("effectiveDate", LocalDate.now().toString()); data.put("expiryDate", LocalDate.now().plusYears(1).toString());
            data.put("creditLimit", "1000000"); data.put("feeRate", "0.03"); data.put("rebateRate", "0.01"); data.put("coverageRatio", "1.05");
            data.put("billingFrequency", "MONTHLY"); data.put("rebateSettlementFrequency", "AT_CLOSE"); data.put("dayBasis", 365);
            data.put("recallAllowed", true); data.put("marginHours", 24); data.put("recallHours", 48); data.put("collateralRules", rules);
            apply(org, "AGREEMENT_OFFERED", ownerId, 1, data);
            var agreement = db.matchingRow(Msla.class, eq("organizationId", org), eq("lenderMslaId", ownerId));
            assertEquals("OFFERED", agreement.get("status"));
            apply(org, "AGREEMENT_OFFERED", ownerId, 1, data);
            assertEquals(1, db.rows(Msla.class, eq("organizationId", org), eq("lenderMslaId", ownerId)).size());
            var stored = db.rows(CollateralHaircut.class, eq("mslaId", n(agreement,"id")));
            assertEquals(rules.size(), stored.size());
            for (var rule : stored) assertEquals(0, bd("SECURITY".equals(rule.get("asset_type")) ? "0.15" : "0").compareTo(money(rule, "haircut")));
        }
    }
}
