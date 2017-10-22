/*
 * Copyright 2013 Alex Epshteyn (alex@typeracer.com)
 */
package com.google.gwt.sample.stacktrace.client;

import com.google.gwt.core.client.GWT;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.user.client.Window;
import com.google.gwt.user.client.rpc.ServiceDefTarget;
import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.gwt.user.client.ui.*;

/**
 * Sample application that demonstrates Alex Epshteyn's clientside stack trace
 * enhaments to GWT: <a href="http://igg.me/at/gwt-stack-traces/x/3494291">Project
 * Description</a>
 */
public class ExceptionSimulator extends Composite {

  /**
   * Causes an NPE to be triggered indirectly as a result of running this
   * method
   */
  public static void triggerFakeNPE() {
    final int[] foo = null;
    Window.alert("foo[3]=" + foo[3]); // will throw NPE
  }

  /** Throws a RuntimeException using an explicit throw statement */
  public static void throwRuntimeException() {
    RuntimeException ex = new RuntimeException("ExceptionSimulator dummy exception");
    // NOTE: in Java, the stack trace of an exception will start on the line where the
    // constructor of the exception is invoked, not where the throw statement is,
    // because the stack trace is created by the exception's constructor
    throw ex;
  }

  /**
   * Causes a Javascript ReferenceError by using a name that hasn't been
   * declared or assigned
   */
  public static native void triggerJavascriptReferenceError() /*-{
    var x = 5;
    // emperorsNewClothes has never been declared, so it can't be used in any
    // capacity other than being assigned without triggering a ReferenceError
    var y = (mickeyMouse = 4, emperorsNewClothes > x);  // mickeyMouse will not trigger a ReferenceError though
    alert(y);
  }-*/;


  private StackTraceDeobfuscationServiceAsync service = GWT.create(StackTraceDeobfuscationService.class);

  {
    ((ServiceDefTarget)service).setServiceEntryPoint(GWT.getModuleBaseURL() + "stackTraceDeobfuscationService");
  }

  private HTML htmlException = new HTML();
  private HTML htmlStackTrace = new HTML();

  public ExceptionSimulator() {
    HorizontalPanel pnlButtons = new HorizontalPanel();
    pnlButtons.add(new ButtonThatTriggersException("NullPointerException Demo") {
      public void triggerException() {
        triggerFakeNPE();
      }
    });
    pnlButtons.add(new ButtonThatTriggersException("RuntimeException Demo") {
      public void triggerException() {
        throwRuntimeException();
      }
    });
    pnlButtons.add(new ButtonThatTriggersException("JavascriptReferenceError Demo") {
      public void triggerException() {
        triggerJavascriptReferenceError();
      }
    });

    VerticalPanel pnlMain = new VerticalPanel();
    pnlMain.add(pnlButtons);
    pnlMain.add(new HTML("<h3>Caught Exception:</h3>"));
    pnlMain.add(htmlException);
    pnlMain.add(new HTML("<h3>Stack Trace:</h3>"));
    pnlMain.add(htmlStackTrace);
    pnlMain.setSpacing(5);
    initWidget(pnlMain);
  }

  private void deobfuscateStackTrace(final Throwable ex) {
    htmlException.setHTML(ex.toString());
    htmlStackTrace.setHTML("Deobfuscating stack trace...\n" +
        "      <img src=\"loading.gif\" alt=\"Loading...\"/>");
    service.deobfuscateStackTrace(ex.getStackTrace(), new AsyncCallback<String>() {
      public void onFailure(Throwable caught) {
        htmlStackTrace.setHTML("Deobfuscation error " + caught.toString());
      }
      public void onSuccess(String result) {
        htmlStackTrace.setHTML("<pre>" + result + "</pre>");
      }
    });
  }

  /**
   * Button widget which triggers an exception, catches it, and displays the
   * stack trace
   */
  private abstract class ButtonThatTriggersException extends Composite {
    protected ButtonThatTriggersException(String label) {
      initWidget(new Button(label, new ClickHandler() {
        public void onClick(ClickEvent event) {
          htmlException.setHTML("");
          htmlStackTrace.setHTML("");
          try {
            triggerException();
          }
          catch (Throwable ex) {
//            htmlStackTrace.setHTML(ex.getClass().getName() + ": " + ex.getMessage());
            deobfuscateStackTrace(ex);
          }
        }
      }));
    }
    public abstract void triggerException();
  }

}