package com.automationstudio.expression;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Resolves dotted ref paths (with [index]) against a context map. Missing path -> null. */
public class RefResolver {

  public Object resolve(String path, Map<String, Object> context) {
    if (path == null || path.isEmpty() || context == null) return null;
    List<PathSegment> segments = split(path);
    Object cur = context;
    for (PathSegment seg : segments) {
      if (cur instanceof Map<?, ?> map) {
        cur = map.get(seg.name());
      } else {
        return null;
      }
      for (int idx : seg.indexes()) {
        if (cur instanceof List<?> list) {
          if (idx < 0 || idx >= list.size()) return null;
          cur = list.get(idx);
        } else {
          return null;
        }
      }
      if (cur == null) return null;
    }
    return cur;
  }

  /** Collect every bare ref path used in an expression (incl. exists() args). */
  public static Set<String> collectRefs(Expr expr) {
    Set<String> out = new LinkedHashSet<>();
    collect(expr, out);
    return out;
  }

  private static void collect(Expr e, Set<String> out) {
    if (e instanceof Expr.Ref r) {
      out.add(r.path());
    } else if (e instanceof Expr.Or o) {
      o.terms().forEach(t -> collect(t, out));
    } else if (e instanceof Expr.And a) {
      a.terms().forEach(t -> collect(t, out));
    } else if (e instanceof Expr.Not n) {
      collect(n.inner(), out);
    } else if (e instanceof Expr.Comparison c) {
      collect(c.left(), out);
      collect(c.right(), out);
    } else if (e instanceof Expr.Call c) {
      c.args().forEach(a -> collect(a, out));
    }
  }

  private record PathSegment(String name, List<Integer> indexes) {}

  /** Split "a.b[0].c" -> [a, b[0], c]. Bare "input" stays single segment. */
  static List<PathSegment> split(String path) {
    List<PathSegment> out = new ArrayList<>();
    // split on '.' but keep [n] attached; refs never contain quoted dots in MVP
    String[] parts = path.split("\\.", -1);
    for (String p : parts) {
      if (p.isEmpty()) throw new ParseException("Bad ref path: " + path);
      String name = p;
      List<Integer> idx = new ArrayList<>();
      int b = p.indexOf('[');
      if (b >= 0) {
        name = p.substring(0, b);
        int i = b;
        while (i < p.length()) {
          if (p.charAt(i) != '[') throw new ParseException("Bad ref path: " + path);
          int j = p.indexOf(']', i);
          if (j < 0) throw new ParseException("Bad ref path: " + path);
          try {
            idx.add(Integer.parseInt(p.substring(i + 1, j)));
          } catch (NumberFormatException ex) {
            throw new ParseException("Bad ref index: " + path);
          }
          i = j + 1;
        }
        if (name.isEmpty()) throw new ParseException("Bad ref path: " + path);
      }
      out.add(new PathSegment(name, List.copyOf(idx)));
    }
    return out;
  }
}
