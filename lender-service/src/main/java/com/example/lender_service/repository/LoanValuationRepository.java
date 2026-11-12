package com.example.lender_service.repository;

import com.example.lender_service.model.LoanValuation;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

public interface LoanValuationRepository extends JpaRepository<LoanValuation, Long> {
  Optional<LoanValuation> findFirstByLoanIdOrderByValuedAtDescIdDesc(Long loanId);

  Page<LoanValuation> findByOrganizationId(Long organizationId, Pageable pageable);

  Optional<LoanValuation> findByIdAndOrganizationId(Long id, Long organizationId);

  Optional<LoanValuation> findByOrganizationIdAndOwnerReferenceId(
      Long organizationId, Long ownerReferenceId);
  List<LoanValuation> findByOrganizationIdAndLoanIdIn(Long organizationId, Collection<Long> loanIds);

}
