package com.automationstudio.expression;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Interprets the AST. No reflection, no method calls, only the allowlisted functions.
 * Type rules: mismatched-type comparison is false; &lt;/&gt; need numbers or numeric strings.
 */
public class ExpressionEvaluator {

  private static final Set<String> ALLOWED_FUNCTIONS =
      Set.of("contains", "startsWith", "endsWith", "lower", "upper", "trim",
          "length", "isEmpty", "number", "string", "exists");

  private final RefResolver refs = new RefResolver();

  public static boolean isAllowedFunction(String name) {
    return ALLOWED_FUNCTIONS.contains(name);
  }

  /** Evaluate and coerce the top level to boolean (condition nodes). */
  public boolean evaluateBoolean(Expr expr, Map<String, Object> context) {
    return toBoolean(evaluate(expr, context));
  }

  public Object evaluate(Expr expr, Map<String, Object> context) {
    if (expr instanceof Expr.Literal l) {
      return l.value();
    }
    if (expr instanceof Expr.Ref r) {
      return refs.resolve(r.path(), context);
    }
    if (expr instanceof Expr.Not n) {
      return !toBoolean(evaluate(n.inner(), context));
    }
    if (expr instanceof Expr.Or o) {
      for (Expr t : o.terms()) {
        if (toBoolean(evaluate(t, context))) return true;
      }
      return false;
    }
    if (expr instanceof Expr.And a) {
      for (Expr t : a.terms()) {
        if (!toBoolean(evaluate(t, context))) return false;
      }
      return true;
    }
    if (expr instanceof Expr.Comparison c) {
      return compare(c.op(), evaluate(c.left(), context), evaluate(c.right(), context));
    }
    if (expr instanceof Expr.Call c) {
      return call(c.name(), c.args(), context);
    }
    throw new IllegalStateException("Unknown expr: " + expr);
  }

  private Object compare(String op, Object left, Object right) {
    // "in": right must be a list; true if left equals any element (stringified compare fallback)
    if (op.equals("in")) {
      if (right instanceof List<?> list) {
        for (Object el : list) {
          if (equalsLenient(left, el)) return true;
        }
        return false;
      }
      return false;
    }
    if (op.equals("==")) return equalsLenient(left, right);
    if (op.equals("!=")) return !equalsLenient(left, right);
    // ordering: numbers or numeric strings only, else false
    Double l = toNumberOrNull(left);
    Double r = toNumberOrNull(right);
    if (l == null || r == null) return false;
    return switch (op) {
      case "<" -> l < r;
      case "<=" -> l <= r;
      case ">" -> l > r;
      case ">=" -> l >= r;
      default -> false;
    };
  }

  private boolean equalsLenient(Object a, Object b) {
    if (a == null || b == null) return a == b;
    if (a instanceof Number && b instanceof Number) {
      return ((Number) a).doubleValue() == ((Number) b).doubleValue();
    }
    if (a instanceof Boolean || b instanceof Boolean) {
      return a.equals(b);
    }
    // number vs numeric string: compare numerically
    if (a instanceof Number && b instanceof String) {
      Double r = toNumberOrNull(b);
      return r != null && ((Number) a).doubleValue() == r;
    }
    if (a instanceof String && b instanceof Number) {
      Double l = toNumberOrNull(a);
      return l != null && l == ((Number) b).doubleValue();
    }
    return a.equals(b);
  }

  private Double toNumberOrNull(Object o) {
    if (o instanceof Number n) return n.doubleValue();
    if (o instanceof String s) {
      try {
        return Double.parseDouble(s.trim());
      } catch (NumberFormatException e) {
        return null;
      }
    }
    return null;
  }

  private boolean toBoolean(Object o) {
    if (o instanceof Boolean b) return b;
    if (o == null) return false;
    if (o instanceof Number n) return n.doubleValue() != 0;
    if (o instanceof String s) return !s.isEmpty();
    if (o instanceof List<?> l) return !l.isEmpty();
    if (o instanceof Map<?, ?> m) return !m.isEmpty();
    return true;
  }

  private Object call(String name, List<Expr> args, Map<String, Object> ctx) {
    if (!ALLOWED_FUNCTIONS.contains(name)) {
      throw new IllegalArgumentException("Unknown function: " + name);
    }
    // exists() takes the ref path without resolving to null-ambiguity: resolve raw
    if (name.equals("exists")) {
      if (args.size() != 1 || !(args.get(0) instanceof Expr.Ref r)) {
        throw new IllegalArgumentException("exists() needs one ref argument");
      }
      return refs.resolve(r.path(), ctx) != null;
    }
    List<Object> vals = args.stream().map(a -> evaluate(a, ctx)).toList();
    return switch (name) {
      case "contains" -> {
        checkArity(name, vals, 2);
        yield contains(vals.get(0), vals.get(1));
      }
      case "startsWith" -> {
        checkArity(name, vals, 2);
        yield str(vals.get(0)) != null && str(vals.get(1)) != null
            && str(vals.get(0)).startsWith(str(vals.get(1)));
      }
      case "endsWith" -> {
        checkArity(name, vals, 2);
        yield str(vals.get(0)) != null && str(vals.get(1)) != null
            && str(vals.get(0)).endsWith(str(vals.get(1)));
      }
      case "lower" -> {
        checkArity(name, vals, 1);
        yield vals.get(0) == null ? null : str(vals.get(0)).toLowerCase();
      }
      case "upper" -> {
        checkArity(name, vals, 1);
        yield vals.get(0) == null ? null : str(vals.get(0)).toUpperCase();
      }
      case "trim" -> {
        checkArity(name, vals, 1);
        yield vals.get(0) == null ? null : str(vals.get(0)).trim();
      }
      case "length" -> {
        checkArity(name, vals, 1);
        Object v = vals.get(0);
        if (v == null) yield 0L;
        if (v instanceof String s) yield (long) s.length();
        if (v instanceof List<?> l) yield (long) l.size();
        if (v instanceof Map<?, ?> m) yield (long) m.size();
        yield (long) str(v).length();
      }
      case "isEmpty" -> {
        checkArity(name, vals, 1);
        Object v = vals.get(0);
        if (v == null) yield true;
        if (v instanceof String s) yield s.isEmpty();
        if (v instanceof List<?> l) yield l.isEmpty();
        if (v instanceof Map<?, ?> m) yield m.isEmpty();
        yield false;
      }
      case "number" -> {
        checkArity(name, vals, 1);
        Object v = vals.get(0);
        if (v instanceof Number n) yield n.doubleValue();
        if (v instanceof String s) {
          try {
            yield Double.parseDouble(s.trim());
          } catch (NumberFormatException e) {
            yield null;
          }
        }
        yield null;
      }
      case "string" -> {
        checkArity(name, vals, 1);
        Object v = vals.get(0);
        yield v == null ? null : v.toString();
      }
      default -> throw new IllegalArgumentException("Unknown function: " + name);
    };
  }

  private boolean contains(Object haystack, Object needle) {
    if (haystack instanceof List<?> list) {
      for (Object el : list) {
        if (equalsLenient(el, needle)) return true;
      }
      return false;
    }
    String h = str(haystack);
    String nd = str(needle);
    return h != null && nd != null && h.contains(nd);
  }

  private String str(Object o) {
    return o == null ? null : o.toString();
  }

  private void checkArity(String name, List<Object> vals, int n) {
    if (vals.size() != n) throw new IllegalArgumentException(name + "() needs " + n + " args");
  }
}
