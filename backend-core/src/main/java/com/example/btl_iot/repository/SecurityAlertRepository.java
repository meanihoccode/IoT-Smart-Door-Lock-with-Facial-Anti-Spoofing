package com.example.btl_iot.repository;

import com.example.btl_iot.entity.SecurityAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface SecurityAlertRepository extends JpaRepository<SecurityAlert, Long> {
    List<SecurityAlert> findAllByOrderByCreatedAtDesc();
    long countByResolvedFalse();
}
