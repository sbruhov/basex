package org.basex.query.func;

import static org.basex.query.QueryError.*;
import static org.basex.query.QueryText.*;

import java.util.*;
import java.util.Map.*;

import org.basex.core.*;
import org.basex.query.*;
import org.basex.query.ann.*;
import org.basex.query.expr.*;
import org.basex.query.expr.gflwor.*;
import org.basex.query.iter.*;
import org.basex.query.scope.*;
import org.basex.query.util.*;
import org.basex.query.util.list.*;
import org.basex.query.value.*;
import org.basex.query.value.item.*;
import org.basex.query.value.node.*;
import org.basex.query.value.type.*;
import org.basex.query.var.*;
import org.basex.util.*;
import org.basex.util.hash.*;

/**
 * Inline function.
 *
 * @author BaseX Team 2005-17, BSD License
 * @author Leo Woerteler
 */
public final class Closure extends Single implements Scope, XQFunctionExpr {
  /** Function name, {@code null} if not specified. */
  private final QNm name;
  /** Formal parameters. */
  private final Var[] params;
  /** Value type, {@code null} if not specified. */
  private SeqType declType;
  /** Annotations. */
  private AnnList anns;
  /** Updating flag. */
  private boolean updating;

  /** Map with requested function properties. */
  private final EnumMap<Flag, Boolean> map = new EnumMap<>(Flag.class);
  /** Compilation flag. */
  private boolean compiled;

  /** Local variables in the scope of this function. */
  private final VarScope vs;
  /** Non-local variable bindings. */
  private final Map<Var, Expr> global;

  /**
   * Constructor.
   * @param info input info
   * @param declType declared type (can be {@code null})
   * @param params formal parameters
   * @param expr function body
   * @param anns annotations
   * @param global bindings for non-local variables
   * @param vs scope
   */
  public Closure(final InputInfo info, final SeqType declType, final Var[] params, final Expr expr,
      final AnnList anns, final Map<Var, Expr> global, final VarScope vs) {
    this(info, null, declType, params, expr, anns, global, vs);
  }

  /**
   * Package-private constructor allowing a name.
   * @param info input info
   * @param name name of the function (can be {@code null})
   * @param declType declared type (can be {@code null})
   * @param params formal parameters
   * @param expr function expression
   * @param anns annotations
   * @param global bindings for non-local variables (can be {@code null})
   * @param vs variable scope
   */
  Closure(final InputInfo info, final QNm name, final SeqType declType, final Var[] params,
      final Expr expr, final AnnList anns, final Map<Var, Expr> global, final VarScope vs) {
    super(info, expr, SeqType.FUN_O);
    this.name = name;
    this.params = params;
    this.declType = declType == null || declType.eq(SeqType.ITEM_ZM) ? null : declType;
    this.anns = anns;
    this.global = global == null ? Collections.emptyMap() : global;
    this.vs = vs;
  }

  @Override
  public int arity() {
    return params.length;
  }

  @Override
  public QNm funcName() {
    return name;
  }

  @Override
  public QNm paramName(final int pos) {
    return params[pos].name;
  }

  @Override
  public FuncType funcType() {
    return FuncType.get(anns, declType, params);
  }

  @Override
  public AnnList annotations() {
    return anns;
  }

  @Override
  public void comp(final CompileContext cc) throws QueryException {
    compile(cc);
  }

  @Override
  public Expr compile(final CompileContext cc) throws QueryException {
    if(compiled) return this;
    compiled = true;

    checkUpdating();

    // compile closure
    for(final Entry<Var, Expr> e : global.entrySet()) {
      final Expr bound = e.getValue().compile(cc);
      e.setValue(bound);
      e.getKey().refineType(bound.seqType(), cc);
    }

    cc.pushScope(vs);
    try {
      expr = expr.compile(cc);
    } catch(final QueryException qe) {
      expr = cc.error(qe, expr);
    } finally {
      cc.removeScope(this);
    }

    // convert all function calls in tail position to proper tail calls
    expr.markTailCalls(cc);

    return optimize(cc);
  }

  @Override
  public Expr optimize(final CompileContext cc) throws QueryException {
    final SeqType st = expr.seqType();
    final SeqType dt = declType == null || st.instanceOf(declType) ? st : declType;
    exprType.assign(FuncType.get(anns, dt, params));

    cc.pushScope(vs);
    try {
      // inline all values in the closure
      final Iterator<Entry<Var, Expr>> cls = global.entrySet().iterator();
      Map<Var, Expr> add = null;
      final int limit = cc.qc.context.options.get(MainOptions.INLINELIMIT);
      while(cls.hasNext()) {
        final Entry<Var, Expr> e = cls.next();
        final Var v = e.getKey();
        final Expr c = e.getValue();
        if(c instanceof Value) {
          // values are always inlined into the closure
          final Expr inlined = expr.inline(v, v.checkType((Value) c, cc.qc, true), cc);
          if(inlined != null) expr = inlined;
          cls.remove();
        } else if(c instanceof Closure) {
          // nested closures are inlined if their size and number of closed-over variables is small
          final Closure cl = (Closure) c;
          if(!cl.has(Flag.NDT) && cl.global.size() < 5
              && expr.count(v) != VarUsage.MORE_THAN_ONCE && cl.exprSize() < limit) {
            cc.info(OPTINLINE_X, e);
            for(final Entry<Var, Expr> e2 : cl.global.entrySet()) {
              final Var v2 = cc.copy(e2.getKey(), null);
              if(add == null) add = new HashMap<>();
              add.put(v2, e2.getValue());
              e2.setValue(new VarRef(cl.info, v2));
            }

            final Expr inlined = expr.inline(v, cl, cc);
            if(inlined != null) expr = inlined;
            cls.remove();
          }
        }
      }
      // add all newly added bindings
      if(add != null) global.putAll(add);
    } catch(final QueryException qe) {
      expr = cc.error(qe, expr);
    } finally {
      cc.removeScope(this);
    }

    // only evaluate if the closure is empty, so we don't lose variables
    return global.isEmpty() ? cc.preEval(this) : this;
  }

  @Override
  public VarUsage count(final Var var) {
    VarUsage all = VarUsage.NEVER;
    for(final Entry<Var, Expr> e : global.entrySet()) {
      if((all = all.plus(e.getValue().count(var))) == VarUsage.MORE_THAN_ONCE) break;
    }
    return all;
  }

  @Override
  public Expr inline(final Var var, final Expr ex, final CompileContext cc) throws QueryException {
    boolean changed = false;
    for(final Entry<Var, Expr> entry : global.entrySet()) {
      final Expr e = entry.getValue().inline(var, ex, cc);
      if(e != null) {
        changed = true;
        entry.setValue(e);
      }
    }
    return changed ? optimize(cc) : null;
  }

  @Override
  public Expr copy(final CompileContext cc, final IntObjMap<Var> vm) {
    final VarScope innerScope = new VarScope(vs.sc);

    final HashMap<Var, Expr> outer = new HashMap<>();
    global.forEach((key, value) -> outer.put(key, value.copy(cc, vm)));

    cc.pushScope(innerScope);
    try {
      final IntObjMap<Var> innerVars = new IntObjMap<>();
      vs.copy(cc, innerVars);

      final HashMap<Var, Expr> nl = new HashMap<>();
      outer.forEach((key, value) -> nl.put(innerVars.get(key.id), value));

      final Var[] prms = params.clone();
      final int pl = prms.length;
      for(int p = 0; p < pl; p++) prms[p] = innerVars.get(prms[p].id);

      final Expr ex = expr.copy(cc, innerVars);
      ex.markTailCalls(null);
      return copyType(new Closure(info, name, declType, prms, ex, anns, nl, cc.vs()));
    } finally {
      cc.removeScope();
    }
  }

  @Override
  public Expr inlineExpr(final Expr[] exprs, final CompileContext cc, final InputInfo ii)
      throws QueryException {

    if(expr.has(Flag.CTX)) return null;

    cc.info(OPTINLINE_X, this);
    // create let bindings for all variables
    final LinkedList<Clause> cls =
        exprs.length == 0 && global.isEmpty() ? null : new LinkedList<>();
    final IntObjMap<Var> vm = new IntObjMap<>();
    final int pl = params.length;
    for(int p = 0; p < pl; p++) {
      cls.add(new Let(cc.copy(params[p], vm), exprs[p], false).optimize(cc));
    }
    for(final Entry<Var, Expr> e : global.entrySet()) {
      cls.add(new Let(cc.copy(e.getKey(), vm), e.getValue(), false).optimize(cc));
    }

    // copy the function body
    final Expr cpy = expr.copy(cc, vm), dt = declType == null ? cpy :
      new TypeCheck(vs.sc, ii, cpy, declType, true).optimize(cc);

    return cls == null ? dt : new GFLWOR(ii, cls, dt).optimize(cc);
  }

  @Override
  public FuncItem item(final QueryContext qc, final InputInfo ii) throws QueryException {
    final Expr body;
    if(global.isEmpty()) {
      body = expr;
    } else {
      // collect closure
      final LinkedList<Clause> cls = new LinkedList<>();
      for(final Entry<Var, Expr> e : global.entrySet()) {
        cls.add(new Let(e.getKey(), qc.value(e.getValue()), false));
      }
      body = new GFLWOR(info, cls, expr);
    }

    final SeqType argType = body.seqType();
    final Expr checked;
    if(declType == null || argType.instanceOf(declType)) {
      // return type is already correct
      checked = body;
    } else if(body instanceof FuncItem && declType.type instanceof FuncType) {
      // function item coercion
      if(!declType.occ.check(1)) throw typeError(body, declType, null, info);
      final FuncItem fi = (FuncItem) body;
      checked = fi.coerceTo((FuncType) declType.type, qc, info, true);
    } else if(body instanceof Value) {
      // we can type check immediately
      final Value val = (Value) body;
      checked = declType.instance(val) ? val :
        declType.promote(val, null, qc, vs.sc, info, false);
    } else {
      // check at each call
      if(argType.type.instanceOf(declType.type) && !body.has(Flag.NDT)) {
        // reject impossible arities
        final Occ occ = argType.occ.intersect(declType.occ);
        if(occ == null) throw typeError(body, declType, null, info);
      }
      checked = new TypeCheck(vs.sc, info, body, declType, true);
    }

    final FuncType tp = (FuncType) seqType().type;
    return new FuncItem(vs.sc, anns, name, params, tp, checked, vs.stackSize());
  }

  @Override
  public Value value(final QueryContext qc) throws QueryException {
    return item(qc, info);
  }

  @Override
  public BasicIter<?> iter(final QueryContext qc) throws QueryException {
    return value(qc).iter();
  }

  @Override
  public boolean has(final Flag... flags) {
    // handle recursive calls: check which flags have already been assigned
    final ArrayList<Flag> flgs = new ArrayList<>();
    for(final Flag flag : flags) {
      if(!map.containsKey(flag)) {
        map.put(flag, false);
        // skip updating flag (function itself does not perform any updates)
        if(flag != Flag.UPD) flgs.add(flag);
      }
    }
    // cache flags for remaining, new properties
    for(final Flag flag : flgs) map.put(flag, expr.has(flag));
    // evaluate result
    for(final Flag flag : flags) if(map.get(flag)) return true;
    return false;
  }

  @Override
  public boolean removable(final Var var) {
    for(final Entry<Var, Expr> e : global.entrySet()) {
      if(!e.getValue().removable(var)) return false;
    }
    return true;
  }

  @Override
  public boolean visit(final ASTVisitor visitor) {
    for(final Entry<Var, Expr> v : global.entrySet()) {
      if(!(v.getValue().accept(visitor) && visitor.declared(v.getKey()))) return false;
    }
    for(final Var v : params) {
      if(!visitor.declared(v)) return false;
    }
    return expr.accept(visitor);
  }

  @Override
  public void checkUp() throws QueryException {
    checkUpdating();
    if(updating) {
      expr.checkUp();
      if(declType != null && !declType.zero()) throw UUPFUNCTYPE.get(info);
    }
  }

  @Override
  public boolean isVacuous() {
    return declType != null && declType.zero() && !has(Flag.UPD);
  }

  @Override
  public boolean isVacuousBody() {
    return isVacuous();
  }

  @Override
  public boolean accept(final ASTVisitor visitor) {
    for(final Expr e : global.values()) {
      if(!e.accept(visitor)) return false;
    }
    return visitor.inlineFunc(this);
  }

  @Override
  public int exprSize() {
    int sz = 1;
    for(final Expr e : global.values()) sz += e.exprSize();
    return sz + expr.exprSize();
  }

  @Override
  public boolean compiled() {
    return compiled;
  }

  /**
   * Returns an iterator over the non-local bindings of this closure.
   * @return the iterator
   */
  public Iterator<Entry<Var, Expr>> globalBindings() {
    return global.entrySet().iterator();
  }

  /**
   * Fixes the function type of this closure after it was generated for a function literal during
   * parsing.
   * @param ft function type
   */
  void adoptSignature(final FuncType ft) {
    anns = ft.anns;
    final int pl = params.length;
    for(int p = 0; p < pl; p++) params[p].declType = ft.argTypes[p];
    final SeqType dt = ft.declType;
    if(!dt.eq(SeqType.ITEM_ZM)) declType = dt;
  }

  /**
   * Assigns the updating flag.
   * @throws QueryException query exception
   */
  private void checkUpdating() throws QueryException {
    // derive updating flag from function body
    updating = expr.has(Flag.UPD);
    final boolean updAnn = anns.contains(Annotation.UPDATING);
    if(updating != updAnn) {
      if(!updAnn) anns.add(new Ann(info, Annotation.UPDATING));
      else if(!expr.isVacuous()) throw UPEXPECTF.get(info);
    }
  }

  /**
   * Creates a function literal for a function that was not yet encountered while parsing.
   * @param name function name
   * @param arity function arity
   * @param qc query context
   * @param sc static context
   * @param info input info
   * @return function literal
   * @throws QueryException query exception
   */
  public static Closure unknownLit(final QNm name, final int arity, final QueryContext qc,
      final StaticContext sc, final InputInfo info) throws QueryException {

    final VarScope scp = new VarScope(sc);
    final Var[] params = new Var[arity];
    final Expr[] args = new Expr[arity];
    for(int a = 0; a < arity; a++) {
      params[a] = scp.addNew(new QNm(ARG + (a + 1), ""), null, true, qc, info);
      args[a] = new VarRef(info, params[a]);
    }
    final TypedFunc call = qc.funcs.getFuncRef(name, args, sc, info);
    return new Closure(info, name, null, params, call.fun, new AnnList(), null, scp);
  }

  @Override
  public boolean equals(final Object obj) {
    // [CG] could be enhanced
    return this == obj;
  }

  @Override
  public void plan(final FElem plan) {
    final FElem el = planElem();
    global.forEach((key, value) -> {
      key.plan(el);
      value.plan(el);
    });
    addPlan(plan, el, expr);
    final int pl = params.length;
    for(int p = 0; p < pl; p++) el.add(planAttr(ARG + p, params[p].name.string()));
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder();
    if(!global.isEmpty()) {
      sb.append("((: inline-closure :) ");
      global.forEach((k, v) -> sb.append("let ").append(k).append(" := ").append(v).append(' '));
      sb.append(RETURN).append(' ');
    }
    sb.append(FUNCTION).append(PAREN1);
    final int pl = params.length;
    for(int p = 0; p < pl; p++) {
      if(p > 0) sb.append(", ");
      sb.append(params[p]);
    }
    sb.append(PAREN2).append(' ');
    sb.append("as ").append(declType != null ? declType : SeqType.ITEM_ZM).append(' ');
    sb.append("{ ").append(expr).append(" }");
    if(!global.isEmpty()) sb.append(')');
    return sb.toString();
  }
}
