package com.hotelsaas.common.error;

public class BusinessRuleException extends BaseException {
  private final ErrorCode code;

  public BusinessRuleException(String message) {
    this(ErrorCode.BUSINESS_RULE_VIOLATION, message);
  }

  public BusinessRuleException(ErrorCode code, String message) {
    super(message);
    this.code = code;
  }

  public ErrorCode getCode() {
    return code;
  }
}
