package com.example.btl_iot.controller;

import com.example.btl_iot.service.DoorAccessService;
import java.util.HashMap;
import org.springframework.http.ResponseEntity;

final class AccessResponses {
    private AccessResponses() {}
    static ResponseEntity<?> of(DoorAccessService.Result result, String requestId) {
        var body = new HashMap<String, Object>();
        body.put("status", result.status()); body.put("message", result.message());
        body.put("decision", result.commandId() != null ? "GRANTED" : "DENIED");
        if (result.reasonCode() != null) body.put("reasonCode", result.reasonCode());
        if (result.aiRequestId() != null) body.put("aiRequestId", result.aiRequestId());
        if (requestId != null) body.put("requestId", requestId);
        if (result.commandId() != null) { body.put("commandId", result.commandId()); body.put("commandStatus", result.commandStatus()); }
        return ResponseEntity.status(result.code()).body(body);
    }
}
