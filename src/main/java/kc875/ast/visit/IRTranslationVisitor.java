package kc875.ast.visit;

import com.google.common.graph.*;
import edu.cornell.cs.cs4120.util.InternalCompilerError;
import edu.cornell.cs.cs4120.xic.ir.*;
import edu.cornell.cs.cs4120.xic.ir.IRBinOp.OpType;
import java_cup.runtime.ComplexSymbolFactory.Location;
import kc875.ast.*;
import kc875.symboltable.TypeSymTableFunc;
import kc875.utils.Maybe;
import polyglot.util.Pair;

import java.math.BigInteger;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class IRTranslationVisitor implements ASTVisitor<IRNode> {
    private static final int WORD_NUM_BYTES = 8;
    private static final String IMPL_DV_CELL_NAME = "dummyFunc_";
    private int labelcounter;
    private int tempcounter;
    private boolean optimCF; // whether constant folding should be switched on
    private String name; //name of the comp unit
    public boolean inClass;
    private Set<String> global_names;
    public ClassDefn currentClass; //name of current class

    // a class cannot possibly have this name:
    private static String CLASS_SUPER_PARENT = "_";

    // map from class names to their ordered fields (only those declared in that class)
    private Map<String, List<String>> classFields;

    // map from class names to their ordered fields, including fields inherited from parent classes
    // note that interfaces cannot declare fields, so the list starts from the first non-interface parent
    private Map<String, List<String>> classAllFields;

    // map from class names to super class names (if a superclass exists)
    private Map<String, Maybe<String>> classHierarchy;
    // a top-down tree representing the class hierarchy
    @SuppressWarnings("UnstableApiUsage")
    private Traverser<String> classTree;

    /* For each class, this contains a map from method names to the earliest
     * ancestor of the class that defines that method (could be the class
     * itself).
     */
    private Map<String, Map<String, String>> classMethodDefinitions;

    // maps class names to the class' furthest parent (highest up in the hierarchy); can be the class itself
    private Map<String, String> furthestParents;

    // maps class names to the class' closest interface parent (lowest in the hierarchy), if one exists
    private Map<String, Maybe<String>> closestInterfaceParents;

    // ordered layouts of dispatch vectors for each class
    private Map<String, List<String>> dispatchVectorLayouts;

    // maps class names to all stmt decls accessible by the class
    private Map<String, List<StmtDecl>> classAllStmtDecls;

    private String newLabel() {
        return String.format("_mir_l%d", (labelcounter++));
    }

    private String newTemp() {
        return String.format("_mir_t%d", (tempcounter++));
    }

    public Map<String, List<String>> getDispatchVectorLayouts() {
        return dispatchVectorLayouts;
    }

    public IRTranslationVisitor(boolean optimCF,
                                String name,
                                Map<String, ClassXi> classes,
                                Map<String, Maybe<String>> classHierarchy) {
        this.labelcounter = 0;
        this.tempcounter = 0;
        this.optimCF = optimCF;
        this.name = name;

        if (classes.entrySet().size() != 0) {
            // classes exist
            this.classFields = new HashMap<>();

            for (Map.Entry<String, ClassXi> entry : classes.entrySet()) {
                String className = entry.getKey();
                ClassXi clazz = entry.getValue();
                List<String> orderedFields = clazz.getFields().stream()
                        .flatMap((StmtDecl field) -> field.varsOf().stream())
                        .collect(Collectors.toList());
                this.classFields.put(className, orderedFields);
            }

            this.classHierarchy = classHierarchy;
            this.classTree = invertHierarchy(classHierarchy, CLASS_SUPER_PARENT);

            /* The traverser allows us to visit each class in an order that
             * guarantees that a child can never be visited before its parent. This
             * ensures that when we start building a child class' dispatch vector,
             * its parent's DV will have already been built. */
            Iterable<String> classHierarchyTraversal =
                    this.classTree.depthFirstPreOrder(CLASS_SUPER_PARENT);

            this.dispatchVectorLayouts = new HashMap<>();
            this.classMethodDefinitions = new HashMap<>();
            this.classAllFields = new HashMap<>();
            this.furthestParents = new HashMap<>();
            this.closestInterfaceParents = new HashMap<>();
            this.classAllStmtDecls = new HashMap<>();
            for (String className : classHierarchyTraversal) {
                // skip the top parent node
                if (className.equals(CLASS_SUPER_PARENT)) {
                    // define an empty method map for the super parent
                    classMethodDefinitions.put(CLASS_SUPER_PARENT, new HashMap<>());
                    continue;
                }

                List<String> dvLayout = new ArrayList<>();
                Map<String, String> methodMap = new HashMap<>();
                List<String> allFields = new ArrayList<>();
                List<StmtDecl> allStmtDecls = new ArrayList<>();
                // build on the parent's dispatch vector and method map, if a parent exists
                try {
                    // get the parent class, if it exists
                    String parent = classHierarchy.get(className).get();

                    // inherit the parent's method map
                    methodMap.putAll(
                            this.classMethodDefinitions.get(parent)
                    );

                    // inherit the parent's DV layout
                    dvLayout.addAll(
                            this.dispatchVectorLayouts.get(parent)
                    );

                    // inherit the parent's known field layout
                    allFields.addAll(
                            this.classAllFields.get(parent)
                    );

                    // inherit the parent's stmtdecls
                    allStmtDecls.addAll(
                            this.classAllStmtDecls.get(parent)
                    );

                    // if the class is an interface, its closes interface parent is itself
                    // otherwise, its closest interface parent is its parent's closest interface parent
                    if (classes.get(className) instanceof ClassDecl) {
                        this.closestInterfaceParents.put(className, Maybe.definitely(className));
                    } else {
                        this.closestInterfaceParents.put(className, this.closestInterfaceParents.get(parent));
                    }

                    // the furthest parent is the parent's furthest parent
                    this.furthestParents.put(className, this.furthestParents.get(parent));
                } catch (Maybe.NoMaybeValueException e) {
                    // class has no parent, so its closest interface parent is None, and its furthest parent is itself
                    this.closestInterfaceParents.put(className, Maybe.unknown());
                    this.furthestParents.put(className, className);
                }

                // add all fields and methods that are defined in the class (not inherited/overridden)
                ClassXi clazz = classes.get(className);
                dvLayout.add(IMPL_DV_CELL_NAME + className);
                for (FuncDecl methodDecl : clazz.getMethodDecls()) {
                    String methodName = methodDecl.getName();

                    // add to DV layout if new method is being defined in clazz
                    if (!dvLayout.contains(methodName)) {
                        // This is linear, but the length of the list (dv
                        // layout) is not expected to be large
                        dvLayout.add(methodName);
                    }

                    // add to method map (since this class defines the method)
                    methodMap.put(methodName, className);
                }

                for (StmtDecl field : clazz.getFields()) {
                    allFields.addAll(field.varsOf());
                    allStmtDecls.add(field);
                }

                this.dispatchVectorLayouts.put(className, dvLayout);
                this.classMethodDefinitions.put(className, methodMap);
                this.classAllFields.put(className, allFields);
                this.classAllStmtDecls.put(className, allStmtDecls);
            }
        }
    }

    @SuppressWarnings("UnstableApiUsage")
    private <T> Traverser<T> invertHierarchy(Map<T, Maybe<T>> hierarchy, T superParent) {
        // first, build a graph from the hierarchy
        MutableGraph<T> graph = GraphBuilder.directed().build();
        graph.addNode(superParent);
        for (Map.Entry<T, Maybe<T>> entry : hierarchy.entrySet()) {
            T child = entry.getKey();
            Maybe<T> parentMaybe = entry.getValue();

            graph.addNode(child);
            parentMaybe.thenDo(graph::addNode);
            graph.putEdge(
                    child,
                    parentMaybe.to(Function.identity()).otherwise(superParent)
            );
        }

        // now, we transpose the graph
        Graph<T> transposed = Graphs.transpose(graph);

        // finally, use the transposed graph's definition of successor nodes to create a traverser
        return Traverser.forTree(transposed);
    }

    private int classFieldOffsetFromBack(String className, String fieldName) {
        return this.classFields.get(className).indexOf(fieldName) * -8;
    }

    private int classMethodOffset(String className, String methodName) {
        return this.dispatchVectorLayouts.get(className).indexOf(methodName) * 8;
    }

    private String returnValueName(int i) {
        return "_RET" + i;
    }

    private String funcArgName(int i) {
        return "_ARG" + i;
    }

    private String dispatchVectorLoc(String className) {
        return "_I_vt_" + escapeName(className);
    }

    private String classSizeLoc(String className) {
        return "_I_size_" + escapeName(className);
    }

    public String globalName(String name, TypeTTau signature) {
        String newName = escapeName(name);
        String type = typeName(signature);
        return "_I_g_" + newName + "_" + type;
    }

    private String currentLoopEndLabel;

    private String returnTypeName(TypeT type) {
        if (type instanceof TypeTList) {
            TypeTList tuple = (TypeTList) type;
            ArrayList<String> types = new ArrayList<>();
            tuple.getTTauList().forEach((t) -> types.add(typeName(t)));
            return "t" + tuple.getLength() + String.join("", types);
        } else if (type instanceof TypeTUnit) { //TypeTUnit
            return "p";
        } else {
            return typeName(type);
        }
    }

    private String typeName(TypeT type) {
        if (type instanceof TypeTList) {
            TypeTList tuple = (TypeTList) type;
            ArrayList<String> types = new ArrayList<>();
            tuple.getTTauList().forEach((t) -> types.add(typeName(t)));
            return String.join("", types);
        } else if (type instanceof TypeTUnit) {
            return "";
        } else if (type instanceof TypeTTauArray) {
            TypeTTauArray a = (TypeTTauArray) type;
            return "a" + typeName(a.getTypeTTau());
        } else if (type instanceof TypeTTauInt) {
            return "i";
        } else if (type instanceof TypeTTauBool) {
            return "b";
        } else if (type instanceof TypeTTauClass) {
            TypeTTauClass c = (TypeTTauClass) type;
            return "o" + c.getName().length() + escapeName(c.getName());
        } else {
            throw new IllegalArgumentException("invalid type");
        }
    }

    //_I_function_types
    public String functionName(String name, TypeSymTableFunc signature) {
        String newName = escapeName(name);
        String returnType = returnTypeName(signature.getOutput());
        String inputType = typeName(signature.getInput());
        return "_I" + newName + "_" + returnType + inputType;
    }

    public String escapeName(String name) {
        return name.replaceAll("_", "__");
    }

    //_I_class_function_types
    public String methodName(String name, TypeTTauClass clazz,
                             TypeSymTableFunc signature) {
        String newName = escapeName(name);
        String newClassName = typeName(clazz);
        String returnType = returnTypeName(signature.getOutput());
        String inputType = typeName(signature.getInput());
        return "_I" + newName + "_" + returnType + newClassName + inputType;
    }

    private IRStmt conditionalTranslate(Expr e, String labelt, String labelf) {
        if (e instanceof ExprBoolLiteral) { // C[true/false, t, f]
            boolean val = ((ExprBoolLiteral) e).getValue();
            return new IRJump(new IRName(val ? labelt : labelf));
        } else if (e instanceof ExprBinop) {
            ExprBinop eb = (ExprBinop) e;
            if (eb.getOp() == Binop.AND) {// C[e1 & e2, t, f]
                String t_ = newLabel();
                return new IRSeq(
                        conditionalTranslate(eb.getLeftExpr(), t_, labelf),
                        new IRLabel(t_),
                        conditionalTranslate(eb.getRightExpr(), labelt, labelf)
                );
            } else if (eb.getOp() == Binop.OR) {// C[e1 | e2, t, f]
                String f_ = newLabel();
                return new IRSeq(
                        conditionalTranslate(eb.getLeftExpr(), labelt, f_),
                        new IRLabel(f_),
                        conditionalTranslate(eb.getRightExpr(), labelt, labelf)
                );
            }
        } else if (e instanceof ExprUnop) {
            ExprUnop eu = (ExprUnop) e;
            if (eu.getOp() == Unop.NOT) { // C[!e , t, f]
                return conditionalTranslate(eu.getExpr(), labelf, labelt);
            }
        }
        // C[e, t, f] default rule
        return new IRCJump((IRExpr) e.accept(this), labelt, labelf);
    }

    /**
     * Check bounds of array access
     *
     * @param temp_array name of temp that points to head of array
     * @param temp_index name of temp that points to index
     * @return a list of IR statements for performing this check.
     */
    private IRStmt checkIndex(String temp_array, String temp_index) {
        String lt = newLabel();
        String lf = newLabel();
        //array bounds checking - True if invalid
        IRExpr test = new IRBinOp(OpType.OR,
                new IRBinOp(OpType.LT, new IRTemp(temp_index), new IRConst(0)),
                new IRBinOp(OpType.GEQ, new IRTemp(temp_index), new IRMem(
                        new IRBinOp(
                                OpType.ADD,
                                new IRTemp(temp_array),
                                new IRConst(-WORD_NUM_BYTES)
                        )
                ))
        );
        return new IRSeq(
                new IRCJump(test, lt, lf),
                new IRLabel(lt),
                new IRExp(new IRCall(new IRName("_xi_out_of_bounds"), 0)),
                new IRLabel(lf)
        );
    }

    /**
     * Allocates memory of size.
     *
     * @param size size of the memory.
     * @return A function call expression.
     */
    private IRCall allocateMem(IRExpr size) {
        return new IRCall(new IRName("_xi_alloc"), 1, size);
    }

    /**
     * Allocates array of size (length+1), of which the address is stored in
     * temporary t. The extra word, i.e., MEM(t) - 64, is used for storing
     * the length. This means that only length bytes are actually available
     * for array t.
     *
     * @param t   temporary or memory address.
     * @param eIR length of array to be allocated.
     * @return a list of IR statements for performing this array allocation.
     */
    private List<IRStmt> allocateArray(IRExpr t, IRExpr eIR) {
        assert t instanceof IRTemp || t instanceof IRMem;

        // Copy eIR to a temporary
        String length = newTemp();
        IRMove copyLenToTemp = new IRMove(new IRTemp(length), eIR);

        IRBinOp numBytesForArray = new IRBinOp(
                OpType.ADD,
                new IRConst(WORD_NUM_BYTES), // extra word for storing length
                new IRBinOp(
                        OpType.MUL,
                        new IRTemp(length),
                        new IRConst(WORD_NUM_BYTES)
                )
        );

        // Allocate memory, create y
        String arrayBaseAddress = newTemp();
        IRMove baseAllocAddress = new IRMove(
                new IRTemp(arrayBaseAddress),
                allocateMem(numBytesForArray)
        );

        // MEM(y) <- length
        IRMove storeLength = new IRMove(new IRMem(new IRTemp(arrayBaseAddress)), new IRTemp(length));

        // t <- y + 8
        IRMove zeroIdxAddress = new IRMove(
                t,
                new IRBinOp(
                        OpType.ADD,
                        new IRTemp(arrayBaseAddress),
                        new IRConst(WORD_NUM_BYTES)
                )
        );

        return new ArrayList<>(Arrays.asList(
                copyLenToTemp, baseAllocAddress, storeLength, zeroIdxAddress
        ));
    }

    //unfolds the sizes of the array and converts them all into IRTemps
    private Pair<List<IRStmt>, List<String>> unfoldSizes(TypeTTauArray arr) {
        List<IRStmt> moves = new ArrayList<>();
        List<String> sizeTemps = new ArrayList<>();
        Expr size = arr.getSize();
        TypeTTauArray curr = arr;
        while (size != null && curr != null) {
            IRExpr sizeIR = (IRExpr) size.accept(this);
            String sizeTemp = newTemp();
            moves.add(new IRMove(new IRTemp(sizeTemp), sizeIR));
            sizeTemps.add(sizeTemp);
            if (curr.getTypeTTau() instanceof TypeTTauArray) {
                curr = (TypeTTauArray) curr.getTypeTTau();
                size = curr.getSize();
            } else {
                curr = null;
                size = null;
            }
        }
        return new Pair<>(moves, sizeTemps);
    }

    /**
     * Allocates a multi dimensional array of type arr starting at
     * temporary/memory location t.
     *
     * @param t         temporary or memory address.
     * @param arr       type of multi dim array.
     * @param sizeTemps list of temp names where the array sizes are stored
     *                  need to pre-calculate and store them elsewhere
     * @return a list of IR statements for performing this array allocation.
     */
    private List<IRStmt> allocateMultiDimArray(IRExpr t, TypeTTauArray arr, List<String> sizeTemps) {
        assert t instanceof IRTemp || t instanceof IRMem;

        TypeTTau innerType = arr.getTypeTTau();
        Expr size = arr.getSize();
        if (size != null) {
            String sizeTemp = sizeTemps.get(0);

            // Create an array of size sizeTemp
            List<IRStmt> allocIR = new ArrayList<>(allocateArray(t, new IRTemp(sizeTemp)));

            if (innerType instanceof TypeTTauArray
                    && ((TypeTTauArray) innerType).getSize() != null) {
                // innerType is an array, create a while loop to initialize
                // each element of t
                TypeTTauArray itArray = (TypeTTauArray) innerType;

                String whileStart = newLabel();
                String whileBody = newLabel();
                String whileEnd = newLabel();

                String i = newTemp();   // loop counter
                IRBinOp whileGuard = new IRBinOp(
                        OpType.LT, new IRTemp(i), new IRTemp(sizeTemp)
                );

                // i <- 0
                allocIR.add(new IRMove(new IRTemp(i), new IRConst(0)));
                allocIR.add(new IRLabel(whileStart));  // while loop starts
                // Go to whileBody if i < sizeTemp else go to whileEnd
                allocIR.add(new IRCJump(whileGuard, whileBody, whileEnd));
                allocIR.add(new IRLabel(whileBody));
                // Allocate multi dim array at t + i*8
                allocIR.add(new IRSeq(allocateMultiDimArray(
                        new IRMem(new IRBinOp(
                                OpType.ADD,
                                t,
                                new IRBinOp(
                                        OpType.MUL,
                                        new IRTemp(i),
                                        new IRConst(WORD_NUM_BYTES)
                                )
                        )),
                        itArray,
                        sizeTemps.subList(1, sizeTemps.size()))));
                // i++
                allocIR.add(new IRMove(
                        new IRTemp(i),
                        new IRBinOp(OpType.ADD, new IRTemp(i), new IRConst(1))
                ));
                allocIR.add(new IRJump(new IRName(whileStart)));
                allocIR.add(new IRLabel(whileEnd));    // while loop ends
            }
            return allocIR;
        } else {
            // size == null ==> the inner arrays, if any, are also
            // uninitialized. So just return an empty list of IRStmts
            // (nothing to initialize)
            return new ArrayList<>();
        }
    }

    /**
     * Moves contents of array arr to temporary/memory location newLoc.
     *
     * @param newLoc   expression representing location of new array
     * @param arr      expression representing location of old array
     * @param sizeTemp a temp storing the size of the array
     * @return a list of IR statements for performing this array copying.
     */
    private List<IRStmt> copyArray(IRExpr newLoc, IRExpr arr, String sizeTemp) {

        String i = newTemp();   // loop counter
        String l = newLabel();

        String whileStart = newLabel();
        String whileEnd = newLabel();
        IRBinOp whileGuard = new IRBinOp(OpType.LT, new IRTemp(i), new IRTemp(sizeTemp));
        return new ArrayList<>(Arrays.asList(
                new IRMove(new IRTemp(i), new IRConst(0)),  // i <- 0
                new IRLabel(whileStart), // while loop starts
                // Enter loop if i < sizeTemp
                new IRCJump(whileGuard, l, whileEnd),
                new IRLabel(l),
                new IRMove(
                        //new location
                        new IRMem(new IRBinOp(
                                OpType.ADD,
                                newLoc,
                                new IRBinOp(
                                        OpType.MUL,
                                        new IRTemp(i),
                                        new IRConst(WORD_NUM_BYTES)
                                )
                        )),
                        //old element
                        new IRMem(new IRBinOp(
                                OpType.ADD,
                                arr,
                                new IRBinOp(
                                        OpType.MUL,
                                        new IRTemp(i),
                                        new IRConst(WORD_NUM_BYTES)
                                )
                        ))),
                // i++
                new IRMove(
                        new IRTemp(i),
                        new IRBinOp(OpType.ADD, new IRTemp(i), new IRConst(1))
                ),
                new IRJump(new IRName(whileStart)),
                new IRLabel(whileEnd)    // while loop ends
        ));
    }

    private IRExpr translateBinop(IRExpr l, IRExpr r, Binop op) {
        //arrays are handled elsewhere
        switch (op) {
            case PLUS:
                return new IRBinOp(OpType.ADD, l, r);
            case MINUS:
                return new IRBinOp(OpType.SUB, l, r);
            case MULT:
                return new IRBinOp(OpType.MUL, l, r);
            case HI_MULT:
                return new IRBinOp(OpType.HMUL, l, r);
            case DIV:
                return new IRBinOp(OpType.DIV, l, r);
            case MOD:
                return new IRBinOp(OpType.MOD, l, r);
            case EQEQ:
                return new IRBinOp(OpType.EQ, l, r);
            case NEQ:
                return new IRBinOp(OpType.NEQ, l, r);
            case GT:
                return new IRBinOp(OpType.GT, l, r);
            case LT:
                return new IRBinOp(OpType.LT, l, r);
            case GTEQ:
                return new IRBinOp(OpType.GEQ, l, r);
            case LTEQ:
                return new IRBinOp(OpType.LEQ, l, r);
            case AND:
            case OR:
                String l1 = newLabel();
                String l2 = newLabel();
                String l3 = newLabel();
                String x = newTemp();
                if (op == Binop.AND) {
                    return new IRESeq(new IRSeq(
                            new IRMove(new IRTemp(x), new IRConst(0)),
                            new IRCJump(l, l1, l3),
                            new IRLabel(l1),
                            new IRCJump(r, l2, l3),
                            new IRLabel(l2),
                            new IRMove(new IRTemp(x), new IRConst(1)),
                            new IRLabel(l3)),
                            new IRTemp(x)
                    );
                } else {
                    return new IRESeq(new IRSeq(
                            new IRMove(new IRTemp(x), new IRConst(1)),
                            new IRCJump(l, l3, l1),
                            new IRLabel(l1),
                            new IRCJump(r, l3, l2),
                            new IRLabel(l2),
                            new IRMove(new IRTemp(x), new IRConst(0)),
                            new IRLabel(l3)),
                            new IRTemp(x)
                    );
                }

            default:
                throw new InternalCompilerError("Invalid binary operation");
        }
    }

    @Override
    public IRExpr visit(ExprBinop node) {
        IRExpr l = (IRExpr) node.getLeftExpr().accept(this);
        IRExpr r = (IRExpr) node.getRightExpr().accept(this);
        Binop op = node.getOp();
        //special case for array concatenation
        if (node.getTypeCheckType() instanceof TypeTTauArray && op == Binop.PLUS) {
            String tempL = newTemp();
            String tempR = newTemp();
            IRMove moveL = new IRMove(new IRTemp(tempL), l);
            IRMove moveR = new IRMove(new IRTemp(tempR), r);

            String tempLLength = newTemp();
            String tempRLength = newTemp();
            IRMove lengthL = new IRMove(new IRTemp(tempLLength), new IRMem(
                    new IRBinOp(OpType.ADD,
                            new IRTemp(tempL),
                            new IRConst(-WORD_NUM_BYTES)
                    )));
            IRMove lengthR = new IRMove(new IRTemp(tempRLength), new IRMem(
                    new IRBinOp(OpType.ADD,
                            new IRTemp(tempR),
                            new IRConst(-WORD_NUM_BYTES)
                    )));
            String tempNewArray = newTemp();
            String newRStart = newTemp();

            //move lengths to temps
            List<IRStmt> stmts = new ArrayList<>(Arrays.asList(
                    moveL,
                    moveR,
                    lengthL,
                    lengthR
            ));
            //make new array
            stmts.addAll(allocateArray(
                    new IRTemp(tempNewArray),
                    new IRBinOp(OpType.ADD,
                            new IRTemp(tempLLength),
                            new IRTemp(tempRLength))
            ));
            //copy left
            stmts.addAll(copyArray(
                    new IRTemp(tempNewArray),
                    new IRTemp(tempL), tempLLength));
            //copy right
            stmts.add(
                    new IRMove(
                            new IRTemp(newRStart),
                            new IRBinOp(OpType.ADD,
                                    new IRTemp(tempNewArray),
                                    new IRBinOp(OpType.MUL,
                                            new IRConst(WORD_NUM_BYTES),
                                            new IRTemp(tempLLength)
                                    )
                            )
                    )
            );
            stmts.addAll(copyArray(
                    new IRTemp(newRStart),
                    new IRTemp(tempR),
                    tempRLength
            ));
            IRSeq seq = new IRSeq(stmts);
            return new IRESeq(seq, new IRTemp(tempNewArray));
        }
        //constant folding the booleans before they get screwed up
        if (l instanceof IRConst && r instanceof IRConst && optimCF) {
            long lval = ((IRConst) l).value();
            long rval = ((IRConst) r).value();
            switch (op) {
                case PLUS:
                    return new IRConst(lval + rval);
                case MINUS:
                    return new IRConst(lval - rval);
                case MULT:
                    return new IRConst(lval * rval);
                case HI_MULT:
                    return new IRConst(BigInteger.valueOf(lval)
                            .multiply(BigInteger.valueOf(rval))
                            .shiftRight(64)
                            .longValue());
                case DIV:
                    if (rval == 0L) {
                        return translateBinop(l, r, op);
                    }
                    return new IRConst(lval / rval);
                case MOD:
                    if (rval == 0L) {
                        return translateBinop(l, r, op);
                    }
                    return new IRConst(lval % rval);
                case EQEQ:
                    return new IRConst((lval == rval) ? 1 : 0);
                case NEQ:
                    return new IRConst((lval != rval) ? 1 : 0);
                case GT:
                    return new IRConst((lval > rval) ? 1 : 0);
                case LT:
                    return new IRConst((lval < rval) ? 1 : 0);
                case GTEQ:
                    return new IRConst((lval >= rval) ? 1 : 0);
                case LTEQ:
                    return new IRConst((lval <= rval) ? 1 : 0);
                case AND:
                    return new IRConst((lval == 1 && rval == 1) ? 1 : 0);
                case OR:
                    return new IRConst((lval == 1 || rval == 1) ? 1 : 0);
                default:
                    throw new InternalCompilerError("Invalid binary operation");
            }
        } else {
            return translateBinop(l, r, op);
        }
    }

    @Override
    public IRExpr visit(ExprBoolLiteral node) {
        return new IRConst(node.getValue() ? 1 : 0);
    }

    private int getNumRets(TypeT output) {
        if (output instanceof TypeTUnit)
            return 0;
        else if (output instanceof TypeTTau)
            return 1;
        else if (output instanceof TypeTList)
            return ((TypeTList) output).getLength();
        else
            throw new IllegalStateException("TypeT not a unit/tau/tau list");
    }

    @Override
    public IRExpr visit(ExprFunctionCall node) {
        if (inClass && dispatchVectorLayouts.get(currentClass.getName()).contains(node.getName())) {
            Location loc = node.getLocation();
            return visit(new ExprMethodCall(
                    new ExprThis(currentClass.getName(), loc),
                    node, loc));
        }
        String funcName = functionName(node.getName(), node.getSignature());
        int numRets = getNumRets(node.getSignature().getOutput());
        ArrayList<IRExpr> argsIR = new ArrayList<>();

        for (Expr arg : node.getArgs()) {
            // Add argIR to list of arguments to be passed to IRCall
            argsIR.add((IRExpr) arg.accept(this));
        }
        return new IRESeq(
                new IRExp(new IRCall(new IRName(funcName), numRets, argsIR)),
                new IRTemp(returnValueName(0)));
    }

    private IRMem getFieldOffset(IRExpr obj,
                                 String className,
                                 String fieldName,
                                 List<IRStmt> seq) {
        String t = newTemp();
        seq.add(new IRMove(new IRTemp(t), obj));

        int idxField = this.classAllFields.get(className).indexOf(fieldName);
        Maybe<String> cWithSizeNotAvail = this.closestInterfaceParents.get(className);

        IRExpr offsetObjLayoutAccess = cWithSizeNotAvail.to(
                interfaceName -> (IRExpr) new IRName(classSizeLoc(interfaceName))
        ).otherwise(new IRConst(8));

        seq.add(new IRMove(new IRTemp(t), new IRBinOp(
                OpType.ADD, new IRTemp(t), offsetObjLayoutAccess
        )));

        return new IRMem(new IRBinOp(OpType.ADD,
                new IRTemp(t), new IRConst(idxField * 8)
        ));
    }

    @Override
    public IRExpr visit(ExprId node) {
        if (global_names.contains(node.getName())) {
            String name = "_I_g_" + escapeName(node.getName()) + "_" + typeName(node.getTypeCheckType());
            return new IRMem(new IRName(name));
        } else if (inClass && currentClass.getFieldNames().contains(node.getName())) {
            //treat like a field access for "this"
            List<IRStmt> seq = new ArrayList<>();
            IRMem fieldLoc = getFieldOffset(
                    new IRTemp("this"), currentClass.getName(),
                    node.getName(), seq
            );
            return new IRESeq(new IRSeq(seq), fieldLoc);
        }
        return new IRTemp(node.getName());
    }

    @Override
    public IRNode visit(ExprIndex node) {
        IRExpr idx = (IRExpr) node.getIndex().accept(this);
        IRExpr array = (IRExpr) node.getArray().accept(this);
        String t_a = newTemp();
        String t_i = newTemp();
        IRExpr offset = new IRBinOp(
                OpType.MUL,
                new IRConst(WORD_NUM_BYTES),
                new IRTemp(t_i)
        );
        IRMem access = new IRMem(new IRBinOp(
                OpType.ADD,
                new IRTemp(t_a),
                offset
        ));
        return new IRESeq(
                new IRSeq(
                        new IRMove(new IRTemp(t_a), array),
                        new IRMove(new IRTemp(t_i), idx),
                        checkIndex(t_a, t_i)
                ),
                access);
    }

    @Override
    public IRExpr visit(ExprIntLiteral node) {
        return new IRConst(node.getValue());
    }

    @Override
    public IRExpr visit(ExprLength node) {
        String t = newTemp();
        //extra temp to avoid side effect of modifying length accidentally
        return new IRESeq(
                new IRMove(
                        new IRTemp(t),
                        new IRMem(new IRBinOp(
                                OpType.ADD,
                                (IRExpr) node.getArray().accept(this),
                                new IRConst(-WORD_NUM_BYTES))
                        )
                ),
                new IRTemp(t)
        );
    }

    @Override
    public IRExpr visit(ExprNull node) {
        return new IRMem(new IRConst(0));
    }

    @Override
    public IRExpr visit(ExprNew node) {
        // the memory layout is found in classFields
        String className = node.getName();
        // must exist due to typechecking

        String objectBaseAddress = newTemp();
        IRMove baseAllocAddress = new IRMove(
                new IRTemp(objectBaseAddress),
                allocateMem(new IRMem(new IRName(classSizeLoc(className))))
        );

        // store a pointer to the dispatch vector in our new temp
        IRMove storeDV = new IRMove(
                new IRMem(new IRTemp(objectBaseAddress)),
                new IRName(dispatchVectorLoc(className))
        );

        List<IRStmt> stmts = new ArrayList<>();
        stmts.add(baseAllocAddress);
        stmts.add(storeDV);

        // Initialize the fields when the fields are arrays
        for (StmtDecl field : this.classAllStmtDecls.get(className)) {
            String fieldName;

            if (field instanceof StmtDeclSingle) {
                StmtDeclSingle s = (StmtDeclSingle) field;
                if (s.getDecl().getType() instanceof TypeTTauArray) {
                    fieldName = s.getName();
                    stmts.add(this.visit(s));
                    // Calculate offset for fieldName
                    IRMem fieldLoc = getFieldOffset(
                            new IRTemp(objectBaseAddress), className, fieldName, stmts
                    );
                    stmts.add(new IRMove(fieldLoc, new IRTemp(fieldName)));
                }
            } else if (field instanceof StmtDeclMulti
                    && ((StmtDeclMulti) field).getType() instanceof TypeTTauArray) {
                List<String> names = (field).varsOf();
                for (String name : names) {
                    fieldName = name;
                    TypeTTau fieldType = ((StmtDeclMulti) field).getType();
                    stmts.add(initDecl(fieldName, fieldType));
                    IRMem fieldLoc = getFieldOffset(
                            new IRTemp(objectBaseAddress), className, fieldName, stmts
                    );
                    stmts.add(new IRMove(fieldLoc, new IRTemp(fieldName)));
                }
            } else if (field instanceof StmtDeclAssign) {
                throw new IllegalStateException("Fields should not be " +
                        "initialized with a right hand side");
            }
        }
        return new IRESeq(new IRSeq(stmts), new IRTemp(objectBaseAddress));
    }

    @Override
    public IRExpr visit(ExprArrayLiteral node) {
        String t = newTemp();
        List<Expr> contents = node.getContents();
        int length = contents.size();
        List<IRStmt> seq = allocateArray(new IRTemp(t), new IRConst(length));
        //store contents
        int offset = 0;
        for (Expr e : contents) {
            IRExpr e_trans = (IRExpr) e.accept(this);
            if (offset == 0) {
                seq.add(new IRMove(new IRMem(new IRTemp(t)), e_trans));
            } else {
                seq.add(new IRMove(
                        new IRMem(new IRBinOp(
                                OpType.ADD,
                                new IRTemp(t),
                                new IRConst(WORD_NUM_BYTES * offset)
                        )),
                        e_trans
                ));
            }
            offset++;
        }
        return new IRESeq(new IRSeq(seq), new IRTemp(t));
    }

    @Override
    public IRExpr visit(ExprUnop node) {
        IRExpr e = (IRExpr) node.getExpr().accept(this);
        Unop op = node.getOp();
        switch (op) {
            //NOT(e)  -> XOR(1,e)
            case NOT:
                if (e instanceof IRConst && optimCF) {
                    long e_val = ((IRConst) e).value();
                    return new IRConst((e_val == 0) ? 1 : 0);
                }
                return new IRBinOp(OpType.XOR, new IRConst(1), e);
            //UMINUS(e) -> SUB(0, e)
            case UMINUS:
                if (e instanceof IRConst && optimCF) {
                    long e_val = ((IRConst) e).value();
                    return new IRConst(0 - e_val);
                }
                return new IRBinOp(OpType.SUB, new IRConst(0), e);
            default:
                throw new InternalCompilerError("Invalid unary operation");
        }
    }

    @Override
    public IRNode visit(AssignableIndex node) {
        ExprIndex idx_expr = (ExprIndex) node.getIndex();
        return idx_expr.accept(this);
    }

    @Override
    public IRNode visit(AssignableId node) {
        ExprId id = node.getExprId();
        return id.accept(this);
    }

    @Override
    public IRNode visit(AssignableFieldAccess node) {
        return node.getAccess().accept(this);
    }

    @Override
    public IRNode visit(StmtReturn node) {
        // Translate each expr into IR and pass it into IRReturn
        return new IRReturn(
                node.getReturnVals().stream()
                        .map(e -> (IRExpr) e.accept(this))
                        .collect(Collectors.toList())
        );
    }

    @Override
    public IRNode visit(StmtAssign node) {
        IRExpr l = (IRExpr) node.getLhs().accept(this);
        IRExpr r = (IRExpr) node.getRhs().accept(this);
        return new IRMove(l, r);
    }

    /**
     * Initializes memory for type declType and assigns the memory to the the
     * temporary variable given by declName.
     *
     * @param declName name of the temporary.
     * @param declType type of the memory to be allocated.
     * @return IR stmts to do this allocation.
     */
    private IRStmt initDecl(String declName, TypeTTau declType) {
        if (declType instanceof TypeTTauArray) {
            TypeTTauArray declArray = (TypeTTauArray) declType;
            Pair<List<IRStmt>, List<String>> unfoldedSizes = unfoldSizes(declArray);
            List<IRStmt> stmts = new ArrayList<>(unfoldedSizes.part1());
            stmts.addAll(
                    allocateMultiDimArray(new IRTemp(declName),
                            declArray, unfoldedSizes.part2())
            );
            return new IRSeq(stmts);
        } else {
            // declType either an int or bool, initialize arbitrarily
            return new IRMove(new IRTemp(declName), new IRConst(0));
        }
    }

    @Override
    public IRStmt visit(StmtDeclSingle node) {
        Pair<String, TypeTTau> decl = node.getDecl().getPair();
        return initDecl(decl.part1(), decl.part2());
    }

    @Override
    public IRStmt visit(StmtDeclAssign node) {
        List<TypeDecl> decls = node.getDecls();
        IRExpr rhsIR = (IRExpr) node.getRhs().accept(this);

        List<IRStmt> declsInitIR = new ArrayList<>();
        List<IRStmt> moveRetIR = new ArrayList<>();

        if (node.getRhs() instanceof ExprFunctionCall
                || node.getRhs() instanceof ExprMethodCall) {
            for (int i = 0; i < decls.size(); ++i) {
                TypeDecl di = decls.get(i);
                // if di is an underscore, the _RETi is ignored, i.e., don't
                // do a move operation
                if (!(di.typeOf() instanceof TypeTUnit)) {
                    // di is a var
                    Pair<String, TypeTTau> decl = ((TypeDeclVar) di).getPair();
                    // Initialize the ith declaration
                    if (decl.part2() instanceof TypeTTauArray) {
                        declsInitIR.add(initDecl(decl.part1(), decl.part2()));
                    }
                    // Move return value i to this declaration
                    moveRetIR.add(new IRMove(
                            new IRTemp(decl.part1()),
                            new IRTemp(returnValueName(i))
                    ));
                }
            }

            return new IRSeq(
                    new IRExp(rhsIR),   // evaluate rhs
                    new IRSeq(declsInitIR), // initialize declarations
                    new IRSeq(moveRetIR)    // move return values of func to decls
            );
        } else {
            // rhs not a function, decls must be size 1
            Pair<String, TypeTTau> decl =
                    ((TypeDeclVar) decls.get(0)).getPair();
            if (decl.part2() instanceof TypeTTauArray) {
                // Add initializing code if necessary
                declsInitIR.add(initDecl(decl.part1(), decl.part2()));
            }
            moveRetIR.add(new IRMove(
                    new IRTemp(decl.part1()),
                    rhsIR
            ));

            return new IRSeq(
                    new IRSeq(declsInitIR), // initialize declarations
                    new IRSeq(moveRetIR)    // move return values of func to decls
            );
        }
    }

    @Override
    public IRNode visit(StmtProcedureCall node) {
        if (inClass && dispatchVectorLayouts.get(currentClass.getName()).contains(node.getName())) {
            Location loc = node.getLocation();
            return visit(new StmtMethodCall(
                    new ExprThis(currentClass.getName(), loc),
                    new ExprFunctionCall(node.getName(), node.getArgs(), loc),
                    loc));
        }
        String funcName = functionName(node.getName(), node.getSignature());
        List<IRExpr> argsIR = new ArrayList<>();

        for (Expr arg : node.getArgs()) {
            argsIR.add((IRExpr) arg.accept(this));
        }
        // procedure returns nothing
        return new IRSeq(new IRExp(new IRCall(new IRName(funcName), 0, argsIR)));
    }

    @Override
    public IRStmt visit(StmtIf node) {
        IRStmt stmt = (IRStmt) node.getThenStmt().accept(this);
        String lt = newLabel();
        String lf = newLabel();
        IRStmt condition = conditionalTranslate(node.getGuard(), lt, lf);
        return new IRSeq(condition, new IRLabel(lt), stmt, new IRLabel(lf));
    }

    @Override
    public IRStmt visit(StmtIfElse node) {
        IRStmt stmtThen = (IRStmt) node.getThenStmt().accept(this);
        IRStmt stmtElse = (IRStmt) node.getElseStmt().accept(this);
        String lt = newLabel();
        String lf = newLabel();
        String lfin = newLabel();
        IRStmt condition = conditionalTranslate(node.getGuard(), lt, lf);
        IRJump jmp = new IRJump(new IRName(lfin));
        return new IRSeq(condition,
                new IRLabel(lt),
                stmtThen,
                jmp,
                new IRLabel(lf),
                stmtElse,
                new IRLabel(lfin)
        );
    }

    @Override
    public IRStmt visit(StmtWhile node) {
        String lh = newLabel();
        String lt = newLabel();
        String le = newLabel();
        currentLoopEndLabel = le;
        IRStmt stmt = (IRStmt) node.getDoStmt().accept(this);
        IRStmt condition = conditionalTranslate(node.getGuard(), lt, le);
        IRJump jmp = new IRJump(new IRName(lh));
        return new IRSeq(
                new IRLabel(lh),
                condition,
                new IRLabel(lt),
                stmt,
                jmp,
                new IRLabel(le));
    }

    @Override
    public IRSeq visit(StmtBlock node) {
        return new IRSeq(node.getStatments().stream()
                .map(s -> (IRStmt) s.accept(this))
                .collect(Collectors.toList())
        );
    }

    @Override
    public IRNode visit(StmtBreak node) {
        return new IRJump(new IRName(currentLoopEndLabel));
    }

    @Override
    public IRNode visit(StmtMethodCall node) {
        List<IRStmt> seq = new ArrayList<>();
        Expr obj = node.getObj();
        ExprFunctionCall call = node.getCall();

        String t = newTemp();
        IRExpr objExpr = (IRExpr) obj.accept(this);
        seq.add(new IRMove(new IRTemp(t), objExpr));

        ArrayList<IRExpr> argsIR = new ArrayList<>();
        argsIR.add(new IRTemp(t));// t added to the list of arguments
        for (Expr arg : call.getArgs()) {
            // Add argIR to list of arguments to be passed to IRCall
            argsIR.add((IRExpr) arg.accept(this));
        }

        String className = ((TypeTTauClass) obj.getTypeCheckType()).getName();
        // method = [[DV] + offset]
        IRExpr method = new IRMem(new IRBinOp(
                OpType.ADD,
                new IRMem(new IRTemp(t)),//DV + offset
                new IRConst(classMethodOffset(className, call.getName()))
        ));
        // stmt method (procedure) returns nothing
        seq.add(new IRExp(new IRCall(method, 0, argsIR)));
        return new IRSeq(seq);
    }

    @Override
    public IRCompUnit visit(FileProgram node) {
        IRCompUnit program = new IRCompUnit(name);

        global_names = node.getGlobalNames();

        //class init
        for (ClassDefn c : node.getClassDefns()) {
            program.appendFunc(generateInitClass(c));
        }

        //global init
        if (node.getGlobalVars().size() != 0) {
            program.appendFunc(generateInitGlobals(node.getGlobalVars()));
        }

        for (ClassDefn c : node.getClassDefns()) {
            inClass = true;
            currentClass = c;
            for (FuncDefn method : c.getMethodDefns()) {
                program.appendFunc(visitMethod(c.getName(), method));
            }
            inClass = false;
        }

        for (FuncDefn d : node.getFuncDefns()) {
            program.appendFunc((IRFuncDecl) d.accept(this));
        }

        return program;
    }

    @Override
    public IRNode visit(FileInterface node) {
        return null;
    }

    @Override
    public IRFuncDecl visit(FuncDefn node) {
        if (inClass) {
            return visitMethod(currentClass.getName(), node);
        }
        String funcName = functionName(
                node.getName(), (TypeSymTableFunc) node.getSignature().part2());

        List<Pair<String, TypeTTau>> params = node.getParams();
        List<IRStmt> moveArgs = new ArrayList<>();
        for (int i = 0; i < params.size(); ++i) {
            // Move argi into params
            moveArgs.add(new IRMove(
                    new IRTemp(params.get(i).part1()),
                    new IRTemp(funcArgName(i))
            ));
        }

        Stmt body = node.getBody();
        IRSeq bodyIR = (IRSeq) body.accept(this);

        // Add a return statement if not already present
        if (body.getTypeCheckType().equals(TypeR.Unit)) {
            bodyIR = new IRSeq(bodyIR, new IRReturn());
        }

        return new IRFuncDecl(
                funcName, params.size(), getNumRets(node.getOutput()),
                new IRSeq(new IRSeq(moveArgs), bodyIR)
        );
    }

    private IRFuncDecl visitMethod(String className, FuncDefn node) {
        String funcName = methodName(
                node.getName(), new TypeTTauClass(className),
                (TypeSymTableFunc) node.getSignature().part2()
        );

        List<Pair<String, TypeTTau>> params = node.getParams();
        // Make "this" the first argument
        params.add(0, new Pair<>("this", new TypeTTauClass(className)));
        List<IRStmt> moveArgs = new ArrayList<>();
        for (int i = 0; i < params.size(); ++i) {
            // Move argi into params
            moveArgs.add(new IRMove(
                    new IRTemp(params.get(i).part1()),
                    new IRTemp(funcArgName(i))
            ));
        }

        Stmt body = node.getBody();
        IRSeq bodyIR = (IRSeq) body.accept(this);

        // Add a return statement if not already present
        if (body.getTypeCheckType().equals(TypeR.Unit)) {
            bodyIR = new IRSeq(bodyIR, new IRReturn());
        }

        return new IRFuncDecl(
                funcName, params.size(), getNumRets(node.getOutput()),
                new IRSeq(new IRSeq(moveArgs), bodyIR)
        );
    }

    @Override
    public IRNode visit(FuncDecl node) {
        return null;
    }

    @Override
    public IRNode visit(ClassDecl node) {
        return null;
    }

    @Override
    public IRNode visit(ClassDefn node) {
        return null;
    }

    @Override
    public IRStmt visit(UseInterface node) {
        return null;
    }

    @Override
    public IRSeq visit(StmtDeclMulti node) {
        List<IRStmt> s = new ArrayList<>();
        for (String v : node.varsOf()) {
            s.add(initDecl(v, node.getType()));
        }
        return new IRSeq(s);
    }

    @Override
    public IRNode visit(ExprThis node) {
        return new IRTemp("this");
    }

    @Override
    public IRExpr visit(ExprFieldAccess node) {
        List<IRStmt> seq = new ArrayList<>();
        Expr obj = node.getObj();
        String fieldName = node.getField().getName();
        String className = ((TypeTTauClass) obj.getTypeCheckType()).getName();

        String t = newTemp();
        IRExpr objExpr = (IRExpr) obj.accept(this);
        seq.add(new IRMove(new IRTemp(t), objExpr));

        int idxField = this.classAllFields.get(className).indexOf(fieldName);
        Maybe<String> cWithSizeNotAvail = this.closestInterfaceParents.get(className);

        IRExpr offsetObjLayoutAccess = cWithSizeNotAvail.to(
                interfaceName -> (IRExpr) new IRName(classSizeLoc(interfaceName))
        ).otherwise(new IRConst(8));

        seq.add(new IRMove(new IRTemp(t), new IRBinOp(
                OpType.ADD, new IRTemp(t), offsetObjLayoutAccess
        )));

        IRMem fieldLoc = new IRMem(new IRBinOp(OpType.ADD,
                new IRTemp(t), new IRConst(idxField * 8)
        ));

        return new IRESeq(new IRSeq(seq), fieldLoc);
    }

    @Override
    public IRExpr visit(ExprMethodCall node) {
        List<IRStmt> seq = new ArrayList<>();
        Expr obj = node.getObj();
        ExprFunctionCall call = node.getCall();

        String t = newTemp();
        IRExpr objExpr = (IRExpr) obj.accept(this);
        seq.add(new IRMove(new IRTemp(t), objExpr));

        ArrayList<IRExpr> argsIR = new ArrayList<>();
        argsIR.add(new IRTemp(t));// add t to the list of arguments
        for (Expr arg : call.getArgs()) {
            // Add argIR to list of arguments to be passed to IRCall
            argsIR.add((IRExpr) arg.accept(this));
        }

        String className = ((TypeTTauClass) obj.getTypeCheckType()).getName();
        int numRets = getNumRets(call.getSignature().getOutput());

        // t is the obj pointer
        // [t] is the vt pointer
        // [[t] + x] is the vt pointer for the method, which is what we call
        IRExpr method = new IRMem(new IRBinOp(
                OpType.ADD,
                new IRMem(new IRTemp(t)),
                new IRConst(classMethodOffset(className, call.getName()))
        ));
        seq.add(new IRExp(new IRCall(method, numRets, argsIR)));
        return new IRESeq(new IRSeq(seq), new IRTemp(returnValueName(0)));
    }

    @Override
    public IRNode visit(ExprTernary node) {
        String t = newTemp();
        IRStmt stmtT = new IRMove(
                new IRTemp(t),
                (IRExpr) node.getTrueCase().accept(this));
        IRStmt stmtF = new IRMove(
                new IRTemp(t),
                (IRExpr) node.getFalseCase().accept(this));
        String lt = newLabel();
        String lf = newLabel();
        String lfin = newLabel();
        IRStmt condition = conditionalTranslate(node.getCond(), lt, lf);
        IRJump jmp = new IRJump(new IRName(lfin));
        return new IRESeq(new IRSeq(condition,
                new IRLabel(lt),
                stmtT,
                jmp,
                new IRLabel(lf),
                stmtF,
                new IRLabel(lfin)
        ), new IRTemp(t));
    }

    //generate _I_global_init function
    //initialize all globals (incl arrays and objects if necessary)
    //this initializes them all into named temps but immediately moves them to the global memory location
    //mostly done for code reuse
    private IRFuncDecl generateInitGlobals(List<StmtDecl> globals) {
        String funcName = "_I_global_init";
        List<IRStmt> body = new ArrayList<>();
        for (StmtDecl d : globals) {
            String gname;
            TypeTTau gtype;
            if (d instanceof StmtDeclSingle) {
                StmtDeclSingle s = (StmtDeclSingle) d;
                gname = s.getName();
                gtype = s.getDecl().getType();
                body.add(this.visit(s));
                body.add(new IRMove(
                        new IRMem(new IRName(globalName(gname, gtype))),
                        new IRTemp(gname)));
            } else if (d instanceof StmtDeclAssign) {
                StmtDeclAssign s = (StmtDeclAssign) d;
                body.add(this.visit(s));
                List<String> names = s.getNames();
                for (int i = 0; i < names.size(); i++) {
                    gname = names.get(i);
                    TypeT t = s.getDecls().get(i).typeOf();
                    if (t instanceof TypeTTau) {
                        gtype = (TypeTTau) t;
                        body.add(new IRMove(
                                new IRMem(new IRName(globalName(gname, gtype))),
                                new IRTemp(gname)));
                    }
                }
            } else if (d instanceof StmtDeclMulti) {
                List<String> names = d.varsOf();
                for (String name : names) {
                    gname = name;
                    gtype = ((StmtDeclMulti) d).getType();
                    body.add(initDecl(gname, gtype));
                    body.add(new IRMove(
                            new IRMem(new IRName(globalName(gname, gtype))),
                            new IRTemp(gname)));
                }
            }
        }
        body.add(new IRReturn());
        return new IRFuncDecl(funcName, 0, 0, new IRSeq(body));
    }


    //generate _I_init_someClass function
    //initialize size and VT (needs method/field layouts)
    private IRFuncDecl generateInitClass(ClassDefn c) {
        String funcName = "_I_init_" + escapeName(c.getName());
        String classSize = classSizeLoc(c.getName());
        String classVt = dispatchVectorLoc(c.getName());
        int n_fields = c.getFieldNames().size();
        List<IRStmt> body = new ArrayList<>();

        String l_body = newLabel();
        String l_end = newLabel();

        //if size != 0 (means already init)  jump to end
        body.add(new IRCJump(
                new IRBinOp(OpType.NEQ,
                        new IRMem(new IRName(classSize)),
                        new IRConst(0)),
                l_end, l_body));
        body.add(new IRLabel(l_body));
        try {//has superclasses
            String superClass = c.getSuperClass().get();
            String superClassSize = classSizeLoc(superClass);
            String superClassVt = dispatchVectorLoc(superClass);
            String superClassInit = "_I_init_" + escapeName(superClass);
            body.add(new IRExp(new IRCall(new IRName(superClassInit), 0)));

            String t = newTemp(); //size = superClassSize + n_fields
            body.add(new IRMove(
                    new IRTemp(t),
                    new IRMem(new IRName(superClassSize)))
            );
            body.add(new IRMove(
                    new IRTemp(t),
                    new IRBinOp(OpType.ADD,
                            new IRTemp(t),
                            new IRConst(n_fields * 8))) //no need for vt ptr because superclass already has
            );
            body.add(new IRMove(
                    new IRMem(new IRName(classSize)),
                    new IRTemp(t))
            );

            String classVT_ = newTemp();
            body.add(new IRMove(new IRTemp(classVT_), new IRName(classVt)));
            String superClassVT_ = newTemp();
            body.add(new IRMove(new IRTemp(superClassVT_), new IRName(superClassVt)));

            List<String> dvLayout = dispatchVectorLayouts.get(c.getName());
            Set<String> defMethods = c.getMethodDefns().stream()
                    .map(FuncDefn::getName)
                    .collect(Collectors.toSet());

            // num of times sup method seen
            for (int i = 0; i < dvLayout.size(); i++) {
                String methodName = dvLayout.get(i);
                if (methodName.startsWith(IMPL_DV_CELL_NAME)) {
                    String dummyClassName = methodName.substring(
                            methodName.indexOf("_") + 1
                    );
                    if (c.getName().equals(dummyClassName))
                        // our implementation for the dummy function is 0,
                        // when we encounter the function being defined
                        continue;
                    // else this dummy needs to be copied from the outside
                    // implementation
                }
                //only copy if method is not overridden
                if (!defMethods.contains(methodName)) {
                    //[classVT + offset] <- [superVT + offset]
                    body.add(new IRMove(
                            new IRMem(
                                    new IRBinOp(OpType.ADD,
                                            new IRTemp(classVT_),
                                            new IRConst(i * 8))),
                            new IRMem(
                                    new IRBinOp(OpType.ADD,
                                            new IRTemp(superClassVT_),
                                            new IRConst(i * 8)))
                    ));
                } else {
                    //[classVT + offset] <- methodLabel
                    FuncDefn m = c.getMethodDefn(methodName);
                    String mLabelName = methodName(methodName,
                            new TypeTTauClass(c.getName()),
                            (TypeSymTableFunc) m.getSignature().part2());
                    body.add(new IRMove(
                            new IRMem(
                                    new IRBinOp(OpType.ADD,
                                            new IRTemp(classVT_),
                                            new IRConst(i * 8))),
                            new IRName(mLabelName)
                    ));
                }
            }
        } catch (Maybe.NoMaybeValueException e) {//no superclasses
            body.add(new IRMove(
                    new IRMem(new IRName(classSize)),
                    new IRConst(n_fields * 8 + 8))
            );
            String t = newTemp();
            body.add(new IRMove(new IRTemp(t), new IRName(classVt)));

            List<String> dvLayout = dispatchVectorLayouts.get(c.getName());
            for (int i = 0; i < dvLayout.size(); i++) {
                String methodName = dvLayout.get(i);
                if (methodName.startsWith(IMPL_DV_CELL_NAME)) {
                    continue;
                }
                // Actual method, add to dv
                FuncDefn m = c.getMethodDefn(methodName);
                String mLabelName = methodName(methodName,
                        new TypeTTauClass(c.getName()),
                        (TypeSymTableFunc) m.getSignature().part2());
                //[classVT + offset] <- methodLabel
                body.add(new IRMove(
                        new IRMem(
                                new IRBinOp(OpType.ADD,
                                        new IRTemp(t),
                                        new IRConst(i * 8))),
                        new IRName(mLabelName)
                ));
            }
        }
        body.add(new IRLabel(l_end));
        body.add(new IRReturn());
        return new IRFuncDecl(funcName, 0, 0, new IRSeq(body));
    }

}
