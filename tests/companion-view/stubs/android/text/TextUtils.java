package android.text;
public class TextUtils { public enum TruncateAt { END }
  public static CharSequence ellipsize(CharSequence s, TextPaint p, float width, TruncateAt at) {
    String t = s.toString(); if (p.measureText(t) <= width) return t;
    while (t.length() > 1 && p.measureText(t + "…") > width) t = t.substring(0, t.length() - 1);
    return t + "…"; } }
