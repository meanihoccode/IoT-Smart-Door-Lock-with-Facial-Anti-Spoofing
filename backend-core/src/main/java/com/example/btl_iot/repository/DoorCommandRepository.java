package com.example.btl_iot.repository;

import com.example.btl_iot.entity.DoorCommand;
import com.example.btl_iot.entity.DoorCommand.Status;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface DoorCommandRepository extends JpaRepository<DoorCommand, String> {
    // Compare-and-set: a late or duplicate ACK can never move a command backwards.
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update DoorCommand c set c.status = :to, c.statusReason = :reason, c.updatedAt = :now "
            + "where c.id = :id and c.status in :from")
    int transition(@Param("id") String id, @Param("from") Collection<Status> from, @Param("to") Status to,
                   @Param("reason") String reason, @Param("now") Instant now);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update DoorCommand c set c.status = :to, c.statusReason = :reason, c.updatedAt = :now "
            + "where c.status in :from and c.expiresAt < :cutoff")
    int expire(@Param("from") Collection<Status> from, @Param("to") Status to, @Param("reason") String reason,
               @Param("cutoff") Instant cutoff, @Param("now") Instant now);

    List<DoorCommand> findByDeviceIdOrderByIssuedAtDesc(String deviceId, Pageable page);

    Optional<DoorCommand> findFirstByDeviceIdAndTypeOrderByIssuedAtDesc(String deviceId, DoorCommand.Type type);
}
