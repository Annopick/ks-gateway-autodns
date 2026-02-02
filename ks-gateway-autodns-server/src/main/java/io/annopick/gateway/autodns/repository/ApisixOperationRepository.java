package io.annopick.gateway.autodns.repository;

import io.annopick.gateway.autodns.model.ApisixOperation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;

@Repository
public interface ApisixOperationRepository extends JpaRepository<ApisixOperation, Long> {
    
    @Modifying
    @Query("DELETE FROM ApisixOperation a WHERE a.createdAt < :cutoffDate")
    void deleteOlderThan(LocalDateTime cutoffDate);
}
