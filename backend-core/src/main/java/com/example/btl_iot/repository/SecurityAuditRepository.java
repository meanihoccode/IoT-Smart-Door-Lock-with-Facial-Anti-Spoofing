package com.example.btl_iot.repository;
import com.example.btl_iot.entity.SecurityAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface SecurityAuditRepository extends JpaRepository<SecurityAudit, Long> {
    List<SecurityAudit> findTop50ByOrderByTimeDesc();
}
