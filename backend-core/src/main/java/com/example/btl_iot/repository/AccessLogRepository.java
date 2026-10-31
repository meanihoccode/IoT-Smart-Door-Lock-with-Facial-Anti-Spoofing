package com.example.btl_iot.repository;

import com.example.btl_iot.entity.AccessLog;
import com.example.btl_iot.entity.DoorCommand;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Repository
public interface AccessLogRepository extends JpaRepository<AccessLog, Long> {
    long countByAccessTimeBetween(LocalDateTime start, LocalDateTime end);
    
    List<AccessLog> findTop5ByOrderByAccessTimeDesc();
    
    Page<AccessLog> findAllByOrderByAccessTimeDesc(Pageable pageable);

    @Query("SELECT MAX(a.accessTime) FROM AccessLog a WHERE a.user.id = :userId")
    LocalDateTime findLastAccessTimeByUserId(@Param("userId") Long userId);

    // Mirrors command status into the audit row so the dashboard never shows "opened" for a mere publish.
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AccessLog a set a.status = :status where a.commandId = :commandId")
    int updateStatusByCommandId(@Param("commandId") String commandId, @Param("status") String status);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AccessLog a set a.status = :status where a.status in :from and a.commandId in "
            + "(select c.id from DoorCommand c where c.status = :commandStatus)")
    int syncCommandStatus(@Param("from") Collection<String> from, @Param("status") String status,
                          @Param("commandStatus") DoorCommand.Status commandStatus);
}
