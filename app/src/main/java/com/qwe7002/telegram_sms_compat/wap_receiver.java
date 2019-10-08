package com.qwe7002.telegram_sms_compat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class wap_receiver extends BroadcastReceiver {
    @Override
    public void onReceive(final Context context, Intent intent) {
        final String log_tag = "wap_receiver";
        Log.d(log_tag, "Receive action: " + intent.getAction());
    }
}
