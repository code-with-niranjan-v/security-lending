package com.example.lender_service.service;

import static com.example.lender_service.service.Checks.found;
import com.example.lender_service.dto.LocateScreen.*;
import com.example.lender_service.model.*;
import com.example.lender_service.repository.*;
import com.example.lender_service.response.PageResponse;
import java.time.*;
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
public class LocateScreenService {
  private static final Set<String> APPROVED = Set.of("APPROVED", "PARTIALLY_APPROVED");
  private static final Set<String> RESERVED = Set.of("ACTIVE", "CONSUMED");
  private static final List<String> LOCATE_AUDIT = List.of("LOCATE_RECEIVED", "REGISTER_LOCATE_EXPIRY", "LOCATE_DECISION_APPROVED", "LOCATE_DECISION_REJECTED", "LOCATE_EXPIRY_CONFIRMED");
  private static final List<String> RESERVATION_AUDIT = List.of("RESERVATION_RECEIVED", "REGISTER_RESERVATION_EXPIRY", "RESERVATION_CLOSED");
  private final TenantService tenants;
  private final LocateRequestRepository locates;
  private final ReservationRepository reservations;
  private final BorrowerConnectionRepository borrowers;
  private final MslaRepository agreements;
  private final SecurityMasterRepository securities;
  private final InventoryPositionRepository inventory;
  private final LoanRepository loans;
  private final AuditEventRepository audit;
  private final Clock clock;

  public Overview overview(long org, int page, int size, Long connectionId, Long mslaId,
      Long securityId, String status, String expiry, String search) {
    tenants.exists(org);
    if (page < 0 || size < 1 || size > 200) throw new IllegalArgumentException("page must be nonnegative; size must be 1..200");
    if (expiry != null && !expiry.isBlank() && !Set.of("EXPIRED", "URGENT", "UPCOMING", "LATER").contains(expiry))
      throw new IllegalArgumentException("expiry must be EXPIRED, URGENT, UPCOMING or LATER");
    Instant now = clock.instant();
    var connections = borrowers.findByOrganizationId(org);
    var mslas = agreements.findByOrganizationId(org, Pageable.unpaged()).getContent();
    var securityOptions = securities.findAllById(locates.securityIds(org));
    Specification<LocateRequest> filter = (root, query, cb) -> {
      var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
      predicates.add(cb.equal(root.get("organizationId"), org));
      if (connectionId != null) predicates.add(cb.equal(root.get("connectionId"), connectionId));
      if (mslaId != null) predicates.add(cb.equal(root.get("mslaId"), mslaId));
      if (securityId != null) predicates.add(cb.equal(root.get("securityId"), securityId));
      if (status != null && !status.isBlank()) predicates.add(cb.equal(root.get("status"), status.toUpperCase(Locale.ROOT)));
      if (expiry != null) {
        switch (expiry) {
          case "EXPIRED" -> predicates.add(cb.lessThanOrEqualTo(root.get("expiresAt"), now));
          case "URGENT" -> { predicates.add(cb.greaterThan(root.get("expiresAt"), now)); predicates.add(cb.lessThanOrEqualTo(root.get("expiresAt"), now.plusSeconds(3600))); }
          case "UPCOMING" -> { predicates.add(cb.greaterThan(root.get("expiresAt"), now.plusSeconds(3600))); predicates.add(cb.lessThanOrEqualTo(root.get("expiresAt"), now.plusSeconds(86400))); }
          case "LATER" -> predicates.add(cb.greaterThan(root.get("expiresAt"), now.plusSeconds(86400)));
          default -> { }
        }
      }
      if (search != null && !search.isBlank()) {
        String term = search.trim().toLowerCase(Locale.ROOT);
        var matches = new ArrayList<jakarta.persistence.criteria.Predicate>();
        var connectionIds = connections.stream().filter(c -> c.getName().toLowerCase(Locale.ROOT).contains(term) || c.getExternalId().toLowerCase(Locale.ROOT).contains(term)).map(BorrowerConnection::getId).toList();
        var agreementIds = mslas.stream().filter(a -> a.getNumber().toLowerCase(Locale.ROOT).contains(term)).map(Msla::getId).toList();
        var securityIds = securityOptions.stream().filter(s -> s.getSymbol().toLowerCase(Locale.ROOT).contains(term) || s.getName().toLowerCase(Locale.ROOT).contains(term) || (s.getIsin() != null && s.getIsin().toLowerCase(Locale.ROOT).contains(term))).map(SecurityMaster::getId).toList();
        if (!connectionIds.isEmpty()) matches.add(root.get("connectionId").in(connectionIds));
        if (!agreementIds.isEmpty()) matches.add(root.get("mslaId").in(agreementIds));
        if (!securityIds.isEmpty()) matches.add(root.get("securityId").in(securityIds));
        try { matches.add(cb.equal(root.get("id"), Long.parseLong(term.replaceFirst("^loc-", "")))); } catch (NumberFormatException ignored) { }
        predicates.add(matches.isEmpty() ? cb.disjunction() : cb.or(matches.toArray(jakarta.persistence.criteria.Predicate[]::new)));
      }
      return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
    };
    var result = locates.findAll(filter, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")));
    var related = result.isEmpty() ? List.<Reservation>of() : reservations.findByOrganizationIdAndLocateIdIn(org, result.getContent().stream().map(LocateRequest::getId).toList());
    var linked = related.isEmpty() ? List.<Loan>of() : loans.findByOrganizationIdAndReservationIdIn(org, related.stream().map(Reservation::getId).toList());
    var stocks = result.isEmpty() ? List.<InventoryPosition>of() : inventory.findByOrganizationIdAndSecurityIdIn(org, result.getContent().stream().map(LocateRequest::getSecurityId).distinct().toList());
    var connectionMap = connections.stream().collect(Collectors.toMap(BorrowerConnection::getId, Function.identity()));
    var agreementMap = mslas.stream().collect(Collectors.toMap(Msla::getId, Function.identity()));
    var securityMap = securityOptions.stream().collect(Collectors.toMap(SecurityMaster::getId, Function.identity()));
    var stockMap = stocks.stream().collect(Collectors.toMap(InventoryPosition::getSecurityId, Function.identity()));
    var rows = result.getContent().stream().map(l -> row(l, related, linked, connectionMap.get(l.getConnectionId()), agreementMap.get(l.getMslaId()), securityMap.get(l.getSecurityId()), stockMap.get(l.getSecurityId()), now)).toList();
    return new Overview(new PageResponse<>(rows, page, size, result.getTotalElements(), result.getTotalPages()),
        locates.countByOrganizationIdAndStatus(org, "PENDING"), reservations.countByOrganizationIdAndStatus(org, "ACTIVE"),
        connections.stream().sorted(Comparator.comparing(BorrowerConnection::getName)).map(c -> new Option(c.getId(), c.getName() + " (" + c.getExternalId() + ")")).toList(),
        mslas.stream().sorted(Comparator.comparing(Msla::getNumber)).map(a -> new Option(a.getId(), a.getNumber())).toList(),
        securityOptions.stream().sorted(Comparator.comparing(SecurityMaster::getSymbol)).map(s -> new Option(s.getId(), s.getSymbol())).toList(), locates.statuses(org), now);
  }

  public Detail detail(long org, long id) {
    var locate = found(locates.findByIdAndOrganizationId(id, org));
    var related = reservations.findByOrganizationIdAndLocateIdIn(org, List.of(id));
    var linked = related.isEmpty() ? List.<Loan>of() : loans.findByOrganizationIdAndReservationIdIn(org, related.stream().map(Reservation::getId).toList());
    Instant now = clock.instant();
    var summary = row(locate, related, linked, borrowers.findByIdAndOrganizationId(locate.getConnectionId(), org).orElse(null), agreements.findByIdAndOrganizationId(locate.getMslaId(), org).orElse(null),
        securities.findById(locate.getSecurityId()).orElse(null), inventory.findByOrganizationIdAndSecurityId(org, locate.getSecurityId()).orElse(null), now);
    var preview = related.stream().sorted(Comparator.comparing(Reservation::getId)).map(r -> new ReservationRow(r.getId(), r.getBorrowerReservationId(), r.getQuantity(), r.getRemainingQuantity(), r.getStatus(), r.getExpiresAt(), Duration.between(now, r.getExpiresAt()).getSeconds(),
        linked.stream().filter(l -> l.getReservationId().equals(r.getId())).sorted(Comparator.comparing(Loan::getId)).map(l -> new LinkedLoan(l.getId(), l.getQuantity(), l.getOutstandingQuantity(), l.getStatus())).toList())).toList();
    var paging = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
    var activity = new ArrayList<Activity>();
    var locateAudit = audit.findByOrganizationIdAndReferenceAndTypeIn(org, String.valueOf(id), LOCATE_AUDIT, paging);
    locateAudit.forEach(a -> activity.add(new Activity(a.getId(), "LOCATE", a.getType(), a.getReference(), a.getCreatedAt())));
    long count = locateAudit.getTotalElements();
    for (var reservation : related) {
      var history = audit.findByOrganizationIdAndReferenceAndTypeIn(org, String.valueOf(reservation.getId()), RESERVATION_AUDIT, paging);
      count += history.getTotalElements();
      history.forEach(a -> activity.add(new Activity(a.getId(), "RESERVATION", a.getType(), a.getReference(), a.getCreatedAt())));
    }
    return new Detail(summary, preview, activity.stream().sorted(Comparator.comparing(Activity::createdAt).reversed().thenComparing(Activity::id, Comparator.reverseOrder())).limit(20).toList(), count, now,
        List.of("custodyAllocations", "negotiatedLocateFee", "owner", "decisionNotesHistory", "reservationAmendmentHistory"));
  }

  private Row row(LocateRequest l, List<Reservation> reservations, List<Loan> loans, BorrowerConnection connection, Msla agreement, SecurityMaster security, InventoryPosition stock, Instant now) {
    var related = reservations.stream().filter(r -> r.getLocateId().equals(l.getId())).toList();
    var reservationIds = related.stream().map(Reservation::getId).collect(Collectors.toSet());
    long reserved = related.stream().filter(r -> RESERVED.contains(r.getStatus())).mapToLong(Reservation::getRemainingQuantity).sum();
    long consumed = loans.stream().filter(loan -> reservationIds.contains(loan.getReservationId()) && !"REJECTED".equals(loan.getStatus())).mapToLong(Loan::getQuantity).sum();
    long unallocated = Math.max(0, l.getApprovedQuantity() - reserved - consumed);
    boolean expired = !l.getExpiresAt().isAfter(now);
    Long availableStock = stock == null ? null : stock.getAvailableQuantity();
    LocalDate today = now.atZone(ZoneId.of("Asia/Kolkata")).toLocalDate();
    boolean agreementActive = agreement != null && "ACTIVE".equals(agreement.getStatus()) && !today.isBefore(agreement.getEffectiveDate()) && !today.isAfter(agreement.getExpiryDate());
    boolean pending = "PENDING".equals(l.getStatus()) && connection != null && Boolean.TRUE.equals(connection.getActive());
    long seconds = Duration.between(now, l.getExpiresAt()).getSeconds();
    String bucket = expired ? "EXPIRED" : !l.getExpiresAt().isAfter(now.plusSeconds(3600)) ? "URGENT" : !l.getExpiresAt().isAfter(now.plusSeconds(86400)) ? "UPCOMING" : "LATER";
    return new Row(l.getId(), l.getBorrowerLocateId(), l.getConnectionId(), connection == null ? null : connection.getName(), connection == null ? null : connection.getExternalId(), l.getMslaId(), agreement == null ? null : agreement.getNumber(), l.getSecurityId(), security == null ? null : security.getSymbol(),
        l.getQuantity(), l.getApprovedQuantity(), reserved, consumed, unallocated, APPROVED.contains(l.getStatus()) && !expired ? unallocated : 0,
        availableStock, l.getStatus(), l.getCreatedAt(), l.getExpiresAt(), seconds, bucket, pending && !expired && agreementActive && availableStock != null && availableStock > 0, pending);
  }
}
