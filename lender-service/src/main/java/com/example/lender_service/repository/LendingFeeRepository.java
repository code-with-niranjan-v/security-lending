package com.example.lender_service.repository;

import com.example.lender_service.model.LendingFee;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

public interface LendingFeeRepository extends JpaRepository<LendingFee, Long> {
  Page<LendingFee> findByOrganizationId(Long organizationId, Pageable pageable);

  Optional<LendingFee> findByIdAndOrganizationId(Long id, Long organizationId);

  Optional<LendingFee> findByOrganizationIdAndOwnerReferenceId(
      Long organizationId, Long ownerReferenceId);

  Page<LendingFee> findByOrganizationIdAndLoanIdIn(Long organizationId, Collection<Long> loanIds, Pageable pageable);
}
