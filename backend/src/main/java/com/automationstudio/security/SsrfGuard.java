package com.automationstudio.security;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * SSRF guard, ON by default. Static checks at validation time, DNS + redirect
 * re-checks at call time. Override only via HTTP_ALLOW_PRIVATE=true.
 */
@Component
public class SsrfGuard {

  private final boolean allowPrivate;

  public SsrfGuard(@Value("${security.http-allow-private:false}") boolean allowPrivate) {
    this.allowPrivate = allowPrivate;
  }

  public void checkUrl(String url) {
    if (url == null || url.isBlank()) throw new BlockedException("URL is blank");
    URI uri;
    try {
      uri = new URI(url);
    } catch (Exception e) {
      throw new BlockedException("Bad URL: " + url);
    }
    String scheme = uri.getScheme();
    if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
      throw new BlockedException("Only http/https allowed: " + url);
    }
    String host = uri.getHost();
    if (host == null || host.isBlank()) throw new BlockedException("URL has no host: " + url);
    checkHost(host, url);
  }

  public void checkHost(String host, String url) {
    if (allowPrivate) return;
    String h = IDN.toASCII(host).toLowerCase();
    if (h.equals("localhost") || h.endsWith(".local") || h.endsWith(".internal")
        || h.equals("metadata.google.internal") || h.equals("metadata.google.internal.")) {
      throw new BlockedException("Blocked host: " + host);
    }
    try {
      InetAddress[] addrs = InetAddress.getAllByName(h);
      for (InetAddress addr : addrs) {
        if (isBlocked(addr)) throw new BlockedException("Blocked IP for " + host);
      }
    } catch (java.net.UnknownHostException e) {
      throw new BlockedException("Cannot resolve host: " + host);
    }
  }

  private boolean isBlocked(InetAddress addr) {
    if (addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isMulticastAddress()
        || addr.isSiteLocalAddress()) {
      return true;
    }
    byte[] b = addr.getAddress();
    if (b.length == 4) {
      int a = b[0] & 0xFF, c = b[1] & 0xFF, d = b[2] & 0xFF;
      if (a == 100 && c >= 64 && c <= 127) return true; // CGNAT
      if (a == 192 && c == 0 && d == 2) return true; // TEST-NET-1
      if (a == 198 && c == 51 && d == 100) return true; // TEST-NET-2
      if (a == 203 && c == 0 && d == 113) return true; // TEST-NET-3
    }
    return false;
  }

  public static class BlockedException extends RuntimeException {
    public BlockedException(String message) {
      super(message);
    }
  }
}
