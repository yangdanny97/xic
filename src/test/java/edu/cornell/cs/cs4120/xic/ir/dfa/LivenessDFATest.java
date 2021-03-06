package edu.cornell.cs.cs4120.xic.ir.dfa;

import com.google.common.collect.Lists;
import edu.cornell.cs.cs4120.xic.ir.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class LivenessDFATest {

    private LivenessDFA livenessDFA;
    private IRGraph graph;

    @Before
    public void setUp() {
        graph = new IRGraph(new IRFuncDecl("", 0, 0, new IRSeq()));
        livenessDFA = new LivenessDFA(graph);
    }

    private boolean twoListsEqual(List a, List b) {
        return (a.size() == b.size() &&
                a.containsAll(b) && b.containsAll(a));
    }

    @Test
    public void testTempsUsedInExpr() {
        IRBinOp expr = new IRBinOp(IRBinOp.OpType.ADD,
                new IRBinOp(IRBinOp.OpType.SUB,
                        new IRTemp("a"), new IRConst(5)),
                new IRBinOp(IRBinOp.OpType.ADD,
                        new IRMem(new IRTemp("b")), new IRTemp("c")));
        assert(twoListsEqual(Arrays.asList(new IRTemp("a"),
                new IRTemp("b"), new IRTemp("c")),
                Lists.newArrayList(livenessDFA.getTempsUsedInExpr(expr))));
    }
}
