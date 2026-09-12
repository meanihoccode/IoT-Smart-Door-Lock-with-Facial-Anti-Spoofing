package com.example.btl_iot.controller;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import java.util.Map;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> status(ResponseStatusException ex) {
        var builder=ResponseEntity.status(ex.getStatusCode());
        if (ex.getStatusCode().value()==429) builder.header("Retry-After","300");
        return builder.body(Map.of("status","error","message",ex.getReason()==null?"Yêu cầu không hợp lệ.":ex.getReason()));
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<?> duplicate() {
        return ResponseEntity.status(409).body(Map.of("status","error","message","Thông tin bị trùng hoặc không hợp lệ."));
    }
    @ExceptionHandler({MaxUploadSizeExceededException.class,HttpMessageNotReadableException.class})
    ResponseEntity<?> invalid() {
        return ResponseEntity.badRequest().body(Map.of("status","error","message","Dữ liệu không hợp lệ hoặc ảnh vượt quá 5 MB."));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> failure() {
        return ResponseEntity.status(500).body(Map.of("status","error","message","Không thể xử lý yêu cầu. Vui lòng thử lại."));
    }
}
