package com.studysnap.app;

import android.content.*;

public class StudyReceiver extends BroadcastReceiver {
  public void onReceive(Context context, Intent intent) {
    PendingResult pending = goAsync();
    new Thread(
            () -> {
              try {
                String control = intent.getStringExtra("control");
                if ("snooze".equals(control))
                  Reminders.snooze(context, intent.getLongExtra("subject", -1));
                else if ("pause".equals(control)) Reminders.pauseToday(context);
                else Reminders.deliver(context, intent.getLongExtra("subject", -1));
              } finally {
                pending.finish();
              }
            },
            "study-reminder")
        .start();
  }
}
