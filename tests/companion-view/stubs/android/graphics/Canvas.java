package android.graphics;
import java.awt.*;
import java.awt.geom.*;
public class Canvas { public final Graphics2D g;
  public Canvas(Graphics2D g) { this.g = g; g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE); }
  private final java.util.ArrayDeque<Object[]> saved = new java.util.ArrayDeque<>();
  public int save() { saved.push(new Object[] {g.getTransform(), g.getClip()}); return saved.size(); }
  public void restore() { Object[] o = saved.pop(); g.setTransform((AffineTransform) o[0]); g.setClip((Shape) o[1]); }
  public boolean clipRect(float l, float t, float r, float b) { g.clip(new Rectangle2D.Float(l, t, r - l, b - t)); return true; }
  public void drawColor(int c) { g.setPaint(new Color(c, true)); g.fill(new Rectangle(-10000, -10000, 20000, 20000)); }
  public void translate(float x, float y) { g.translate(x, y); } public void scale(float x, float y) { g.scale(x, y); }
  private void draw(Shape s, Paint p) {
    if (p.style == Paint.Style.FILL && p.shadowR > 0) {  // a rough glow: a few widening translucent outlines
      Color sc = new Color(p.shadowColor, true);
      for (int i = 4; i >= 1; i--) { g.setPaint(new Color(sc.getRed(), sc.getGreen(), sc.getBlue(), sc.getAlpha() / 9));
        g.setStroke(new BasicStroke(p.shadowR * i / 2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); g.draw(s); } }
    g.setPaint(p.shader != null ? p.shader.awt : new Color(p.color, true));
    if (p.style == Paint.Style.STROKE) { g.setStroke(new BasicStroke(p.strokeWidth, p.cap == Paint.Cap.ROUND ? BasicStroke.CAP_ROUND : BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER)); g.draw(s); }
    else g.fill(s); }
  public void drawRect(float l, float t, float r, float b, Paint p) { draw(new Rectangle2D.Float(l, t, r - l, b - t), p); }
  public void drawPath(Path path, Paint p) { draw(path.p, p); }
  public void drawRoundRect(RectF r, float rx, float ry, Paint p) { draw(new RoundRectangle2D.Float(r.left, r.top, r.right - r.left, r.bottom - r.top, rx * 2, ry * 2), p); }
  public boolean clipPath(Path path) { g.clip(path.p); return true; }
  public void drawCircle(float x, float y, float r, Paint p) { draw(new Ellipse2D.Float(x - r, y - r, 2 * r, 2 * r), p); }
  public void drawLine(float x0, float y0, float x1, float y1, Paint p) { Paint.Style s = p.style; p.style = Paint.Style.STROKE; draw(new Line2D.Float(x0, y0, x1, y1), p); p.style = s; }
  public void drawArc(RectF r, float start, float sweep, boolean center, Paint p) { draw(new Arc2D.Float(r.left, r.top, r.right - r.left, r.bottom - r.top, -start, -sweep, center ? Arc2D.PIE : Arc2D.OPEN), p); }
  public void drawText(String s, float x, float y, Paint p) { Font f = p.font(); float w = p.measureText(s);
    if (p.align == Paint.Align.RIGHT) x -= w; else if (p.align == Paint.Align.CENTER) x -= w / 2;
    g.setFont(f); g.setPaint(new Color(p.color, true)); g.drawString(s, x, y); } }
