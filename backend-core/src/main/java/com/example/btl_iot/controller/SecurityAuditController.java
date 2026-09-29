package com.example.btl_iot.controller;

import com.example.btl_iot.entity.SecurityAudit;
import com.example.btl_iot.repository.SecurityAuditRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/security-audits")
public class SecurityAuditController {
    private final SecurityAuditRepository audits;
    public SecurityAuditController(SecurityAuditRepository audits) { this.audits = audits; }
    public record Entry(Long id, String actor, String action, String target, LocalDateTime time) {}
    public record Results(List<Entry> items, int page, int size, long totalElements, int totalPages) {}
    @GetMapping
    public Results list(@RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size,
                        @RequestParam(required=false) String actor, @RequestParam(required=false) String action,
                        @RequestParam(required=false) @org.springframework.format.annotation.DateTimeFormat(iso=org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate from,
                        @RequestParam(required=false) @org.springframework.format.annotation.DateTimeFormat(iso=org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate to) {
        if (page < 0 || page > 100000 || size < 1 || size > 100 || (actor != null && actor.length() > 64)
                || (action != null && !action.matches("[A-Z_]{1,64}"))
                || (from != null && to != null && from.isAfter(to))
                || (to != null && to.equals(LocalDate.MAX)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Bộ lọc nhật ký không hợp lệ.");
        Specification<SecurityAudit> spec = (root, query, cb) -> {
            var predicates = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            if (actor != null && !actor.isBlank()) predicates.add(cb.equal(root.get("actor"), actor));
            if (action != null) predicates.add(cb.equal(root.get("action"), action));
            if (from != null) predicates.add(cb.greaterThanOrEqualTo(root.get("time"), from.atStartOfDay()));
            if (to != null) predicates.add(cb.lessThan(root.get("time"), to.plusDays(1).atStartOfDay()));
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        var result = audits.findAll(spec, PageRequest.of(page,size,Sort.by(Sort.Direction.DESC,"time","id")));
        return new Results(result.stream().map(a -> new Entry(a.getId(),a.getActor(),a.getAction(),a.getTarget(),a.getTime())).toList(),
                page,size,result.getTotalElements(),result.getTotalPages());
    }
}
