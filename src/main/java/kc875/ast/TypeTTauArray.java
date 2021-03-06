package kc875.ast;

import edu.cornell.cs.cs4120.util.CodeWriterSExpPrinter;

public class TypeTTauArray extends TypeTTau {
    private TypeTTau typeTTau; //if null, then the list is empty and matches any type
    private Expr size = null;

    public TypeTTauArray(TypeTTau typeTTau) {
        this.typeTTau = typeTTau;
    }

    public TypeTTauArray(TypeTTau typeTTau, Expr size) {
        this.typeTTau = typeTTau;
        this.size = size;
    }

    public TypeTTauArray(){ //For empty lists
    }

    @Override
    public String toString() {
        if (typeTTau == null) {
            return "'a[]";// alpha list if the type is unknown....
        }
        return typeTTau.toString() + "[]";
    }

    public TypeTTau getTypeTTau() {
        return typeTTau;
    }

    public Expr getSize() {
        return size;
    }

    public void setTypeTTau(TypeTTau typeTTau) {
        this.typeTTau = typeTTau;
    }

    public void prettyPrint(CodeWriterSExpPrinter w) {
        w.startList();
        w.printAtom("[]");
        typeTTau.prettyPrint(w);
        if (size != null){
            size.prettyPrint(w);
        }
        w.endList();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof TypeTTauArray)) {
            return false;
        }
        TypeTTauArray t = (TypeTTauArray) obj;
        return this.typeTTau == null || t.typeTTau == null
                || this.typeTTau.equals(((TypeTTauArray) obj).typeTTau);
    }
}
