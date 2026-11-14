package android.graphics;
public class Typeface { public static final int NORMAL = 0, BOLD = 1; public java.awt.Font font;
  public static Typeface createFromAsset(android.content.res.AssetManager am, String path) {
    try { Typeface t = new Typeface(); t.font = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, new java.io.File(android.content.res.AssetManager.root, path)); return t; }
    catch (Exception e) { throw new RuntimeException(e); } }
  public static Typeface create(String name, int style) { Typeface t = new Typeface(); t.font = new java.awt.Font("SansSerif", style, 1); return t; } }
