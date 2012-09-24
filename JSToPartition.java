import org.mozilla.javascript.Token;
import org.mozilla.javascript.ast.*;

import batch.Op;
import batch.partition.*;

import java.util.ArrayList;
import java.util.List;

public class JSToPartition {
  private String root;

  public JSToPartition(String root) {
    this.root = root;
  }

  public <E> E exprFrom(PartitionFactory<E> f, AstNode node) {
    switch (node.getType()) {
      case Token.BLOCK:
        return exprFrom(f, (Scope)node);
      case Token.CALL:
        return exprFrom(f, (FunctionCall)node);
      case Token.EXPR_RESULT:
      case Token.EXPR_VOID:
        return exprFrom(f, (ExpressionStatement)node);
      case Token.NAME:
        return exprFrom(f, (Name)node);
      // Binary operators
      // Note: AND and OR are not listed here, since they carry a different
      // meaning in javascript
      case Token.ADD:
      case Token.SUB:
      case Token.MUL:
      case Token.DIV:
      case Token.MOD:
      case Token.NE:
      case Token.EQ:
      case Token.LT:
      case Token.GT:
      case Token.LE:
      case Token.GE:
        return exprFrom(f, (InfixExpression)node);
      //case Token.NOT: // TODO: this has slightly different meaning in javascript
      //  return exprFrom(f, (UnaryExpression)node);
      case Token.STRING:
        return exprFrom(f, (StringLiteral)node);
      case Token.NUMBER:
        return exprFrom(f, (NumberLiteral)node);
    }
    System.out.println(Token.typeToName(node.getType()));
    return f.Skip();
  }

  public <E> E exprFrom(PartitionFactory<E> f, Scope scope) {
    return f.Prim(Op.SEQ, mapExprFrom(f, scope.getStatements()));
  }

  public <E> E exprFrom(PartitionFactory<E> f, ExpressionStatement statement) {
    return exprFrom(f, statement.getExpression());
  }

  public <E> E exprFrom(PartitionFactory<E> f, FunctionCall call) {
    AstNode target = call.getTarget();
    switch (target.getType()) {
      case Token.GETPROP:
        PropertyGet propGet = (PropertyGet)target;
        return f.Call(
          exprFrom(f, propGet.getTarget()),
          propGet.getProperty().getIdentifier(),
          mapExprFrom(f, call.getArguments())
        );
    }
    return noimpl();
  }

  public <E> E exprFrom(PartitionFactory<E> f, Name nameNode) {
    String name = nameNode.getIdentifier();
    if (name.equals(root)) { // TODO: inner scopes
      return f.Var(f.RootName());
    } else if (name.equals(f.RootName())) {
      return noimpl(); // TODO: avoid collisions
    } else {
      return f.Var(name);
    }
  }

  public <E> E exprFrom(PartitionFactory<E> f, InfixExpression infix) {
    Op binOp;
    switch (infix.getOperator()) {
      case Token.ADD: binOp = Op.ADD; break;
      case Token.SUB: binOp = Op.SUB; break;
      case Token.MUL: binOp = Op.MUL; break;
      case Token.DIV: binOp = Op.DIV; break;
      case Token.MOD: binOp = Op.MOD; break;
      case Token.NE:  binOp = Op.NE;  break;
      case Token.EQ:  binOp = Op.EQ;  break;
      case Token.LT:  binOp = Op.LT;  break;
      case Token.GT:  binOp = Op.GT;  break;
      case Token.LE:  binOp = Op.LE;  break;
      case Token.GE:  binOp = Op.GE;  break;
      default:
        return noimpl();
    }
    return f.Prim(
      binOp, 
      exprFrom(f, infix.getLeft()),
      exprFrom(f, infix.getRight())
    );
  }

  public <E> E exprFrom(PartitionFactory<E> f, StringLiteral literal) {
    return f.Data(literal.getValue());
  }

  public <E> E exprFrom(PartitionFactory<E> f, NumberLiteral literal) {
    return f.Data((float)literal.getNumber());
  }

  public <E> List<E> mapExprFrom(PartitionFactory<E> f, List<AstNode> nodes) {
    List<E> exprs = new ArrayList<E>(nodes.size());
    for (AstNode node : nodes) {
      exprs.add(exprFrom(f, node));
    }
    return exprs;
  }

  private <E> E noimpl() {
    throw new RuntimeException("Not yet implemented");
  }
}