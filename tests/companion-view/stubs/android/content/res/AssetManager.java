package android.content.res;
public class AssetManager { public static String root;
  public java.io.InputStream open(String name) throws java.io.IOException { return new java.io.FileInputStream(new java.io.File(root, name)); } }
