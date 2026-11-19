package com.hotelsaas.authservice.application;

import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class RolePermissionService {
  private final JdbcTemplate db;
  private final StringRedisTemplate redis;

  public RolePermissionService(JdbcTemplate db, StringRedisTemplate redis) {
    this.db = db;
    this.redis = redis;
  }

  public boolean anyRoleHas(List<String> roles, String permission) {
    for (String role : roles) {
      String key = "auth:permission:" + role + ":" + permission;
      String cached = redis.opsForValue().get(key);
      if (cached != null) {
        if ("1".equals(cached)) return true;
        continue;
      }
      Integer found =
          db.queryForObject(
              "select count(*) from auth.role_permission where role_code=? and permission_code=?",
              Integer.class,
              role,
              permission);
      boolean allowed = found != null && found > 0;
      redis.opsForValue().set(key, allowed ? "1" : "0", Duration.ofMinutes(10));
      if (allowed) return true;
    }
    return false;
  }
}
