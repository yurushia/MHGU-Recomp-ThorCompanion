package android.graphics;
import java.awt.Color;
public class RadialGradient extends Shader {
  public RadialGradient(float x, float y, float r, int[] cs, float[] ps, TileMode m) {
    Color[] c = new Color[cs.length]; for (int i = 0; i < cs.length; i++) c[i] = new Color(cs[i], true);
    awt = new java.awt.RadialGradientPaint(x, y, r, ps, c); } }
