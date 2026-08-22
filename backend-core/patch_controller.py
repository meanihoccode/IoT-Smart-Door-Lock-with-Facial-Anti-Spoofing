import re
with open('e:/BTL_IOT/backend-core/src/main/java/com/example/btl_iot/controller/ApiController.java', 'r', encoding='utf-8') as f:
    code = f.read()

new_apis = """
    @GetMapping("/overview")
    public ResponseEntity<?> getOverview() {
        long totalEmployees = userRepository.count();
        
        LocalDateTime startOfDay = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime endOfDay = LocalDateTime.now().withHour(23).withMinute(59).withSecond(59).withNano(999999999);
        long accessesToday = accessLogRepository.countByAccessTimeBetween(startOfDay, endOfDay);
        
        java.util.List<AccessLog> recentLogs = accessLogRepository.findTop5ByOrderByAccessTimeDesc();
        
        java.util.List<Map<String, Object>> recentLogsData = new java.util.ArrayList<>();
        for (AccessLog log : recentLogs) {
            Map<String, Object> logData = new HashMap<>();
            logData.put("id", log.getId());
            logData.put("method", log.getAccessMethod());
            logData.put("status", log.getStatus());
            logData.put("time", log.getAccessTime().toString());
            if (log.getUser() != null) {
                logData.put("userName", log.getUser().getFullName());
            } else {
                logData.put("userName", "Unknown");
            }
            recentLogsData.add(logData);
        }
        
        return ResponseEntity.ok(Map.of(
            "totalEmployees", totalEmployees,
            "accessesToday", accessesToday,
            "recentLogs", recentLogsData
        ));
    }

    @GetMapping("/users")
    public ResponseEntity<?> getUsers() {
        java.util.List<User> users = userRepository.findAll();
        java.util.List<Map<String, Object>> usersData = new java.util.ArrayList<>();
        
        for (User user : users) {
            Map<String, Object> userData = new HashMap<>();
            userData.put("id", user.getId());
            userData.put("username", user.getUsername());
            userData.put("name", user.getFullName());
            
            // Determine method
            String method = "N/A";
            boolean hasFace = user.getFaceEmbedding() != null && !user.getFaceEmbedding().isEmpty();
            boolean hasPin = user.getPinCode() != null && !user.getPinCode().isEmpty();
            if (hasFace && hasPin) method = "Face + PIN";
            else if (hasFace) method = "Face Only";
            else if (hasPin) method = "PIN Only";
            
            userData.put("method", method);
            userData.put("status", "Active");
            
            LocalDateTime lastAccess = accessLogRepository.findLastAccessTimeByUserId(user.getId());
            userData.put("lastActive", lastAccess != null ? lastAccess.toString() : "Chưa từng hoạt động");
            
            usersData.add(userData);
        }
        
        return ResponseEntity.ok(usersData);
    }
"""

# Insert before the last brace
last_brace_idx = code.rfind('}')
if last_brace_idx != -1:
    code = code[:last_brace_idx] + new_apis + code[last_brace_idx:]

with open('e:/BTL_IOT/backend-core/src/main/java/com/example/btl_iot/controller/ApiController.java', 'w', encoding='utf-8') as f:
    f.write(code)
