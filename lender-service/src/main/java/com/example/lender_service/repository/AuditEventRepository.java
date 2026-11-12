package com.example.lender_service.repository;

import com.example.lender_service.model.AuditEvent;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {
  Page<AuditEvent> findByOrganizationId(Long organizationId, Pageable pageable);

  Optional<AuditEvent> findByIdAndOrganizationId(Long id, Long organizationId);

  Page<AuditEvent> findByOrganizationIdAndReferenceAndTypeIn(Long organizationId, String reference, Collection<String> types, Pageable pageable);
}
