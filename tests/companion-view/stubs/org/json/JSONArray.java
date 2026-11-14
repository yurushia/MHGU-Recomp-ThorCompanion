package org.json;
public class JSONArray { final java.util.List<Object> l = new java.util.ArrayList<>();
  public int length() { return l.size(); }
  public JSONObject optJSONObject(int i) { Object o = i >= 0 && i < l.size() ? l.get(i) : null; return o instanceof JSONObject ? (JSONObject) o : null; } }
