package com.example.lender_service.repository;

import com.example.lender_service.model.LoanReturn;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

public interface LoanReturnRepository extends JpaRepository<LoanReturn, Long> {
  Page<LoanReturn> findByOrganizationId(Long organizationId, Pageable pageable);

  Optional<LoanReturn> findByIdAndOrganizationId(Long id, Long organizationId);

  Optional<LoanReturn> findByConnectionIdAndBorrowerReturnId(
      Long connectionId, Long borrowerReturnId);

  List<LoanReturn> findByLoanId(Long loanId);

  List<LoanReturn> findByLoanIdAndRecallIdAndStatus(Long loanId, Long recallId, String status);
  List<LoanReturn> findByOrganizationIdAndLoanIdIn(Long organizationId, Collection<Long> loanIds);

}
