package com.novelanalyzer.modules.security.controller;

import com.novelanalyzer.common.result.Result;
import com.novelanalyzer.modules.security.annotation.RequireRole;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/secure")
public class SecureController {

    @RequireRole({"ADMIN"})
    @GetMapping("/admin/ping")
    public Result<Map<String, Object>> adminPing() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scope", "admin");
        data.put("status", "ok");
        return Result.success(data);
    }

    @RequireRole({"ADMIN", "USER"})
    @GetMapping("/user/ping")
    public Result<Map<String, Object>> userPing() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scope", "user");
        data.put("status", "ok");
        return Result.success(data);
    }
}

