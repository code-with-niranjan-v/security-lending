package com.example.lender_service.repository;

import com.example.lender_service.model.InventoryPosition;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

public interface InventoryPositionRepository extends JpaRepository<InventoryPosition, Long> {
  Page<InventoryPosition> findByOrganizationId(Long organizationId, Pageable pageable);

  Optional<InventoryPosition> findByIdAndOrganizationId(Long id, Long organizationId);

  Optional<InventoryPosition> findByOrganizationIdAndSecurityId(
      Long organizationId, Long securityId);

  List<InventoryPosition> findByOrganizationIdAndSecurityIdIn(Long organizationId, Collection<Long> securityIds);
}
