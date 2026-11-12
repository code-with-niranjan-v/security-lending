package com.example.lender_service.repository;

import com.example.lender_service.model.Loan;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

public interface LoanRepository extends JpaRepository<Loan, Long> {
  Page<Loan> findByOrganizationId(Long organizationId, Pageable pageable);

  Optional<Loan> findByIdAndOrganizationId(Long id, Long organizationId);

  Optional<Loan> findByConnectionIdAndBorrowerLoanId(Long connectionId, Long borrowerLoanId);

  List<Loan> findByMslaId(Long mslaId);

  List<Loan> findByOrganizationIdAndMslaIdIn(Long organizationId, Collection<Long> mslaIds);

  List<Loan> findByOrganizationIdAndReservationIdIn(Long organizationId, Collection<Long> reservationIds);
}
