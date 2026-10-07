package com.automationstudio.pieces;

import java.util.List;
import java.util.Map;

/**
 * A piece manifest is data, not code. One YAML per app in
 * {@code backend/src/main/resources/pieces/<app>.yml}.
 * OAuth-only apps ship with {@code auth.type: oauth2} and {@code enabled: false}
 * until the OAuth slice lands (keys-only v1).
 */
public record PieceManifest(
    String app,
    String displayName,
    String category,
    boolean enabled,
    Auth auth,
    String baseUrl,
    Map<String, String> headers,
    List<Operation> operations,
    List<Trigger> triggers) {

  public record Auth(
      String type,
      String header,
      String prefix,
      String queryParam,
      List<String> queryParams,
      List<String> credentialFields) {}

  public record Operation(
      String id,
      String displayName,
      String method,
      String path,
      String baseUrl,
      List<String> params,
      Map<String, Object> body) {}

  public record Trigger(
      String id,
      String displayName,
      String method,
      String path,
      List<String> params,
      String cursor) {}
}
