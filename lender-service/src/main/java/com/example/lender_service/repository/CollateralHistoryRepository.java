package com.example.lender_service.repository;

import com.example.lender_service.model.CollateralHistory;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

public interface CollateralHistoryRepository extends JpaRepository<CollateralHistory, Long> {
  List<CollateralHistory> findByOrganizationIdAndPositionIdOrderByCreatedAtDescIdDesc(Long organizationId,Long positionId);
  Page<CollateralHistory> findByOrganizationId(Long organizationId, Pageable pageable);

  Optional<CollateralHistory> findByIdAndOrganizationId(Long id, Long organizationId);
}
