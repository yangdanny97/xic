package kc875.ast;

import edu.cornell.cs.cs4120.util.CodeWriterSExpPrinter;

public class TypeTTauBool extends TypeTTau {

    @Override
    public boolean equals(Object obj) {
        return obj instanceof TypeTTauBool;
    }

    @Override
    public String toString() {
        return "bool";
    }

    public void prettyPrint(CodeWriterSExpPrinter w) {
        w.printAtom("bool");
    }

    @Override
    public boolean subtypeOf(TypeT t) {
        return t instanceof TypeTTauBool || t instanceof TypeTUnit;
    }
}