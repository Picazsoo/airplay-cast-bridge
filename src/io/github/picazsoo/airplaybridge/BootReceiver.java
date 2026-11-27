package io.github.picazsoo.airplaybridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (!Prefs.enabled(c)) return;
        BridgeService.start(c);
    }
}
