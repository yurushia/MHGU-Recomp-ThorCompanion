package android.graphics;
public class Path { public java.awt.geom.Path2D.Float p = new java.awt.geom.Path2D.Float();
  public void rewind() { p.reset(); } public void moveTo(float x, float y) { p.moveTo(x, y); }
  public void lineTo(float x, float y) { p.lineTo(x, y); } public void quadTo(float a, float b, float c, float d) { p.quadTo(a, b, c, d); } public void close() { p.closePath(); } }
