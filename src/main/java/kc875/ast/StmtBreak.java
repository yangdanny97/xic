package kc875.ast;

import edu.cornell.cs.cs4120.util.CodeWriterSExpPrinter;
import edu.cornell.cs.cs4120.xic.ir.IRNode;
import java_cup.runtime.ComplexSymbolFactory;
import kc875.ast.visit.IRTranslationVisitor;
import kc875.ast.visit.TypeCheckVisitor;

public class StmtBreak extends Stmt implements Printable {
    public StmtBreak(ComplexSymbolFactory.Location location) { super(location); }

    @Override
    public void accept(TypeCheckVisitor visitor) {
        //TODO
    }

    @Override
    public IRNode accept(IRTranslationVisitor visitor) {
        return null; //TODO
    }

    @Override
    public void prettyPrint(CodeWriterSExpPrinter w) {
        //TODO
    }
}