package ast;

import edu.cornell.cs.cs4120.util.CodeWriterSExpPrinter;
import java_cup.runtime.ComplexSymbolFactory;

import java.util.List;

public class StmtAssign extends Stmt {
    private List<Assignable> lhs;
    private List<Expr> rhs;

    public StmtAssign(List<Assignable> lhs, List<Expr> rhs,
                      ComplexSymbolFactory.Location location) {
        super(location);
        this.lhs = lhs;
        this.rhs = rhs;
        this.s_type = StmtType.AssignStmt;
    }

    public List<Assignable> getLhs() {
        return lhs;
    }

    public List<Expr> getRhs() {
        return rhs;
    }

    public void prettyPrint(CodeWriterSExpPrinter w) {
        w.startList();
        w.printAtom("=");
        if (lhs.size() == 1){
            lhs.get(0).prettyPrint(w);
        } else {
            w.startList();
            lhs.forEach((e) -> e.prettyPrint(w));
            w.endList();
        }
        if (rhs.size() == 1){
            rhs.get(0).prettyPrint(w);
        } else {
            w.startList();
            rhs.forEach((e) -> e.prettyPrint(w));
            w.endList();
        }
        w.endList();
    }

    @Override
    public void accept(VisitorAST visitor) {
        for (Assignable lh : lhs) {
            lh.accept(visitor);
        }
        for (Expr e : rhs) {
            e.accept(visitor);
        }
        visitor.visit(this);
    }


}
