package com.example.lender_service.repository;

import com.example.lender_service.model.LocateRequest;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

public interface LocateRequestRepository extends JpaRepository<LocateRequest, Long>, JpaSpecificationExecutor<LocateRequest> {
  Page<LocateRequest> findByOrganizationId(Long organizationId, Pageable pageable);

  Optional<LocateRequest> findByIdAndOrganizationId(Long id, Long organizationId);

  Optional<LocateRequest> findByConnectionIdAndBorrowerLocateId(
      Long connectionId, Long borrowerLocateId);

  Page<LocateRequest> findByOrganizationIdAndMslaId(Long organizationId, Long mslaId, Pageable pageable);

  long countByOrganizationIdAndStatus(Long organizationId, String status);
  @Query("select distinct l.status from LocateRequest l where l.organizationId = :org order by l.status")
  List<String> statuses(@org.springframework.data.repository.query.Param("org") Long org);
  @Query("select distinct l.securityId from LocateRequest l where l.organizationId = :org")
  List<Long> securityIds(@org.springframework.data.repository.query.Param("org") Long org);
}
