package edu.cornell.cs.cs4120.xic.ir;

import edu.cornell.cs.cs4120.util.SExpPrinter;
import edu.cornell.cs.cs4120.xic.ir.visit.ASMTranslationVisitor;
import edu.cornell.cs.cs4120.xic.ir.visit.AggregateVisitor;
import edu.cornell.cs.cs4120.xic.ir.visit.IRVisitor;
import edu.cornell.cs.cs4120.xic.ir.visit.InsnMapsBuilder;
import kc875.asm.ASMInstr;

import java.util.List;

/** An IR function declaration */
public class IRFuncDecl extends IRNode_c {
    private String name;
    private IRStmt body;
    private int numParams;
    private int numRets;

    public IRFuncDecl(String name, int numParams, int numRets, IRStmt body) {
        this.name = name;
        this.body = body;
        this.numParams = numParams;
        this.numRets = numRets;
    }

    public String name() {
        return name;
    }

    public IRStmt body() {
        return body;
    }

    public int getNumParams() {
        return numParams;
    }

    public int getNumRets() {
        return numRets;
    }

    @Override
    public String label() {
        return "FUNC " + name;
    }

    @Override
    public IRNode visitChildren(IRVisitor v) {
        IRStmt stmt = (IRStmt) v.visit(this, body);

        if (stmt != body) return v.nodeFactory().IRFuncDecl(
                name, numParams, numRets, stmt
        );

        return this;
    }

    @Override
    public <T> T aggregateChildren(AggregateVisitor<T> v) {
        T result = v.unit();
        result = v.bind(result, v.visit(body));
        return result;
    }

    @Override
    public InsnMapsBuilder buildInsnMapsEnter(InsnMapsBuilder v) {
        v.addNameToCurrentIndex(name);
        v.addInsn(this);
        return v;
    }

    @Override
    public IRNode buildInsnMaps(InsnMapsBuilder v) {
        return this;
    }

    public List<ASMInstr> accept(ASMTranslationVisitor v) {
        return v.visit(this);
    }

    @Override
    public void printSExp(SExpPrinter p) {
        p.startList();
        p.printAtom("FUNC");
        p.printAtom(name);
        body.printSExp(p);
        p.endList();
    }

    @Override
    public boolean equals(Object node) {
        if (node instanceof IRFuncDecl) {
            IRFuncDecl irFuncDecl = (IRFuncDecl) node;
            return name.equals(irFuncDecl.name)
                    && body.equals(irFuncDecl.body);
        } else {
            return false;
        }
    }
}
