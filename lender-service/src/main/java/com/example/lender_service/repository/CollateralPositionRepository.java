package com.example.lender_service.repository;

import com.example.lender_service.model.CollateralPosition;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

public interface CollateralPositionRepository extends JpaRepository<CollateralPosition, Long> {
  Page<CollateralPosition> findByOrganizationId(Long organizationId, Pageable pageable);

  Optional<CollateralPosition> findByIdAndOrganizationId(Long id, Long organizationId);

  Optional<CollateralPosition> findByConnectionIdAndBorrowerPositionId(
      Long connectionId, Long borrowerPositionId);

  List<CollateralPosition> findByConnectionIdAndBorrowerLoanId(
      Long connectionId, Long borrowerLoanId);

  List<CollateralPosition> findByLoanId(Long loanId);
  List<CollateralPosition> findByOrganizationIdAndLoanIdIn(Long organizationId, Collection<Long> loanIds);

}
