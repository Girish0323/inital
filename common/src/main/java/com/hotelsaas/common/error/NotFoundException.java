package com.hotelsaas.common.error;

public class NotFoundException extends BaseException {
  private final ErrorCode code;

  public NotFoundException(String message) {
    this(ErrorCode.NOT_FOUND, message);
  }

  public NotFoundException(ErrorCode code, String message) {
    super(message);
    this.code = code;
  }

  public ErrorCode getCode() {
    return code;
  }
}
