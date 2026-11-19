package com.hotelsaas.common.error;

public class ConflictException extends BaseException {
  private final ErrorCode code;

  public ConflictException(String message) {
    this(ErrorCode.CONFLICT, message);
  }

  public ConflictException(ErrorCode code, String message) {
    super(message);
    this.code = code;
  }

  public ErrorCode getCode() {
    return code;
  }
}
