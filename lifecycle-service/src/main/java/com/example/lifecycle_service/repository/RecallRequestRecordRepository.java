package com.example.lifecycle_service.repository;

import com.example.lifecycle_service.model.RecallRequestRecord;
import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface RecallRequestRecordRepository extends Repository<RecallRequestRecord, Long> {
  java.util.List<RecallRequestRecord> findByLoanIdAndTypeAndStatusIn(Long loanId,String type,java.util.Collection<String> statuses);
  Optional<RecallRequestRecord> findByIdAndOrganizationId(Long id, Long organizationId);
}
