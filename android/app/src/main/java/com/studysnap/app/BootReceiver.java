package com.studysnap.app;

import android.content.*;

public class BootReceiver extends BroadcastReceiver {
  public void onReceive(Context context, Intent intent) {
    String action = intent.getAction();
    if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
        && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
    PendingResult pending = goAsync();
    new Thread(
            () -> {
              try {
                Reminders.restore(context, true);
              } finally {
                pending.finish();
              }
            },
            "study-restore")
        .start();
  }
}
