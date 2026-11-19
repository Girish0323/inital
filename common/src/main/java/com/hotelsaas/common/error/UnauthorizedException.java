package com.hotelsaas.common.error;

public class UnauthorizedException extends BaseException {
  private final ErrorCode code;

  public UnauthorizedException(String message) {
    this(ErrorCode.UNAUTHORIZED, message);
  }

  public UnauthorizedException(ErrorCode code, String message) {
    super(message);
    this.code = code;
  }

  public ErrorCode getCode() {
    return code;
  }
}
