package com.hotelsaas.authservice.application;

/** Boundary for a future authenticator; MFA is available but not enforced by Phase 2. */
public interface TotpVerifier {
  boolean verify(String secret, String code);
}
