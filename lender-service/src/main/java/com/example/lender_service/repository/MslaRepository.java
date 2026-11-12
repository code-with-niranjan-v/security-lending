package com.example.lender_service.repository;

import com.example.lender_service.model.Msla;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

public interface MslaRepository extends JpaRepository<Msla, Long>, JpaSpecificationExecutor<Msla> {
  Page<Msla> findByOrganizationId(Long organizationId, Pageable pageable);

  Optional<Msla> findByIdAndOrganizationId(Long id, Long organizationId);

  Optional<Msla> findByConnectionIdAndBorrowerMslaId(Long connectionId, Long borrowerMslaId);
  long countByOrganizationIdAndStatus(Long organizationId, String status);

  @Query("select distinct m.status from Msla m where m.organizationId = :org order by m.status")
  List<String> statuses(@org.springframework.data.repository.query.Param("org") Long org);
}
