/*
 * Copyright 2013 Alex Epshteyn (alex@typeracer.com)
 */

package com.google.gwt.sample.stacktrace.client;

import com.google.gwt.user.client.rpc.RemoteService;

/**
 * @author Alex
 * @since Jul 22, 2013
 */
public interface StackTraceDeobfuscationService extends RemoteService {
  /**
   * Constructs a human-readable equivalent of a GWT clientside stack trace in
   * production mode.
   */
  String deobfuscateStackTrace(StackTraceElement[] obfStackTrace);

}
