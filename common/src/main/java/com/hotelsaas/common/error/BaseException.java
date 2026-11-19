package com.hotelsaas.common.error;

public class BaseException extends RuntimeException {
  private final ErrorCode code;

  public BaseException(String message) {
    this(ErrorCode.INTERNAL_ERROR, message);
  }

  public BaseException(ErrorCode code, String message) {
    super(message);
    this.code = code;
  }

  public ErrorCode getCode() {
    return code;
  }
}
