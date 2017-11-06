/*
 * Copyright 2013 Alex Epshteyn (alex@typeracer.com)
 */

package solutions.trsoftware.gwt.stacktrace.client;

import com.google.gwt.user.client.rpc.AsyncCallback;

/**
 * @author Alex
 * @since Jul 22, 2013
 */
public interface StackTraceDeobfuscatorServiceAsync {

  /**
   * Proxy for {@link StackTraceDeobfuscatorService#deobfuscateStackTrace(StackTraceElement[], String, String)}
   */
  void deobfuscateStackTrace(StackTraceElement[] obfStackTrace, String exceptionMessage, String moduleName, AsyncCallback<String> async);
}