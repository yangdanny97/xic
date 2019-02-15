package symboltable;

import ast.Type;

public interface SymbolTable {
    /**
     * Look up the type of an identifier.
     *
     * @param ID The identifier.
     * @return The type of the identifier, if it has been declared.
     * @throws NotFoundException If there is no identifier {@code ID} in the
     *         current context.
     */
    Type lookup(String ID) throws NotFoundException;
}
