package edu.cornell.cs.cs4120.xic.ir.dfa;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import edu.cornell.cs.cs4120.xic.ir.*;
import kc875.cfg.Graph;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class IRGraph extends Graph<IRStmt> {

    private BiMap<Node, IRStmt> nodeMap;

    public IRGraph(List<IRStmt> stmts){
        nodeMap = HashBiMap.create();

        List<IRStmt> basicBlocks = new ArrayList<>();
        HashMap<String, Integer> nodeLabelMap = new HashMap<>();
        HashMap<Integer, List<String>> jumps = new HashMap<>();

        for (IRStmt q : stmts) {

            IRSeq curr = new IRSeq();

            for (IRStmt s : stmts) {
                if (s instanceof IRLabel) {
                    basicBlocks.add(curr);
                    curr = new IRSeq();
                    curr.stmts().add(s);
                    nodeLabelMap.put(((IRLabel)s).name(), basicBlocks.size());
                }
                else curr.stmts().add(s);
                if (s instanceof IRJump ||
                        s instanceof IRCJump ||
                        s instanceof IRReturn) {
                    basicBlocks.add(curr);
                    curr = new IRSeq();
                    List<String> blockjumps = new ArrayList<>();
                    if (s instanceof IRCJump) {
                        IRCJump sj = (IRCJump) s;
                        blockjumps.add(sj.trueLabel());
                        blockjumps.add(sj.falseLabel());
                    }
                    jumps.put(basicBlocks.size(), blockjumps);
                }
            }
        }

        for (IRStmt bb : basicBlocks) {
            Graph.Node n = new Graph.Node(bb);
            if (getStartNode() == null) {
                setStartNode(n);
            }
            else addOtherNode(n);
        }

        //TODO: add edges

    }


}
