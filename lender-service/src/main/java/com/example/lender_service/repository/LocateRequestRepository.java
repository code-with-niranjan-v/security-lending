package com.example.lender_service.repository;

import com.example.lender_service.model.LocateRequest;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

public interface LocateRequestRepository extends JpaRepository<LocateRequest, Long> {
  Page<LocateRequest> findByOrganizationId(Long organizationId, Pageable pageable);

  Optional<LocateRequest> findByIdAndOrganizationId(Long id, Long organizationId);

  Optional<LocateRequest> findByConnectionIdAndBorrowerLocateId(
      Long connectionId, Long borrowerLocateId);

  Page<LocateRequest> findByOrganizationIdAndMslaId(Long organizationId, Long mslaId, Pageable pageable);
}
