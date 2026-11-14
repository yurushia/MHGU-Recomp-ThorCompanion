package org.json;
import java.util.*;
public class JSONObject { final Map<String, Object> m = new LinkedHashMap<>(); private String s; private int i;
  JSONObject() {}
  public JSONObject(String text) throws JSONException { s = text; i = 0; Object o = value(); if (!(o instanceof JSONObject)) throw new JSONException("not an object"); m.putAll(((JSONObject) o).m); }
  private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
  private Object value() throws JSONException { ws(); char c = s.charAt(i);
    if (c == '{') { i++; JSONObject o = new JSONObject(); ws(); if (s.charAt(i) == '}') { i++; return o; }
      for (;;) { ws(); String k = (String) value(); ws(); i++; o.m.put(k, value()); ws(); if (s.charAt(i++) == '}') return o; } }
    if (c == '[') { i++; JSONArray a = new JSONArray(); ws(); if (s.charAt(i) == ']') { i++; return a; }
      for (;;) { a.l.add(value()); ws(); if (s.charAt(i++) == ']') return a; } }
    if (c == '"') { StringBuilder b = new StringBuilder(); i++;
      while (s.charAt(i) != '"') { char d = s.charAt(i++); if (d == '\\') { d = s.charAt(i++); if (d == 'u') { d = (char) Integer.parseInt(s.substring(i, i + 4), 16); i += 4; } } b.append(d); }
      i++; return b.toString(); }
    int j = i; while (i < s.length() && ",}] ".indexOf(s.charAt(i)) < 0) i++;
    String t = s.substring(j, i); if (t.equals("true")) return Boolean.TRUE; if (t.equals("false")) return Boolean.FALSE; if (t.equals("null")) return null;
    try { return Double.valueOf(t); } catch (NumberFormatException e) { throw new JSONException(t); } }
  public String optString(String k) { Object o = m.get(k); return o == null ? "" : o instanceof Double && ((Double) o) == Math.rint((Double) o) ? String.valueOf(((Double) o).longValue()) : o.toString(); }
  public int optInt(String k) { return optInt(k, 0); }
  public int optInt(String k, int d) { Object o = m.get(k); return o instanceof Double ? ((Double) o).intValue() : d; }
  public double optDouble(String k, double d) { Object o = m.get(k); return o instanceof Double ? (Double) o : d; }
  public boolean optBoolean(String k) { return Boolean.TRUE.equals(m.get(k)); }
  public JSONObject optJSONObject(String k) { Object o = m.get(k); return o instanceof JSONObject ? (JSONObject) o : null; }
  public JSONArray optJSONArray(String k) { Object o = m.get(k); return o instanceof JSONArray ? (JSONArray) o : null; } }
