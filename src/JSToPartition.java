import org.mozilla.javascript.Token;
import org.mozilla.javascript.Node;
import org.mozilla.javascript.ast.*;

import batch.Op;
import batch.partition.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class JSToPartition<E> {
  private final PartitionFactory<E> factory;
  private final String root;
  private boolean isReturnRemote;
  private final Map<String, DynamicCallInfo> batchFunctionsInfo;

  public JSToPartition(
      PartitionFactory<E> factory,
      String root,
      boolean isReturnRemote,
      Map<String, DynamicCallInfo> batchFunctionsInfo) {
    this.isReturnRemote = isReturnRemote;
    this.factory = factory;
    this.root = root;
    this.batchFunctionsInfo = batchFunctionsInfo;
  }

  public E exprFrom(AstNode node) {
    if (node == null) {
      return factory.Skip();
    }
    // paper TODO dates, true, false, ?:
    switch (node.getType()) {
      case Token.BLOCK:
        return exprFromBlock(node); // may be Block or Scope
      case Token.CALL:
        return exprFromFunctionCall((FunctionCall)node);
      case Token.EXPR_RESULT:
      case Token.EXPR_VOID: // TODO: LabledExpression
        return exprFromExpressionStatement((ExpressionStatement)node);
      case Token.NAME:
        return exprFromName((Name)node);
      // Binary operators
	    //   AVG, MIN, MAX, COUNT, // aggregation
	    //   ASC, DESC, // sorting
	    //   GROUP; // mapping and grouping
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
      case Token.AND:
      case Token.OR:
        return exprFromInfixExpression((InfixExpression)node);
      case Token.NOT:
        return exprFromUnaryExpression((UnaryExpression)node);
      case Token.STRING:
        return exprFromStringLiteral((StringLiteral)node);
      case Token.NUMBER:
        return exprFromNumberLiteral((NumberLiteral)node);
      case Token.ASSIGN:
        return exprFromAssignment((Assignment)node);
      case Token.FOR:
        if (node instanceof ForInLoop) {
          return exprFromForInLoop((ForInLoop)node);
        } else {
          return exprFromOther(node);
        }
      case Token.VAR:
        return exprFromVariableDeclaration(
          (VariableDeclaration)node,
          factory.Skip()
        );
      case Token.IF:
        return exprFromIfStatement((IfStatement)node);
      case Token.FUNCTION:
        return exprFromFunctionNode((FunctionNode)node);
      case Token.RETURN:
        return exprFromReturnStatement((ReturnStatement)node);
      case Token.EMPTY:
        return factory.Skip();
      case Token.BATCH:
        return exprFromBatchExpression((BatchExpression)node);
      case Token.LP:
        return exprFrom(((ParenthesizedExpression)node).getExpression());
      case Token.HOOK:
        return exprFromConditionalExpression((ConditionalExpression)node);
      case Token.GETPROP:
        return exprFromPropertyGet((PropertyGet)node);
      case Token.OBJECTLIT:
        return exprFromObjectLiteral((ObjectLiteral)node);
      default:
        System.err.println("INCOMPLETE: "+Token.typeToName(node.getType())+" "+node.getClass().getName());
        return JSUtil.noimpl();
    }
  }

  private List<E> mapExprFrom(List<AstNode> nodes) {
    List<E> exprs = new ArrayList<E>(nodes.size());
    for (AstNode node : nodes) {
      exprs.add(exprFrom(node));
    }
    return exprs;
  }

  private E convertSequence(Iterator<Node> nodes) {
    LinkedList<E> sequence = new LinkedList<E>();
    while (nodes.hasNext()) {
      AstNode node = (AstNode)nodes.next();
      switch (node.getType()) {
        case Token.VAR:
          sequence.addLast(
            exprFromVariableDeclaration(
              (VariableDeclaration)node,
              convertSequence(nodes) // Note: while loop will end since
                                     // recursive call will finish going
                                     // through the iterator
            )
          );
          break;
        default:
          sequence.addLast(exprFrom(node));
      }
    }
    // Must reduce here so that CodeModel will optimize inner
    // single branch if statements
    switch (sequence.size()) {
      case 0:
        return factory.Skip();
      case 1:
        return factory.setExtra(sequence.get(0), JSMarkers.STATEMENT, true);
      default:
        return factory.Prim(Op.SEQ, sequence);
    }
  }

  private E exprFromBlock(AstNode block) {
    return convertSequence(block.iterator());
  }

  private E exprFromExpressionStatement(ExpressionStatement statement) {
    return exprFrom(statement.getExpression());
  }

  private DynamicCallInfo getBatchFunctionInfo(String funcName) {
    if (funcName != null && batchFunctionsInfo.containsKey(funcName)) {
      return batchFunctionsInfo.get(funcName);
    } else {
      if (funcName != null) {
        throw new Error(
          "Batch call to <" + funcName + "> does not correspond to "
          + "a batch function of that name"
        );
      } else {
        // Should not occur on ast coming from the parser
        throw new Error(
          "batch keyword used incorrectly"
        );
      }
    }
  }

  private E exprFromFunctionCall(FunctionCall call) {
    AstNode target = call.getTarget();
    switch (target.getType()) {
      case Token.GETPROP:
        PropertyGet propGet = (PropertyGet)target;
        return factory.Call(
          exprFrom(propGet.getTarget()),
          propGet.getProperty().getIdentifier(),
          mapExprFrom(call.getArguments())
        );
      default:
        return JSUtil.noimpl();
    }
  }

  private E exprFromName(Name nameNode) {
    String name = nameNode.getIdentifier();
    if (root != null && name.equals(root)) { // TODO: inner scopes
      return factory.Root();
    } else if (name.equals(factory.RootName())) {
      return JSUtil.noimpl(); // TODO: avoid collisions
    } else {
      return factory.Var(name);
    }
  }

  private int condVarId = 0;
  private E falsyValueOf(E expr) {
    // javascript !x = batch (x!=x || x=="" || x==0 || x==false)
    return factory.Prim(Op.OR,
      factory.Prim(Op.NE, expr, expr),
      factory.Prim(Op.EQ, expr, factory.Data("")),
      factory.Prim(Op.EQ, expr, factory.Data(0)),
      factory.Prim(Op.EQ, expr, factory.Data(false))
    );
  }

  private E exprFromInfixExpression(InfixExpression infix) {
    Op binOp = null;
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
    }
    if (binOp != null) {
      return factory.Prim(
        binOp,
        exprFrom(infix.getLeft()),
        exprFrom(infix.getRight())
      );
    }
    E x = exprFrom(infix.getLeft());
    E y = exprFrom(infix.getRight());
    String var = "cond$" + condVarId++;
    switch (infix.getOperator()) {
      // javascript x && y = batch if(falsy(x)) {x} else {y}
      case Token.AND:
        return factory.setExtra(
          factory.Let(
            var,
            x,
            factory.If(falsyValueOf(factory.Var(var)), factory.Var(var), y)
          ),
          JSMarkers.AND,
          true
        );
      // javascript x || y = batch if(falsy(x)) {y} else {x}
      case Token.OR:
        return factory.setExtra(
          factory.Let(
            var,
            x,
            factory.If(falsyValueOf(factory.Var(var)), y, factory.Var(var))
          ),
          JSMarkers.OR,
          true
        );
      default:
        return JSUtil.noimpl();
    }
      
  }
      

  private E exprFromUnaryExpression(UnaryExpression unary) {
    switch (unary.getOperator()) {
      case Token.NOT:
        String var = "cond$" + condVarId++;
        return factory.setExtra(
          factory.Let(
            var,
            exprFrom(unary.getOperand()),
            falsyValueOf(factory.Var(var))
          ),
          JSMarkers.NOT,
          true
        );
      default:
        return JSUtil.noimpl();
    }
  }

  private E exprFromStringLiteral(StringLiteral literal) {
    return factory.Data(literal.getValue());
  }

  private E exprFromNumberLiteral(NumberLiteral literal) {
    return factory.Data((float)literal.getNumber());
  }

  private E exprFromAssignment(Assignment assignment) {
    switch (assignment.getOperator()) {
      case Token.ASSIGN:
        return factory.Assign(
          exprFrom(assignment.getLeft()),
          exprFrom(assignment.getRight())
        );
      case Token.ASSIGN_ADD:
      case Token.ASSIGN_BITAND:
      case Token.ASSIGN_BITOR:
      case Token.ASSIGN_BITXOR:
      case Token.ASSIGN_DIV:
      case Token.ASSIGN_LSH:
      case Token.ASSIGN_MOD:
      case Token.ASSIGN_MUL:
      case Token.ASSIGN_RSH:
      case Token.ASSIGN_SUB:
      case Token.ASSIGN_URSH:
        return JSUtil.noimpl();
      default:
        return JSUtil.noimpl();
    }
  }

  private E exprFromForInLoop(ForInLoop loop) {
    if (loop.isForEach()) {
      return factory.Loop(
        JSUtil.mustIdentifierOf(loop.getIterator()),
        exprFrom(loop.getIteratedObject()),
        exprFrom(loop.getBody())
      );
    } else {
      return exprFromOther(loop);
    }
  }

  private E exprFromVariableDeclaration(
      VariableDeclaration decl,
      E innerScope) {
    if (decl.isStatement()) {
      List<VariableInitializer> inits = decl.getVariables();
      E result = innerScope;
      for (VariableInitializer init : inits) {
        E initialValue = init.getInitializer() != null
          ? exprFrom(init.getInitializer())
          : JSUtil.<E>noimpl();
        result = factory.Let(
          JSUtil.identifierOf(init.getTarget()),
          initialValue,
          result
        );
      }
      return result;
    } else {
      String identifier = JSUtil.identifierOf(decl);
      if (identifier == null) {
        return exprFromOther(decl);
      } else {
        return factory.Var(identifier);
      }
    }
  }

  private E exprFromIfStatement(IfStatement ifStmt) {
    return factory.setExtra(
      factory.If(
        exprFrom(ifStmt.getCondition()),
        exprFrom(ifStmt.getThenPart()),
        exprFrom(ifStmt.getElsePart())
      ),
      JSMarkers.IF_STATEMENT,
      true
    );
  }

  private E exprFromConditionalExpression(ConditionalExpression expr) {
    return factory.If(
      exprFrom(expr.getTestExpression()),
      exprFrom(expr.getTrueExpression()),
      exprFrom(expr.getFalseExpression())
    );
  }

  private E exprFromFunctionNode(FunctionNode func) {
    switch (func.getParams().size()) {
      case 1:
        boolean parentIsReturnRemote = isReturnRemote;
        isReturnRemote = true;
        E e = factory.Fun(
          JSUtil.mustIdentifierOf(func.getParams().get(0)),
          exprFrom(func.getBody())
        );
        isReturnRemote = parentIsReturnRemote;
        return e; 
      default:
        return JSUtil.noimpl();
    }
  }

  private E exprFromReturnStatement(ReturnStatement ret) {
    if (isReturnRemote) {
      return factory.setExtra(
        exprFrom(ret.getReturnValue()),
        JSMarkers.RETURN,
        true
      );
    } else {
      return factory.Other(JSMarkers.RETURN, exprFrom(ret.getReturnValue()));
    }
  }

  private E exprFromPropertyGet(PropertyGet prop) {
    return factory.Prop(
      exprFrom(prop.getTarget()),
      prop.getProperty().getIdentifier()
    );
  }

  private E exprFromBatchExpression(BatchExpression batch) {
    switch (batch.getExpression().getType()) {
      case Token.NAME: {
        String funcName = JSUtil.identifierOf(batch.getExpression());
        DynamicCallInfo info = getBatchFunctionInfo(funcName);
        if (info.returns != Place.REMOTE) {
          throw new Error(
            "Using a batch functions as a first-class value, "
            + "requires that the batch function returns a remote value"
          );
        }
        if (info.arguments.size()!=1 || info.arguments.get(0) != Place.REMOTE) {
          throw new Error(
            "Using a batch functions as a first-class value, "
            + "requires that the batch function takes exactly 1 remote argument"
          );
        }
        return factory.Fun(
          "arg",
          factory.setExtra(
            factory.DynamicCall(
              factory.Skip(),
              funcName,
              new ArrayList<E>() {{
                add(factory.Var("arg"));
              }}
            ),
            DynamicCallInfo.TYPE_INFO_KEY,
            getBatchFunctionInfo(funcName)
          )
        );
      }
        
      case Token.CALL: {
        FunctionCall call = (FunctionCall)batch.getExpression();
        String funcName = JSUtil.mustIdentifierOf(
          call.getTarget()
        );
        return factory.setExtra(
          factory.DynamicCall(
            factory.Skip(),
            funcName,
            mapExprFrom(call.getArguments())
          ),
          DynamicCallInfo.TYPE_INFO_KEY,
          getBatchFunctionInfo(funcName)
        );
      }
      
      default:
        return JSUtil.noimpl();
    }
  }

  private E exprFromObjectLiteral(ObjectLiteral obj) {
    List<E> elements = new ArrayList<E>(obj.getElements().size());
    for (ObjectProperty elem : obj.getElements()) {
      elements.add(
        factory.Other(
          new JSMarkerWithInfo<String>(
            JSMarkers.PROPERTY,
            JSUtil.mustIdentifierOf(elem.getLeft())
          ),
          exprFrom(elem.getRight())
        )
      );
    }
    return factory.Other(JSMarkers.OBJECT, elements);
  }

  private E exprFromOther(AstNode node) {
    return JSUtil.noimpl();
  }
}
