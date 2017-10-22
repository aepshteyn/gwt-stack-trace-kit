This sample application demonstrates how to use the patch (../patch/README.txt)
that provides perfect stack traces in all browsers in production.

The patch consists of a jar file in the lib directory.
--------------------------------------------------------------------------------
Instructions:

Building and running the sample app:

1. Modify the "gwt.sdk" setting in build.properties to reflect the path of your
GWT 2.5.0 installation's root directory.  (This patch works only against GWT 2.5.0)

2. Build this project by running "ant war" and deploy the result,
StackTraceDemo.war, to any Java servlet container.

3. Take a look at build.xml and StackTraceDemo.gwt.xml for examples of how to use this patch
with your project.

Feel free to tinker with the settings in StackTraceDemo.gwt.xml to see the two
different ways of using the patch:

a) enable stack traces only on Chrome with no overhead (by uncommenting the
relevant lines in StackTraceDemo.gwt.xml)

b) enable stack traces in all browsers (default) with no overhead in Chrome
but some overhead for all other browser permutations.


--------------------------------------------------------------------------------
Donate:

If you found this project useful, please consider making a donation via
Paypal to alex@typeracer.com

Suggested donation for individuals: $25
Suggested donation for corporations: $500

Thanks for your support!

- Alex Epshteyn (alex@typeracer.com)

--------------------------------------------------------------------------------
Background Reading:

1) "Revising the GWT Compiler to Enable Java-like Stack Traces for Production-mode Exceptions"
http://goo.gl/YGsrQ

2) "Enable GWT to Preserve Java Debugging Info in Production"
http://igg.me/at/gwt-stack-traces/x/3494291

3) Discussion on Google Groups:
http://goo.gl/ekOuGr

--------------------------------------------------------------------------------
Copyright 2013 Alex Epshteyn
