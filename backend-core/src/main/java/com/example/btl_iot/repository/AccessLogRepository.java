package com.example.btl_iot.repository;

import com.example.btl_iot.entity.AccessLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AccessLogRepository extends JpaRepository<AccessLog, Long> {
    long countByAccessTimeBetween(LocalDateTime start, LocalDateTime end);
    
    List<AccessLog> findTop5ByOrderByAccessTimeDesc();
    
    @Query("SELECT MAX(a.accessTime) FROM AccessLog a WHERE a.user.id = :userId")
    LocalDateTime findLastAccessTimeByUserId(@Param("userId") Long userId);
}
