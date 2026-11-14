package android.graphics;
import java.awt.Color;
public class LinearGradient extends Shader {
  static Color col(int c) { return new Color(c, true); }
  public LinearGradient(float x0, float y0, float x1, float y1, int c0, int c1, TileMode m) { this(x0, y0, x1, y1, new int[] {c0, c1}, new float[] {0, 1}, m); }
  public LinearGradient(float x0, float y0, float x1, float y1, int[] cs, float[] ps, TileMode m) {
    Color[] c = new Color[cs.length]; for (int i = 0; i < cs.length; i++) c[i] = col(cs[i]);
    awt = new java.awt.LinearGradientPaint(x0, y0, x1, y1, ps, c); } }
