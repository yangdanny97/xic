package lexer;
import java_cup.runtime.*;


enum TokenType {
    // identifiers/variables
    ID,
    UNDERSCORE,

    // literals
    INT_LIT,
    BOOL_LIT,
    STRING_LIT,
    CHAR_LIT,

    // type variables
    INT_TYPE,
    BOOL_TYPE,

    // keywords
    USE,
    IF,
    WHILE,
    ELSE,
    RETURN,
    LENGTH,

    // operators
    EQ, //=
    MINUS,
    PLUS,
    NOT,
    MULT,
    HI_MULT,// *>>
    DIV,
    MOD,
    EQEQ,// ==
    NEQ,
    GT,
    LT,
    GTEQ,
    LTEQ,
    AND,
    OR,

    // separators
    COLON,
    SEMICOLON,
    COMMA,
    LPAREN,
    RPAREN,
    LBRAC,
    RBRAC,
    LCURL,
    RCURL,
    ERROR
}

public class XiToken extends Symbol{

    private TokenType type;
    private int line;
    private int col;
    private Object value;

    XiToken(TokenType type, int line, int col, Object value) {
        super(type.ordinal(), -1, -1, value);
        this.type = type;
        this.line = line;
        this.col = col;
        this.value = value;
    }

    XiToken(TokenType type, int line, int col, int left, int right, Object value) {
        super(type.ordinal(), left, right, value);
        this.type = type;
        this.line = line;
        this.col = col;
        this.value = value;
    }

    private String format() {
        String s = value.toString();
        switch (type) {
            case STRING_LIT:
            case CHAR_LIT:
                return s.replace("\\", "\\\\")
                        .replace("\n", "\\n")
                        .replace("\r", "\\r")
                        .replace("\t", "\\t")
                        .replace("\"", "\\\"")
                        .replace("\'", "\\'");
            default: return s;
        }
    }

    public String toString() {
        String type_rep = "";
        switch (type) {
            case INT_LIT:       type_rep = "integer "; break;
            case STRING_LIT:    type_rep = "string "; break;
            case CHAR_LIT:      type_rep = "character "; break;
            case ID:            type_rep = "id "; break;
            case ERROR:         type_rep = "error:"; break;
            default:            break;
        }
        // make line and col 1-indexed
        return (line+1) + ":" + (col+1) + " " + type_rep + format();
    }

    public boolean isError() {
        return type == TokenType.ERROR;
    }

    public TokenType getType() {
        return type;
    }

    public int getLine() {
        return line;
    }

    public int getCol() {
        return col;
    }

    public Object getValue() {
        return value;
    }
}