package android.view;
public class View { public int w, h; public View(android.content.Context c) {}
  public int getWidth() { return w; } public int getHeight() { return h; } public void invalidate() {} public void postInvalidate() {}
  protected void onDraw(android.graphics.Canvas c) {} public boolean onTouchEvent(MotionEvent e) { return false; }
  public void draw(android.graphics.Canvas c) { onDraw(c); } }
