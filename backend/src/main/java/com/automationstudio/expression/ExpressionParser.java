package com.automationstudio.expression;

import java.util.ArrayList;
import java.util.List;

/**
 * Hand-written recursive-descent parser for the MVP expression grammar.
 * Never uses eval. Throws {@link ParseException} on any syntax error.
 */
public class ExpressionParser {

  private List<Token> tokens;
  private int pos;

  public Expr parse(String input) {
    if (input == null || input.isBlank()) {
      throw new ParseException("Expression is blank");
    }
    this.tokens = tokenize(input);
    this.pos = 0;
    Expr e = parseOr();
    if (pos != tokens.size()) {
      throw new ParseException("Unexpected token: " + tokens.get(pos).text);
    }
    return e;
  }

  // or := and ( "||" and )*
  private Expr parseOr() {
    List<Expr> terms = new ArrayList<>();
    terms.add(parseAnd());
    while (match(TokenType.OR)) {
      terms.add(parseAnd());
    }
    return terms.size() == 1 ? terms.get(0) : new Expr.Or(List.copyOf(terms));
  }

  // and := not ( "&&" not )*
  private Expr parseAnd() {
    List<Expr> terms = new ArrayList<>();
    terms.add(parseNot());
    while (match(TokenType.AND)) {
      terms.add(parseNot());
    }
    return terms.size() == 1 ? terms.get(0) : new Expr.And(List.copyOf(terms));
  }

  // not := "!" not | cmp
  private Expr parseNot() {
    if (match(TokenType.NOT)) {
      return new Expr.Not(parseNot());
    }
    return parseCmp();
  }

  // cmp := value ( ("=="|"!="|"<"|"<="|">"|">="|"in") value )?
  private Expr parseCmp() {
    Expr left = parseValue();
    TokenType t = peekType();
    String op = null;
    if (t == TokenType.EQ) op = "==";
    else if (t == TokenType.NEQ) op = "!=";
    else if (t == TokenType.LT) op = "<";
    else if (t == TokenType.LTE) op = "<=";
    else if (t == TokenType.GT) op = ">";
    else if (t == TokenType.GTE) op = ">=";
    else if (t == TokenType.IN) op = "in";
    if (op != null) {
      pos++;
      Expr right = parseValue();
      return new Expr.Comparison(op, left, right);
    }
    return left;
  }

  // value := literal | ref | call | "(" expr ")"
  private Expr parseValue() {
    Token tok = peek();
    if (tok == null) throw new ParseException("Unexpected end of expression");
    switch (tok.type) {
      case STRING -> {
        pos++;
        return new Expr.Literal(tok.literal);
      }
      case NUMBER, INT -> {
        pos++;
        return new Expr.Literal(tok.literal);
      }
      case TRUE -> {
        pos++;
        return new Expr.Literal(Boolean.TRUE);
      }
      case FALSE -> {
        pos++;
        return new Expr.Literal(Boolean.FALSE);
      }
      case NULL -> {
        pos++;
        return new Expr.Literal(null);
      }
      case LPAREN -> {
        pos++;
        Expr inner = parseOr();
        expect(TokenType.RPAREN, "Expected ')'");
        return inner;
      }
      case IDENT -> {
        // call or ref
        if (peekAt(1) != null && peekAt(1).type == TokenType.LPAREN) {
          return parseCall();
        }
        return parseRef();
      }
      default -> throw new ParseException("Unexpected token: " + tok.text);
    }
  }

  private Expr parseCall() {
    String name = expectIdent();
    expect(TokenType.LPAREN, "Expected '(' after function name");
    List<Expr> args = new ArrayList<>();
    if (peekType() != TokenType.RPAREN) {
      args.add(parseOr());
      while (match(TokenType.COMMA)) {
        args.add(parseOr());
      }
    }
    expect(TokenType.RPAREN, "Expected ')' to close call");
    return new Expr.Call(name, List.copyOf(args));
  }

  private Expr parseRef() {
    StringBuilder sb = new StringBuilder();
    sb.append(expectIdent());
    while (true) {
      if (match(TokenType.DOT)) {
        sb.append('.').append(expectIdent());
      } else if (peekType() == TokenType.LBRACKET) {
        pos++; // [
        Token idx = peek();
        if (idx == null || idx.type != TokenType.INT) {
          throw new ParseException("Expected array index after '['");
        }
        pos++;
        expect(TokenType.RBRACKET, "Expected ']'");
        sb.append('[').append(idx.text).append(']');
      } else {
        break;
      }
    }
    return new Expr.Ref(sb.toString());
  }

  // ---- tokens ----

  private enum TokenType {
    OR, AND, NOT, EQ, NEQ, LT, LTE, GT, GTE, IN,
    LPAREN, RPAREN, COMMA, DOT, LBRACKET, RBRACKET,
    STRING, NUMBER, INT, IDENT, TRUE, FALSE, NULL
  }

  private record Token(TokenType type, String text, Object literal) {}

  private Token peek() {
    return pos < tokens.size() ? tokens.get(pos) : null;
  }

  private Token peekAt(int off) {
    int i = pos + off;
    return i < tokens.size() ? tokens.get(i) : null;
  }

  private TokenType peekType() {
    Token t = peek();
    return t == null ? null : t.type;
  }

  private boolean match(TokenType type) {
    if (peekType() == type) {
      pos++;
      return true;
    }
    return false;
  }

  private void expect(TokenType type, String msg) {
    if (!match(type)) throw new ParseException(msg);
  }

  private String expectIdent() {
    Token t = peek();
    if (t == null || t.type != TokenType.IDENT) {
      throw new ParseException("Expected identifier");
    }
    pos++;
    return t.text;
  }

  static List<Token> tokenize(String s) {
    List<Token> out = new ArrayList<>();
    int i = 0;
    int n = s.length();
    while (i < n) {
      char c = s.charAt(i);
      if (Character.isWhitespace(c)) {
        i++;
        continue;
      }
      // two-char ops
      if (c == '|' && i + 1 < n && s.charAt(i + 1) == '|') { out.add(new Token(TokenType.OR, "||", null)); i += 2; continue; }
      if (c == '&' && i + 1 < n && s.charAt(i + 1) == '&') { out.add(new Token(TokenType.AND, "&&", null)); i += 2; continue; }
      if (c == '=' && i + 1 < n && s.charAt(i + 1) == '=') { out.add(new Token(TokenType.EQ, "==", null)); i += 2; continue; }
      if (c == '!' && i + 1 < n && s.charAt(i + 1) == '=') { out.add(new Token(TokenType.NEQ, "!=", null)); i += 2; continue; }
      if (c == '<' && i + 1 < n && s.charAt(i + 1) == '=') { out.add(new Token(TokenType.LTE, "<=", null)); i += 2; continue; }
      if (c == '>' && i + 1 < n && s.charAt(i + 1) == '=') { out.add(new Token(TokenType.GTE, ">=", null)); i += 2; continue; }
      if (c == '<') { out.add(new Token(TokenType.LT, "<", null)); i++; continue; }
      if (c == '>') { out.add(new Token(TokenType.GT, ">", null)); i++; continue; }
      if (c == '!') { out.add(new Token(TokenType.NOT, "!", null)); i++; continue; }
      if (c == '(') { out.add(new Token(TokenType.LPAREN, "(", null)); i++; continue; }
      if (c == ')') { out.add(new Token(TokenType.RPAREN, ")", null)); i++; continue; }
      if (c == ',') { out.add(new Token(TokenType.COMMA, ",", null)); i++; continue; }
      if (c == '.') { out.add(new Token(TokenType.DOT, ".", null)); i++; continue; }
      if (c == '[') { out.add(new Token(TokenType.LBRACKET, "[", null)); i++; continue; }
      if (c == ']') { out.add(new Token(TokenType.RBRACKET, "]", null)); i++; continue; }
      // strings
      if (c == '\'' || c == '"') {
        StringBuilder sb = new StringBuilder();
        i++;
        boolean closed = false;
        while (i < n) {
          char d = s.charAt(i);
          if (d == '\\' && i + 1 < n) {
            char e = s.charAt(i + 1);
            switch (e) {
              case 'n' -> sb.append('\n');
              case 't' -> sb.append('\t');
              case '\'' -> sb.append('\'');
              case '"' -> sb.append('"');
              case '\\' -> sb.append('\\');
              default -> sb.append(e);
            }
            i += 2;
            continue;
          }
          if (d == c) {
            closed = true;
            i++;
            break;
          }
          sb.append(d);
          i++;
        }
        if (!closed) throw new ParseException("Unterminated string");
        out.add(new Token(TokenType.STRING, sb.toString(), sb.toString()));
        continue;
      }
      // numbers (incl. negative handled as unary? MVP has no arithmetic/negation of numbers;
      // support leading '-' for literals like -5)
      if (Character.isDigit(c) || (c == '-' && i + 1 < n && Character.isDigit(s.charAt(i + 1)))) {
        int j = i;
        if (s.charAt(j) == '-') j++;
        while (j < n && Character.isDigit(s.charAt(j))) j++;
        boolean isDouble = false;
        if (j < n && s.charAt(j) == '.' && j + 1 < n && Character.isDigit(s.charAt(j + 1))) {
          isDouble = true;
          j++;
          while (j < n && Character.isDigit(s.charAt(j))) j++;
        }
        String num = s.substring(i, j);
        if (isDouble) {
          out.add(new Token(TokenType.NUMBER, num, Double.parseDouble(num)));
        } else {
          out.add(new Token(TokenType.INT, num, Long.parseLong(num)));
        }
        i = j;
        continue;
      }
      // identifiers / keywords
      if (Character.isLetter(c) || c == '_') {
        int j = i;
        while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) j++;
        String word = s.substring(i, j);
        switch (word) {
          case "true" -> out.add(new Token(TokenType.TRUE, word, Boolean.TRUE));
          case "false" -> out.add(new Token(TokenType.FALSE, word, Boolean.FALSE));
          case "null" -> out.add(new Token(TokenType.NULL, word, null));
          case "in" -> out.add(new Token(TokenType.IN, word, null));
          default -> out.add(new Token(TokenType.IDENT, word, null));
        }
        i = j;
        continue;
      }
      throw new ParseException("Unexpected character: '" + c + "'");
    }
    return out;
  }
}
