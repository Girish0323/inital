package com.hotelsaas.authservice.api;

import com.hotelsaas.authservice.application.AuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
  private final AuthService auth;

  public AuthController(AuthService auth) {
    this.auth = auth;
  }

  public record LoginRequest(@Email @NotBlank String email, @NotBlank String password) {}

  public record RefreshRequest(@NotBlank String refreshToken) {}

  public record TokenResponse(
      String accessToken, String refreshToken, String tokenType, long expiresIn) {}

  public record EmailRequest(@Email @NotBlank String email) {}

  public record ResetRequest(
      @NotBlank String token, @NotBlank @Size(min = 10) String newPassword) {}

  public record ChangePasswordRequest(
      @NotBlank String currentPassword, @NotBlank @Size(min = 10) String newPassword) {}

  @GetMapping("/validate")
  public Object validate(@RequestHeader("Authorization") String bearer) {
    var p = auth.validate(bearer);
    return Map.of("userId", p.userId(), "tenantId", p.tenantId(), "roles", p.roles());
  }

  @PostMapping("/login")
  public TokenResponse login(@Valid @RequestBody LoginRequest r) {
    return auth.login(r.email(), r.password());
  }

  @PostMapping("/refresh")
  public TokenResponse refresh(@Valid @RequestBody RefreshRequest r) {
    return auth.refresh(r.refreshToken());
  }

  @PostMapping("/logout")
  public ResponseEntity<Void> logout(
      @RequestHeader("Authorization") String b, @Valid @RequestBody RefreshRequest r) {
    auth.logout(b, r.refreshToken());
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/password/forgot")
  public Map<String, String> forgot(@Valid @RequestBody EmailRequest r) {
    auth.forgotPassword(r.email());
    return Map.of("message", "If the account exists, reset instructions will be sent.");
  }

  @PostMapping("/password/reset")
  public ResponseEntity<Void> reset(@Valid @RequestBody ResetRequest r) {
    auth.resetPassword(r.token(), r.newPassword());
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/password/change")
  public ResponseEntity<Void> change(
      @RequestHeader("Authorization") String b, @Valid @RequestBody ChangePasswordRequest r) {
    auth.changePassword(b, r.currentPassword(), r.newPassword());
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/permissions/{permission}")
  public Map<String, Boolean> permission(
      @RequestHeader("Authorization") String b, @PathVariable String permission) {
    return Map.of("allowed", auth.hasPermission(b, permission));
  }

  @GetMapping("/sessions")
  public Object sessions(@RequestHeader("Authorization") String b) {
    return auth.sessions(b);
  }

  @DeleteMapping("/sessions/{id}")
  public ResponseEntity<Void> revoke(
      @RequestHeader("Authorization") String b, @PathVariable UUID id) {
    auth.revokeSession(b, id);
    return ResponseEntity.noContent().build();
  }
}
