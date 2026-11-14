// Gets what Android's installer says about an update (Updater.java commits the session with this receiver's address): its
// "Install?" prompt to show, then the result.
package io.github.morerokk.mhgurecomp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class UpdateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Updater.status(context, intent);
    }
}
