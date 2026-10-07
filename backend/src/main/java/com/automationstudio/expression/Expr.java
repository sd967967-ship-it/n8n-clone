package com.automationstudio.expression;

import java.util.List;

/** AST for the MVP condition expression grammar (no eval, no arithmetic). */
public sealed interface Expr
    permits Expr.Or, Expr.And, Expr.Not, Expr.Comparison, Expr.Literal, Expr.Ref, Expr.Call {

  record Or(List<Expr> terms) implements Expr {}

  record And(List<Expr> terms) implements Expr {}

  record Not(Expr inner) implements Expr {}

  record Comparison(String op, Expr left, Expr right) implements Expr {}

  record Literal(Object value) implements Expr {}

  record Ref(String path) implements Expr {}

  record Call(String name, List<Expr> args) implements Expr {}
}
