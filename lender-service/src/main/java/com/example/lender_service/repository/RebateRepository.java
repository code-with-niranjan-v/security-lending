package com.example.lender_service.repository;

import com.example.lender_service.model.Rebate;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

public interface RebateRepository extends JpaRepository<Rebate, Long> {
  Page<Rebate> findByOrganizationId(Long organizationId, Pageable pageable);

  Optional<Rebate> findByIdAndOrganizationId(Long id, Long organizationId);

  Optional<Rebate> findByOrganizationIdAndOwnerReferenceId(
      Long organizationId, Long ownerReferenceId);

  Page<Rebate> findByOrganizationIdAndLoanId(Long organizationId, Long loanId, Pageable pageable);

  Page<Rebate> findByOrganizationIdAndLoanIdIn(Long organizationId, Collection<Long> loanIds, Pageable pageable);
}
