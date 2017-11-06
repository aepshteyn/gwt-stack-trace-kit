/*
 * Copyright 2013 Alex Epshteyn (alex@typeracer.com)
 */

package solutions.trsoftware.gwt.stacktrace.server;

import com.google.gwt.user.server.rpc.RemoteServiceServlet;
import solutions.trsoftware.gwt.stacktrace.client.StackTraceDeobfuscatorService;

import java.io.File;
import java.util.HashMap;

/**
 * @author Alex
 * @since Jul 22, 2013
 */
public class StackTraceDeobfuscatorServlet extends RemoteServiceServlet implements StackTraceDeobfuscatorService {

  /**
   * Stores instances of {@link StackTraceDeobfuscator}, keyed by symbolMaps path.
   */
  private HashMap<String, StackTraceDeobfuscator> deobfuscators = new HashMap<String, StackTraceDeobfuscator>();

  public synchronized StackTraceDeobfuscator getDeobfuscator(String moduleName) {
    String symbolMapsPath = getSymbolMapsPath(moduleName);
    StackTraceDeobfuscator deobfuscator = deobfuscators.get(symbolMapsPath);
    // lazy init
    if (deobfuscator == null) {
      String fullSymbolMapsFilePath = getServletContext().getRealPath(symbolMapsPath);
      // make sure the symbol maps directory exists (print a warning if not; don't throw an exception because this might be running in a unit test where no symbol maps are available)
      File symbolMapsDir = new File(fullSymbolMapsFilePath);
      if (!symbolMapsDir.exists() || !symbolMapsDir.isDirectory()) {
        String errorMsg = "WARNING: can't find the symbol maps needed by StackTraceDeobfuscator: " + fullSymbolMapsFilePath + " not found or not a directory";
        System.err.println(errorMsg);
        getServletContext().log(errorMsg, new IllegalArgumentException(errorMsg));
      }
      deobfuscator = StackTraceDeobfuscator.fromFileSystem(fullSymbolMapsFilePath);
      deobfuscator.setLazyLoad(true);  // only loads the symbols as needed (saves lots of memory at the expense of more filesystem reads)
      deobfuscators.put(symbolMapsPath, deobfuscator);
    }
    return deobfuscator;
  }

  /**
   * {@inheritDoc}
   *
   * De-obfuscates the given stack trace.
   *
   * Subclasses may override to take additional actions with the de-obfuscated stack trace (e.g. to log it).
   * If overriding, don't forget to call {@code super.}{@link #deobfuscateStackTrace(StackTraceElement[], String, String)}
   *
   * @see StackTraceDeobfuscatorService#deobfuscateStackTrace(StackTraceElement[], String, String)
   */
  @Override
  public String deobfuscateStackTrace(StackTraceElement[] obfStackTrace, String exceptionMessage, String moduleName) {
    StringBuilder str = new StringBuilder(1024);
    StackTraceElement[] stackTrace = getDeobfuscator(moduleName).resymbolize(obfStackTrace, getPermutationStrongName());
    for (StackTraceElement ste : stackTrace) {
      str.append(ste).append("\n");
    }
    return str.toString();
  }

  /**
   * Subclasses may override to provide the path where the compiler-emitted deobfuscation resources
   * (e.g. symbol maps, obfuscated filenames, source maps) are located in the webapp.
   *
   * If not overridden, this method returns {@code "WEB-INF/deploy/" + moduleName + "/symbolMaps"}
   *
   * @param moduleName The name of the GWT module that's making this RPC call, to be used in figuring out
   * where the symbol maps are located on the server. This value can be obtained client-side by calling {@code GWT.getModuleName()}
   *
   * @return the path where the deobfuscation resources emitted by the compiler are deployed for the given module
   * (e.g. symbol maps, obfuscated filenames, source maps) relative to the webapp root.
   * Example: {@code "WEB-INF/deploy/MyModule/symbolMaps"}
   */
  protected static String getSymbolMapsPath(String moduleName) {
    return "WEB-INF/deploy/" + moduleName + "/symbolMaps";
  }
}
