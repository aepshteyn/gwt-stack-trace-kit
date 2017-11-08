// GWT jar: gwt-dev

package com.google.gwt.dev.js;


import com.google.gwt.core.linker.SymbolMapsLinker;
import com.google.gwt.dev.jjs.SourceInfo;
import com.google.gwt.dev.js.ast.JsContext;
import com.google.gwt.dev.js.ast.JsModVisitor;
import com.google.gwt.dev.js.ast.JsProgram;
import com.google.gwt.dev.js.ast.JsStringLiteral;
import com.google.gwt.dev.util.collect.KeyCounter;

import java.util.*;

/**
 * <p>Support class for JsStackEmulator that allows obfuscating the filenames that
 * need to be recorded while recording line numbers.
 * SymbolMapsLinker will output this info as a comment at the end of each permutation's
 * symbol map file:
 * <pre># obfuscated filenames: [Element.java,DOMImpl.java,...]</pre>
 * which means that "Element.java" is obfuscated as "0", DOMImpl.java as "1",
 * etc.
 * </p>
 * <p>
 * The obfuscation is performed in two phases:
 * 1) All the line numbers and filenames are recorded during the AST traversal
 * performed by JsStackEmulator.
 * 2) The plaintext filenames are replaced with numbers 0,1,...N, in descending order
 * of frequency, to get the most compact representation possible.
 * </p>
 *
 *
 * @author Alex Epshteyn
 * @since Apr 17, 2013
*/
public class FileNameObfuscator {

  public FileNameObfuscator() {
  }

  /** This set is filled during Phase 1, the data gathering phase */
  private Set<JsStringLiteral> locationExpressions = new HashSet<JsStringLiteral>();
  /** This map contains the final result: the plain->obfuscated filename mapping */
  private Map<String, String> obfuscatedNames;


  /** This method is repeatedly called during Phase 1 */
  public JsStringLiteral generateLocationExpression(SourceInfo info, int lineNumber, String fileName) {
    // 'fileName:lineNumber'
    JsStringLiteral locationExpr = new JsStringLiteral(info, fileName + ":" + String.valueOf(lineNumber));
    locationExpressions.add(locationExpr);
    return locationExpr;
  }

  private void updateFilenameCount(Map<String, KeyCounter<String>> counters, String fileName) {
    KeyCounter<String> counter = counters.get(fileName);
    if (counter == null) {
      counter = new KeyCounter<String>(fileName);
      counters.put(fileName, counter);
    }
    counter.increment();
  }

  /**
   * This method performs Phase 2 (the final obfuscation) and cleans up the
   * artifacts generated during Phase 1.
   */
  public void obfuscateNames(JsProgram jsProgram) {
    if (locationExpressions == null) {
      throw new IllegalStateException("FileNameObfuscator.obfuscateNames can only be called once");
    }
    /*
      We want to assign the shortest names to the files that appear most frequently
      however, the compiler already performs an optimization that extracts duplicate
      string literals into global variables, so we only care about counting filenames that
      appear in non-duplicate situations.
    */
    Set<String> uniqueLocations = new HashSet<String>();
    for (JsStringLiteral lit : locationExpressions) {
      uniqueLocations.add(lit.getValue());
    }
    // now count the frequencies of each filename in these locations
    Map<String, KeyCounter<String>> freqs = new LinkedHashMap<String, KeyCounter<String>>();
    for (String location : uniqueLocations) {
      String fileName = location.substring(0, location.indexOf(':'));
      updateFilenameCount(freqs, fileName);
    }
    // now sort the filenames by descending order of frequency, and assign ordinal names starting with 0
    List<KeyCounter<String>> freqsList = new ArrayList<KeyCounter<String>>(freqs.values());
    Collections.sort(freqsList);
    obfuscatedNames = new LinkedHashMap<String, String>(); // LinkedHashMap to preserve insertion order
    int i = 0;
    // simply assign ordinal numbers as obfuscated names (this will help StackTraceDeobfuscator understand what to do with a filename like this)
    for (KeyCounter<String> f : freqsList) {
      String name = f.getKey();
      obfuscatedNames.put(name, Integer.toString(i++));
    }
    // now replace all the expressions with the obfuscated names
    new ReplaceLocationExpressionsVisitor().accept(jsProgram);
    // now clean up the artifacts from Phase1, thereby locking in the results
    locationExpressions = null;
  }


  /**
   * @return an artifact that can be used to deobfuscate the filenames.
   */
  public SymbolMapsLinker.FilenameDeobfuscationArtifact makeArtifact(int permutationId) {
    if (obfuscatedNames == null)
      return null;
    StringBuilder str = new StringBuilder(1024);
    // print out the filename deobfuscation mapping created by FileNameObfuscator at the end of the symbol map file
    Iterator<String> filenameIter = obfuscatedNames.keySet().iterator();
    while (filenameIter.hasNext()) {
      str.append(filenameIter.next());
      if (filenameIter.hasNext())
        str.append(',');
    }
    return new SymbolMapsLinker.FilenameDeobfuscationArtifact(permutationId, str.toString());
  }

  /** A visitor to replace the JsExpressions created by generateLocationExpression with the obfuscated versions. */
  private class ReplaceLocationExpressionsVisitor extends JsModVisitor {
    @Override
    public void endVisit(JsStringLiteral x, JsContext ctx) {
      if (!locationExpressions.contains(x))
        return;  // this not an expression that was generated by generateLocationExpression
      // this is an expression that looks like 'fileName:lineNumber'
      // we're going to replace it with one that looks like 'obfuscatedFileName:lineNumber'
      String location = x.getValue();
      int idx = location.indexOf(':');
      String fileName = location.substring(0, idx);
      String lineNumber = location.substring(idx + 1);
      ctx.replaceMe(new JsStringLiteral(x.getSourceInfo(), obfuscatedNames.get(fileName) + ":" + lineNumber));
    }
  }
}
