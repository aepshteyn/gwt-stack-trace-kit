/*
 * Copyright 2008 Google Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.gwt.dev.js;

import com.google.gwt.dev.js.ast.JsName;
import com.google.gwt.dev.js.ast.JsProgram;
import com.google.gwt.dev.js.ast.JsScope;
import com.google.gwt.dev.util.collect.KeyCounter;

import java.util.*;

/**
 * A namer that uses short, unrecognizable idents to minimize generated code
 * size.  The length of these short names will be inversely proportional to
 * their frequency, to reduce overall code size.
 */
public class JsObfuscateNamer extends JsNamer {

  /**
   * A lookup table of base-64 chars we use to encode idents.
   */
  private static final char[] sBase64Chars = new char[]{
      'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n',
      'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z', 'A', 'B',
      'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M', 'N', 'O', 'P',
      'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z', '$', '_', '0', '1',
      '2', '3', '4', '5', '6', '7', '8', '9'};

  public static void exec(JsProgram program) {
    new JsObfuscateNamer(program).execImpl();
  }

  /**
   * Communicates to a parent scope the maximum id used by any of its children.
   */
  private int maxChildId = 0;

  /**
   * A temp buffer big enough to hold at least 32 bits worth of base-64 chars.
   */
  private final char[] sIdentBuf = new char[6];

  private final Map<JsScope, Map<JsName, KeyCounter<JsName>>> refCountsByScope
      = new LinkedHashMap<JsScope, Map<JsName, KeyCounter<JsName>>>();

  public JsObfuscateNamer(JsProgram program) {
    super(program);
    countReferences();
  }

  @Override
  protected void reset() {
    maxChildId = 0;
  }

  @Override
  protected void visit(JsScope scope) {
    // Save off the maxChildId which is currently being computed for my parent.
    int mySiblingsMaxId = maxChildId;

    /*
     * Visit my children first. Reset maxChildId so that my children will get a
     * clean slate: I do not communicate to my children.
     */
    maxChildId = 0;
    for (JsScope child : scope.getChildren()) {
      visit(child);
    }
    // maxChildId is now the max of all of my children's ids

    // Visit my idents.
    int curId = maxChildId;

    // we visit them in descending order of frequency
    Map<JsName, KeyCounter<JsName>> refsInScope = refCountsByScope.get(scope);
    if (refsInScope != null) {
      List<KeyCounter<JsName>> refCounts = new ArrayList<KeyCounter<JsName>>(refsInScope.values());
      Collections.sort(refCounts);
      for (KeyCounter<JsName> refCount : refCounts) {
        JsName name = refCount.getKey();
        if (!name.isObfuscatable()) {
          // Unobfuscatable names become themselves.
          name.setShortIdent(name.getIdent());
          continue;
        }
        String newIdent;
        while (true) {
          // Get the next possible obfuscated name
          newIdent = makeObfuscatedIdent(curId++);
          if (isLegal(scope, newIdent)) {
            break;
          }
        }
        name.setShortIdent(newIdent);
      }
    }

    maxChildId = Math.max(mySiblingsMaxId, curId);
  }

  private boolean isLegal(JsScope scope, String newIdent) {
    if (JsKeywords.isKeyword(newIdent)) {
      return false;
    }
    /*
     * Never obfuscate a name into an identifier that conflicts with an existing
     * unobfuscatable name! It's okay if it conflicts with an existing
     * obfuscatable name, since that name will get obfuscated to something else
     * anyway.
     */
    return (scope.findExistingUnobfuscatableName(newIdent) == null);
  }

  private String makeObfuscatedIdent(int id) {
    // Use base-54 for the first character of the identifier,
    // so that we don't use any numbers (which are illegal at
    // the beginning of an identifier).
    //
    int i = 0;
    sIdentBuf[i++] = sBase64Chars[id % 54];
    id /= 54;

    // Use base-64 for the rest of the identifier.
    //
    while (id != 0) {
      sIdentBuf[i++] = sBase64Chars[id & 0x3f];
      id >>= 6;
    }

    return new String(sIdentBuf, 0, i);
  }

  private void addReference(JsName name) {
    JsScope scope = name.getEnclosing();
    Map<JsName, KeyCounter<JsName>> refsInScope = refCountsByScope.get(scope);
    if (refsInScope == null) {
      refCountsByScope.put(scope, refsInScope = new LinkedHashMap<JsName, KeyCounter<JsName>>(4));
    }
    KeyCounter<JsName> nameCounter = refsInScope.get(name);
    if (nameCounter == null) {
      refsInScope.put(name, nameCounter = new KeyCounter<JsName>(name));
    }
    nameCounter.increment();
  }

  private void countReferences() {
    new RefVisitor() {
      protected void doAddReference(JsName name) {
        addReference(name);
      }
    }.accept(program);
  }

}