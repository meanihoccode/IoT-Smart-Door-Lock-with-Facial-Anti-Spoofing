package com.example.btl_iot.controller;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import java.util.Map;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    ResponseEntity<?> methodNotAllowed() {
        return ResponseEntity.status(405).body(Map.of("status","error","message","Thao tác này không được hỗ trợ."));
    }
    @ExceptionHandler(com.example.btl_iot.service.FaceGateway.AiFailure.class)
    ResponseEntity<?> ai(com.example.btl_iot.service.FaceGateway.AiFailure ex) {
        var body = new java.util.HashMap<String, Object>();
        body.put("status", "error"); body.put("message", ex.getReason()); body.put("reasonCode", ex.reasonCode());
        if (ex.requestId() != null) body.put("requestId", ex.requestId());
        return ResponseEntity.status(ex.getStatusCode()).body(body);
    }
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> status(ResponseStatusException ex) {
        var builder=ResponseEntity.status(ex.getStatusCode());
        if (ex.getStatusCode().value()==429) builder.header("Retry-After","300");
        return builder.body(Map.of("status","error","message",ex.getReason()==null?"Yêu cầu không hợp lệ.":ex.getReason()));
    }
    @ExceptionHandler({DataIntegrityViolationException.class, OptimisticLockingFailureException.class})
    ResponseEntity<?> duplicate() {
        return ResponseEntity.status(409).body(Map.of("status","error","message","Thông tin bị trùng hoặc không hợp lệ."));
    }
    @ExceptionHandler({MaxUploadSizeExceededException.class, HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class, MissingServletRequestPartException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> invalid() {
        return ResponseEntity.badRequest().body(Map.of("status","error","message","Dữ liệu không hợp lệ hoặc ảnh vượt quá 5 MB."));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> failure() {
        return ResponseEntity.status(500).body(Map.of("status","error","message","Không thể xử lý yêu cầu. Vui lòng thử lại."));
    }
}
