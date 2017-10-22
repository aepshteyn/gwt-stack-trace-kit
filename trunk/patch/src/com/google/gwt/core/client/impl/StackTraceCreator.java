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
package com.google.gwt.core.client.impl;

import com.google.gwt.core.client.*;

/**
 * Encapsulates logic to create a stack trace. This class should only be used in
 * Production Mode.
 */
public class StackTraceCreator {

  /**
   * Line number used in a stack trace when it is unknown.
   */
  public static final int LINE_NUMBER_UNKNOWN = -1;

  private static Collector collectorInstance;

  /**
   * Returns the singleton instance of the Collector. This method should
   * only be called in Production Mode.
   */
  static Collector getCollector() {
    if (collectorInstance == null) {
      if (!GWT.isScript()) {
        throw new RuntimeException(
            "StackTraceCreator should only be called in Production Mode");
      }
      collectorInstance = GWT.create(Collector.class);
    }
    return collectorInstance;
  }


  /**
   * This class acts as a deferred-binding hook point to allow more optimal
   * versions to be substituted. This base version simply crawls
   * <code>arguments.callee.caller</code>.
   */
  static class Collector {

    /**
     * The browsers that provide stack traces via the <code>stack</code>
     * property of exception objects expose an API to control how
     * many stack frames will be made available via that property.
     */
    public native void maybeIncreaseStackTraceLimit() /*-{
      // subclasses can override this browser-specific feature
    }-*/;

    public native JsArrayString collect() /*-{
      var seen = {};
      var toReturn = [];

      // Ignore the collect() and fillInStackTrace call
      var callee = arguments.callee.caller.caller;
      while (callee) {
        var name = this.@com.google.gwt.core.client.impl.StackTraceCreator.Collector::extractName(Ljava/lang/String;)(callee.toString());
        toReturn.push(name);

        // Avoid infinite loop by associating names to function objects.  We
        // record each caller in the withThisName variable to handle functions
        // with identical names but separate identity (such as 'anonymous')
        var keyName = ':' + name;
        var withThisName = seen[keyName];
        if (withThisName) {
          var i, j;
          for (i = 0, j = withThisName.length; i < j; i++) {
            if (withThisName[i] === callee) {
              return toReturn;
            }
          }
        }

        (withThisName || (seen[keyName] = [])).push(callee);
        callee = callee.caller;
      }
      return toReturn;
    }-*/;

    /**
     * Same as {@link #fillInStackTrace}, but uses the native exception object
     * already contained by the given JavaScriptException.
     */
    public void createStackTrace(JavaScriptException e) {
      parseAndSetStackTrace(e, inferFrom(e.getException()));
    }

    public void fillInStackTrace(Throwable t) {
      parseAndSetStackTrace(t, collect());
    }

    protected void parseAndSetStackTrace(Throwable t, JsArrayString stack) {
      StackTraceElement[] stackTrace = new StackTraceElement[stack.length()];
      for (int i = 0, j = stackTrace.length; i < j; i++) {
        stackTrace[i] = new StackTraceElement("Unknown", stack.get(i), null,
            LINE_NUMBER_UNKNOWN);
      }
      t.setStackTrace(stackTrace);
    }

    /**
     * Returns the list of properties of an unexpected JavaScript exception.
     */
    public native String getProperties(JavaScriptObject e) /*-{
      var result = "";
      try {
        for (var prop in e) {
          if (prop != "name" && prop != "message" && prop != "toString") {
            try {
              result += "\n " + prop + ": " + e[prop];
            } catch (ignored) {
              // Skip the property if it threw an exception.
            }
          }
        }
      } catch (ignored) {
        // If we can't do "in" on the exception, just return what we have.
      }
      return result;
    }-*/;

    /**
     * Attempt to infer the stack from an unknown JavaScriptObject that had been
     * thrown. The default implementation just returns an empty array.
     *
     * @param e a JavaScriptObject
     */
    public JsArrayString inferFrom(JavaScriptObject e) {
      return JavaScriptObject.createArray().cast();
    }

    /**
     * Extract the name of a function from it's toString() representation.
     * Package-access for testing.
     */
    protected String extractName(String fnToString) {
      return extractNameFromToString(fnToString);
    }

    /**
     * Raise an exception and return it.
     */
    protected native JavaScriptObject makeException() /*-{
      try {
        null.a();
      } catch (e) {
        return e;
      }
    }-*/;
  }

  /**
   * Collaborates with JsStackEmulator.
   */
  static class CollectorEmulated extends Collector {

    @Override
    public JsArrayString collect() {
      JsArrayString toReturn = JsArrayString.createArray().cast();
      JsArray<JavaScriptObject> stack = getStack();
      int depth = getStackDepth();
      for (int i = 0; i <= depth; i++) {
        String name = stack.get(i) == null ? "anonymous"
            : extractName(stack.get(i).toString());
        // Reverse the order
        toReturn.set(depth - i, name);
      }
      return toReturn;
    }

    @Override
    public void createStackTrace(JavaScriptException e) {
      // No-op, relying on Throwable initializer to call fillInStackTrace
    }

    @Override
    public void fillInStackTrace(Throwable t) {
      JsArrayString stack = collect();
      int stackLength = stack.length();
      StackTraceElement[] stackTrace = new StackTraceElement[stackLength];
      for (int i = 0; i < stackLength; i++) {
        // Locations is also backwards
        String location = getLocation(stackLength - i - 1);
        String fileName = null;
        int lineNumber = LINE_NUMBER_UNKNOWN;
        if (location != null) {
          int idx = location.indexOf(':');
          if (idx != -1) {
            fileName = location.substring(0, idx);
            lineNumber = Integer.parseInt(location.substring(idx + 1));
          } else {
            lineNumber = Integer.parseInt(location);
          }
        }
        stackTrace[i] = new StackTraceElement("Unknown", stack.get(i),
            fileName, lineNumber);
      }
      t.setStackTrace(stackTrace);
    }

    /**
     * When compiler.stackMode = emulated, return an empty string, rather than a
     * list of properties, since the additional information regarding the origin
     * of the JavaScriptException, relative to compiled JavaScript source code,
     * adds no real value, since we have fully emulated stack traces.
     */
    @Override
    public String getProperties(JavaScriptObject e) {
      return "";
    }

    @Override
    public JsArrayString inferFrom(JavaScriptObject e) {
      throw new RuntimeException("Should not reach here");
    }

    /**
     * The location array might contain just a line number (as a number type)
     * or a "fileName:lineNumber" string.
     * @return the value of the location array at the given index; could be null
     */
    private native String getLocation(int index)/*-{
      var ret = $location[index];
      return ret ? String(ret) : null;
    }-*/;

    private native JsArray<JavaScriptObject> getStack()/*-{
      return $stack;
    }-*/;

    private native int getStackDepth() /*-{
      return $stackDepthCap || $stackDepth;
    }-*/;
  }

  /**
   * Mozilla provides a <code>stack</code> property in thrown objects.
   */
  static class CollectorMoz extends Collector {
    /**
     * This implementation doesn't suffer from the limitations of crawling
     * <code>caller</code> since Mozilla provides proper activation records.
     */
    @Override
    public JsArrayString collect() {
      JsArrayString stack = inferFrom(makeException());
      return splice(stack, toSplice(stack));
    }

    @Override
    public JsArrayString inferFrom(JavaScriptObject e) {
      JsArrayString stack = getStack(e);
      for (int i = 0, j = stack.length(); i < j; i++) {
        stack.set(i, extractName(stack.get(i)));
      }
      return stack;
    }

    protected native JsArrayString getStack(JavaScriptObject e) /*-{
      return (e && e.stack) ? e.stack.split('\n') : [];
    }-*/;

    protected int toSplice(JsArrayString stack) {
      return 2;
    }
  }

  /**
   * Chrome uses a slightly different format to Mozilla.
   *
   * See http://code.google.com/p/v8/source/browse/branches/bleeding_edge/src/
   * messages.js?r=2340#712 for formatting code.
   *
   * Function calls can be of the four following forms:
   *
   * <pre>
   * at file.js:1:2
   * at functionName (file.js:1:2)
   * at Type.functionName (file.js:1:2)
   * at Type.functionName [as methodName] (file.js:1:2)
   * </pre>
   */
  static class CollectorChrome extends CollectorMoz {

    /**
     * By default, Chrome only provides 10 lines per stack trace.
     * TODO(cromwellian) make this a configurable?
     */
    @Override
    public native void maybeIncreaseStackTraceLimit() /*-{
      // 128 seems like a reasonable maximum
      Error.stackTraceLimit = 128;
    }-*/;

    /**
     * JsStackEmulator wraps all user-defined exception constructor invocations
     * in a function called $newException, so that we may strip off the stack
     * frames above where the exception is actually constructed.
     * Originally, we wanted to do
     * <pre>Error.captureStackTrace(e, $newException); </pre>
     * but unfortunately Chrome's implementation of that V8 API method
     * turned out to be buggy and unreliable, so we'll manually search the stack trace
     * to find $newException.
     * @param
     */
    protected native String getExceptionFactoryFcnName() /*-{
      return $newException.name; // the name $newException is defined and replaced by JsStackEmulator
    }-*/;

    @Override
    public JsArrayString collect() {
      JsArrayString res = super.collect();
      if (res.length() == 0) {
        /*
         * Ensure Safari falls back to default Collector implementation.
         * Remember to remove this method call from the stack:
         */
        res = splice(new Collector().collect(), 1);
      }
      return res;
    }

    @Override
    public JsArrayString inferFrom(JavaScriptObject e) {
      JsArrayString stack = super.inferFrom(e);
      if (stack.length() == 0) {
        // Safari should fall back to default Collector:
        return new Collector().inferFrom(e);
      } else {
        // Chrome contains the error itself as the first line of the stack:
        return splice(stack, 1);
      }
    }

    @Override
    protected String extractName(String fnToString) {
      String extractedName = "anonymous";
      String location = "";

      if (fnToString.length() == 0) {
        return extractedName;
      }

      String toReturn = fnToString.trim();

      // Strip the "at " prefix:
      if (toReturn.startsWith("at ")) {
        toReturn = toReturn.substring(3);
      }

      // Strip square bracketed items from the end:
      int index = toReturn.indexOf("[");
      if (index != -1) {
        toReturn = toReturn.substring(0, index).trim() +
            toReturn.substring(toReturn.indexOf("]", index) + 1).trim();
      }

      index = toReturn.indexOf("(");
      if (index == -1) {
        // No bracketed items found, hence no function name available
        location = toReturn;
        toReturn = "";
      } else {
        // Bracketed items found: strip them off, parse location info
        int closeParen = toReturn.indexOf(")", index);
        location = toReturn.substring(index + 1, closeParen);
        toReturn = toReturn.substring(0, index).trim();
      }

      // Strip the Type off t
      index = toReturn.indexOf('.');
      if (index != -1) {
        toReturn = toReturn.substring(index + 1);
      }
      // sometimes chrome uses a line like "HTMLAnchorElement.<anonymous>"
      // and sometimes it uses an empty string as a function name;
      // for the sake of uniformity, we convert everything to the latter form
      if ("<anonymous>".equals(toReturn)) {
        toReturn = "";
      }

      return (toReturn.length() > 0 ? toReturn : "anonymous") + "@@" + location;
    }

    protected int replaceIfNoSourceMap(int line) {
      return line;
    }

    @Override
    protected int toSplice(JsArrayString stack) {
      // JsStackEmulator wraps all user-defined exception constructor invocations
      // in a function called $newException, so that we may strip off the stack
      // frames above where the exception is actually constructed.
      String target = getExceptionFactoryFcnName() + "@@";
      for (int i = 0, len = stack.length(); i < len; i++) {
        String elt = stack.get(i);
        if (elt != null && elt.trim().startsWith(target))
          return i+1;  // keep everything below the stack frame of the exception factory function
      }
      return 0; // didn't find the $newException function on the stack; oh well
    }

    @Override
    protected void parseAndSetStackTrace(Throwable e, JsArrayString stack) {
      StackTraceElement[] stackTrace = new StackTraceElement[stack.length()];
      for (int i = 0, j = stackTrace.length; i < j; i++) {
        String stackElements[] = stack.get(i).split("@@");

        int line = LINE_NUMBER_UNKNOWN;
        int col = -1;
        String fileName = "Unknown";
        if (stackElements.length == 2 && stackElements[1] != null) {
          String location = stackElements[1];
          // colon between line and column
          int lastColon = location.lastIndexOf(':');
          // colon between file url and line number
          int endFileUrl = location.lastIndexOf(':', lastColon - 1);
          fileName = location.substring(0, endFileUrl);

          if (lastColon != -1 && endFileUrl != -1) {
              line = parseInt(location.substring(endFileUrl + 1, lastColon));
              col = parseInt(location.substring(lastColon + 1));
          }
        }
        stackTrace[i] = new StackTraceElement("Unknown", stackElements[0], fileName + "@" + col,
            replaceIfNoSourceMap(line < 0 ? -1 : line));
      }
      e.setStackTrace(stackTrace);
    }
  }

  /**
   * Subclass that forces reported line numbers to -1 (fetch from symbolMap) if source maps are
   * disabled.
   */
  static class CollectorChromeNoSourceMap extends CollectorChrome {

    protected int replaceIfNoSourceMap(int line) {
      return -1;
    }

    @Override
    protected int toSplice(JsArrayString stack) {
      return 3;
    }
  }

  private static native int parseInt(String number) /*-{
    return parseInt(number) || -1;
  }-*/;

  /**
   * Opera encodes stack trace information in the error's message.
   */
  static class CollectorOpera extends CollectorMoz {
    /**
     * We have much a much simpler format to work with.
     */
    @Override
    protected String extractName(String fnToString) {
      return fnToString.length() == 0 ? "anonymous" : fnToString;
    }

    /**
     * Opera has the function name on every-other line.
     */
    @Override
    protected JsArrayString getStack(JavaScriptObject e) {
      JsArrayString toReturn = getMessage(e);
      assert toReturn.length() % 2 == 0 : "Expecting an even number of lines";

      int i, i2, j;
      for (i = 0, i2 = 0, j = toReturn.length(); i2 < j; i++, i2 += 2) {
        int idx = toReturn.get(i2).lastIndexOf("function ");
        if (idx == -1) {
          toReturn.set(i, "");
        } else {
          toReturn.set(i, toReturn.get(i2).substring(idx + 9).trim());
        }
      }
      setLength(toReturn, i);

      return toReturn;
    }

    @Override
    protected int toSplice(JsArrayString stack) {
      return 3;
    }

    private native JsArrayString getMessage(JavaScriptObject e) /*-{
      return (e && e.message) ? e.message.split('\n') : [];
    }-*/;

    private native void setLength(JsArrayString obj, int length) /*-{
      obj.length = length;
    }-*/;
  }

  /**
   * When compiler.stackMode = strip, we stub out the collector.
   */
  static class CollectorNull extends Collector {
    @Override
    public  JsArrayString collect() {
      return JsArrayString.createArray().cast();
    }

    @Override
    public void createStackTrace(JavaScriptException e) {
      // empty, since Throwable.getStackTrace() properly handles null
    }

    @Override
    public void fillInStackTrace(Throwable t) {
      // empty, since Throwable.getStackTrace() properly handles null
    }
  }

  /**
   * Create a stack trace based on a JavaScriptException. This method should
   * only be called in Production Mode.
   */
  public static void createStackTrace(JavaScriptException e) {
    getCollector().createStackTrace(e);
  }

  /**
   * Fill in a stack trace based on the current execution stack. This method
   * should only be called in Production Mode.
   */
  public static void fillInStackTrace(Throwable t) {
    getCollector().fillInStackTrace(t);
  }

  /**
   * Returns the list of properties of an unexpected JavaScript exception,
   * unless compiler.stackMode = emulated, in which case the empty string is
   * returned. This method should only be called in Production Mode.
   */
  public static String getProperties(JavaScriptObject e) {
    return getCollector().getProperties(e);
  }

  /**
   * Initializes the browser-specific stack trace collector system.
   */
  public static void init() {
    if (GWT.isScript()) {
      getCollector().maybeIncreaseStackTraceLimit();
    }
  }



  static String extractNameFromToString(String fnToString) {
    String toReturn = "";
    fnToString = fnToString.trim();
    int index = fnToString.indexOf("(");
    int start = fnToString.startsWith("function") ? 8 : 0;
    if (index == -1) {
      // Firefox 14 does not include parenthesis and uses '@' symbol instead to terminate symbol
      index = fnToString.indexOf('@');
      /**
       * Firefox 14 doesn't return strings like 'function()' for anonymous methods, so
       * we assert a space must trail 'function' keyword for a method named 'functionName', e.g.
       * functionName:file.js:2 won't accidentally strip off the 'function' prefix which is part
       * of the name.
       */
      start = fnToString.startsWith("function ") ? 9 : 0;
    }
    if (index != -1) {
      toReturn = fnToString.substring(start, index).trim();
    }
    return toReturn.length() > 0 ? toReturn : "anonymous";
  }

  private static native JsArrayString splice(JsArrayString arr, int length) /*-{
    (arr.length >= length) && arr.splice(0, length);
    return arr;
  }-*/;
}