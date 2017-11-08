package solutions.trsoftware.gwt.patch;

import com.google.gwt.dev.CompileModule;
import solutions.trsoftware.commons.server.io.FileSet;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Uses {@link CompileModule} to rebuild the {@code .gwtar} files that may need to be recompiled
 *
 * @author Alex, 10/27/2017
 */
public class CompileModules {

  private File jarDir;

  public CompileModules(File jarDir) {
    this.jarDir = jarDir;
  }

  public void run() throws IOException {
    FileSet gwtars = new FileSet(jarDir, (dir, name) -> name.endsWith(".gwtar"));
    List<String> args = new ArrayList<>();
    args.add("-out");
    args.add(jarDir.getPath());
    System.out.printf("Found %d .gwtar files:%n", gwtars.size());
    for (File file : gwtars) {
      System.out.println(file);
      // find the corresponding module file
      String moduleName = PatchGwtDistro.getFilenameWithoutExtension(file);
      File moduleFile = new File(file.getParentFile(), moduleName + ".gwt.xml");
      assert moduleFile.exists();
      System.out.println("  -> " + moduleFile);
      String moduleFqName = getModuleName(moduleName, moduleFile);
      System.out.println("  ---> " + moduleFqName);
      args.add(moduleFqName);
    }
    CompileModule.main(args.toArray(new String[args.size()]));
  }

  /**
   * @return the fully-qualified name of the given module
   */
  private String getModuleName(String moduleName, File moduleFile) {
    String modulePath = PatchGwtDistro.getRelativePath(moduleFile, jarDir).substring(1);
    String fileSepRegex = File.separatorChar == '\\' ? "\\\\" : File.separator;
    return modulePath.replaceAll(fileSepRegex, ".") + "." + moduleName;
  }


  public static void main(String[] args) throws IOException {
    new CompileModules(new File(args[0])).run();
  }

}
