package edu.cornell.cs.cs4120.xic.ir.dfa;

import com.google.common.collect.Sets;
import edu.cornell.cs.cs4120.xic.ir.*;
import edu.cornell.cs.cs4120.xic.ir.visit.ListChildrenVisitor;
import kc875.cfg.DFAFramework;
import kc875.utils.SetWithInf;
import kc875.utils.XiUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AvailableExprsDFA extends DFAFramework<SetWithInf<IRExpr>, IRStmt> {

    public AvailableExprsDFA(IRGraph graph) {
        super(
                graph,
                Direction.FORWARD,
                (node, l) -> {
                    if (node.getT() instanceof IRLabel) {
                        IRLabel label = (IRLabel) node.getT();
                        if (XiUtils.isNonLibFunction(label.label())) {
                            // function name ==> must be a top-level one ==> start node;
                            // everything gets killed
                            return new SetWithInf<>();
                        }
                    }
                    // node not a function label
                    if (l.isInf())
                        // Given that the start nodes kills all nodes, all in[node] for
                        // all nodes should be empty if in[node] is infinity
                        l = new SetWithInf<>();

                    l = l.union(exprs(node));
                    for (IRExpr e : kill(node)) {
                        // remove elements from l whose sub exprs have e in them
                        l.removeIf(le -> getSubExpressions(le).contains(e));
                    }
                    return l;
                },
                SetWithInf::infSet,
                (l1, l2) -> {
                    if (l1.isInf())// l1 is top
                        return l2;
                    if (l2.isInf())// l2 is top
                        return l1;
                    // l1 and l2 are not top, take the normal intersection
                    return new SetWithInf<>(Sets.intersection(
                            l1.getSet(), l2.getSet()
                    ));
                },
                SetWithInf.infSet()// top
        );
    }

    private static SetWithInf<IRExpr> exprs(IRGraph.Node gn) {
        return new SetWithInf<>(getSubExpressions(gn.getT()));
    }

    private static SetWithInf<IRExpr> kill(IRGraph.Node gn) {
        IRStmt stmt = gn.getT();
        List<IRStmt> stmts = new ArrayList<>();
        if (stmt instanceof IRSeq)
            stmts = ((IRSeq) stmt).stmts();
        else
            stmts.add(stmt);

        Set<IRExpr> killSet = new HashSet<>();

        for (IRStmt s : stmts) {
            Set<IRExpr> subexpressions = getSubExpressions(s);
            if (s instanceof IRMove) {
                IRMove smove = (IRMove) s;
                // add exprs that contain the lhs of move
                if (smove.target() instanceof IRTemp) {
                    // mov x, e ==> kill e' that contain x
                    killSet.addAll(exprsContainingTemp(
                            (IRTemp) smove.target(), subexpressions
                    ));
                } else if (smove.target() instanceof IRMem) {
                    // mov [e], e' ==> kill aliases of [e]
                    killSet.addAll(possibleAliasExprs(
                            ((IRMem) smove.target()).expr(), subexpressions
                    ));
                }

                if (smove.source() instanceof IRCall) {
                    // mov e, f(e) ==> kill mems that f can modify
                    killSet.addAll(exprsCanBeModified(
                            ((IRName) ((IRCall) smove.source()).target()).name(),
                            subexpressions
                    ));
                }
            } else if (s instanceof IRCJump) {
                IRCJump jmp = (IRCJump) s;
                if (jmp.cond() instanceof IRCall) {
                    killSet.addAll(exprsCanBeModified(
                            ((IRName) ((IRCall) jmp.cond()).target()).name(),
                            subexpressions
                    ));
                }
            }
        }

        return new SetWithInf<>(killSet);
    }

    /**
     * Return the set of expressions generated by a node, which is the difference
     * between the set of expressions that it uses and those that were available
     * in.
     *
     * @param node graph node
     * @return the set of generated expressions
     */
    public SetWithInf<IRExpr> exprsGeneratedBy(IRGraph.Node node) {
        SetWithInf<IRExpr> exprs = exprs(node);
        for (IRExpr e : kill(node)) {
            // remove elements from l whose sub exprs have e in them
            exprs.removeIf(le -> getSubExpressions(le).contains(e));
        }
        return exprs;
    }

    public SetWithInf<IRGraph.Node> nodesUsingExpr(IRExpr e) {
        Set<IRGraph.Node> nodeSet = new HashSet<>();
        for (IRGraph.Node n : ((IRGraph) getGraph()).getAllNodes()) {
            if (exprs(n).contains(e)) {
                nodeSet.add(n);
            }
        }
        return new SetWithInf<>(nodeSet);
    }

    /**
     * Get the subexpressions of a given IR node
     *
     * @param irNode An IR node with 0 or more children
     * @return A set of all the children of irNode that are of type IRExpr.
     */
    private static Set<IRExpr> getSubExpressions(IRNode irNode) {
        ListChildrenVisitor lcv = new ListChildrenVisitor();
        Set<IRExpr> exprSet = new HashSet<>();

        for (IRNode n : lcv.visit(irNode)) {
            if (n instanceof IRExpr) {
                if (n instanceof IRCall || n instanceof IRName) {
                    // don't add n
                    continue;
                }
                exprSet.add((IRExpr) n);
            }
        }

        return exprSet;
    }

    /**
     * Return the subset of a list of IR expressions that reference a given temp.
     *
     * @param t     IR level temporary variable
     * @param exprs the list of IR expressions to be searched
     * @return the subset of exprlst containing references to t
     */
    public static Set<IRExpr> exprsContainingTemp(IRTemp t,
                                                  Set<IRExpr> exprs) {
        ListChildrenVisitor lcv = new ListChildrenVisitor();
        Set<IRExpr> exprSet = new HashSet<>();

        for (IRExpr expr : exprs) {
            List<IRNode> children = lcv.visit(expr);
            for (IRNode n : children) {
                if (n instanceof IRTemp) {
                    IRTemp tn = (IRTemp) n;
                    if (tn.name().equals(t.name())) {
                        exprSet.add(expr);
                    }
                }
            }
        }

        return exprSet;
    }

    /**
     * Returns the set of expressions used in a mem that may be an alias for e.
     * Two memory operands are considered aliases unless:
     * 1. One is a stack location and the other is a heap location
     * 2. The operands are of format [rbp + i] and [rbp + j], and i =/= j
     * 3. The operand points to immutable memory
     * 4. The types of the operands are incompatible
     *
     * @param e     IR expression used in a mem: [e]
     * @param exprs the list of IR expression to be searched
     * @return the subset of exprlst containing any expression [e'] that may be
     * an alias for [e]
     */
    public static Set<IRExpr> possibleAliasExprs(IRExpr e,
                                                 Set<IRExpr> exprs) {
        ListChildrenVisitor lcv = new ListChildrenVisitor();
        Set<IRExpr> exprSet = new HashSet<>();

        for (IRExpr expr : exprs) {
            List<IRNode> children = lcv.visit(expr);
            for (IRNode c : children) {
                if (c instanceof IRMem) {
                    if (((IRMem) c).expr() instanceof IRConst && e instanceof IRConst) {
                        if (((IRConst) ((IRMem) c).expr()).value() != ((IRConst) e).value()) {
                            // values not equal, not memory aliases
                            continue;
                        }
                    }
                    exprSet.add(expr);
                }
            }
        }

        return exprSet;
    }

    /**
     * Returns the set of expressions that can be modified by a function call
     * to f.
     *
     * @param fname An IR function declaration
     * @param exprs expressions to be searched
     * @return The subset of exprlist containing any expression [e] that could
     * be modified by a call to f.
     */
    public static Set<IRExpr> exprsCanBeModified(String fname,
                                                 Set<IRExpr> exprs) {
        ListChildrenVisitor lcv = new ListChildrenVisitor();
        Set<IRExpr> exprSet = new HashSet<>();

        for (IRExpr expr : exprs) {
            List<IRNode> children = lcv.visit(expr);
            for (IRNode n : children) {
                if (n instanceof IRMem) {
                    exprSet.add(expr);
                }
            }
        }

        return exprSet;
    }

}
