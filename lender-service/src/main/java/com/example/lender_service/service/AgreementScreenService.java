package com.example.lender_service.service;

import static com.example.lender_service.service.Checks.found;
import com.example.lender_service.dto.AgreementScreen.*;
import com.example.lender_service.model.*;
import com.example.lender_service.repository.*;
import com.example.lender_service.response.PageResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AgreementScreenService {
  private static final BigDecimal HUNDRED = new BigDecimal("100");
  private static final BigDecimal BPS = new BigDecimal("10000");
  private static final Set<String> EXPOSED = Set.of("ACTIVE", "APPROVED_PENDING_SETTLEMENT", "DEFAULTED");
  private static final List<String> UNAVAILABLE = List.of("jurisdiction", "masterAgreementType", "concentrationLimits", "fixMessages");
  private final TenantService tenants;
  private final MslaRepository agreements;
  private final BorrowerConnectionRepository borrowers;
  private final LoanRepository loans;
  private final SecurityMasterRepository securities;
  private final CollateralHaircutRepository haircuts;
  private final LocateRequestRepository locates;
  private final ReservationRepository reservations;
  private final LendingFeeRepository fees;
  private final RebateRepository rebates;
  private final AuditEventRepository audits;
  private final JsonService json;

  public Overview overview(long org, int page, int size, Long connectionId, String status,
      Boolean recallAllowed, LocalDate activeOn, String search) {
    tenants.exists(org);
    if (page < 0 || size < 1 || size > 200) throw new IllegalArgumentException("page must be nonnegative; size must be 1..200");
    var connections = borrowers.findByOrganizationId(org);
    var connectionMap = connections.stream().collect(Collectors.toMap(BorrowerConnection::getId, Function.identity()));
    Specification<Msla> filter = (root, query, cb) -> {
      var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
      predicates.add(cb.equal(root.get("organizationId"), org));
      if (connectionId != null) predicates.add(cb.equal(root.get("connectionId"), connectionId));
      if (status != null && !status.isBlank()) predicates.add(cb.equal(root.get("status"), status.toUpperCase(Locale.ROOT)));
      if (recallAllowed != null) predicates.add(cb.equal(root.get("recallAllowed"), recallAllowed));
      if (activeOn != null) {
        predicates.add(cb.lessThanOrEqualTo(root.get("effectiveDate"), activeOn));
        predicates.add(cb.greaterThanOrEqualTo(root.get("expiryDate"), activeOn));
      }
      if (search != null && !search.isBlank()) {
        String term = search.trim().toLowerCase(Locale.ROOT);
        var ids = connections.stream().filter(c -> c.getName().toLowerCase(Locale.ROOT).contains(term)
            || c.getExternalId().toLowerCase(Locale.ROOT).contains(term)).map(BorrowerConnection::getId).toList();
        String pattern = "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        var numberMatches = cb.like(cb.lower(root.get("number")), pattern, '\\');
        predicates.add(ids.isEmpty() ? numberMatches : cb.or(numberMatches, root.get("connectionId").in(ids)));
      }
      return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
    };
    var result = agreements.findAll(filter, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")));
    var ids = result.getContent().stream().map(Msla::getId).toList();
    var related = ids.isEmpty() ? List.<Loan>of() : loans.findByOrganizationIdAndMslaIdIn(org, ids);
    var prices = securityMap(related.stream().map(Loan::getSecurityId).toList());
    var byAgreement = related.stream().collect(Collectors.groupingBy(Loan::getMslaId));
    var rows = result.getContent().stream().map(a -> row(a, connectionMap.get(a.getConnectionId()), byAgreement.getOrDefault(a.getId(), List.of()), prices)).toList();
    return new Overview(new PageResponse<>(rows, page, size, result.getTotalElements(), result.getTotalPages()),
        agreements.countByOrganizationIdAndStatus(org, "ACTIVE"),
        connections.stream().sorted(Comparator.comparing(BorrowerConnection::getName).thenComparing(BorrowerConnection::getId))
            .map(c -> new BorrowerOption(c.getId(), c.getName(), c.getExternalId())).toList(),
        agreements.statuses(org), UNAVAILABLE);
  }

  public Detail detail(long org, long id) {
    var agreement = found(agreements.findByIdAndOrganizationId(id, org));
    var connection = borrowers.findByIdAndOrganizationId(agreement.getConnectionId(), org).orElse(null);
    var related = loans.findByOrganizationIdAndMslaIdIn(org, List.of(id));
    var recent = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
    var locatePage = locates.findByOrganizationIdAndMslaId(org, id, recent);
    var reservationPage = reservations.findByOrganizationIdAndMslaId(org, id, recent);
    var rules = haircuts.findByMslaId(id).stream().filter(r -> r.getOrganizationId().equals(org)).sorted(Comparator.comparing(CollateralHaircut::getId)).toList();
    var securityIds = new HashSet<Long>();
    related.forEach(l -> securityIds.add(l.getSecurityId()));
    locatePage.forEach(l -> securityIds.add(l.getSecurityId()));
    reservationPage.forEach(r -> securityIds.add(r.getSecurityId()));
    rules.forEach(r -> { if (r.getSecurityId() != null) securityIds.add(r.getSecurityId()); });
    var prices = securityMap(securityIds);
    var active = related.stream().filter(l -> "ACTIVE".equals(l.getStatus())).sorted(Comparator.comparing(Loan::getId).reversed()).toList();
    var activeRows = active.stream().limit(20).map(l -> {
      var security = prices.get(l.getSecurityId());
      return new LoanRow(l.getId(), l.getSecurityId(), symbol(prices, l.getSecurityId()), l.getStatus(), l.getQuantity(), l.getOutstandingQuantity(),
          security == null ? null : security.getPrice(), marketValue(l, prices), security == null ? null : security.getPriceAt(), l.getSettledAt());
    }).toList();
    var ruleRows = rules.stream().map(r -> new Rule(r.getId(), r.getAssetType(), r.getSecurityId(), symbol(prices, r.getSecurityId()), true,
        r.getHaircut().multiply(HUNDRED), BigDecimal.ONE.subtract(r.getHaircut()).multiply(HUNDRED), null)).toList();
    var activity = new ArrayList<Activity>();
    locatePage.forEach(l -> activity.add(new Activity(l.getId(), "LOCATE", l.getSecurityId(), symbol(prices, l.getSecurityId()), l.getQuantity(), l.getStatus(), l.getCreatedAt(), l.getExpiresAt())));
    reservationPage.forEach(r -> activity.add(new Activity(r.getId(), "RESERVATION", r.getSecurityId(), symbol(prices, r.getSecurityId()), r.getQuantity(), r.getStatus(), r.getCreatedAt(), r.getExpiresAt())));
    var recentActivity = activity.stream().sorted(Comparator.comparing(Activity::createdAt).reversed().thenComparing(Activity::id, Comparator.reverseOrder()).thenComparing(Activity::type)).limit(20).toList();
    var loanIds = related.stream().map(Loan::getId).toList();
    var billing = new ArrayList<Billing>();
    long billingCount = 0;
    if (!loanIds.isEmpty()) {
      var feePage = fees.findByOrganizationIdAndLoanIdIn(org, loanIds, recent);
      var rebatePage = rebates.findByOrganizationIdAndLoanIdIn(org, loanIds, recent);
      feePage.forEach(f -> billing.add(new Billing(f.getId(), f.getLoanId(), "FEE", f.getAmount(), f.getStatus(), f.getPeriodStart(), f.getPeriodEnd(), f.getDueDate())));
      rebatePage.forEach(r -> billing.add(new Billing(r.getId(), r.getLoanId(), "REBATE", r.getAmount(), r.getStatus(), r.getPeriodStart(), r.getPeriodEnd(), r.getPaymentDate())));
      billingCount = feePage.getTotalElements() + rebatePage.getTotalElements();
    }
    var auditPage = audits.findByOrganizationIdAndReferenceAndTypeIn(org, String.valueOf(id), List.of("AGREEMENT_PROPOSAL_RECEIVED", "AGREEMENT_DECIDED"), recent);
    return new Detail(row(agreement, connection, related, prices), json.tree(agreement.getTerms()), ruleRows, activeRows, active.size(), recentActivity,
        locatePage.getTotalElements() + reservationPage.getTotalElements(), billing, billingCount,
        auditPage.map(a -> new Audit(a.getId(), a.getType(), a.getReference(), a.getCreatedAt())).getContent(), auditPage.getTotalElements(), UNAVAILABLE);
  }

  private Map<Long, SecurityMaster> securityMap(Collection<Long> ids) {
    return securities.findAllById(ids).stream().collect(Collectors.toMap(SecurityMaster::getId, Function.identity()));
  }
  private String symbol(Map<Long, SecurityMaster> prices, Long id) {
    var security = id == null ? null : prices.get(id);
    return security == null ? null : security.getSymbol();
  }
  private BigDecimal marketValue(Loan loan, Map<Long, SecurityMaster> prices) {
    if (loan.getOutstandingQuantity() == 0) return BigDecimal.ZERO;
    var security = prices.get(loan.getSecurityId());
    return security == null || security.getPrice() == null ? null : security.getPrice().multiply(BigDecimal.valueOf(loan.getOutstandingQuantity()));
  }
  private Row row(Msla a, BorrowerConnection connection, List<Loan> related, Map<Long, SecurityMaster> prices) {
    var exposed = related.stream().filter(l -> EXPOSED.contains(l.getStatus())).toList();
    long unpriced = exposed.stream().filter(l -> marketValue(l, prices) == null).count();
    var exposure = unpriced > 0 ? null : exposed.stream().map(l -> marketValue(l, prices)).reduce(BigDecimal.ZERO, BigDecimal::add);
    return new Row(a.getId(), a.getNumber(), a.getConnectionId(), connection == null ? null : connection.getName(), connection == null ? null : connection.getExternalId(),
        a.getStatus(), a.getEffectiveDate(), a.getExpiryDate(), a.getFeeRate().multiply(BPS), a.getRebateSettlementFrequency(), a.getCoverageRatio().multiply(HUNDRED), Boolean.TRUE.equals(a.getRecallAllowed()),
        related.stream().filter(l -> "ACTIVE".equals(l.getStatus())).count(), related.size(), exposure, unpriced, "INR", a.getCreditLimit(), a.getRebateRate().multiply(BPS), a.getBillingFrequency(), a.getDayBasis());
  }
}
