package solutions.trsoftware.gwt.patch;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import solutions.trsoftware.commons.server.io.FileSet;

import java.io.*;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

/**
 * Copies the files from our patch to the exploded {@code gwt-dev.jar} and {@code gwt-user.jar}
 *
 * @author Alex, 10/27/2017
 */
public class PatchGwtDistro {

  private File gwtJarsRoot;
  private File patchSrcRoot;
  private File patchClassesRoot;

  public PatchGwtDistro(File gwtJarsRoot, File patchSrcRoot, File patchClassesRoot) {
    this.gwtJarsRoot = gwtJarsRoot;
    this.patchSrcRoot = patchSrcRoot;
    this.patchClassesRoot = patchClassesRoot;
  }

  public void run() throws IOException {
    FileSet gwtFileSet = new FileSet(gwtJarsRoot, File::isFile);
    FileSet srcFileSet = new FileSet(patchSrcRoot, File::isFile);
    System.out.println("gwtFiles.size() = " + gwtFileSet.size());
    System.out.println("srcFiles.size() = " + srcFileSet.size());
    // map the files by name
    Multimap<String, File> gwtFilesByName = ArrayListMultimap.create();
    Multimap<String, File> srcFilesByName = ArrayListMultimap.create();
    // remove the base dir and extension from the file names
    for (File file : gwtFileSet) {
      gwtFilesByName.put(getFilenameWithoutExtension(file), file);
    }
    for (File file : srcFileSet) {
      srcFilesByName.put(getFilenameWithoutExtension(file), file);
    }
    Patch patch = new Patch();
    boolean allSrcFilesMapped = true;
    for (String srcFileName : srcFilesByName.keySet()) {
      List<File> srcFiles = new ArrayList<>(srcFilesByName.get(srcFileName));
      List<File> matchingGwtFiles = new ArrayList<>(gwtFilesByName.get(srcFileName));
      System.out.printf("%s (%s):%n", srcFileName, srcFiles);
      if (srcFiles.size() > 1)
        System.out.println("WARNING: multiple src files with this name");
      File srcFile = srcFiles.get(0);
      String srcFileExt = getFilenameExtension(srcFile);
      if (!matchingGwtFiles.isEmpty()) {
        for (File gwtFile : matchingGwtFiles) {
          String gwtFileExt = getFilenameExtension(gwtFile);
          switch (gwtFileExt) {
            case ".java":
              assert srcFileExt.equals(".java");
              patch.copy(srcFile, gwtFile);
              break;
            case ".class":
              assert srcFileExt.equals(".java");
              // TODO: cont here: delete all the inner classes from gwt and copy all the inner classes from patch
              File gwtFileParentDir = gwtFile.getParentFile();
              patch.delete(findInnerClasses(srcFileName, gwtFileParentDir));
              // copy the compiled version of our src file
              patch.copy(getSrcClassFiles(srcFileName, srcFile), gwtFileParentDir);
              break;
            default:
              assert gwtFileExt.equals(".gwt.xml") : gwtFile;
              patch.copy(srcFile, gwtFile);
              break;
          }
          String gwtFileRelativePath = gwtFile.getPath().substring(gwtJarsRoot.getPath().length());
          System.out.println("  -> " + gwtFileRelativePath);
        }
      }
      else {
        // this src file didn't match any existing gwt files, so it should contain a comment line like "// GWT jar: gwt-dev"
        String gwtJar = getJarMapping(srcFile);
        if (gwtJar != null) {
          String targetPath = gwtJarsRoot.getPath() + File.separator + gwtJar + getRelativePath(srcFile, patchSrcRoot);
          System.out.println("  -> " + targetPath + File.separator);
          File targetFile = new File(targetPath, srcFile.getName());
          patch.copy(srcFile, targetFile);
          if (srcFileExt.equals(".java")) {
            patch.copy(getSrcClassFiles(srcFileName, srcFile), new File(targetPath));
          }
        }
        else {
          allSrcFilesMapped = false;
          System.out.println("WARNING: need mapping for " + srcFile);
        }
      }

    }
    assert allSrcFilesMapped : "Copy tasks will not be run, since not all src files have been mapped";
    System.out.println("--------------------------------------------------------------------------------");
    System.out.println("Applying patch:");
    System.out.println("--------------------------------------------------------------------------------");
    patch.apply();
    // TODO: temp
//    for (FileCopyTask copyTask : copyTasks) {
//      System.out.println(copyTask);
//      copyTask.execute();
//    }
  }

  private String getJarMapping(File srcFile) throws IOException {
    String linePrefix = "// GWT jar: ";
    try (BufferedReader br = new BufferedReader(new FileReader(srcFile))) {
      for (String line = br.readLine(); line != null; line = br.readLine()) {
        if (line.startsWith(linePrefix))
          return line.substring(linePrefix.length());
      }
    }
    return null;
  }

  private File getSrcClassFile(String srcFileName, File srcFile) {
    String srcFileRelativePath = getRelativePath(srcFile, patchSrcRoot);
    return new File(patchClassesRoot.getPath() + srcFileRelativePath,  srcFileName + ".class");
  }

  /**
   * Finds the {@code .class} files for the class with the given name in the source compiler output directory.
   * @return the {@code .class} files of the main class and all its inner classes
   */
  private Set<File> getSrcClassFiles(String clsName, File srcFile) {
    File mainClassFile = getSrcClassFile(clsName, srcFile);
    Set<File> ret = findInnerClasses(clsName, mainClassFile.getParentFile());
    ret.add(mainClassFile);
    return ret;
  }

  private Set<File> findInnerClasses(String clsName, File dir) {
    return new FileSet(dir, (dir1, name) -> name.startsWith(clsName + "$"));
  }

  public static String getRelativePath(File file, File baseDir) {
    return file.getParentFile().getPath().substring(baseDir.getPath().length());
  }

  public static String getFilenameWithoutExtension(File file) {
    String name = file.getName();
    if (!name.endsWith(".gwt.xml")) {
      int idxOfDot = name.indexOf('.');
      if (idxOfDot > 0)
        name = name.substring(0, idxOfDot);
    }
    return name;
  }

  public static String getFilenameExtension(File file) {
    String name = file.getName();
    int idxOfDot = name.indexOf('.');
    if (idxOfDot >= 0)
      return name.substring(idxOfDot);
    return "";  // no extension
  }

  public static void main(String[] args) throws IOException {
    new PatchGwtDistro(new File(args[0]), new File(args[1]), new File(args[2])).run();
  }

  interface FileTask {
    void execute() throws IOException;
  }

  public static class CopyFileTask implements FileTask {
    private File from;
    private File to;

    public CopyFileTask(File from, File to) {
      this.from = from;
      this.to = to;
    }

    @Override
    public void execute() throws IOException {
      Files.copy(from.toPath(), to.toPath(), REPLACE_EXISTING);
    }

    @Override
    public String toString() {
      return "Copy " + from + " -> " + to;
    }
  }

  public static class DeleteFileTask implements FileTask {
    private File file;

    public DeleteFileTask(File file) {
      this.file = file;
    }

    @Override
    public void execute() throws IOException {
      Files.delete(file.toPath());
    }

    @Override
    public String toString() {
      return "Delete " + file;
    }
  }

  public static class Patch {
    private List<FileTask> tasks = new ArrayList<>();

    public void copy(File from, File to) {
      tasks.add(new CopyFileTask(from, to));
    }

    public void copy(Set<File> files, File toDir) {
      for (File file : files) {
        copy(file, new File(toDir, file.getName()));
      }
    }

    public void delete(File file) {
      tasks.add(new DeleteFileTask(file));
    }

    public void delete(Set<File> files) {
      for (File file : files) {
        delete(file);
      }
    }

    public void apply() throws IOException {
      for (FileTask task : tasks) {
        System.out.println(task);
        task.execute();
      }
    }
  }

}
