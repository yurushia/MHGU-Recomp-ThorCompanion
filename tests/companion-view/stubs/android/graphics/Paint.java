package android.graphics;
import java.awt.font.TextAttribute;
import java.util.HashMap;
public class Paint { public static final int ANTI_ALIAS_FLAG = 1;
  public enum Style { FILL, STROKE } public enum Align { LEFT, CENTER, RIGHT } public enum Cap { BUTT, ROUND }
  public Style style = Style.FILL; public int color = 0xff000000; public float strokeWidth = 1, textSize = 12, spacing; public Cap cap = Cap.BUTT;
  public Shader shader; public Typeface typeface; public Align align = Align.LEFT; public float shadowR; public int shadowColor;
  public Paint(int flags) {} public void setAntiAlias(boolean b) {}
  public void setStyle(Style s) { style = s; } public void setColor(int c) { color = c; } public void setStrokeWidth(float w) { strokeWidth = w; }
  public void setStrokeCap(Cap c) { cap = c; } public Shader setShader(Shader s) { shader = s; return s; }
  public void setShadowLayer(float r, float dx, float dy, int c) { shadowR = r; shadowColor = c; } public void clearShadowLayer() { shadowR = 0; }
  public void setTextSize(float s) { textSize = s; } public Typeface setTypeface(Typeface t) { typeface = t; return t; }
  public void setTextAlign(Align a) { align = a; } public void setLetterSpacing(float s) { spacing = s; }
  public java.awt.Font font() { HashMap<TextAttribute, Object> m = new HashMap<>(); m.put(TextAttribute.SIZE, textSize); m.put(TextAttribute.TRACKING, spacing);
    return typeface.font.deriveFont(m); }
  public float measureText(String s) { return (float) font().getStringBounds(s, new java.awt.font.FontRenderContext(null, true, true)).getWidth(); } }
