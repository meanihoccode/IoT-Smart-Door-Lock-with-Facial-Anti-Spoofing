package com.example.btl_iot.repository;

import com.example.btl_iot.entity.DeviceRequest;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceRequestRepository extends JpaRepository<DeviceRequest, Long> {
}
