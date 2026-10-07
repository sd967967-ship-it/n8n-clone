package com.automationstudio.execution;

public interface NodeExecutor {
  /** Node type this executor handles (e.g. "http_request"). */
  String type();

  NodeResult execute(ExecContext ctx) throws Exception;
}
