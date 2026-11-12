package com.example.lender_service.repository;

import com.example.lender_service.model.Recall;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

public interface RecallRepository extends JpaRepository<Recall, Long> {
  Page<Recall> findByOrganizationId(Long organizationId, Pageable pageable);

  Optional<Recall> findByIdAndOrganizationId(Long id, Long organizationId);

  Optional<Recall> findByOrganizationIdAndOwnerReferenceId(
      Long organizationId, Long ownerReferenceId);

  List<Recall> findByLoanIdAndStatusInOrderByDueAtAscIdAsc(
      Long loanId, Collection<String> statuses);
  List<Recall> findByOrganizationIdAndLoanIdIn(Long organizationId, Collection<Long> loanIds);

}
