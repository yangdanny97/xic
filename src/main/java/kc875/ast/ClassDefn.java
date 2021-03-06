package kc875.ast;

import edu.cornell.cs.cs4120.util.CodeWriterSExpPrinter;
import edu.cornell.cs.cs4120.xic.ir.IRNode;
import java_cup.runtime.ComplexSymbolFactory;
import kc875.ast.visit.IRTranslationVisitor;
import kc875.ast.visit.TypeCheckVisitor;
import kc875.utils.Maybe;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class ClassDefn extends ClassXi {
    private List<FuncDefn> methodDefns;

    private Set<String> fieldNames;
    private Set<String> methodNames;

    public ClassDefn(String name,
                     Maybe<String> superClass,
                     List<StmtDecl> fields,
                     List<FuncDefn> methodDefns,
                     ComplexSymbolFactory.Location location) {
        super(name,
                superClass,
                fields,
                methodDefns.stream().map(FuncDefn::toDecl)
                        .collect(Collectors.toList()),
                location);
        this.methodDefns = methodDefns;
    }

    public ClassDefn(String name,
                     String superClass,
                     List<StmtDecl> fields,
                     List<FuncDefn> methodDefns,
                     ComplexSymbolFactory.Location location) {
        this(name, Maybe.definitely(superClass), fields, methodDefns, location);
    }

    public ClassDefn(String name,
                     List<StmtDecl> fields,
                     List<FuncDefn> methodDefns,
                     ComplexSymbolFactory.Location location) {
        this(name, Maybe.unknown(), fields, methodDefns, location);
    }

    public List<FuncDefn> getMethodDefns() {
        return methodDefns;
    }

    @Override
    public void accept(TypeCheckVisitor visitor) {
        visitor.visit(this);
    }

    @Override
    public IRNode accept(IRTranslationVisitor visitor) {
        visitor.inClass = true;
        visitor.currentClass = this;
        IRNode n = visitor.visit(this);
        visitor.inClass = false;
        return n;
    }

    @Override
    public void prettyPrint(CodeWriterSExpPrinter w) {
        w.startList();
        w.printAtom(name + superClass.to(sc -> " extends " + sc).otherwise(""));
        w.startList();
        fields.forEach(f -> f.prettyPrint(w));
        w.endList();
        w.startList();
        methodDefns.forEach(m -> m.prettyPrint(w));
        w.endList();
        w.endList();
    }

    /**
     * Create a corresponding class declaration for this definition.
     *
     * @return The declaration.
     */
    public ClassDecl toDecl() {
        return new ClassDecl(name, superClass, fields, methodDecls, getLocation());
    }

    public FuncDefn getMethodDefn(String name){
        for (FuncDefn f : methodDefns) {
            if (f.getName().equals(name)) {
                return f;
            }
        }
        return null;
    }

    public Set<String> getFieldNames(){
        return this.getFields().stream()
                .flatMap(sd -> sd.varsOf().stream())
                .collect(Collectors.toSet());
    }
}
