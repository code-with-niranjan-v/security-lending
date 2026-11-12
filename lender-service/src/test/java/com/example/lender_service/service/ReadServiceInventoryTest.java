package com.example.lender_service.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.example.lender_service.dto.InventorySummary;
import com.example.lender_service.model.InventoryPosition;
import com.example.lender_service.model.SecurityMaster;
import com.example.lender_service.repository.*;
import com.example.lender_service.dto.MarketPrice;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class ReadServiceInventoryTest {
  @Mock private TenantService tenants;
  @Mock private LenderAccountRepository accounts;
  @Mock private SecurityMasterRepository securities;
  @Mock private CollateralHaircutRepository haircuts;
  @Mock private CollateralService preview;
  @Mock private MarketService market;
  @Mock private JsonService json;
  @Mock private NotificationRepository notification;
  @Mock private BorrowerConnectionRepository borrowerConnection;
  @Mock private LendingFeeRepository lendingFee;
  @Mock private CollateralPositionRepository collateralPosition;
  @Mock private RecallRepository recall;
  @Mock private MarginCallRepository marginCall;
  @Mock private InventoryHistoryRepository inventoryHistory;
  @Mock private InventoryPositionRepository inventoryPosition;
  @Mock private BuyInRepository buyIn;
  @Mock private LoanRepository loan;
  @Mock private AuditEventRepository auditEvent;
  @Mock private MslaRepository msla;
  @Mock private LenderAccountLedgerRepository lenderAccountLedger;
  @Mock private RebateRepository rebate;
  @Mock private LifecycleRequestRepository lifecycleRequest;
  @Mock private RebatePaymentRepository rebatePayment;
  @Mock private ReservationRepository reservation;
  @Mock private DailyAccrualRepository dailyAccrual;
  @Mock private UserRepository user;
  @Mock private LoanValuationRepository loanValuation;
  @Mock private LocateRequestRepository locateRequest;
  @Mock private LoanReturnRepository loanReturn;
  @Mock private CollateralHistoryRepository collateralHistory;
  @Mock private LoanDefaultRepository loanDefault;

  @Test
  void inventorySummariesReturnsSecurityLevelAvailabilityData() {
    long org = 7L;
    long securityId = 42L;
    var position = new InventoryPosition();
    position.setId(100L);
    position.setOrganizationId(org);
    position.setSecurityId(securityId);
    position.setTotalQuantity(1_000_000L);
    position.setReservedQuantity(180_000L);
    position.setLoanedQuantity(420_000L);

    var security = new SecurityMaster();
    security.setId(securityId);
    security.setSymbol("AAPL");
    security.setName("Apple Inc.");
    security.setIsin("US0378331005");
    security.setCurrency("USD");
    security.setPrice(new BigDecimal("192.45"));
    security.setPriceAt(Instant.parse("2026-11-12T10:00:00Z"));

    when(inventoryPosition.findByOrganizationId(eq(org), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(position)));
    when(securities.findById(securityId)).thenReturn(java.util.Optional.of(security));
    when(market.price(securityId)).thenReturn(new MarketPrice(new BigDecimal("192.45"), Instant.now()));

    var service =
        new ReadService(
            tenants,
            accounts,
            securities,
            haircuts,
            preview,
            market,
            json,
            notification,
            borrowerConnection,
            lendingFee,
            collateralPosition,
            recall,
            marginCall,
            inventoryHistory,
            inventoryPosition,
            buyIn,
            loan,
            auditEvent,
            msla,
            lenderAccountLedger,
            rebate,
            lifecycleRequest,
            rebatePayment,
            reservation,
            dailyAccrual,
            user,
            loanValuation,
            locateRequest,
            loanReturn,
            collateralHistory,
            loanDefault);

    var result = service.inventorySummaries(org, 0, 20);

    assertEquals(1, result.size());
    InventorySummary summary = result.get(0);
    assertEquals("AAPL", summary.symbol());
    assertEquals(1_000_000L, summary.totalQuantity());
    assertEquals(400_000L, summary.availableQuantity());
    assertEquals("Available", summary.availability());
    assertEquals(new BigDecimal("192.45"), summary.marketPrice());
  }
}
