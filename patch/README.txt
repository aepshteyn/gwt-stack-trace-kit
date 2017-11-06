This source tree contains all the files needed to patch GWT 2.5 to provide
perfect stack traces in all browsers in production.

--------------------------------------------------------------------------------
Instructions:

1. Modify the "gwt.sdk" setting in build.properties to reflect the path of your
GWT 2.5.0 installation's root directory.  (This patch works only against GWT 2.5.0)

2. Build this project by running "ant build"

3. Copy the output .jar file from the ./build directory to your project

Take a look at the example application in ../sample to see how you can
integrate this patch into your GWT-based application.

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