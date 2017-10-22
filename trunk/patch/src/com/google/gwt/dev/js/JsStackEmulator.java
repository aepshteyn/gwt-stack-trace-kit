/*
 * Copyright 2009 Google Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.gwt.dev.js;

import com.google.gwt.core.client.impl.StackTraceCreator;
import com.google.gwt.core.ext.BadPropertyValueException;
import com.google.gwt.core.ext.PropertyOracle;
import com.google.gwt.core.ext.SelectionProperty;
import com.google.gwt.core.ext.TreeLogger;
import com.google.gwt.core.linker.SymbolMapsLinker;
import com.google.gwt.dev.Permutation;
import com.google.gwt.dev.jjs.HasSourceInfo;
import com.google.gwt.dev.jjs.InternalCompilerException;
import com.google.gwt.dev.jjs.SourceInfo;
import com.google.gwt.dev.jjs.SourceOrigin;
import com.google.gwt.dev.jjs.ast.*;
import com.google.gwt.dev.jjs.impl.ExpressionAnalyzer;
import com.google.gwt.dev.jjs.impl.JavaToJavaScriptMap;
import com.google.gwt.dev.jjs.impl.ResolveRebinds;
import com.google.gwt.dev.js.ast.*;
import com.google.gwt.dev.js.ast.JsVars.JsVar;
import com.google.gwt.dev.util.collect.IdentityHashSet;
import com.google.gwt.dev.util.collect.Maps;

import java.io.File;
import java.io.StringReader;
import java.util.*;

/**
 * Emulates the JS stack in order to provide useful stack traces on browers that
 * do not provide useful stack information.
 *
 * @see com.google.gwt.core.client.impl.StackTraceCreator
 */
public class JsStackEmulator {

  private static final String PROPERTY_NAME = "compiler.stackMode";

  /**
   * Resets the global stack depth to the local stack index and top stack frame
   * after calls to Exceptions.caught. This is created by
   * {@link EntryExitVisitor#visit(JsCatch, JsContext)}.
   */
  private class CatchStackReset extends JsModVisitor {

    /**
     * The local stackIndex variable in the function.
     */
    private final EntryExitVisitor eeVisitor;

    public CatchStackReset(EntryExitVisitor eeVisitor) {
      this.eeVisitor = eeVisitor;
    }

    @Override
    public void endVisit(JsExprStmt x, JsContext ctx) {
      // Looking for e = caught(e);
      JsExpression expr = x.getExpression();

      if (!(expr instanceof JsBinaryOperation)) {
        return;
      }

      JsBinaryOperation op = (JsBinaryOperation) expr;
      if (!(op.getArg2() instanceof JsInvocation)) {
        return;
      }

      JsInvocation i = (JsInvocation) op.getArg2();
      JsExpression q = i.getQualifier();
      if (!(q instanceof JsNameRef)) {
        return;
      }

      JsName name = ((JsNameRef) q).getName();
      if (name == null) {
        return;
      }

      // caughtFunction is the JsFunction translated from Exceptions.caught
      if (name.getStaticRef() != caughtFunction) {
        return;
      }

      // $stackDepth = stackIndex
      SourceInfo info = x.getSourceInfo();
      JsBinaryOperation reset = new JsBinaryOperation(info,
          JsBinaryOperator.ASG, stackDepth.makeRef(info),
          eeVisitor.stackIndexRef(info));

      ctx.insertAfter(reset.makeStmt());
    }
  }

  /**
   * The EntryExitVisitor handles pushing and popping frames onto the emulated
   * stack. It will operate on exactly one JsFunction. The basic transformation
   * is to add a push operation at every function entry, and then a pop
   * operation for every statement that might be the final statement executed by
   * the function.
   * <p>
   * General stack depth entry/exit code:
   *
   * <pre>
   * function foo() {
   *   var stackIndex;
   *   $stack[stackIndex = ++$stackDepth] = foo;
   *
   *   ... do stuff ..
   *
   *   $stackDepth = stackIndex - 1;
   * }
   * </pre>
   * <p>
   * For more complicated control flows involving return statements in try
   * blocks with as associated finally block, it is necessary to introduce a
   * local variable to indicate if control flow is expected to terminate
   * normally at the end of the finally block:
   *
   * <pre>
   * var exitingEarly;
   * try {
   *   if (...) {
   *     return (exitingEarly = true, new Foo());
   *   }
   *   ...
   * } finally {
   *   ... existing finally code ..
   *   exitingEarly && $stackDepth = stackIndex - 1;
   * }
   * </pre>
   * A separate local variable is used for each try/finally nested within a
   * finally block.
   * <p>
   * Try statements without a catch block will have a catch block added to them
   * so that catch blocks are the only places where flow-control may jump to.
   * All catch blocks are altered so that the global $stackDepth variable is
   * reset to the local stack index value. This allows browser-native exceptions
   * to be created with the correct stack trace before the finally code is
   * executed with a correct stack depth.
   *
   * <pre>
   * try {
   *   foo();
   * } finally {
   *   bar();
   * }
   * </pre>
   *
   * becomes
   *
   * <pre>
   * try {
   *   foo();
   * } catch (e) {
   *   e = caught(e);
   *   $stackDepth = stackIndex;
   *   throw e;
   * } finally {
   *   bar();
   * }
   * <p>
   * Note that there is no specific handling for explicit throw statements, as
   * the stack instrumentation must also handle browser-generated exceptions
   * (e.g. <code>null.a()</code>).
   */
  private class EntryExitVisitor extends JsSuperModVisitor {

    /**
     * The name of a function-local variable to hold the invocation's slot in
     * the stack.
     */
    protected JsName stackIndex;

    protected final JsFunction currentFunction;

    /**
     * Maps finally blocks to the local variable name which is used to indicate
     * if that finally block will exit the function early. This is a map and not
     * a single value because a finally block might be nested in another exit
     * block.
     */
    private Map<JsBlock, JsName> finallyBlocksToExitVariables = Maps.create();

    /**
     * This variable will indicate the finally block that contains the last
     * statement that will be executed if an unconditional flow control change
     * were to occur within the associated try block.
     */
    private JsBlock outerFinallyBlock;

    /**
     * Used to temporary store values of expressions that could potentially
     * trigger an exception, so that we may insert instrumentations in between
     * the evaluation of the expression and the application of that value.
     */
    private JsName temp;

    /**
     * Final cleanup for any new local variables that need to be created.
     */
    private Map<JsName, JsVar> varsToAdd = null;

    /**
     * Computes some useful data about the function, most importantly
     * the set of all JS expressions where exceptions can arise.
     * This set is computed by static analysis on both the JS and Java ASTs and,
     * allows us to reduce the quantity of JS expressions whose line numbers
     * have to be recorded.
     */
    protected final ThrowabilityAnalyzer.JsFunctionAnalyzer functionAnalyzer;

    protected final boolean useLocalStackIndex;

    public EntryExitVisitor(JsFunction currentFunction) {
      this.currentFunction = currentFunction;
      functionAnalyzer = throwabilityAnalyzer.new JsFunctionAnalyzer(currentFunction);
      useLocalStackIndex = functionAnalyzer.containsTryStmt();
      if (useLocalStackIndex) {
        stackIndex = allocateLocalVariable(synthOrigin, "stackIndex", null);
      }
    }

    /**
     * Decides whether this visitor needs to run.
     * @return false if JsFunctionAnalyzer that was run by the constructor
     * did not find any expressions that might throw exceptions in this function,
     * nor any try blocks. Otherwise true.
     */
    public boolean shouldModifyThisFunction() {
      return !functionAnalyzer.functionCanNotThrow() || functionAnalyzer.containsTryStmt();
    }

    /**
     * If the visitor is exiting the current function's block, add additional
     * local variables and the final stack-pop instructions.
     */
    @Override
    public void endVisit(JsBlock x, JsContext ctx) {
      if (x == currentFunction.getBody()) {
        List<JsStatement> statements = x.getStatements();

        // Add any needed variables
        if (varsToAdd != null) {
          JsVars vars;
          if (statements.get(0) instanceof JsVars) {
            vars = (JsVars)statements.get(0);
          }
          else {
            vars = new JsVars(currentFunction.getSourceInfo());
            statements.add(0, vars);
          }
          if (useLocalStackIndex) {
            // the stackIndex variable must go first so its initialization expr
            // (i.e. the stack push) is evaluated before anything else
            JsVar stackIndexVar = varsToAdd.remove(stackIndex);
            stackIndexVar.setInitExpr(push(currentFunction));
            vars.add(0, stackIndexVar);
          }
          // add the rest of the variables
          for (JsVar var : varsToAdd.values()) {
            vars.add(var);
          }
        }
        else {
          assert !useLocalStackIndex;
        }
        // Add the entry code if we haven't already done so
        if (!useLocalStackIndex) {
          statements.add(0, push(currentFunction).makeStmt());
        }
        addPopAtEndOfBlock(x, false);
      }
    }

    @Override
    public void endVisit(JsReturn x, JsContext ctx) {
      if (outerFinallyBlock != null) {
        // There is a finally block, so we need to set the early-exit flag
        JsBinaryOperation asg = new JsBinaryOperation(x.getSourceInfo(),
            JsBinaryOperator.ASG, earlyExitRef(outerFinallyBlock),
            JsBooleanLiteral.get(true));
        if (x.getExpr() == null) {
          if (ctx.canInsert()) {
            // exitingEarly = true; return;
            ctx.insertBefore(asg.makeStmt());
          } else {
            // {exitingEarly = true; return;}
            JsBlock block = new JsBlock(x.getSourceInfo());
            block.getStatements().add(asg.makeStmt());
            block.getStatements().add(x);
            ctx.replaceMe(block);
          }
        } else {
          // return (exitingEarly = true, expr);
          JsBinaryOperation op = makeComma(x.getSourceInfo(), asg, x.getExpr());
          x.setExpr(op);
        }
      } else {
        if (x.getExpr() != null && !functionAnalyzer.canNotThrowRecursive(x.getExpr())) {
          // temp = expr; pop(); return temp;
          SourceInfo info = x.getSourceInfo();
          JsBinaryOperation asg = new JsBinaryOperation(info,
              JsBinaryOperator.ASG, tempRef(info), x.getExpr());
          x.setExpr(tempRef(info));
          pop(x, asg, ctx);
        } else {
          // Otherwise, pop the stack frame
          pop(x, null, ctx);
        }
      }
    }

    /**
     * We want to look at unaltered versions of the catch block, so this is a
     * <code>visit<code> and not a <code>endVisit</code>.
     */
    @Override
    public boolean visit(JsCatch x, JsContext ctx) {
      // Reset the stack depth to the local index
      new CatchStackReset(this).accept(x);
      return true;
    }

    @Override
    public boolean visit(JsFunction x, JsContext ctx) {
      // Will be taken care of by the InstrumentAllFunctions visitor
      return false;
    }

    @Override
    public void endVisit(JsFunction x, JsContext ctx) {
      // Will be taken care of by the InstrumentAllFunctions visitor
    }

    @Override
    public boolean visit(JsTry x, JsContext ctx) {
      assert useLocalStackIndex;
      /*
       * Only the outermost finally block needs special treatment; try/finally
       * block within try blocks do not receive special treatment.
       */
      JsBlock finallyBlock = x.getFinallyBlock();
      if (finallyBlock != null && outerFinallyBlock == null) {
        outerFinallyBlock = finallyBlock;

        // Manual traversal
        accept(x.getTryBlock());

        if (x.getCatches().isEmpty()) {
          JsCatch c = makeSyntheticCatchBlock(x);
          x.getCatches().add(c);
        }
        assert x.getCatches().size() >= 1;
        acceptList(x.getCatches());

        // Exceptions in the finally block just exit the function
        assert outerFinallyBlock == finallyBlock;
        outerFinallyBlock = null;
        accept(finallyBlock);

        // Stack-pop instruction
        addPopAtEndOfBlock(finallyBlock, true);

        // Clean up entry after adding pop instruction
        finallyBlocksToExitVariables = Maps.remove(
            finallyBlocksToExitVariables, finallyBlock);
        return false;
      }

      // Normal visit
      return true;
    }

    /**
     * Allocate a function-local variable.
     */
    protected JsName allocateLocalVariable(SourceInfo info, String shortName, String longSuffix) {
      String longName = "JsStackEmulator_" + shortName;
      if (longSuffix != null) {
        longName += longSuffix;
      }
      JsName jsName = currentFunction.getScope().declareName(longName, shortName);
      JsVar var = new JsVar(info, jsName);
      if (varsToAdd == null) {
        // using LHM to preserve ordering of vars between builds
        varsToAdd = new LinkedHashMap<JsName, JsVar>();
      }
      varsToAdd.put(jsName, var);
      return jsName;
    }

    /**
     * Create a reference to the function-local stack index variable, possibly
     * allocating it if needed.  If the function doesn't have any try statements,
     * then we don't need a local stackIndex variable, and can simply use
     * the global stackDepth variable instead.
     */
    protected JsNameRef stackIndexRef(SourceInfo info) {
      return stackIndex != null ? stackIndex.makeRef(info) : stackDepth.makeRef(info);
    }

    /**
     * Code-gen function for generating the stack-pop statement at the end of a
     * block. A no-op if the last statement is a <code>throw</code> or
     * <code>return</code> statement, since it will have already caused a pop
     * statement to have been added.
     *
     * @param checkEarlyExit if <code>true</code>, generates
     *          <code>earlyExit && pop()</code>
     */
    private void addPopAtEndOfBlock(JsBlock x, boolean checkEarlyExit) {
      JsStatement last = x.getStatements().isEmpty() ? null
          : x.getStatements().get(x.getStatements().size() - 1);
      if (last instanceof JsReturn || last instanceof JsThrow) {
        /*
         * Don't need a pop after a throw or break statement. This is an
         * optimization for the common case of returning a value as the last
         * statement, but doesn't cover all flow-control cases.
         */
        return;
      } else if (checkEarlyExit && !finallyBlocksToExitVariables.containsKey(x)) {
        /*
         * No early-exit variable was ever allocated for this block. This means
         * that the variable can never be true, and thus the stack-popping
         * expression will never be executed.
         */
        return;
      }

      // pop()
      SourceInfo info = x.getSourceInfo();
      JsExpression op = pop(info);

      if (checkEarlyExit) {
        // earlyExit && pop()
        op = new JsBinaryOperation(info, JsBinaryOperator.AND, earlyExitRef(x),
            op);
      }

      x.getStatements().add(op.makeStmt());
    }

    /**
     * Generate a name reference to the early-exit variable for a given block,
     * possibly allocating a new variable.
     */
    private JsNameRef earlyExitRef(JsBlock x) {
      JsName earlyExitName = finallyBlocksToExitVariables.get(x);
      if (earlyExitName == null) {
        earlyExitName = allocateLocalVariable(x.getSourceInfo(), "exitingEarly", String.valueOf(finallyBlocksToExitVariables.size()));
        finallyBlocksToExitVariables = Maps.put(finallyBlocksToExitVariables,
            x, earlyExitName);
      }
      return earlyExitName.makeRef(x.getSourceInfo());
    }

    private JsCatch makeSyntheticCatchBlock(JsTry x) {
      /*
       * catch (e) { e = caught(e); throw e; }
       */
      SourceInfo info = x.getSourceInfo();

      JsCatch c = new JsCatch(info, currentFunction.getScope(), "e");
      JsName paramName = c.getParameter().getName();

      // caught(e)
      JsInvocation caughtCall = new JsInvocation(info);
      caughtCall.setQualifier(caughtFunction.getName().makeRef(info));
      caughtCall.getArguments().add(paramName.makeRef(info));

      // e = caught(e)
      JsBinaryOperation asg = new JsBinaryOperation(info, JsBinaryOperator.ASG,
          paramName.makeRef(info), caughtCall);

      // throw e
      JsThrow throwStatement = new JsThrow(info, paramName.makeRef(info));

      JsBlock body = new JsBlock(info);
      body.getStatements().add(asg.makeStmt());
      body.getStatements().add(throwStatement);
      c.setBody(body);
      return c;
    }

    /**
     * Pops the stack frame.
     *
     * @param x the statement that will cause the pop
     * @param ctx the visitor context
     */
    private void pop(JsStatement x, JsExpression expr, JsContext ctx) {
      // $stackDepth = stackIndex - 1
      SourceInfo info = x.getSourceInfo();

      JsExpression op = pop(info);

      if (ctx.canInsert()) {
        if (expr != null) {
          ctx.insertBefore(expr.makeStmt());
        }
        ctx.insertBefore(op.makeStmt());
      } else {
        JsBlock block = new JsBlock(info);
        if (expr != null) {
          block.getStatements().add(expr.makeStmt());
        }
        block.getStatements().add(op.makeStmt());
        block.getStatements().add(x);
        ctx.replaceMe(block);
      }
    }

    /**
     * Decrement the $stackDepth variable.
     */
    private JsExpression pop(SourceInfo info) {
      if (useLocalStackIndex) {
        // $stackDepth = stackIndex - 1;
        return new JsBinaryOperation(info, JsBinaryOperator.ASG,
            stackDepth.makeRef(info),
            new JsBinaryOperation(info, JsBinaryOperator.SUB,
                stackIndexRef(info), new JsNumberLiteral(info, 1)));
      }
      // $stackDepth--;
      return new JsPostfixOperation(info, JsUnaryOperator.DEC, stackDepth.makeRef(info));
    }

    /**
     * Create an expression that pushes the current function onto the stack.
     * The value of this expression will be assigned to a local variable
     * stackIndex, if the condition useLocalStackIndex is true:
     * (stack[++stackDepth] = currentFunctionRef, stackDepth) // if useLocalStackIndex
     * or
     * stack[++stackDepth] = currentFunctionRef
     * @return an expression whose value is the new stackDepth if
     * useLocalStackIndex is set.
     */
    protected JsExpression push(HasSourceInfo x) {
      SourceInfo info = x.getSourceInfo();
      return makeStackPushCode(info, makeCurrentFunctionRef(info), useLocalStackIndex);
    }

    protected JsExpression makeCurrentFunctionRef(SourceInfo info) {
      JsExpression currentFunctionRef;
      if (currentFunction.getName() == null) {
        // Anonymous
        currentFunctionRef = JsNullLiteral.INSTANCE;
      } else {
        currentFunctionRef = currentFunction.getName().makeRef(info);
      }
      return currentFunctionRef;
    }

    protected JsNameRef tempRef(SourceInfo info) {
//      if (temp == null) {
//        temp = allocateLocalVariable(info, "temp", null);
//      }
//      return temp.makeRef(info);
      return globalTemp.makeRef(info);
    }

  }

  /**
   * Creates a visitor to instrument each JsFunction in the jsProgram.
   */
  private class InstrumentAllFunctions extends JsVisitor {

    private JsFunction capStackDepthFunction;

    private InstrumentAllFunctions() {
      initCapStackDepthFunction();
    }

    /**
     * Creates a global function that looks like:
     * <pre>
     * function capStackDepth(closure, stackIndex) {
     *   if (stackDepthCap == null)
     *     stackDepthCap = stackIndex
     *   try {
     *     return closure();
     *   }
     *   finally {
     *     if (stackDepthCap == stackIndex)
     *       stackDepthCap = null;
     *   }
     * }
     * </pre>
     */
    private void initCapStackDepthFunction() {
      if (capStackDepthFunction != null)
        return;
      StringBuilder code = new StringBuilder()
          .append("function capStackDepth(stackIndex, closure) {\n")
          .append("  if ($stackDepthCap == null)\n")
          .append("    $stackDepthCap = stackIndex;\n")
          .append("  try {\n")
          .append("    return closure();\n")
          .append("  }\n")
          .append("  finally {\n")
          .append("    if ($stackDepthCap == stackIndex)\n")
          .append("      $stackDepthCap = null;\n")
          .append("  }\n")
          .append("}");
      capStackDepthFunction = createGlobalFunction(code.toString());
      // must resolve the identifiers after parsing the above code
      final JsScope scope = capStackDepthFunction.getScope();
      new JsModVisitor() {
        @Override
        public void endVisit(JsNameRef x, JsContext ctx) {
          if (x.getQualifier() == null) {
            x.resolve(scope.findExistingName(x.getIdent()));
          }
        }
      }.accept(capStackDepthFunction);
    }


    @Override
    public void endVisit(JsFunction x, JsContext ctx) {
      if (!x.getBody().getStatements().isEmpty()) {
        JsName fnName = x.getName();
        JMethod method = jjsmap.nameToMethod(fnName);
        /**
         * Do not instrument immortal types because they are potentially
         * evaluated before anything else has been defined.
         */
        if (method != null && jprogram.immortalCodeGenTypes.contains(method.getEnclosingType())) {
          return;
        }

        // possibly instrument this function
        EntryExitVisitor modVisitor = null;
        if (x != caughtFunction && x != capStackDepthFunction) {
          // do not instrument these functions because we don't want them to appear in stack traces
          modVisitor = createModVisitor(x);
          if (modVisitor != null && modVisitor.shouldModifyThisFunction())
            modVisitor.accept(x.getBody());
        }

        // replace all exception instantiations in this function
        JsName stackIndexVarName = stackDepth;
        if (modVisitor != null && modVisitor.stackIndex != null) {
          stackIndexVarName = modVisitor.stackIndex;
        }
        new ReplaceExceptionInstantiations(x, capStackDepthFunction, stackIndexVarName).accept(x.getBody());
      }
    }

    protected EntryExitVisitor createModVisitor(JsFunction x) {
      return new EntryExitVisitor(x);
    }
  }

  /**
   * Creates a visitor to instrument each JsFunction in the jsProgram with line
   * numbers in addition to pushing the function name onto the stack.
   */
  private class InstrumentAllFunctionsWithLineNumbers extends InstrumentAllFunctions {

    private JsFunction stackPushFunction;

    private InstrumentAllFunctionsWithLineNumbers() {
      initStackPushFunction();
    }

    @Override
    protected EntryExitVisitor createModVisitor(JsFunction x) {
      // do not instrument our artificial support function
      if (x == stackPushFunction) {
        return null;
      }
      return new LocationVisitor(x);
    }

    /**
     * Creates a global function that looks like:
     * <pre>
     * function stackPush(currentFunction) {
     *   stack[++stackDepth] = currentFunction;
     *   location[stackDepth] = null;
     *   return stackDepth;
     * }
     * </pre>
     */
    private void initStackPushFunction() {
      if (stackPushFunction != null)
        return;
      JsScope programScope = jsProgram.getScope();
      stackPushFunction = new JsFunction(synthOrigin, programScope,
          programScope.declareName("$JsStackEmulator_push", "$stackPush"));
      JsName currentFunctionParamName = stackPushFunction.getScope().declareName("currentFunction");
      stackPushFunction.getParameters().add(new JsParameter(synthOrigin, currentFunctionParamName));
      JsBlock body = new JsBlock(synthOrigin);
      stackPushFunction.setBody(body);
      List<JsStatement> stmts = body.getStatements();
      //   stack[++stackDepth] = currentFunction;
      stmts.add(makeStackPushCode(synthOrigin, currentFunctionParamName.makeRef(synthOrigin), false).makeStmt());
      //   location[stackDepth] = null;
      JsNameRef stackDepthRef = stackDepth.makeRef(synthOrigin);
      stmts.add(
          new JsBinaryOperation(synthOrigin, JsBinaryOperator.ASG,
              new JsArrayAccess(synthOrigin, lineNumbers.makeRef(synthOrigin), stackDepthRef),
              JsNullLiteral.INSTANCE).makeStmt());
      //   return stackDepth;
      stmts.add(new JsReturn(synthOrigin, stackDepthRef));
      // insert the function into the program
      jsProgram.getGlobalBlock().getStatements().add(0, stackPushFunction.makeStmt());
    }


    /**
     * Extends EntryExit visitor to record location information in the AST. This
     * visitor will modify every JsExpression that can potentially result in a
     * change of flow control with file and line number data.
     * <p>
     * This simply generates code to set entries in the <code>$location</code>
     * stack, parallel to <code>$stack</code>:
     *
     * <pre>
     * ($location[stackIndex] = 'Foo.java:' + 42, expr);
     * </pre>
     *
     * Inclusion of file names is dependent on the value of the
     * {@link JsStackEmulator#recordFileNames} field.
     */
    private class LocationVisitor extends EntryExitVisitor {
      private String lastFile;
      private int lastLine;

      /**
       * Nodes in this set are used in a context that expects a reference, not
       * just an arbitrary expression. For example, <code>delete</code> takes a
       * reference. These are tracked because it wouldn't be safe to rewrite
       * <code>delete foo.bar</code> to <code>delete (line='123',foo).bar</code>.
       */
      private final Set<JsNode> blacklist = new IdentityHashSet<JsNode>();

      /**
       * For perfect accuracy, we might need to record the line number of an
       * expression before it's evaluated as well as restore the line number of
       * the parent expression after it's been evaluated.  This stack holds
       * all the parents in the current context.
       */
      private ArrayDeque<Parent> parentStack = new ArrayDeque<Parent>();

      private class Parent extends ParentStackElement {
        /**
         * If any children of expr modify the location, they must
         * set this field to indicate that expr's location should be restored.
         */
        boolean restoreLocation;

        public Parent(HasSourceInfo astNode) {
          super(astNode);
        }
      }

      /**
       * The name of the Java file where the function that's being processed by
       * this visitor was orginially defined. If any of the code in this function
       * comes from a different file (e.g. an inlined method), that file name
       * must be explicitly pushed onto the stack along with the line number,
       * regardless of the value of the {@link JsStackEmulator#recordFileNames} field.
       */
      private String functionFileName;

      public LocationVisitor(JsFunction function) {
        super(function);
        resetPosition();
        functionFileName = function.getSourceInfo().getFileName();
      }

      // NOTE: the visit and endVisit methods have to be symmetric (because we need to push and pop stuff off the locationStack),
      // so let's try to keep the declarations for the same type next to each other in the source code

      /**
       * Unless there's an explicit override stating otherwise, we consider
       * all sublcasses of JsExpression eligible for instrumentation. 
       */
      @Override
      public boolean visit(JsExpression x, JsContext ctx) {
        parentStack.push(new Parent(x));
        return true;
      }

      @Override
      public void endVisit(JsExpression x, JsContext ctx) {
        Parent popped = parentStack.pop();
        assert popped.getAstNode() == x;
        maybeRecord(x, ctx);
      }

      @Override
      public boolean visit(JsBinaryOperation x, JsContext ctx) {
        if (x.getOperator().isAssignment() && isInvocationOfCaught(x.getArg2()))
          return false; // skip statements like $e0 = caught_0($e0);
        return super.visit(x, ctx);
      }

      @Override
      public void endVisit(JsBinaryOperation x, JsContext ctx) {
        if (x.getOperator().isAssignment() && isInvocationOfCaught(x.getArg2()))
          return; // skip statements like $e0 = caught_0($e0);
        super.endVisit(x, ctx);
      }

      @Override
      public boolean visit(JsInvocation x, JsContext ctx) {
        if (isInvocationOfCaught(x))
          return false;
        // we can't instrument the qualifier just in case the invocation is for
        // a method of an object.  Example:
        // before: "abc".indexOf("b")
        // after: (recordLocationExpr, "abc").indexOf("b")  // will throw "TypeError: String.prototype.indexOf called on null or undefined"
        blacklist.add(x.getQualifier());
        return super.visit(x, ctx);
      }

      @Override
      public void endVisit(JsInvocation x, JsContext ctx) {
        if (isInvocationOfCaught(x))
          return;
        blacklist.remove(x.getQualifier());
        super.endVisit(x, ctx);
      }

      /**
       * We want to skip invocations of the artificial Exceptions.caught function,
       * because inserting line numbers here will make the stack traces more confusing.
       */
      private boolean isInvocationOfCaught(JsExpression x) {
        if (x instanceof JsInvocation) {
          JsExpression qualifier = ((JsInvocation)x).getQualifier();
          return qualifier instanceof JsNameRef &&
              ((JsNameRef)qualifier).getName() == caughtFunction.getName();
        }
        return false;
      }

      @Override
      public boolean visit(JsPrefixOperation x, JsContext ctx) {
        if (x.getOperator() == JsUnaryOperator.DELETE
            || x.getOperator() == JsUnaryOperator.TYPEOF) {
          blacklist.add(x.getArg());
        }
        return super.visit(x, ctx);
      }

      @Override
      public void endVisit(JsPrefixOperation x, JsContext ctx) {
        super.endVisit(x, ctx);
        blacklist.remove(x.getArg());
      }

      // the rest of the visit methods are overridden just to correct the traversal order

      /**
       * This is essentially a hacked-up version of JsFor.traverse to account for
       * flow control differing from visitation order. It resets lastFile and
       * lastLine before the condition and increment expressions in the for loop
       * so that location data will be recorded correctly.
       */
      @Override
      public boolean visit(JsFor x, JsContext ctx) {
        if (x.getInitExpr() != null) {
          x.setInitExpr(accept(x.getInitExpr()));
        } else if (x.getInitVars() != null) {
          x.setInitVars(accept(x.getInitVars()));
        }

        if (x.getCondition() != null) {
          resetPosition();
          x.setCondition(accept(x.getCondition()));
        }

        if (x.getIncrExpr() != null) {
          resetPosition();
          x.setIncrExpr(accept(x.getIncrExpr()));
        }

        accept(x.getBody());
        return false;
      }

      /**
       * Similar to JsFor, this resets the current location information before
       * evaluating the condition.
       */
      @Override
      public boolean visit(JsWhile x, JsContext ctx) {
        resetPosition();
        x.setCondition(accept(x.getCondition()));
        accept(x.getBody());
        return false;
      }

      /**
       * Strips off the final name segment.
       */
      private String baseName(String fileName) {
        // Try the system path separator
        int lastIndex = fileName.lastIndexOf(File.separator);
        if (lastIndex == -1) {
          // Otherwise, try URL path separator
          lastIndex = fileName.lastIndexOf('/');
        }
        if (lastIndex != -1) {
          return fileName.substring(lastIndex + 1);
        } else {
          return fileName;
        }
      }

      /**
       * Determines whether the given expression, x, could legally be replaced with
       * $location[stackIndex] = lineNumberOfX, x
       */
      private boolean couldReplaceWithComma(JsExpression x, JsContext ctx) {
        if (ctx.isLvalue()) {
          // Assignments to comma expressions aren't legal
          return false;
        } else if (blacklist.contains(x)) {
          // Don't modify references into non-references
          return false;
        }
        return true; // it's legal to record in all other situations
      }

      /**
       * Determines whether the given expression, x, should be replaced with
       * $location[stackIndex] = lineNumberOfX, x
       */
      private boolean shouldRecord(JsExpression x, JsContext ctx) {
        SourceInfo info = x.getSourceInfo();
        if (info.getStartLine() == 0) {
          // "Unknown" source origin: pointless to record line number
          return false;
        }
        if (!couldReplaceWithComma(x, ctx)) {
          // replacing x with a comma expression wouldn't be legal
          return false;
        }
        if (info.getStartLine() == lastLine && info.getFileName().equals(lastFile)) {
          // Same location; ignore
          return false;
        }
        if (!functionAnalyzer.wasVisited(x)) {
          // don't instrument expressions that we have artificially created since functionThrowabilityAnalyzer was run
          return false;
        }
        if (functionAnalyzer.canNotThrow(x)) {
          // don't instrument expressions that can't throw an exception (as determined by static analysis)
          return false;
        }
        return true; // should record the line number of x in all other situations
      }

      /**
       * Returns an expression that records the line number of x, or null, if
       * the line number of x should not be recorded.  For example:
       * 1) If the line number comes from the same file as the function.
       * <pre>
       * $location[stackIndex] = 44
       * </pre>
       * 2) If not the same file and recordFileNames not set:
       * <pre>
       * $location[stackIndex] = '12:44'  // '12' is the obfuscated filename 
       * </pre>
       * 3) if recordFileNames is set:
       * <pre>
       * $location[stackIndex] = 'Example.java:' + 44 
       * </pre>
       */
      private JsExpression maybeCreateLocationRecordingExpression(JsExpression x, JsContext ctx) {
        if (!shouldRecord(x, ctx))
          return null;
        SourceInfo info = x.getSourceInfo();
        int line = lastLine = info.getStartLine();
        String file = lastFile = info.getFileName();
        JsExpression location;
        if (recordFileNames) {
          // record unobfuscated filename: 'fileName:' + lineNumber
          location = new JsBinaryOperation(info, JsBinaryOperator.ADD,
              new JsStringLiteral(info, baseName(file) + ":"),
              new JsNumberLiteral(info, line));
        }
        else if (currentFunction.getName() != null && functionFileName.equals(file)) {
          // we don't need to record this expression's filename becuase it can
          // be derived via symbol map from the function's filename
          location = new JsNumberLiteral(info, line);
        }
        else {
          // we must explicitly record this filename along with the line number, since it differs from the function's filename
          // we can obfuscate the filename, since recordFileNames is not set
          location = fileNameObfuscator.generateLocationExpression(info, line, baseName(file));
        }
        // $location[stackIndex] = location
        return assignToLocation(info, location);
      }

      private JsBinaryOperation assignToLocation(SourceInfo info, JsExpression value) {
        JsArrayAccess access = new JsArrayAccess(info, lineNumbers.makeRef(info), stackIndexRef(info));
        return new JsBinaryOperation(info, JsBinaryOperator.ASG, access, value);
      }

      /**
       * Possibly replaces the given expression, x, with
       *
       *  ($location[stackIndex] = X, x) // where X is the line number of x
       *
       * Also, if there are any parent expressions on the current evaluation stack,
       * and any of their line numbers differ from x, this method restores the topmost
       * such line number as well, if needed. For example, if X, which is the
       * line number of x,  needs to be recorded, and its parent expression's
       * line number is P (!= X), then x is replaced with:
       *
       * (temp = ($location[stackIndex] = X, x), ($location[stackIndex] = P, temp))
       *
       * This pretty much emulates the actual execution order of the code, to make sure
       * the line number of the next expression to be evaluated is always the current
       * line number.
       *
       * NOTE: this method will always be called at the end of the traversal for x
       * iff expressions of x's type need to have their locations recorded
       */
      private void maybeRecord(JsExpression x, JsContext ctx) {
        if (!couldReplaceWithComma(x, ctx)) {
          return;  // it wouldn't be legal to replace x with anything
        }

        SourceInfo sourceInfo = x.getSourceInfo();
        JsExpression replacement = x;
        {
          // if we need to record x's line number, then replace x with ($location[stackIndex] = X, x)
          JsExpression xLocationAssignment = maybeCreateLocationRecordingExpression(x, ctx);
          if (xLocationAssignment != null) {
            // $location[stackIndex] = X, x
            replacement = makeComma(sourceInfo, xLocationAssignment, replacement);
            // since x modified the location, we must remember to restore the locations of all x's parents
            for (Parent parent : parentStack) {
              parent.restoreLocation = true;
            }
          }
        }
        // If x or any of its children have modified the location, we must clean up
        // after x has been evaluated by restoring the line number of x's parent,
        // if it was any different.
        for (Parent parent : parentStack) {
          // check each parent on the stack to see if its location needs to be restored
          if (parent.restoreLocation) {
            JsExpression parentLocationAssignment = maybeCreateLocationRecordingExpression((JsExpression)parent.getAstNode(), ctx);
            if (parentLocationAssignment != null) {
              // we need to introduce a temp variable to make sure that the parent's location is restored *after* x has been evaluated
              // (temp = replacement, ($location[stackIndex] = P, temp))
              replacement = introduceTempVar(replacement, parentLocationAssignment, sourceInfo);
              break; // we only need to restore the nearest parent location
            }
          }
        }
        if (replacement != x)
          ctx.replaceMe(replacement);
      }

      /**
       * Introduces a temporary variable for expr1 to allow it to be evaulated,
       * and used as the final value while also evaluating expr2
       * @return (temp = expr1, (expr2, temp))
       */
      private JsExpression introduceTempVar(JsExpression expr1, JsExpression expr2, SourceInfo info) {
        JsNameRef tempVariableRef = tempRef(info);
        // temp = arg
        JsBinaryOperation tempAsignment = new JsBinaryOperation(info,
            JsBinaryOperator.ASG, tempVariableRef, expr1);
        // (location[stackIndex]=A, temp)
        JsExpression innerComma = makeComma(info, expr2, tempVariableRef);
        // now combine the two
        return makeComma(info, tempAsignment, innerComma);
      }

      /**
       * stackPush(currentFunction)
       * @return an expression whose value is the new stackDepth if
       * useLocalStackIndex is set.
       */
      @Override
      protected JsExpression push(HasSourceInfo x) {
        // we want to override the parent method to also null out the location
        // to reduce code size, we've created a separate function that combines
        // these two operations; we want to invoke this function called stackPush
        // stackPush(currentFunction)
        SourceInfo info = x.getSourceInfo();
        JsInvocation stackPushInvocation = new JsInvocation(x.getSourceInfo());
        stackPushInvocation.setQualifier(stackPushFunction.getName().makeRef(info));
        stackPushInvocation.getArguments().add(makeCurrentFunctionRef(info));
        return stackPushInvocation;
      }

      private void resetPosition() {
        lastFile = "";
        lastLine = -1;
      }

    }

  }

  private static class ParentStackElement {
    private HasSourceInfo astNode;

    public ParentStackElement(HasSourceInfo astNode) {
      this.astNode = astNode;
    }

    public HasSourceInfo getAstNode() {
      return astNode;
    }
  }


  /** Convenienve factory method for constructing JS comma expressions
   * @param sourceInfo
   * @param arg1
   * @param arg2*/
  private static JsBinaryOperation makeComma(SourceInfo sourceInfo, JsExpression arg1, JsExpression arg2) {
    return new JsBinaryOperation(sourceInfo, JsBinaryOperator.COMMA, arg1, arg2);
  }

  /**
   * The StackTraceCreator code refers to identifiers defined in JsRootScope,
   * which are unobfuscatable. This visitor replaces references to those symbols
   * with references to our locally-defined, obfuscatable names.
   */
  private class ReplaceUnobfuscatableNames extends JsModVisitor {
    // See JsRootScope for the definition of these names
    private final JsName rootLineNumbers = JsRootScope.INSTANCE.findExistingUnobfuscatableName("$location");
    private final JsName rootStack = JsRootScope.INSTANCE.findExistingUnobfuscatableName("$stack");
    private final JsName rootStackDepth = JsRootScope.INSTANCE.findExistingUnobfuscatableName("$stackDepth");
    private final JsName rootStackDepthCap = JsRootScope.INSTANCE.findExistingUnobfuscatableName("$stackDepthCap");

    @Override
    public void endVisit(JsNameRef x, JsContext ctx) {
      JsName name = x.getName();
      JsNameRef newRef = null;

      if (name == rootStack) {
        newRef = stack.makeRef(x.getSourceInfo());
      } else if (name == rootStackDepth) {
        newRef = stackDepth.makeRef(x.getSourceInfo());
      } else if (name == rootStackDepthCap) {
        newRef = stackDepthCap.makeRef(x.getSourceInfo());
      } else if (name == rootLineNumbers) {
        newRef = lineNumbers.makeRef(x.getSourceInfo());
      }

      if (newRef == null) {
        return;
      }

      assert x.getQualifier() == null;
      ctx.replaceMe(newRef);
    }
  }

  /**
   * Replaces every instantiation of a Throwable subclass with an invocation of
   * our artificial capStackDepth function, which ensures that the exception
   * stack trace setup code doesn't appear in the actual stack traces.
   * Example:
   * <pre>
   *   new Exception(a, b, c)
   * </pre>
     * will be replaced with
   * <pre>
   *   capStackDepth(stackIndex, function() { new Exception(a, b, c) })
   * </pre>
   */
  private class ReplaceExceptionInstantiations extends JsModVisitor {
    private JsFunction currentFunction;
    private JsFunction functionToInvoke;
    private JsName optionalArg;

    private ReplaceExceptionInstantiations(JsFunction currentFunction, JsFunction functionToInvoke, JsName optionalArg) {
      this.currentFunction = currentFunction;
      this.functionToInvoke = functionToInvoke;
      this.optionalArg = optionalArg;
    }

    @Override
    public void endVisit(JsNew x, JsContext ctx) {
      JsExpression ctor = x.getConstructorExpression();
      if (ctor instanceof JsNameRef) {
        JsName ctorName = ((JsNameRef)ctor).getName();
        // NOTE: for some reason jjsmap.nameToType doesn't work here
        JMethod method = jjsmap.nameToMethod(ctorName);
        if (method != null) {
          JDeclaredType classType = method.getEnclosingType();
          while (classType != null) {
            if ("Throwable".equals(classType.getShortName())) {
              // x is instantiating a subclass of throwable
              ctx.replaceMe(makeCapStackDepthInvocation(x));
              break;
            }
            classType = classType.getSuperClass();
          }
        }
      }
    }

    /**
     * @return
     * <pre>
     *   capStackDepth(stackIndex, function() { return x; })
     * </pre>
     */
    private JsExpression makeCapStackDepthInvocation(JsExpression x) {
      SourceInfo info = x.getSourceInfo();
      JsInvocation inv = new JsInvocation(info);
      inv.setQualifier(functionToInvoke.getName().makeRef(info));
      if (optionalArg != null)
        inv.getArguments().add(optionalArg.makeRef(info));
      JsFunction closure = new JsFunction(info, currentFunction.getScope());
      inv.getArguments().add(closure);
      JsBlock body = new JsBlock(info);
      closure.setBody(body);
      body.getStatements().add(new JsReturn(info, x));
      return inv;
    }

  }


  /**
   * Corresponds to property compiler.stackMode in EmulateJsStack.gwt.xml
   * module.
   */
  public enum StackMode {
    STRIP, NATIVE, EMULATED;
  }

  public static SymbolMapsLinker.FilenameDeobfuscationArtifact exec(JProgram jprogram, JsProgram jsProgram,
      PropertyOracle[] propertyOracles, JavaToJavaScriptMap jjsmap, Permutation permutation) {
    StackMode stackMode = getStackMode(propertyOracles);
    switch (stackMode) {
      case EMULATED:
        return (new JsStackEmulator(jprogram, jsProgram, propertyOracles, jjsmap, permutation.getId())).execImpl();
      case NATIVE:
        // No stack emulation needed, but if we're compiling for Chrome with SourceMaps
        // support for stack trace deobfuscation, then we still want to modify the code
        // slightly to allow StackTraceCreator.CollectorChrome to use Chrome's
        // Error.captureStackTrace function to limit the stack traces to point
        // where the Throwable gets instantiated, in order to match Java's behavior
        // TODO: make these name strings robust to class name changes?
        String baseCollectorName = StackTraceCreator.class.getName() + ".Collector";
        String chromeCollectorName = baseCollectorName + "Chrome";
        Map<String, String> rebindAnswers = ResolveRebinds.getHardRebindAnswers(permutation.getOrderedRebindAnswers());
        if (chromeCollectorName.equals(rebindAnswers.get(baseCollectorName))) {
          (new JsStackEmulator(jprogram, jsProgram, propertyOracles, jjsmap, permutation.getId()))
              .new CapStackTraceForChrome().accept(jsProgram);
        }
        break;
    }
    return null;
  }

  public static StackMode getStackMode(PropertyOracle[] propertyOracles) {
    SelectionProperty property;
    try {
      property = propertyOracles[0].getSelectionProperty(TreeLogger.NULL,
          PROPERTY_NAME);
    } catch (BadPropertyValueException e) {
      // Should be inherited via Core.gwt.xml
      throw new InternalCompilerException("Expected property " + PROPERTY_NAME
          + " not defined", e);
    }

    String value = property.getCurrentValue();
    assert value != null : property.getName() + " did not have a value";
    StackMode stackMode = StackMode.valueOf(value.toUpperCase());
    // Check for multiply defined properties
    if (propertyOracles.length > 1) {
      for (int i = 1; i < propertyOracles.length; ++i) {
        try {
          property = propertyOracles[i].getSelectionProperty(TreeLogger.NULL,
              PROPERTY_NAME);
        } catch (BadPropertyValueException e) {
          // OK!
        }
        assert value.equals(property.getCurrentValue()) : "compiler.stackMode property has multiple values.";
      }
    }
    return stackMode;
  }

  private JsFunction caughtFunction;
  private JsName lineNumbers;
  private JProgram jprogram;
  private final JsProgram jsProgram;
  private JavaToJavaScriptMap jjsmap;
  private final int permutationId;
  private boolean recordFileNames;
  private boolean recordLineNumbers;
  private JsName stack;
  private JsName stackDepth;
  private JsName stackDepthCap;
  private JsName globalTemp;
  private final SourceInfo synthOrigin;
  private ThrowabilityAnalyzer throwabilityAnalyzer;
  private FileNameObfuscator fileNameObfuscator;

  public JsStackEmulator(JProgram jprogram, JsProgram jsProgram,
                          PropertyOracle[] propertyOracles,
                          JavaToJavaScriptMap jjsmap, int permutationId) {
    this.jprogram = jprogram;
    this.jsProgram = jsProgram;
    this.jjsmap = jjsmap;
    this.permutationId = permutationId;

    assert propertyOracles.length > 0;
    PropertyOracle oracle = propertyOracles[0];
    try {
      List<String> values = oracle.getConfigurationProperty(
          "compiler.emulatedStack.recordFileNames").getValues();
      recordFileNames = Boolean.valueOf(values.get(0));

      values = oracle.getConfigurationProperty(
          "compiler.emulatedStack.recordLineNumbers").getValues();
      recordLineNumbers = recordFileNames || Boolean.valueOf(values.get(0));
    } catch (BadPropertyValueException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
    synthOrigin = jsProgram.createSourceInfoSynthetic(getClass());
  }

  /**
   * The first pass simply learns about the structure of the original Java AST,
   * and it must run before the Java AST is modified.
   */
  private SymbolMapsLinker.FilenameDeobfuscationArtifact execImpl() {
    long startTime = System.currentTimeMillis();
    caughtFunction = jsProgram.getIndexedFunction("Exceptions.caught");
    if (caughtFunction == null) {
      // No exceptions caught? Weird, but possible.
      return null;
    }
    initNames();
    makeVars();
    throwabilityAnalyzer = new ThrowabilityAnalyzer();
    SymbolMapsLinker.FilenameDeobfuscationArtifact ret = null;
    if (recordLineNumbers) {
      fileNameObfuscator = new FileNameObfuscator();
      (new InstrumentAllFunctionsWithLineNumbers()).accept(jsProgram);
      fileNameObfuscator.obfuscateNames(jsProgram);
      ret = fileNameObfuscator.makeArtifact(permutationId);
    }
    else {
      (new InstrumentAllFunctions()).accept(jsProgram);
    }
    (new ReplaceUnobfuscatableNames()).accept(jsProgram);
    System.out.println("JsStackEmulator took " + (System.currentTimeMillis() - startTime) + " ms.");
    return ret;
  }

  private void initNames() {
    stack = jsProgram.getScope().declareName("$JsStackEmulator_stack", "$stack");
    stackDepth = jsProgram.getScope().declareName("$JsStackEmulator_stackDepth",
        "$stackDepth");
    stackDepthCap = jsProgram.getScope().declareName("$JsStackEmulator_stackDepthCap",
        "$stackDepthCap");
    lineNumbers = jsProgram.getScope().declareName("$JsStackEmulator_location",
        "$location");
    globalTemp = jsProgram.getScope().declareName("$JsStackEmulator_globalTemp",
        "$temp");
  }

  private void makeVars() {
    JsVar stackVar = new JsVar(synthOrigin, stack);
    stackVar.setInitExpr(new JsArrayLiteral(synthOrigin));
    JsVar stackDepthVar = new JsVar(synthOrigin, stackDepth);
    stackDepthVar.setInitExpr(new JsNumberLiteral(synthOrigin, (-1)));
    JsVar stackDepthCapVar = new JsVar(synthOrigin, stackDepthCap);
    stackDepthCapVar.setInitExpr(JsNullLiteral.INSTANCE);
    JsVar lineNumbersVar = new JsVar(synthOrigin, lineNumbers);
    lineNumbersVar.setInitExpr(new JsArrayLiteral(synthOrigin));
    JsVar globalTempVar = new JsVar(synthOrigin, globalTemp);

    JsVars vars;
    JsStatement first = jsProgram.getGlobalBlock().getStatements().get(0);
    if (first instanceof JsVars) {
      vars = (JsVars) first;
    } else {
      vars = new JsVars(synthOrigin);
      jsProgram.getGlobalBlock().getStatements().add(0, vars);
    }
    vars.add(stackVar);
    vars.add(stackDepthVar);
    vars.add(stackDepthCapVar);
    vars.add(lineNumbersVar);
    vars.add(globalTempVar);
  }


  /**
   * Creates an expression that looks like either
   * (stack[++stackDepth] = currentFunctionRef, stackDepth) // if returnNewStackDepth
   * or
   * stack[++stackDepth] = currentFunctionRef
   * @param returnNewStackDepth determines whether the value of the returned expression
   * should be the new value of stackDepth
   */
  protected JsExpression makeStackPushCode(SourceInfo info, JsExpression currentFunctionRef, boolean returnNewStackDepth) {
    JsNameRef stackRef = stack.makeRef(info);
    JsNameRef stackDepthRef = stackDepth.makeRef(info);
    // ++stackDepth
    JsUnaryOperation inc = new JsPrefixOperation(info, JsUnaryOperator.INC,
        stackDepthRef);
    // stack[++stackDepth]
    JsArrayAccess access = new JsArrayAccess(info, stackRef, inc);
    // stack[++stackDepth] = currentFunction
    JsBinaryOperation pushAsg = new JsBinaryOperation(info, JsBinaryOperator.ASG,
        access, currentFunctionRef);
    if (returnNewStackDepth) {
      // (stack[++stackDepth] = currentFunctionRef, stackDepth)
      return new JsBinaryOperation(info, JsBinaryOperator.COMMA, pushAsg, stackDepthRef);
    }
    return pushAsg;
  }


  /** TODO: this code is duplicated in com.google.gwt.dev.jjs.impl.GenerateJavaScriptAST.CreateNamesAndScopesVisitor#createGlobalFunction(java.lang.String) */
  private JsFunction createGlobalFunction(String code) {
    try {
      List<JsStatement> stmts =
          JsParser.parse(synthOrigin, jsProgram.getScope(), new StringReader(code));
      assert stmts.size() == 1;
      JsExprStmt stmt = (JsExprStmt)stmts.get(0);
      List<JsStatement> globalStmts = jsProgram.getGlobalBlock().getStatements();
      globalStmts.add(0, stmt);
      return (JsFunction)stmt.getExpression();
    }
    catch (Exception e) {
      throw new InternalCompilerException("Unexpected exception parsing '" + code + "'", e);
    }
  }


  /**
   * This visitor modifies the AST to improve stack traces in Chrome
   * when using SourceMaps instead of stack emulation.  We want to match our
   * emulated stack traces, which, in accordance with Java semantics, cut off
   * the stack trace at the call site of the exception's constructor. We do this
   * by leveraging V8's Error.captureStackTrace function and instrument the code
   * by extracting all exception instantiations into closures that get passed
   * to an artificial function whose name can be used in  Error.captureStackTrace.
   * This code performs transofrmations similar to {@link ReplaceUnobfuscatableNames}
   * and {@link InstrumentAllFunctions}.
   */
  private class CapStackTraceForChrome extends JsModVisitor {

    private JsFunction newExceptionFunction;
    // See JsRootScope for the definition of these names
    private final JsName rootNewExceptionFcnName = JsRootScope.INSTANCE.findExistingUnobfuscatableName("$newException");

    private CapStackTraceForChrome() {
      initNewExceptionFunction();
    }

    /**
     * Creates a global function that looks like:
     * <pre>
     * function newException(closure) {
     *   return closure();
     * }
     * </pre>
     * {@link com.google.gwt.core.client.impl.StackTraceCreator.CollectorChrome}
     * is going to invoke <pre>Error.captureStackTrace(e, newException)</pre>
     * in order to cut off the stack trace at the point of the exception's
     * instantiation, to match Java semantics.
     */
    private void initNewExceptionFunction() {
      JsScope programScope = jsProgram.getScope();
      newExceptionFunction = new JsFunction(synthOrigin, programScope,
          programScope.declareName("$JsStackEmulator_newException", "$newException"));
      JsName closureParamName = newExceptionFunction.getScope().declareName("closure");
      newExceptionFunction.getParameters().add(new JsParameter(synthOrigin, closureParamName));
      JsBlock body = new JsBlock(synthOrigin);
      newExceptionFunction.setBody(body);
      JsInvocation closureInvocation = new JsInvocation(synthOrigin);
      closureInvocation.setQualifier(closureParamName.makeRef(synthOrigin));
      //  return closure();
      body.getStatements().add(new JsReturn(synthOrigin, closureInvocation));
      // insert the function into the program
      jsProgram.getGlobalBlock().getStatements().add(0, newExceptionFunction.makeStmt());
    }

    @Override
    public void endVisit(JsFunction x, JsContext ctx) {
      new ReplaceExceptionInstantiations(x, newExceptionFunction, null).accept(x.getBody());
    }

    /**
     * The StackTraceCreator code refers to a "$newException" identifier defined
     * in JsRootScope, which is unobfuscatable. This visitor replaces references
     * to it with references to our locally-defined, obfuscatable name.
     */
    @Override
    public void endVisit(JsNameRef x, JsContext ctx) {
      JsName name = x.getName();
      if (name == rootNewExceptionFcnName) {
        assert x.getQualifier() == null;
        ctx.replaceMe(newExceptionFunction.getName().makeRef(x.getSourceInfo()));
      }
    }

  }

  

  /**
   * Heuristically uses static analysis to determine which expressions can not throw exceptions
   * in the given JS function.  This allows very good space and performance savings
   * when the compiler.emulatedStack.recordLineNumbers option is enabled, by
   * allowing us to not have to instrument every singe expression with line numbers.
   *
   * @author Alex Epshteyn (alexander.epshteyn@gmail.com, feel free to contact with questions).
   */
  private class ThrowabilityAnalyzer {

    private SourceOriginHierarchySet javaLocationsThatCanThrow = new SourceOriginHierarchySet();
    private SourceOriginHierarchySet javaLocationsThatCanNotThrow = new SourceOriginHierarchySet();
    private IdentityHashSet<JsName> namesExplicityDeclaredInGlobalBlock;

    private ThrowabilityAnalyzer() {
      findAllExplicitDeclsInGlobalScope();
      new JavaThrowabilityAnalyzer().accept(jprogram);
    }

    /**
     * Finds all names explicitly declared in the global scope, either
     * with var statements or function declarations.
     */
    private void findAllExplicitDeclsInGlobalScope() {
      namesExplicityDeclaredInGlobalBlock = new IdentityHashSet<JsName>();
      List<JsStatement> stmts = jsProgram.getGlobalBlock().getStatements();
      for (JsStatement stmt : stmts) {
        if (stmt instanceof JsVars) {
          JsVars vars = (JsVars)stmt;
          for (JsVar var : vars) {
            namesExplicityDeclaredInGlobalBlock.add(var.getName());
          }
        }
        else if (stmt instanceof JsExprStmt) {
          JsExpression expr = ((JsExprStmt)stmt).getExpression();
          if (expr instanceof JsFunction) {
            JsName funcName = ((JsFunction)expr).getName();
            if (funcName != null)
              namesExplicityDeclaredInGlobalBlock.add(funcName);
          }
        }
      }
    }

    /**
     * Populates locationsThatCanThrow and locationsThatCanNotThrow based
     * on static analysis of Java expressions.
     */
    private class JavaThrowabilityAnalyzer extends JVisitor {

      private ArrayDeque<ParentStackElement> parentStack = new ArrayDeque<ParentStackElement>();

      @Override
      public boolean visit(JNode x, Context ctx) {
        parentStack.push(new ParentStackElement(x));
        return true;
      }

      @Override
      public void endVisit(JNode x, Context ctx) {
        ParentStackElement poppedStackElement = parentStack.pop();
        assert poppedStackElement.getAstNode() == x; // sanity check
      }

      @Override
      public void endVisit(JExpression x, Context ctx) {
        endVisit((JNode)x, ctx); // pop the stack
        ExpressionAnalyzer analyzer = new ExpressionAnalyzer();
        analyzer.accept(x);
        if (analyzer.canThrowException())
          javaLocationsThatCanThrow.add(parentStack, x);
        else
          javaLocationsThatCanNotThrow.add(parentStack, x);
      }
    }


    private class JsFunctionAnalyzer {
      /**
       * The final results of running this algorithm will be stored here.
       * For any expression x in this map, the entry (x: true) will indicate
       * that x can't throw an exception and neither can any of its child
       * expressions in the AST.  The entry (x: false) will indicate that
       * x can't throw an exception but it has children that might.
       */
      private IdentityHashMap<JsExpression, Boolean> jsExpressionsThatCanNotThrow = new IdentityHashMap<JsExpression, Boolean>();
      /** Similar to jsExpressionsThatCanNotThrow, except uses Java static analysis */
      private IdentityHashMap<JsExpression, Boolean> jsExpressionsWhoseJavaCounterpartsCanNotThrow = new IdentityHashMap<JsExpression, Boolean>();
      /**
       * We must remember all the expressions we've actually examined because
       * EntryExitVisitor/LocationVisitor will be adding new expressions
       * to the AST.  We'll want to exclude those generated expressions
       * from instrumentation (i.e. everthing that's not in this set).
       */
      private IdentityHashSet<JsExpression> allVisitedExpressions = new IdentityHashSet<JsExpression>();

      /** Will contain some useful info about the function after it runs */
      private ThrowabilityReport throwabilityReport;

      private JsFunctionAnalyzer(JsFunction function) {
        // 1) start by running JS static analysis on the function
        // (this will populate expressionsThatCanNotThrow)
        new JsThrowabilityAnalyzer().accept(function.getBody());
        // 2) see if we can add more expressionsThatCanNotThrow via Java static analysis
        JMethod method = jjsmap.nameToMethod(function.getName());
        if (method != null) {
          if (!method.isNative() && method.getBody() instanceof JMethodBody) {
            // we have a Java method on which we can use the results of the java
            // static analysis that was performed by the parent class
            new JavaToJsMatcher().accept(function.getBody());
          }
          // TODO: JSNI methods (method.getBody() instanceof JsniMethodBody) might allow more static analysis than plain JS functions (e.g. in terms of type information of NameRefs); could implement a special visitor for these
        }
        // 3) finish up by running a 3rd pass that computes whether all children of expressions
        // that can't throw also can't throw, and whether all expressions in the function
        // can't throw
        throwabilityReport = new ThrowabilityReport();
        throwabilityReport.accept(function.getBody());
      }

      /**
       * @return true iff there is at least one try statement in the function.
       */
      public boolean containsTryStmt() {
        return throwabilityReport.containsTryStmt;
      }

      /**
       * @return true iff there are no expressions in the function that might throw,
       * as determined by JS static analysis only.
       */
      public boolean functionCanNotThrow() {
        return throwabilityReport.nothingCanThrow;
      }

      /**
       * @return true if x can not throw an exception as determined
       * by either JS static analysis or Java static analysis. This is a softer
       * condition than that of canNotThrowRecursive, and there is a small chance
       * that the given expression might indeed throw.  Therefore, this method
       * should not be used in high risk situations (such as deciding not
       * to instrument a function or not to extract a temporary return variable
       * prior to decreminting the stack index).
       */
      public boolean canNotThrow(JsExpression x) {
        return jsExpressionsThatCanNotThrow.containsKey(x) ||
            jsExpressionsWhoseJavaCounterpartsCanNotThrow.containsKey(x);
      }

      /**
       * @return true if neither x nor its descendants can throw an exception,
       * as determined by JS static analysis only.
       */
      public boolean canNotThrowRecursive(JsExpression x) {
        if (jsExpressionsThatCanNotThrow.containsKey(x)) {
          Boolean value = jsExpressionsThatCanNotThrow.get(x);
          assert value != null;
          return value;
        }
        return false;
      }

      public boolean wasVisited(JsExpression x) {
        return allVisitedExpressions.contains(x);
      }


      /**
       * A visitor that works at the function body level and doesn't visit
       * nested functions.
       */
      private abstract class JsFunctionBodyVisitor extends JsSuperVisitor {
        @Override
        public boolean visit(JsFunction x, JsContext ctx) {
          // don't visit nested functions
          return false;
        }
        @Override
        public void endVisit(JsFunction x, JsContext ctx) {
          // don't visit nested functions
        }
      }


      /**
       * Heuristically examines each JS expression and makes a determination
       * about whether it's possible that it can raise an exception.
       * Expressions that can't raise an exception are added to
       * expressionsThatCanNotThrow.
       *
       * Unlike Java, in JavaScript almost any binary or unary operation can raise
       * an exception.  Consider, for example, the binary operation x + 1.
       * In most cases, this will not raise an exception, but consider this scenario:
       *
       * function Foo() { this.toString = function() {return MickeyMouse} }
       * x = new Foo()
       * x + 1  // ReferenceError: MickeyMouse is not defined
       *
       * This happens because whenever x is an object, JavaScript calls
       * its toString() method whenever it's involved in certain types of casts,
       * like arithmetic binary operations in this case (see the ToNumber definition
       * in the ECMAScript Reference Manual http://bclary.com/2004/11/07/#a-9.3)
       *
       * The only implicit typecast that doesn't throw exceptions is the
       * ToBoolean cast, which has a well-defined truth table
       * (http://bclary.com/2004/11/07/#a-9.2)
       *
       * Since, without sophisticated type analysis (control flow graphs, etc.)
       * we can almost never be certain about the type stored in a Javascript variable,
       * (except in special cases, when it's directly assigned a primitive value in the
       * same basic block), our ability to determine whether expressions can
       * throw exceptions is limited.
       *
       * However, we can be sure that certain types of expressions will not throw:
       *
       * 1) JsNameRef:
       *   a) has a qualifier, but that qualifier can't cause a TypeError (e.g. because it's null)
       *   b) has no qualifier and can't raise a ReferenceError:
       *     i) is defined in Root scope or any scope below Global
       *    ii) is defined in Global scope and Global scope contains an explicit var or function declaration for this name
       *       (implicit declarations don't count, because they aren't declarations but rather property assignments to the global object)
       *
       * 2) JsLiteral: a literal can't throw an exception, but if it's a complex
       *    literal (like array or object), then it's component expressions can.
       *
       * 3) JsNumericEntry: this artifical AST node is pretty much equivalent
       *    to a number literal.
       *
       * 4) JsUnaryOperation: looking at the Unary Operators section (11.4) of
       * the ECMAScriptit's Reference manual completely safe,
       * (http://bclary.com/2004/11/07/#a-11.4) it seems impossible for the
       * following operators to throw exceptions (which doesnt' mean their
       * arguments can't throw):
       * delete, void, typeof, !
       *
       * 5) JsBinaryOperation: if both args definitely cannot throw an exception
       * when evaluated, then the following binary operators cannot throw
       * when evaluated:
       *    a) Logical operators: &&, ||,
       *    b) Strict equality: ===, !==
       *    c) Assignment operator: =
       *    d) Comma operator: ,
       *
       * 6) JsThisRef: a reference to 'this' can never throw an exception in JavaScript
       *
       * 7) JsConditional: this statement itself cannot throw an exception,
       * because the only typecast performed is casting the condition to boolean.
       *
       *
       * The following can definitely throw: comparison operators (see 11.8.5),
       * equality operators (11.9.3), as well as instanceof, in, etc.
       *
       * There may be other cases where a JsExpression can't throw, but we leave
       * those for optional future research work. It might be possible to derive
       * a couple more examples from the JS Reference Manual of expressions
       * that cannot throw, but generally it would require control flow analysis
       * and type inference, and hence is probably more trouble then it's worth.
       * Since we're falling back on Java static analysis anyways, doing this
       * in Javascript will probably not provide significant code size savings.
       */
      private class JsThrowabilityAnalyzer extends JsFunctionBodyVisitor {

        @Override
        public boolean visit(JsExpression x, JsContext ctx) {
          allVisitedExpressions.add(x);
          return super.visit(x, ctx);
        }

        /**
         * 1) JsNameRef:
         *   a) has a qualifier, but that qualifier can't cause a TypeError (e.g. because it's null)
         *   b) has no qualifier and can't raise a ReferenceError:
         *     i) is defined in Root scope or any scope below Global
         *    ii) is defined in Global scope and Global scope contains an explicit var or function declaration for this name
         */
        @Override
        public void endVisit(JsNameRef x, JsContext ctx) {
          // 1) JsNameRef:
          //   a) has a qualifier, but that qualifier can't cause a TypeError (e.g. because it's definitely not null)
          //   b) has no qualifier and can't raise a ReferenceError:
          //     i) is defined in Root scope or any scope below Global
          //    ii) is defined in Global scope and Global scope contains an explicit var or function declaration for this name
          JsExpression qualifier = x.getQualifier();
          if (qualifier != null) {
            // 1.a
            if (qualifier.isDefinitelyNotNull())
              jsExpressionsThatCanNotThrow.put(x, null);
          }
          else {
            // 1.b
            JsName name = x.getName();
            JsScope scope = name.getEnclosing();
            if (scope == JsRootScope.INSTANCE || scope != jsProgram.getScope()) {
              // 1.b.i
              jsExpressionsThatCanNotThrow.put(x, null);
            }
            else {
              // 1.b.ii
              assert scope == jsProgram.getScope();
              // defined in Global scope; this is the default scope where
              // com.google.gwt.dev.js.JsSymbolResolver.JsResolveSymbolsVisitor
              // puts identifiers that haven't been declared anywhere
              // so we must check that this identifier was explicitly declared in a var statement or as a function
              if (namesExplicityDeclaredInGlobalBlock.contains(name)) {
                jsExpressionsThatCanNotThrow.put(x, null);
              }
            }
          }
        }

        /**
         * 2) JsLiteral: a literal can't throw an exception, but if it's a complex
         *    literal (like array or object), then it's component expressions can.
         */
        @Override
        public void endVisit(JsLiteral x, JsContext ctx) {
          jsExpressionsThatCanNotThrow.put(x, null);
        }

        /**
         * 3) JsNumericEntry: this AST node is pretty much equivalent to number literal
         */
        @Override
        public void endVisit(JsNumericEntry x, JsContext ctx) {
          jsExpressionsThatCanNotThrow.put(x, null);
        }

        /**
         * 4) UnaryOperation: delete, void, typeof, !
         */
        @Override
        public void endVisit(JsUnaryOperation x, JsContext ctx) {
          switch (x.getOperator()) {
            case DELETE:
            case VOID:
            case TYPEOF:
            case NOT:
              jsExpressionsThatCanNotThrow.put(x, null);
          }
        }

        /**
         * 5) BinaryOperation: &&, ||, ===, !==, =, ','
         */
        @Override
        public void endVisit(JsBinaryOperation x, JsContext ctx) {
          switch (x.getOperator()) {
            case AND:
            case OR:
            case REF_EQ:
            case REF_NEQ:
            case COMMA:
              jsExpressionsThatCanNotThrow.put(x, null);
              break;
            case ASG:
              if (x.getArg1() instanceof JsNameRef) {
                jsExpressionsThatCanNotThrow.put(x, null);  // the l-value should be an identifier
              }
              break;
          }
        }

        /**
         * 6) JsThisRef: a reference to 'this' can never throw an exception in JavaScript
         */
        @Override
        public void endVisit(JsThisRef x, JsContext ctx) {
          jsExpressionsThatCanNotThrow.put(x, null);
        }

        /**
         * 7) JsConditional: this statement itself cannot throw an exception,
         * because the only typecast performed is casting the condition to boolean.
         */
        @Override
        public void endVisit(JsConditional x, JsContext ctx) {
          jsExpressionsThatCanNotThrow.put(x, null);
        }
      }

      /**
       * Heuristically examines the given expression and makes a determination
       * about whether it's possible that it can raise an exception
       * @return true if it's possible for expr to throw an exception when evaluated,
       * and furthermore, all occurrences of expr should be instrumented
       * regardless of what the Java static analysis might say.
       */
      private boolean definitelyCanThrow(JsExpression expr) {
        return expr instanceof JsInvocation ||
            expr instanceof JsNew;
      }

      /**
       * Traverses the JS AST to find expressions matching locationsThatCanThrow
       * and locationsThatCanNotThrow
       */
      private class JavaToJsMatcher extends JsFunctionBodyVisitor {

        private ArrayDeque<ParentStackElement> parentStack = new ArrayDeque<ParentStackElement>();

        @Override
        public boolean visit(JsNode x, JsContext ctx) {
          parentStack.push(new ParentStackElement(x));
          return true;
        }

        @Override
        public void endVisit(JsNode x, JsContext ctx) {
          ParentStackElement xElt = parentStack.pop();
          assert xElt.getAstNode() == x;
        }

        @Override
        public void endVisit(JsExpression x, JsContext ctx) {
          endVisit((JsNode)x, ctx);  // pop the stack
          // first, see if JS static analysis already picked up this expression
          if (!definitelyCanThrow(x)) {
            // fall back on Java static analysis
            // check if this expression's most likely Java counterpart can throw
            int yesWeight = javaLocationsThatCanThrow.getLongestMatchLength(parentStack, x);
            int noWeight = javaLocationsThatCanNotThrow.getLongestMatchLength(parentStack, x);
            if (noWeight > yesWeight) {
              // gives preference to "yes" when in doubt
              jsExpressionsWhoseJavaCounterpartsCanNotThrow.put(x, null);
            }
          }
        }
      }

      /**
       * Uses the set of jsExpressionsThatCanNotThrow that was computed
       * by the prior passes over the function's AST to make some inferences
       * abouts its throwability and structure.
       */
      private class ThrowabilityReport extends JsFunctionBodyVisitor {

        private class ParentExpression {
          private JsExpression expr;
          private boolean allChildrenCanNotThrow = true;
          private ParentExpression(JsExpression expr) {
            this.expr = expr;
          }
        }

        private Deque<ParentExpression> parentStack = new ArrayDeque<ParentExpression>();

        private boolean nothingCanThrow = true;
        private boolean containsTryStmt = false;

        @Override
        public boolean visit(JsExpression x, JsContext ctx) {
          parentStack.push(new ParentExpression(x));
          return true;
        }

        @Override
        public void endVisit(JsExpression x, JsContext ctx) {
          ParentExpression entry = parentStack.pop();
          assert x == entry.expr;
          if (jsExpressionsThatCanNotThrow.containsKey(x)) {
            jsExpressionsThatCanNotThrow.put(x, entry.allChildrenCanNotThrow);
          }
          else {
            nothingCanThrow = false;
            if (!parentStack.isEmpty()) {
              for (ParentExpression parent : parentStack) {
                // propagate this all the way up the stack
                parent.allChildrenCanNotThrow = false;
              }
            }
          }
        }

        @Override
        public void endVisit(JsTry x, JsContext ctx) {
          containsTryStmt = true;
        }
      }
    }

  }


  /**
   * Stores a set of SourceOrigin "chains", which are lists of parent SourceOrigins
   * that arise during AST traversal, with the first element in the list being
   * the most specific (i.e. applies to the current node being visited
   * during an AST traversal).
   * 
   * Since multiple AST nodes often share the same SourceOrigin, this ancestor
   * chain abstraction is a way to disambiguate between them.
   *
   * For example, take the Java expression "cancel()", which is a call to an
   * instance method.  It might be rewritten as "cancel(this$static)",
   * in which case the JMethodCall node and the JParameterRef node for "this$static"
   * will both share the same SourceOrigin. However, they will have different
   * chains of parent SourceOrigins during visitation, which allows distinguishing
   * between them when we're trying to determine which one can throw an exception,
   * for example.
   */
  public static class SourceOriginHierarchySet {
    /** We implement the set as a tree of hashmaps, for fast lookup */
    private ChainNode root = new RootNode();

    private class ChainNode {
      /** The set of possible next nodes in the chain containing this one */
      protected Map<SourceOrigin, ChainNode> children = Collections.emptyMap();

      public ChainNode get(SourceOrigin key) {
        return children.get(key);
      }

      public void put(SourceOrigin key, ChainNode value) {
        children = Maps.put(children, key, value);
      }

      public Collection<ChainNode> values() {
        return children.values();
      }
    }

    private class RootNode extends ChainNode {
      private RootNode() {
        // start with a fairly large root map to minimize rehashing
        children = new HashMap<SourceOrigin, ChainNode>(2 << 12);
      }

      @Override
      public void put(SourceOrigin key, ChainNode value) {
        children.put(key, value);  // don't use Maps.put for the root map
      }
    }

    public SourceOriginHierarchySet() {

    }

    /** Adds a (parent-child) chain of SourceOrigins to the set */
    public void add(ArrayDeque<? extends ParentStackElement> parentStack, HasSourceInfo child) {
      // add the child node
      ChainNode node = getOrInsertNode(child.getSourceInfo().getOrigin(), root);
      // add all the parent nodes up this chain
      for (ParentStackElement parent : parentStack) {
        if (parent.getAstNode() instanceof JMethod)
          break;  // don't need to store parents beyond the method body level (since the JS consumers of this class only work on function level)
        node = getOrInsertNode(parent.getAstNode().getSourceInfo().getOrigin(), node);
      }
    }

    private ChainNode getOrInsertNode(SourceOrigin origin, ChainNode parentNode) {
      ChainNode node = parentNode.get(origin);
      if (node == null) {
        parentNode.put(origin, node = new ChainNode());
      }
      return node;
    }

    /**
     * @return the longest contained subchain with the same prefix as the given chain,
     * or 0 if not even the first item in the given chain is contained.
     */
    public int getLongestMatchLength(ArrayDeque<? extends ParentStackElement> parentStack, HasSourceInfo child) {
      // this code is symmetric to the add method
      ChainNode node = root.get(child.getSourceInfo().getOrigin());
      if (node == null)
        return 0;
      int longestMatchLength = 1;
      for (ParentStackElement parent : parentStack) {
        node = node.get(parent.getAstNode().getSourceInfo().getOrigin());
        if (node == null)
          break;
        longestMatchLength++;
      }
      return longestMatchLength;
    }

    /** Counts the number of unique chains stored by this instance */
    public int size() {
      return sizeHelper(root.values());
    }

    private int sizeHelper(Collection<ChainNode> collection) {
      if (collection.isEmpty()) {
        return 1;  // base case for recursion: this is the end of 1 chain
      }
      int count = 0;
      for (ChainNode node : collection) {
        count += sizeHelper(node.values());
      }
      return count;
    }
  }



}