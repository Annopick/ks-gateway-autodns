package io.annopick.gateway.autodns.repository;

import io.annopick.gateway.autodns.model.PublicIpRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface PublicIpRecordRepository extends JpaRepository<PublicIpRecord, Long> {
    Optional<PublicIpRecord> findByIdentifier(String identifier);
}
