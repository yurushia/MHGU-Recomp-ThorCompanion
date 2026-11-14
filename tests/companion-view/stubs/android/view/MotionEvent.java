package android.view;
/** Just enough of MotionEvent for CompanionView: built by the test, read like the real one. */
public class MotionEvent {
    public static final int ACTION_DOWN = 0, ACTION_UP = 1, ACTION_MOVE = 2, ACTION_CANCEL = 3;
    private final int action; private final float x, y; private final long time;
    private MotionEvent(int action, float x, float y, long time) { this.action = action; this.x = x; this.y = y; this.time = time; }
    public static MotionEvent obtain(long downTime, long eventTime, int action, float x, float y, int metaState) {
        return new MotionEvent(action, x, y, eventTime);
    }
    public int getActionMasked() { return action; }
    public float getX() { return x; }
    public float getY() { return y; }
    public long getEventTime() { return time; }
}
