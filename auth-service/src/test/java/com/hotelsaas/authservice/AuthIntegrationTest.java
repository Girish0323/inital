package com.hotelsaas.authservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hotelsaas.authservice.api.AuthController.TokenResponse;
import com.hotelsaas.authservice.application.AuthService;
import com.hotelsaas.common.error.UnauthorizedException;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(properties = {"eureka.client.enabled=false", "spring.flyway.clean-disabled=false"})
class AuthIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("auth_test")
          .withUsername("auth")
          .withPassword("auth");

  @Container
  static final GenericContainer<?> redis =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
    r.add("spring.data.redis.host", redis::getHost);
    r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
  }

  @Autowired AuthService auth;
  @Autowired JdbcTemplate db;
  private String email;
  private final String password = "StrongPass9!";

  @BeforeEach
  void user(TestInfo testInfo) {
    if (testInfo.getDisplayName().contains("permission matrix")) return;
    db.update(
        "update tenant.hotel_tenant set lifecycle_state=? where tenant_id=?",
        "ACTIVE",
        "ABC20261117001");
    email = "it-" + UUID.randomUUID() + "@example.test";
    db.update(
        "insert into auth.user_account(tenant_id,email,display_name,password_hash,account_state) values('ABC20261117001',?,'Auth test',?,'ACTIVE')",
        email,
        new BCryptPasswordEncoder(12).encode(password));
    db.update(
        "insert into auth.user_role(tenant_id,user_id,role_code) select tenant_id,id,'CASHIER' from auth.user_account where email=?",
        email);
  }

  @Test
  void loginRefreshAndLogoutWork() {
    TokenResponse login = auth.login(email, password);
    assertThat(login.accessToken()).contains(".");
    TokenResponse rotated = auth.refresh(login.refreshToken());
    assertThat(rotated.refreshToken()).isNotEqualTo(login.refreshToken());
    auth.logout(rotated.accessToken(), rotated.refreshToken());
    assertThatThrownBy(() -> auth.principal(rotated.accessToken()))
        .isInstanceOf(UnauthorizedException.class);
  }

  @Test
  void failedLoginsLockAccount() {
    for (int i = 0; i < 5; i++) assertThatThrownBy(() -> auth.login(email, "WrongPass9!"));
    assertThatThrownBy(() -> auth.login(email, password)).isInstanceOf(UnauthorizedException.class);
    assertThat(
            db.queryForObject(
                "select account_state from auth.user_account where email=?", String.class, email))
        .isEqualTo("LOCKED");
  }

  @Test
  void expiredRefreshTokenIsRejected() {
    TokenResponse login = auth.login(email, password);
    db.update(
        "update auth.refresh_token set expires_at=now()-interval '1 second' where token_hash=?",
        sha(login.refreshToken()));
    assertThatThrownBy(() -> auth.refresh(login.refreshToken()))
        .isInstanceOf(UnauthorizedException.class);
  }

  @Test
  void revokedRefreshTokenIsRejected() {
    TokenResponse login = auth.login(email, password);
    db.update(
        "update auth.refresh_token set revoked_at=now() where token_hash=?",
        sha(login.refreshToken()));
    assertThatThrownBy(() -> auth.refresh(login.refreshToken()))
        .isInstanceOf(UnauthorizedException.class);
  }

  @Test
  void refreshReuseRevokesTheWholeFamily() {
    TokenResponse first = auth.login(email, password);
    TokenResponse second = auth.refresh(first.refreshToken());
    assertThatThrownBy(() -> auth.refresh(first.refreshToken()))
        .isInstanceOf(UnauthorizedException.class);
    assertThatThrownBy(() -> auth.refresh(second.refreshToken()))
        .isInstanceOf(UnauthorizedException.class);
  }

  @Test
  void suspendedTenantCannotLogin() {
    db.update(
        "update tenant.hotel_tenant set lifecycle_state='SUSPENDED' where tenant_id='ABC20261117001'");
    assertThatThrownBy(() -> auth.login(email, password)).isInstanceOf(UnauthorizedException.class);
  }

  @ParameterizedTest(name = "{0} permission matrix")
  @MethodSource("permissionMatrix")
  void entirePermissionMatrixMatchesDatabase(
      String permission, java.util.List<String> expectedRoles) {
    var actual =
        db.query(
            "select role_code from auth.role_permission where permission_code=? order by role_code",
            (rs, n) -> rs.getString(1),
            permission);
    assertThat(actual).containsExactlyElementsOf(expectedRoles);
  }

  static Stream<org.junit.jupiter.params.provider.Arguments> permissionMatrix() {
    return Stream.of(
        org.junit.jupiter.params.provider.Arguments.of(
            "tenant.lifecycle.manage", java.util.List.of("SUPER_ADMIN")),
        org.junit.jupiter.params.provider.Arguments.of(
            "subscription.manage", java.util.List.of("SUPER_ADMIN")),
        org.junit.jupiter.params.provider.Arguments.of(
            "platform.metrics.view", java.util.List.of("SUPER_ADMIN")),
        org.junit.jupiter.params.provider.Arguments.of(
            "platform.audit.read", java.util.List.of("SUPER_ADMIN")),
        org.junit.jupiter.params.provider.Arguments.of(
            "owner.password.reset", java.util.List.of("SUPER_ADMIN")),
        org.junit.jupiter.params.provider.Arguments.of(
            "hotel.profile.read", java.util.List.of("MANAGER", "OWNER")),
        org.junit.jupiter.params.provider.Arguments.of(
            "hotel.profile.update", java.util.List.of("OWNER")),
        org.junit.jupiter.params.provider.Arguments.of(
            "user.manager.manage", java.util.List.of("OWNER")),
        org.junit.jupiter.params.provider.Arguments.of(
            "user.staff.manage", java.util.List.of("MANAGER", "OWNER")),
        org.junit.jupiter.params.provider.Arguments.of(
            "category.create", java.util.List.of("MANAGER")),
        org.junit.jupiter.params.provider.Arguments.of(
            "category.read", java.util.List.of("CASHIER", "MANAGER", "OWNER", "STAFF")),
        org.junit.jupiter.params.provider.Arguments.of(
            "category.update", java.util.List.of("MANAGER")),
        org.junit.jupiter.params.provider.Arguments.of(
            "category.delete", java.util.List.of("MANAGER")),
        org.junit.jupiter.params.provider.Arguments.of("menu.create", java.util.List.of("MANAGER")),
        org.junit.jupiter.params.provider.Arguments.of(
            "menu.read", java.util.List.of("CASHIER", "MANAGER", "OWNER", "STAFF")),
        org.junit.jupiter.params.provider.Arguments.of("menu.update", java.util.List.of("MANAGER")),
        org.junit.jupiter.params.provider.Arguments.of("menu.delete", java.util.List.of("MANAGER")),
        org.junit.jupiter.params.provider.Arguments.of(
            "order.create", java.util.List.of("CASHIER")),
        org.junit.jupiter.params.provider.Arguments.of(
            "order.read", java.util.List.of("CASHIER", "MANAGER", "OWNER")),
        org.junit.jupiter.params.provider.Arguments.of(
            "order.update", java.util.List.of("CASHIER")),
        org.junit.jupiter.params.provider.Arguments.of(
            "order.delete", java.util.List.of("CASHIER")),
        org.junit.jupiter.params.provider.Arguments.of("bill.create", java.util.List.of("CASHIER")),
        org.junit.jupiter.params.provider.Arguments.of(
            "bill.read", java.util.List.of("CASHIER", "MANAGER", "OWNER")),
        org.junit.jupiter.params.provider.Arguments.of("bill.update", java.util.List.of("CASHIER")),
        org.junit.jupiter.params.provider.Arguments.of("bill.cancel", java.util.List.of("MANAGER")),
        org.junit.jupiter.params.provider.Arguments.of(
            "payment.record", java.util.List.of("CASHIER")),
        org.junit.jupiter.params.provider.Arguments.of(
            "payment.verify", java.util.List.of("CASHIER", "MANAGER")),
        org.junit.jupiter.params.provider.Arguments.of(
            "payment.read", java.util.List.of("MANAGER", "OWNER")),
        org.junit.jupiter.params.provider.Arguments.of(
            "payment.read.own", java.util.List.of("CASHIER")),
        org.junit.jupiter.params.provider.Arguments.of("reports.full", java.util.List.of("OWNER")),
        org.junit.jupiter.params.provider.Arguments.of(
            "reports.operational", java.util.List.of("MANAGER")),
        org.junit.jupiter.params.provider.Arguments.of(
            "dashboard.platform", java.util.List.of("SUPER_ADMIN")),
        org.junit.jupiter.params.provider.Arguments.of(
            "dashboard.full", java.util.List.of("OWNER")),
        org.junit.jupiter.params.provider.Arguments.of(
            "dashboard.operational", java.util.List.of("MANAGER")),
        org.junit.jupiter.params.provider.Arguments.of(
            "hotel.audit.read", java.util.List.of("MANAGER", "OWNER")),
        org.junit.jupiter.params.provider.Arguments.of(
            "hotel.audit.write", java.util.List.of("OWNER")));
  }

  @Test
  void passwordResetIsOneTimeAndInvalidatesSessions() {
    auth.forgotPassword(email);
    String token =
        db.queryForObject(
            "select payload #>> '{payload,resetToken}' from auth.outbox_event where event_type='PasswordResetRequested' order by occurred_at desc limit 1",
            String.class);
    auth.resetPassword(token, "NewStrongPass8!");
    assertThatThrownBy(() -> auth.resetPassword(token, "AnotherPass8!"))
        .isInstanceOf(UnauthorizedException.class);
    assertThatThrownBy(() -> auth.login(email, password)).isInstanceOf(UnauthorizedException.class);
    assertThat(auth.login(email, "NewStrongPass8!").accessToken()).isNotBlank();
  }

  @Test
  void revokingASessionInvalidatesItsAccessToken() {
    TokenResponse login = auth.login(email, password);
    var session = auth.sessions(login.accessToken()).getFirst();
    UUID id = (UUID) session.get("id");
    auth.revokeSession(login.accessToken(), id);
    assertThatThrownBy(() -> auth.principal(login.accessToken()))
        .isInstanceOf(UnauthorizedException.class);
  }

  private static String sha(String s) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
