package com.hotelsaas.common.error;

public class ValidationException extends BaseException {
  private final ErrorCode code;

  public ValidationException(String message) {
    this(ErrorCode.VALIDATION_ERROR, message);
  }

  public ValidationException(ErrorCode code, String message) {
    super(message);
    this.code = code;
  }

  public ErrorCode getCode() {
    return code;
  }
}
