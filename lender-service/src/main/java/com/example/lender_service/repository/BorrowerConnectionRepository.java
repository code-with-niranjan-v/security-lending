package com.example.lender_service.repository;

import com.example.lender_service.model.BorrowerConnection;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

public interface BorrowerConnectionRepository extends JpaRepository<BorrowerConnection, Long> {
  Page<BorrowerConnection> findByOrganizationId(Long organizationId, Pageable pageable);

  Optional<BorrowerConnection> findByIdAndOrganizationId(Long id, Long organizationId);

  Optional<BorrowerConnection> findByOrganizationIdAndExternalIdAndBorrowerOrganizationId(
      Long organizationId, String externalId, Long borrowerOrganizationId);

  List<BorrowerConnection> findByOrganizationId(Long organizationId);
}
