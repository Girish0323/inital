package com.hotelsaas.authservice.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotelsaas.authservice.api.AuthController.TokenResponse;
import com.hotelsaas.common.error.UnauthorizedException;
import com.hotelsaas.common.error.ValidationException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
  private static final long ACCESS_SECONDS = 900, REFRESH_SECONDS = 604800;
  private final JdbcTemplate db;
  private final StringRedisTemplate redis;
  private final ObjectMapper json;
  private final RolePermissionService permissionService;
  private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);
  private final byte[] jwtKey;
  private final String keyId;

  public AuthService(
      JdbcTemplate db,
      StringRedisTemplate redis,
      ObjectMapper json,
      RolePermissionService permissionService,
      @Value("${security.jwt.secret-base64:}") String secret,
      @Value("${security.jwt.key-id:dev-ephemeral}") String keyId) {
    this.db = db;
    this.redis = redis;
    this.json = json;
    this.permissionService = permissionService;
    this.keyId = keyId;
    this.jwtKey = key(secret);
  }

  private static byte[] key(String value) {
    try {
      byte[] b = value.isBlank() ? new byte[32] : Base64.getDecoder().decode(value);
      if (value.isBlank()) new SecureRandom().nextBytes(b);
      if (b.length < 32)
        throw new IllegalArgumentException("JWT HMAC key must be at least 256 bits");
      return b;
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("JWT secret must be base64 with at least 32 bytes", e);
    }
  }

  public record Principal(
      String userId, String tenantId, List<String> roles, String jti, Instant expiry) {}

  @Transactional(noRollbackFor = UnauthorizedException.class)
  public TokenResponse login(String email, String password) {
    String normalized = email.trim().toLowerCase(Locale.ROOT);
    List<Map<String, Object>> rows =
        db.queryForList(
            "select u.id,u.tenant_id,u.password_hash,u.account_state,u.locked_until,t.lifecycle_state from auth.user_account u join tenant.hotel_tenant t using(tenant_id) where lower(u.email)=? and u.status<>'DELETED'",
            normalized);
    if (rows.size() != 1) throw unauthorized();
    Map<String, Object> r = rows.getFirst();
    UUID user = (UUID) r.get("id");
    String tenant = (String) r.get("tenant_id");
    if (!"ACTIVE".equals(r.get("lifecycle_state"))) throw unauthorized();
    java.time.Instant locked =
        ((java.sql.Timestamp) r.getOrDefault("locked_until", null)) == null
            ? null
            : ((java.sql.Timestamp) r.get("locked_until")).toInstant();
    if ("LOCKED".equals(r.get("account_state"))
        && (locked == null || locked.isAfter(Instant.now()))) throw unauthorized();
    if ("LOCKED".equals(r.get("account_state"))
        && locked != null
        && !locked.isAfter(Instant.now())) {
      db.update(
          "update auth.user_account set account_state='ACTIVE',failed_login_count=0,locked_until=null where tenant_id=? and id=?",
          tenant,
          user);
    }
    String hash = (String) r.get("password_hash");
    if ((!"ACTIVE".equals(r.get("account_state"))
            && !("LOCKED".equals(r.get("account_state"))
                && locked != null
                && !locked.isAfter(Instant.now())))
        || hash == null
        || !passwords.matches(password, hash)) {
      int attempts =
          db.queryForObject(
              "select failed_login_count from auth.user_account where tenant_id=? and id=?",
              Integer.class,
              tenant,
              user);
      int next = attempts + 1;
      db.update(
          "update auth.user_account set failed_login_count=?, account_state=?, locked_until=? where tenant_id=? and id=?",
          next,
          next >= 5 ? "LOCKED" : "ACTIVE",
          next >= 5 ? java.sql.Timestamp.from(Instant.now().plus(Duration.ofMinutes(15))) : null,
          tenant,
          user);
      db.update("insert into auth.login_attempt(email,succeeded) values(?,false)", normalized);
      throw unauthorized();
    }
    db.update(
        "update auth.user_account set failed_login_count=0,locked_until=null,account_state='ACTIVE' where tenant_id=? and id=?",
        tenant,
        user);
    List<String> roles =
        db.query(
            "select role_code from auth.user_role where tenant_id=? and user_id=? and status='ACTIVE'",
            (rs, n) -> rs.getString(1),
            tenant,
            user);
    if (roles.isEmpty()) throw unauthorized();
    db.update("insert into auth.login_attempt(email,succeeded) values(?,true)", normalized);
    UUID sid = UUID.randomUUID(), family = UUID.randomUUID();
    db.update(
        "insert into auth.refresh_token_family(id,tenant_id,user_id) values(?,?,?)",
        family,
        tenant,
        user);
    String refresh = randomToken();
    UUID rid = UUID.randomUUID();
    db.update(
        "insert into auth.refresh_token(id,tenant_id,user_id,token_hash,expires_at,family_id) values(?,?,?,?,?,?)",
        rid,
        tenant,
        user,
        hash(refresh),
        java.sql.Timestamp.from(Instant.now().plusSeconds(REFRESH_SECONDS)),
        family);
    db.update(
        "insert into auth.session_record(id,tenant_id,user_id) values(?,?,?)", sid, tenant, user);
    db.update("update auth.refresh_token_family set session_id=? where id=?", sid, family);
    emit(tenant, "UserLoggedIn", Map.of("userId", user.toString()));
    return issue(user.toString(), tenant, roles, sid.toString(), refresh);
  }

  @Transactional(noRollbackFor = UnauthorizedException.class)
  public TokenResponse refresh(String raw) {
    String hash = hash(raw);
    List<Map<String, Object>> rows =
        db.queryForList(
            "select r.id,r.tenant_id,r.user_id,r.expires_at,r.revoked_at,r.consumed_at,r.family_id,f.session_id,u.account_state,t.lifecycle_state from auth.refresh_token r join auth.refresh_token_family f on f.id=r.family_id join auth.user_account u on u.id=r.user_id and u.tenant_id=r.tenant_id join tenant.hotel_tenant t on t.tenant_id=r.tenant_id where r.token_hash=? for update of r",
            hash);
    if (rows.isEmpty()) throw unauthorized();
    Map<String, Object> r = rows.getFirst();
    String tenant = (String) r.get("tenant_id");
    UUID user = (UUID) r.get("user_id"), family = (UUID) r.get("family_id");
    if (r.get("consumed_at") != null || r.get("revoked_at") != null) {
      db.update(
          "update auth.refresh_token_family set revoked_at=now(),reuse_detected_at=now() where id=?",
          family);
      db.update("update auth.refresh_token set revoked_at=now() where family_id=?", family);
      throw unauthorized();
    }
    if (((java.sql.Timestamp) r.get("expires_at")).toInstant().isBefore(Instant.now())
        || !"ACTIVE".equals(r.get("account_state"))
        || !"ACTIVE".equals(r.get("lifecycle_state"))) throw unauthorized();
    List<String> roles =
        db.query(
            "select role_code from auth.user_role where tenant_id=? and user_id=? and status='ACTIVE'",
            (rs, n) -> rs.getString(1),
            tenant,
            user);
    String next = randomToken();
    UUID nextId = UUID.randomUUID();
    db.update(
        "insert into auth.refresh_token(id,tenant_id,user_id,token_hash,expires_at,family_id) values(?,?,?,?,?,?)",
        nextId,
        tenant,
        user,
        hash(next),
        java.sql.Timestamp.from(Instant.now().plusSeconds(REFRESH_SECONDS)),
        family);
    db.update(
        "update auth.refresh_token set consumed_at=now(),replaced_by=? where id=?",
        nextId,
        r.get("id"));
    String session = r.get("session_id").toString();
    return issue(user.toString(), tenant, roles, session, next);
  }

  @Transactional
  public void logout(String bearer, String refresh) {
    Principal p = principal(bearer);
    redis
        .opsForValue()
        .set(
            "auth:blacklist:" + p.jti(),
            "1",
            Duration.between(Instant.now(), p.expiry()).isNegative()
                ? Duration.ofSeconds(1)
                : Duration.between(Instant.now(), p.expiry()));
    db.update("update auth.refresh_token set revoked_at=now() where token_hash=?", hash(refresh));
    db.update(
        "update auth.refresh_token_family set revoked_at=now() where id in(select family_id from auth.refresh_token where token_hash=?)",
        hash(refresh));
    db.update("update auth.session_record set revoked_at=now() where id=?::uuid", p.jti());
  }

  @Transactional
  public void forgotPassword(String email) {
    List<Map<String, Object>> rows =
        db.queryForList(
            "select id,tenant_id from auth.user_account where lower(email)=? and status<>'DELETED'",
            email.toLowerCase(Locale.ROOT));
    for (Map<String, Object> r : rows) {
      String token = randomToken();
      db.update(
          "insert into auth.password_reset_token(tenant_id,user_id,token_hash,expires_at) values(?,?,?,?)",
          r.get("tenant_id"),
          r.get("id"),
          hash(token),
          java.sql.Timestamp.from(Instant.now().plus(Duration.ofMinutes(30))));
      emit(
          (String) r.get("tenant_id"),
          "PasswordResetRequested",
          Map.of("userId", r.get("id").toString(), "resetToken", token));
    }
  }

  @Transactional
  public void resetPassword(String token, String newPassword) {
    policy(newPassword);
    List<Map<String, Object>> rows =
        db.queryForList(
            "select id,tenant_id,user_id from auth.password_reset_token where token_hash=? and consumed_at is null and expires_at>now() for update",
            hash(token));
    if (rows.isEmpty()) throw unauthorized();
    Map<String, Object> r = rows.getFirst();
    UUID user = (UUID) r.get("user_id");
    String tenant = (String) r.get("tenant_id");
    String encoded = passwords.encode(newPassword);
    String oldPassword =
        db.queryForObject(
            "select password_hash from auth.user_account where tenant_id=? and id=?",
            String.class,
            tenant,
            user);
    preventReuse(tenant, user, encoded);
    db.update("update auth.password_reset_token set consumed_at=now() where id=?", r.get("id"));
    db.update(
        "update auth.user_account set password_hash=?,account_state='ACTIVE',failed_login_count=0,locked_until=null where id=? and tenant_id=?",
        encoded,
        user,
        tenant);
    if (oldPassword != null) {
      db.update(
          "insert into auth.password_history(tenant_id,user_id,password_hash) values(?,?,?)",
          tenant,
          user,
          oldPassword);
    }
    db.update(
        "insert into auth.password_history(tenant_id,user_id,password_hash) values(?,?,?)",
        tenant,
        user,
        encoded);
    db.update(
        "update auth.refresh_token set revoked_at=now() where user_id=? and tenant_id=?",
        user,
        tenant);
    db.update(
        "update auth.session_record set revoked_at=now() where user_id=? and tenant_id=? and revoked_at is null",
        user,
        tenant);
    db.update(
        "update auth.password_reset_token set consumed_at=coalesce(consumed_at,now()) where user_id=? and tenant_id=?",
        user,
        tenant);
  }

  @Transactional
  public void changePassword(String bearer, String current, String next) {
    Principal p = principal(bearer);
    policy(next);
    String old =
        db.queryForObject(
            "select password_hash from auth.user_account where id=?::uuid and tenant_id=?",
            String.class,
            p.userId(),
            p.tenantId());
    if (!passwords.matches(current, old)) throw unauthorized();
    String encoded = passwords.encode(next);
    preventReuse(p.tenantId(), UUID.fromString(p.userId()), encoded);
    db.update(
        "insert into auth.password_history(tenant_id,user_id,password_hash) values(?,?,?)",
        p.tenantId(),
        p.userId(),
        old);
    db.update(
        "update auth.user_account set password_hash=? where id=?::uuid and tenant_id=?",
        encoded,
        p.userId(),
        p.tenantId());
    db.update(
        "insert into auth.password_history(tenant_id,user_id,password_hash) values(?,?,?)",
        p.tenantId(),
        p.userId(),
        encoded);
    db.update(
        "update auth.refresh_token set revoked_at=now() where user_id=?::uuid and tenant_id=?",
        p.userId(),
        p.tenantId());
    db.update(
        "update auth.session_record set revoked_at=now() where user_id=?::uuid and tenant_id=? and revoked_at is null",
        p.userId(),
        p.tenantId());
  }

  private void preventReuse(String tenant, UUID user, String next) {
    List<String> old =
        db.query(
            "select password_hash from auth.password_history where tenant_id=? and user_id=? order by changed_at desc limit 5",
            (rs, n) -> rs.getString(1),
            tenant,
            user);
    String current =
        db.queryForObject(
            "select password_hash from auth.user_account where tenant_id=? and id=?",
            String.class,
            tenant,
            user);
    if (current != null && passwords.matches(next, current)
        || old.stream().anyMatch(h -> passwords.matches(next, h)))
      throw new ValidationException("Password was used recently");
  }

  private static void policy(String p) {
    if (p.length() < 10
        || p.chars().noneMatch(Character::isUpperCase)
        || p.chars().noneMatch(Character::isLowerCase)
        || p.chars().noneMatch(Character::isDigit)
        || p.chars().noneMatch(c -> !Character.isLetterOrDigit(c)))
      throw new ValidationException(
          "Password must be 10+ characters with upper, lower, digit and symbol");
  }

  public Principal validate(String bearer) {
    return principal(bearer);
  }

  public boolean hasPermission(String bearer, String permission) {
    return permissionService.anyRoleHas(principal(bearer).roles(), permission);
  }

  public List<Map<String, Object>> sessions(String bearer) {
    Principal p = principal(bearer);
    return db.queryForList(
        "select id,created_date,last_seen_at,revoked_at from auth.session_record where tenant_id=? and user_id=?::uuid order by created_date desc",
        p.tenantId(),
        p.userId());
  }

  public void revokeSession(String bearer, UUID id) {
    Principal p = principal(bearer);
    db.update(
        "update auth.session_record set revoked_at=now() where id=? and tenant_id=? and user_id=?::uuid",
        id,
        p.tenantId(),
        p.userId());
  }

  public Principal principal(String bearer) {
    try {
      String[] parts = bearer.replaceFirst("(?i)^Bearer ", "").split("\\.");
      if (parts.length != 3) throw unauthorized();
      String signed = parts[0] + "." + parts[1];
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(jwtKey, "HmacSHA256"));
      if (!MessageDigest.isEqual(
          mac.doFinal(signed.getBytes(StandardCharsets.US_ASCII)),
          Base64.getUrlDecoder().decode(parts[2]))) throw unauthorized();
      Map<?, ?> claims = json.readValue(Base64.getUrlDecoder().decode(parts[1]), Map.class);
      Instant expiry = Instant.ofEpochSecond(((Number) claims.get("exp")).longValue());
      String id = (String) claims.get("jti");
      if (expiry.isBefore(Instant.now())
          || Boolean.TRUE.equals(redis.hasKey("auth:blacklist:" + id))) throw unauthorized();
      Integer activeSession =
          db.queryForObject(
              "select count(*) from auth.session_record where id=?::uuid and revoked_at is null",
              Integer.class,
              id);
      if (activeSession == null || activeSession == 0) throw unauthorized();
      return new Principal(
          (String) claims.get("sub"),
          (String) claims.get("tenant_id"),
          (List<String>) claims.get("roles"),
          id,
          expiry);
    } catch (Exception e) {
      throw unauthorized();
    }
  }

  private TokenResponse issue(
      String sub, String tenant, List<String> roles, String session, String refresh) {
    try {
      Instant now = Instant.now();
      Map<String, Object> header = Map.of("alg", "HS256", "typ", "JWT", "kid", keyId);
      Map<String, Object> claims =
          Map.of(
              "sub",
              sub,
              "iat",
              now.getEpochSecond(),
              "exp",
              now.plusSeconds(ACCESS_SECONDS).getEpochSecond(),
              "jti",
              session,
              "tenant_id",
              tenant,
              "roles",
              roles);
      String h =
          Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(header));
      String p =
          Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(claims));
      String signed = h + "." + p;
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(jwtKey, "HmacSHA256"));
      String token =
          signed
              + "."
              + Base64.getUrlEncoder()
                  .withoutPadding()
                  .encodeToString(mac.doFinal(signed.getBytes(StandardCharsets.US_ASCII)));
      return new TokenResponse(token, refresh, "Bearer", ACCESS_SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException("JWT signing failed", e);
    }
  }

  private void emit(String tenant, String type, Map<String, Object> payload) {
    try {
      db.update(
          "insert into auth.outbox_event(tenant_id,event_type,payload) values(?,?,?::jsonb)",
          tenant,
          type,
          json.writeValueAsString(
              Map.of(
                  "eventType", type, "occurredAt", Instant.now().toString(), "payload", payload)));
    } catch (Exception ex) {
      throw new IllegalStateException("Could not serialize auth event", ex);
    }
  }

  private static String randomToken() {
    byte[] b = new byte[48];
    new SecureRandom().nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  private static String hash(String s) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static UnauthorizedException unauthorized() {
    return new UnauthorizedException("Invalid credentials or token");
  }
}
