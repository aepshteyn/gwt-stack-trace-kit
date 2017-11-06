/*
 * Copyright 2013 Alex Epshteyn (alex@typeracer.com)
 */

package solutions.trsoftware.gwt.stacktrace.client;

import com.google.gwt.core.client.GWT;
import com.google.gwt.user.client.rpc.RemoteService;

/**
 * @author Alex
 * @since Jul 22, 2013
 */
public interface StackTraceDeobfuscatorService extends RemoteService {

  /**
   * Translates an obfuscated JavaScript stack trace to a Java stack trace with names and line numbers referencing the
   * original Java code that was compiled by a <a href="https://github.com/aepshteyn/gwt-stack-trace-kit">patched</a>
   * GWT compiler.
   *
   * @param obfStackTrace the obfuscated client-side stack trace (have to send a list of {@link StackTraceElement}s
   * instead of a {@link Throwable} because the latter aren't serializable by GWT-RPC)
   * @param exceptionMessage the result of {@link Exception#toString()}, to be used for logging the exception on the
   * server
   * @param moduleName The name of the GWT module that's making this RPC call, to be used in figuring out
   * where the symbol maps are located on the server. This value can be obtained by calling {@link GWT#getModuleName()}
   * @return The de-obfuscated stack trace, as a {@link String} with stack trace lines delimited by {@code \n}
   */
  String deobfuscateStackTrace(StackTraceElement[] obfStackTrace, String exceptionMessage, String moduleName);

}
