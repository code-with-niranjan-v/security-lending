package com.example.lender_service.repository;

import com.example.lender_service.model.Reservation;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {
  Page<Reservation> findByOrganizationId(Long organizationId, Pageable pageable);

  Optional<Reservation> findByIdAndOrganizationId(Long id, Long organizationId);

  Optional<Reservation> findByConnectionIdAndBorrowerReservationId(
      Long connectionId, Long borrowerReservationId);

  Optional<Reservation> findByLocateId(Long locateId);

  Page<Reservation> findByOrganizationIdAndMslaId(Long organizationId, Long mslaId, Pageable pageable);

  List<Reservation> findByOrganizationIdAndLocateIdIn(Long organizationId, Collection<Long> locateIds);
  long countByOrganizationIdAndStatus(Long organizationId, String status);
}
