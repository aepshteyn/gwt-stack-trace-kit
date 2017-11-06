package solutions.trsoftware.gwt.stacktrace.client.util;

import com.google.gwt.core.client.JavaScriptObject;

// NOTE: this code duplicates a class in the TR Commons library

/**
 * A JSNI overlay type for the <a href="https://developer.mozilla.org/en-US/docs/Web/API/Console">window.console</a> object.
 *
 * Supports a subset of the methods provided by the various browser implementations
 * of window.console (log, time, timeEnd, etc.)
 *
 * NOTE: {@link elemental.js.html.JsConsole} is a more full-featured implementation
 * of this concept and is part of GWT's experimental new "Elemental" package.
 * However, that class doesn't compensate for lack of functionality of certain methods, and also Elemental only
 * works with SuperDevMode (will produce a GWT compiler error when running under the regular DevMode, see
 * http://stackoverflow.com/questions/17428265/adding-elemental-to-gwt )
 *
 * NOTE: most of the Javadoc comments in this class were copied from the Firebug console
 *
 * @see <a href="http://getfirebug.com/wiki/index.php/Console_API">Firebug Console Reference</a>
 * @see <a href="https://developers.google.com/chrome-developer-tools/docs/console-api">Chrome Dev Tools Console Reference</a>
 *
 * @since Mar 26, 2013
 * @author Alex
 */
public class JsConsole extends JavaScriptObject {

  // Overlay types always have protected, zero-arg constructors, because the object must have been instantiated in javascript
  protected JsConsole() { }

  /**
   * @return the {@code window.console} object if the browser supports it, or an empty object if not.  Guaranteed
   * to never return {@code null}.
   */
  public static native JsConsole get() /*-{
    return $wnd.console || {};
  }-*/;

  /**
   * Alias for <a href="https://developer.mozilla.org/en-US/docs/Web/API/Console/assert">{@code console.assert}</a>:
   * Writes an error message to the console if the assertion is false. If the assertion is true, nothing happens.
   * NOTE: we can't name this method {@code assert} (to match its JS name) because that's a reserved keyword in Java.
   */
  public final native void assertion(Object condition, Object message) /*-{
    this.assert && this.assert(condition, message);
  }-*/;

  /**
   * Clear the console.
   */
  public final native void clear() /*-{
    this.clear && this.clear();
  }-*/;

  /** Log the number of times this line has been called with the given label. */
  public final native void count(Object arg) /*-{
    this.count && this.count(arg);
  }-*/;

  public final native boolean implementsCount() /*-{
    return !!this.count;
  }-*/;

  /** For general output of logging information. We assume that all implementations of window.console provide at least a {@code log} method */
  public final native void log(Object arg) /*-{
    this.log && this.log(arg);
  }-*/;

  /** "Writes a message to the console with the visual "error" icon and color coding and a hyperlink to the line where it was called." */
  public final native void error(Object arg) /*-{
    this.error && this.error(arg);
  }-*/;

  public final native boolean implementsError() /*-{
    return !!this.error;
  }-*/;

  /** Informative logging information. */
  public final native void info(Object arg) /*-{
    this.info && this.info(arg);
  }-*/;

  public final native boolean implementsInfo() /*-{
    return !!this.info;
  }-*/;

  /** "Writes a message to the console with the visual "warning" icon and color coding and a hyperlink to the line where it was called." */
  public final native void warn(Object arg) /*-{
    this.warn && this.warn(arg);
  }-*/;

  public final native boolean implementsWarn() /*-{
    return !!this.warn;
  }-*/;

  /** "Writes a message to the console and opens a nested block to indent all future messages sent to the console. Call console.groupEnd() to close the block." */
  public final native void group(Object arg) /*-{
    this.group && this.group(arg);
  }-*/;

  public final native boolean implementsGroup() /*-{
    return !!this.group;
  }-*/;

  /** "Like console.group(), but the block is initially collapsed." */
  public final native void groupCollapsed(Object arg) /*-{
    this.groupCollapsed && this.groupCollapsed(arg);
  }-*/;

  public final native boolean implementsGroupCollapsed() /*-{
    return !!this.groupCollapsed;
  }-*/;

  /**
   * "Closes the most recently opened block created by a call to console.group()
   * or console.groupCollapsed()"
   */
  public final native void groupEnd() /*-{
    this.groupEnd();
  }-*/;

  public final native boolean implementsGroupEnd() /*-{
    return !!this.groupEnd;
  }-*/;

  /**
   * Some window.console implementations (like WebKit) support a markTimeline
   * method, by which an app can add an annotation to the Timeline section
   * of the browser's developer tools.  This is particularly useful for the
   * Speed Tracer chrome extension (see: https://developers.google.com/web-toolkit/speedtracer/logging-api )
   */
  public final native void markTimeline(Object arg) /*-{
    this.markTimeline && this.markTimeline(arg);
  }-*/;

  public final native boolean implementsMarkTimeline() /*-{
    return !!this.markTimeline;
  }-*/;

  /** "Turns on the JavaScript profiler. The optional argument title would contain the text to be printed in the header of the profile report." */
  public final native void profile(String title) /*-{
    this.profile(title);
  }-*/;

  public final native boolean implementsProfile() /*-{
    return !!this.profile;
  }-*/;

  /** "Turns off the JavaScript profiler and prints its report." */
  public final native void profileEnd(String title) /*-{
    this.profileEnd(title);
  }-*/;

  public final native boolean implementsProfileEnd() /*-{
    return !!this.profileEnd;
  }-*/;

  /** "Creates a new timer under the given name. Call console.timeEnd(name) with the same name to stop the timer and print the time elapsed." */
  public final native void time(String title) /*-{
    this.time(title);
  }-*/;

  public final native boolean implementsTime() /*-{
    return !!this.time;
  }-*/;

  /** "Stops a timer created by a call to console.time(name) and writes the time elapsed." */
  public final native void timeEnd(String title) /*-{
    this.timeEnd(title);
  }-*/;

  public final native boolean implementsTimeEnd() /*-{
    return !!this.timeEnd;
  }-*/;

  public final native void timeStamp(Object arg) /*-{
    this.timeStamp && this.timeStamp(arg);
  }-*/;

  public final native boolean implementsTimeStamp() /*-{
    return !!this.timeStamp;
  }-*/;

  /** "Prints an interactive stack trace of JavaScript execution at the point where it is called." */
  public final native void trace(Object arg) /*-{
    this.trace && this.trace(arg);
  }-*/;

  public final native boolean implementsTrace() /*-{
    return !!this.trace;
  }-*/;

}
