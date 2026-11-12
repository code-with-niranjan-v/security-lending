package com.example.lender_service.repository;

import com.example.lender_service.model.LifecycleRequest;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

public interface LifecycleRequestRepository extends JpaRepository<LifecycleRequest, Long> {
  Page<LifecycleRequest> findByOrganizationId(Long organizationId, Pageable pageable);

  Optional<LifecycleRequest> findByIdAndOrganizationId(Long id, Long organizationId);

  Optional<LifecycleRequest> findByOrganizationIdAndClientRequestId(
      Long organizationId, String clientRequestId);
  List<LifecycleRequest> findByOrganizationIdAndLoanIdIn(Long organizationId, Collection<Long> loanIds);

}
