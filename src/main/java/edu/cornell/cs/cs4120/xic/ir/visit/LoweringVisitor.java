package edu.cornell.cs.cs4120.xic.ir.visit;

import edu.cornell.cs.cs4120.xic.ir.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class LoweringVisitor extends IRVisitor {
    private int tempcounter;

    private String newTemp() {
        return String.format("_lir_t%d", (tempcounter++));
    }

    private class BasicBlock {
        List<IRNode> statements;
        BasicBlock next;  //TODO: possibly make list

        BasicBlock () {
            statements = new ArrayList<>();
            next = null;
        }

        void addStmt(IRNode stmt) {
            statements.add(stmt);
        }

        BasicBlock getNext() {
            return next;
        }

        void setNext(BasicBlock next) {
            this.next = next;
        }
    }

    BasicBlock head;
    BasicBlock current;

    public LoweringVisitor(IRNodeFactory inf) {
        super(inf);
        tempcounter = 0;
        head = new BasicBlock();
        current = head;
    }

    private void addNodeToBlock(IRNode node) {
        if (node instanceof IRLabel && current != head) {
            BasicBlock newblock = new BasicBlock();
            newblock.addStmt(node);
            current.setNext(newblock);
            current = newblock;
        } else if (node instanceof IRReturn || node instanceof IRJump || node instanceof IRCJump) {
            current.addStmt(node);
            BasicBlock newblock = new BasicBlock();
            current.setNext(newblock);
            current = newblock;
        } else {
            current.addStmt(node);
        }
    }

    private ArrayList<String> getTemps(List<IRNode> l) {
        ArrayList<String> temps = new ArrayList<String>();
        for (IRNode n : l) {
            if (n instanceof IRTemp) {
                temps.add(((IRTemp) n).name());
            }
        }
        return temps;
    }

    private boolean ifExprsCommute(IRExpr e1, IRExpr e2) {
        List<IRNode> e1Children = e1.aggregateChildren(new ListChildrenVisitor());
        e1Children.add(e1);
        List<IRNode> e2Children = e1.aggregateChildren(new ListChildrenVisitor());
        e1Children.add(e2);
        //Check MEM
        for (IRNode n : e2Children) {
            if (n instanceof IRMem) return false;
        }
        //Check TEMPs
        ArrayList<String> temps = new ArrayList<String>();
        temps.addAll(getTemps(e1Children));
        temps.addAll(getTemps(e2Children));
        List<String> uniqList = temps.stream()
                .distinct()
                .collect(Collectors.toList());
        if(uniqList.size() < temps.size()) return false;
        return true;
    }


    @Override
    public IRNode leave(IRNode parent, IRNode n, IRNode n_, IRVisitor v_) {
        if (n_ instanceof IRBinOp) return lower((IRBinOp) n_);
        if (n_ instanceof IRCall) return lower((IRCall) n_);
        if (n_ instanceof IRCJump) return lower((IRCJump) n_);
        if (n_ instanceof IRCompUnit) return lower((IRCompUnit) n_);
        if (n_ instanceof IRConst) return lower((IRConst) n_);
        if (n_ instanceof IRESeq) return lower((IRESeq) n_);
        if (n_ instanceof IRExp) return lower((IRExp) n_);
        if (n_ instanceof IRFuncDecl) return lower((IRFuncDecl) n_);
        if (n_ instanceof IRJump) return lower((IRJump) n_);
        if (n_ instanceof IRLabel) return lower((IRLabel) n_);
        if (n_ instanceof IRMem) return lower((IRMem) n_);
        if (n_ instanceof IRMove) return lower((IRMove) n_);
        if (n_ instanceof IRName) return lower((IRName) n_);
        if (n_ instanceof IRReturn) return lower((IRReturn) n_);
        if (n_ instanceof IRSeq) return lower((IRSeq) n_);
        if (n_ instanceof IRTemp) return lower((IRTemp) n_);
        else return null; //Illegal state
    }


    public IRNode lower(IRBinOp irnode) {
        irnode = (IRBinOp) irnode.visitChildren(this);
        IRExpr left = irnode.left();
        IRExpr right = irnode.right();

        if (!(left instanceof IRESeq || right instanceof IRESeq)) {
            addNodeToBlock(irnode);
            return irnode;
        } else if (ifExprsCommute(left, right)) {
            IRESeq leftSeq;
            IRESeq rightSeq;
            IRExpr e1;
            IRExpr e2;
            IRStmt s1 = null;
            IRStmt s2 = null;

            if (left instanceof IRESeq && right instanceof IRESeq) {
                leftSeq = (IRESeq) left;
                rightSeq = (IRESeq) right;
                e1 = leftSeq.expr();
                e2 = rightSeq.expr();
                s1 = leftSeq.stmt();
                s2 = rightSeq.stmt();
            } else if (left instanceof IRESeq) {
                leftSeq = (IRESeq) left;
                e1 = leftSeq.expr();
                e2 = right;
                s1 = leftSeq.stmt();
            } else {
                rightSeq = (IRESeq) right;
                e1 = left;
                e2 = rightSeq.expr();
                s2 = rightSeq.stmt();
            }

            IRESeq ret = new IRESeq(
                    new IRSeq(s1, s2),
                    new IRBinOp(
                            irnode.opType(), e1, e2
                    ));
            addNodeToBlock(ret);
            return ret;
        } else {
            IRESeq leftSeq;
            IRESeq rightSeq;
            IRExpr e1;
            IRExpr e2;
            IRStmt s1 = null;
            IRStmt s2 = null;

            String t1 = newTemp();
            if (left instanceof IRESeq && right instanceof IRESeq) {
                leftSeq = (IRESeq) left;
                rightSeq = (IRESeq) right;
                e1 = leftSeq.expr();
                e2 = rightSeq.expr();
                s1 = leftSeq.stmt();
                s2 = rightSeq.stmt();
            } else if (left instanceof IRESeq) {
                leftSeq = (IRESeq) left;
                e1 = leftSeq.expr();
                e2 = right;
                s1 = leftSeq.stmt();
            } else {
                rightSeq = (IRESeq) right;
                e1 = left;
                e2 = rightSeq.expr();
                s2 = rightSeq.stmt();
            }

            IRESeq ret = new IRESeq(
                    new IRSeq(
                            s1,
                            new IRMove(
                                    new IRTemp(t1), e1
                            ),
                            s2
                    ),
                    new IRBinOp(
                            irnode.opType(), new IRTemp(t1), e2
                    ));
            addNodeToBlock(ret);
            return ret;
        }
    }

    public IRNode lower(IRCall irnode) {
        irnode = (IRCall) irnode.visitChildren(this);
        List<String> tis = new ArrayList<>();
        List<IRStmt> stmts = new ArrayList<>();
        for (IRExpr ei : irnode.args()) {
            String ti = newTemp();
            tis.add(ti);

            if (ei instanceof IRESeq) {
                IRESeq ireSeq = (IRESeq) ei;
                stmts.add(ireSeq.stmt());
                stmts.add(new IRMove(
                        new IRTemp(ti), ireSeq.expr()
                ));
            } else {
                stmts.add(new IRMove(
                        new IRTemp(ti), ei
                ));
            }
        }

        String t = newTemp();
        List<IRExpr> temps = tis.stream().map(IRTemp::new).collect(Collectors.toList());
        IRStmt moveCall = new IRMove(
                new IRTemp(t),
                new IRCall(irnode.target(), temps)
        );

        stmts.add(moveCall);

        IRESeq ret = new IRESeq(
                new IRSeq(stmts),
                new IRTemp(t)
        );
        addNodeToBlock(ret);
        return ret;
    }

    public IRNode lower(IRCJump irnode) {
        // TODO: put in basic blocks
        irnode = (IRCJump) irnode.visitChildren(this);
        IRExpr e = irnode.cond();

        if (e instanceof IRESeq) {
            IRESeq ireSeq = ((IRESeq) e);
            IRExpr eprime = ireSeq.expr();
            IRStmt s = ireSeq.stmt();

            IRSeq ret = new IRSeq(
                    s,
                    new IRCJump(
                            eprime, irnode.trueLabel(), irnode.falseLabel()
                    ));
            addNodeToBlock(ret);
            return ret;
        } else {
            addNodeToBlock(irnode);
            return irnode;
        }
    }

    public IRNode lower(IRCompUnit irnode) {
        //TODO: call method to reorder basic blocks here?
        return irnode.visitChildren(this);
    }

    public IRNode lower(IRConst irnode) {
        //Constants are already canonical
        addNodeToBlock(irnode);
        return irnode;
    }

    public IRNode lower(IRESeq irnode) {
        irnode = (IRESeq) irnode.visitChildren(this);
        IRExpr expr = irnode.expr();
        if (expr instanceof IRESeq) {
            IRESeq es =(IRESeq) expr;
            IRStmt s1 = irnode.stmt();
            IRStmt s2 = es.stmt();
            IRExpr e = es.expr();
            IRESeq ret = new IRESeq(new IRSeq(s1, s2), e);
            addNodeToBlock(ret);
            return ret;
        }
        else {
            addNodeToBlock(irnode);
            return irnode;
        }
    }

    public IRNode lower(IRExp irnode) {
        irnode = (IRExp) irnode.visitChildren(this);
        IRExpr e = irnode.expr();
        if (e instanceof IRESeq) {
            IRStmt ret = ((IRESeq) e).stmt();
            addNodeToBlock(ret);
            return ret;
        }
        else return null;
    }

    public IRNode lower(IRFuncDecl irnode) {
        return irnode.visitChildren(this);
    }

    public IRNode lower(IRJump irnode) {
        irnode = (IRJump) irnode.visitChildren(this);
        IRExpr e = irnode.target();

        if (e instanceof IRESeq) {
            IRESeq ireSeq = (IRESeq) e;
            IRExpr eprime = ireSeq.expr();
            IRStmt s = ireSeq.stmt();

            IRSeq ret = new IRSeq(
                    s,
                    new IRJump(eprime)
            );
            addNodeToBlock(ret);
            return ret;
        } else {
            addNodeToBlock(irnode);
            return irnode;
        }
    }

    public IRNode lower(IRLabel irnode) {
        //Labels are already canonical
        addNodeToBlock(irnode);
        return irnode;
    }

    public IRNode lower(IRMem irnode) {
        irnode = (IRMem) irnode.visitChildren(this);
        IRExpr e = irnode.expr();

        if (e instanceof IRESeq) {
            IRESeq ireSeq = (IRESeq) e;
            IRExpr eprime = ireSeq.expr();
            IRStmt s = ireSeq.stmt();

            IRESeq ret = new IRESeq(
                    s,
                    new IRMem(eprime)
            );
            addNodeToBlock(ret);
            return ret;
        } else {
            addNodeToBlock(irnode);
            return irnode;
        }
    }

    public IRNode lower(IRMove irnode) {
        irnode = (IRMove) irnode.visitChildren(this);
        IRExpr dest = irnode.target();
        IRExpr src = irnode.source();
        if (!(dest instanceof IRESeq || src instanceof IRESeq)) {
            addNodeToBlock(irnode);
            return irnode;
        } else if (ifExprsCommute(dest, src)) {
            IRESeq destSeq;
            IRESeq srcSeq;
            IRExpr destprime;
            IRExpr eprime;
            IRStmt s1 = null;
            IRStmt s2 = null;

            if (dest instanceof IRESeq && src instanceof IRESeq) {
                destSeq = (IRESeq) dest;
                srcSeq = (IRESeq) src;
                destprime = destSeq.expr();
                eprime= srcSeq.expr();
                s1 = destSeq.stmt();
                s2 = srcSeq.stmt();
            } else if (dest instanceof IRESeq) {
                destSeq = (IRESeq) dest;
                destprime = destSeq.expr();
                eprime = src;
                s1 = destSeq.stmt();
            } else {
                srcSeq = (IRESeq) src;
                destprime = dest;
                eprime = srcSeq.expr();
                s2 = srcSeq.stmt();
            }

            List<IRStmt> stmts = new ArrayList<>();
            stmts.add(s1);
            stmts.add(s2);
            stmts.add(new IRMove(destprime, eprime));
            IRSeq ret = new IRSeq(stmts);
            addNodeToBlock(ret);
            return ret;
        } else {
            IRESeq destSeq;
            IRESeq srcSeq;
            IRExpr destprime;
            IRExpr eprime;
            IRStmt s1 = null;
            IRStmt s2 = null;

            String t1 = newTemp();
            if (dest instanceof IRESeq && src instanceof IRESeq) {
                destSeq = (IRESeq) dest;
                srcSeq = (IRESeq) src;
                destprime = destSeq.expr();
                eprime = srcSeq.expr();
                s1 = destSeq.stmt();
                s2 = srcSeq.stmt();
            } else if (dest instanceof IRESeq) {
                destSeq = (IRESeq) dest;
                destprime = destSeq.expr();
                eprime = src;
                s1 = destSeq.stmt();
            } else {
                srcSeq = (IRESeq) src;
                destprime = dest;
                eprime = srcSeq.expr();
                s2 = srcSeq.stmt();
            }

            List<IRStmt> stmts = new ArrayList<>();
            stmts.add(s1);
            stmts.add(new IRMove(new IRTemp(t1), destprime));
            stmts.add(s2);
            stmts.add(new IRMove(new IRMem(new IRTemp(t1)), eprime));
            IRSeq ret = new IRSeq(stmts);
            addNodeToBlock(ret);
            return ret;
        }
    }

    public IRNode lower(IRName irnode) {
        //Names are already canonical
        addNodeToBlock(irnode);
        return irnode;
    }

    public IRNode lower(IRReturn irnode) {
        irnode = (IRReturn) irnode.visitChildren(this);
        List<IRStmt> stmts = new ArrayList<>();
        List<IRExpr> newRets = new ArrayList<>();
        for (IRExpr e : irnode.rets()) {
            if (e instanceof IRESeq) {
                IRESeq es = (IRESeq) e;
                stmts.add(es.stmt());
                newRets.add(es.expr());
            }
            else newRets.add(e);
        }
        IRReturn retNode = new IRReturn(newRets);
        if (stmts.size() > 0) {
            stmts.add(retNode);
            IRSeq ret = new IRSeq(stmts);
            addNodeToBlock(ret);
            return ret;
        }
        else {
            addNodeToBlock(retNode);
            return retNode;
        }
    }

    public IRNode lower(IRSeq irnode) {
        irnode = (IRSeq) irnode.visitChildren(this);
        List<IRStmt> newStmts = new ArrayList<>();
        for (IRStmt s : irnode.stmts()) {
            if (s instanceof IRSeq) {
                newStmts.addAll(((IRSeq) s).stmts());
            }
            else newStmts.add(s);
        }
        IRSeq ret = new IRSeq(newStmts);
        addNodeToBlock(ret);
        return ret;
    }

    public IRNode lower(IRTemp irnode) {
        //Temps are already canonical
        addNodeToBlock(irnode);
        return irnode;
    }



}
