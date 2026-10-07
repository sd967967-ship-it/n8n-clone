package com.automationstudio.workflow;

public record ValidationIssue(
    String code, String nodeId, String path, String message, boolean warning) {

  public static ValidationIssue error(String code, String nodeId, String path, String message) {
    return new ValidationIssue(code, nodeId, path, message, false);
  }

  public static ValidationIssue warning(String code, String nodeId, String path, String message) {
    return new ValidationIssue(code, nodeId, path, message, true);
  }
}
