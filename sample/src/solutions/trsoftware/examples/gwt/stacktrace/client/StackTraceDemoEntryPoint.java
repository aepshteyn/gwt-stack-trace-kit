/*
 * Copyright 2013 Alex Epshteyn (alex@typeracer.com)
 */
package solutions.trsoftware.examples.gwt.stacktrace.client;

import com.google.gwt.core.client.EntryPoint;
import com.google.gwt.user.client.ui.RootPanel;
import com.google.gwt.dom.client.Style;
import solutions.trsoftware.examples.gwt.stacktrace.client.ExceptionSimulator;

/**
 * Sample application that demonstrates Alex Epshteyn's clientside stack trace
 * enhaments to GWT: <a href="http://igg.me/at/gwt-stack-traces/x/3494291">Project Description</a>
 */
public class StackTraceDemoEntryPoint implements EntryPoint {

  public void onModuleLoad() {
    RootPanel.get("loadingMessage").getElement().getStyle().setDisplay(Style.Display.NONE);
    RootPanel.get("dynamicUI").add(new ExceptionSimulator());
  }

}
