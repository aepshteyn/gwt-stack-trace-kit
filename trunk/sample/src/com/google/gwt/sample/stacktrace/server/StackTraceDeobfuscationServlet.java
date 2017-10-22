/*
 * Copyright 2013 Alex Epshteyn (alex@typeracer.com)
 */

package com.google.gwt.sample.stacktrace.server;

import com.google.gwt.core.server.impl.StackTraceDeobfuscator;
import com.google.gwt.user.server.rpc.RemoteServiceServlet;
import com.google.gwt.sample.stacktrace.client.StackTraceDeobfuscationService;

import java.io.File;

/**
 * @author Alex
 * @since Jul 22, 2013
 */
public class StackTraceDeobfuscationServlet extends RemoteServiceServlet implements StackTraceDeobfuscationService {

  private static final String SYMBOL_MAPS_DIR_PATH = "WEB-INF/deploy/stdemo/symbolMaps";
  
  private StackTraceDeobfuscator deobfuscator;
  

  public synchronized StackTraceDeobfuscator getDeobfuscator() {
    // lazy init
    if (deobfuscator == null) {
      String fullSymbolMapsFilePath = getServletContext().getRealPath(SYMBOL_MAPS_DIR_PATH);
      // make sure the symbol maps directory exists (print a warning if not; don't throw an exception because this might be running in a unit test where no symbol maps are available)
      File symbolMapsDir = new File(fullSymbolMapsFilePath);
      if (!symbolMapsDir.exists() || !symbolMapsDir.isDirectory()) {
        System.err.println(fullSymbolMapsFilePath + " not found or not a directory.");
      }
      deobfuscator = StackTraceDeobfuscator.fromFileSystem(fullSymbolMapsFilePath);
      deobfuscator.setLazyLoad(true);  // only loads the symbols as needed (saves lots of memory at the expense of more filesystem reads)
    }
    return deobfuscator;

  }

  public String deobfuscateStackTrace(StackTraceElement[] obfStackTrace) {
    StringBuilder str = new StringBuilder(1024);
    StackTraceElement[] stackTrace = getDeobfuscator().resymbolize(obfStackTrace, getPermutationStrongName());
    for (StackTraceElement ste : stackTrace) {
      str.append(ste).append("\n");
    }
    return str.toString();
  }
}
