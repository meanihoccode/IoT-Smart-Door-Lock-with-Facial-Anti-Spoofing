package com.example.btl_iot.repository;

import com.example.btl_iot.entity.Device;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeviceRepository extends JpaRepository<Device, String> {
    // Serializes seq allocation and the lockdown check against lockdown changes.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Device d where d.id = :id")
    Optional<Device> findLockedById(@Param("id") String id);
}
