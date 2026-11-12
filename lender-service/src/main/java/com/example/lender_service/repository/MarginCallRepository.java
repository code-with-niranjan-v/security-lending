package com.example.lender_service.repository;

import com.example.lender_service.model.MarginCall;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

public interface MarginCallRepository extends JpaRepository<MarginCall, Long> {
  Page<MarginCall> findByOrganizationId(Long organizationId, Pageable pageable);

  Optional<MarginCall> findByIdAndOrganizationId(Long id, Long organizationId);

  Optional<MarginCall> findByOrganizationIdAndOwnerReferenceId(
      Long organizationId, Long ownerReferenceId);
  List<MarginCall> findByOrganizationIdAndLoanIdIn(Long organizationId, Collection<Long> loanIds);

}
