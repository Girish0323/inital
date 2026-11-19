package com.hotelsaas.common.error;

public class ForbiddenException extends BaseException {
  private final ErrorCode code;

  public ForbiddenException(String message) {
    this(ErrorCode.FORBIDDEN, message);
  }

  public ForbiddenException(ErrorCode code, String message) {
    super(message);
    this.code = code;
  }

  public ErrorCode getCode() {
    return code;
  }
}
