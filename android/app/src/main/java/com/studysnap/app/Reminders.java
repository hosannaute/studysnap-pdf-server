package com.studysnap.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import java.util.*;

public final class Reminders {
  static final String CHANNEL = "study_reminders";

  public static void channel(Context c) {
    NotificationChannel channel =
        new NotificationChannel(CHANNEL, "Study reminders", NotificationManager.IMPORTANCE_HIGH);
    channel.setDescription("Short excerpts from your own notes");
    c.getSystemService(NotificationManager.class).createNotificationChannel(channel);
  }

  public static boolean allowed(Context c) {
    channel(c);
    return (Build.VERSION.SDK_INT < 33
            || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED)
        && c.getSystemService(NotificationManager.class).areNotificationsEnabled()
        && c.getSystemService(NotificationManager.class)
                .getNotificationChannel(CHANNEL)
                .getImportance()
            != NotificationManager.IMPORTANCE_NONE;
  }

  private static PendingIntent alarm(Context c, long id) {
    Intent i = new Intent(c, StudyReceiver.class).setAction("study." + id).putExtra("subject", id);
    return PendingIntent.getBroadcast(
        c, (int) id, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  public static void cancel(Context c, long id) {
    c.getSystemService(AlarmManager.class).cancel(alarm(c, id));
    c.getSystemService(NotificationManager.class).cancel((int) id);
  }

  public static void schedule(Context c, Store db, long id, boolean reset) {
    Store.Subject s = db.subject(id);
    if (s == null) return;
    if (!s.active || !allowed(c)) {
      cancel(c, id);
      db.update(id, "next_at", 0);
      return;
    }
    long now = System.currentTimeMillis();
    long next =
        (!reset && s.next > now) ? s.next : (s.snooze > now ? s.snooze : now + s.minutes * 60000L);
    List<Long> others = new ArrayList<>();
    for (Store.Subject o : db.subjects())
      if (o.id != id && o.active && o.next > now) others.add(o.next);
    next = Math.max(next, Math.max(pauseUntil(c), s.snooze));
    // Move past quiet hours, then separate alarms. Repeat if separation enters quiet hours.
    for (int i = 0; i <= others.size() + 1; i++) {
      long before = next;
      next = outsideQuiet(c, next);
      next = StudyRules.avoidCollision(next, others);
      if (next == before) break;
    }
    db.update(id, "next_at", next);
    // Inexact alarms respect Android's battery policy; exact-alarm permission is unnecessary.
    c.getSystemService(AlarmManager.class)
        .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, alarm(c, id));
  }

  public static long outsideQuiet(Context c, long time) {
    SharedPreferences p = c.getSharedPreferences("settings", 0);
    if (!p.getBoolean("quiet", false)) return time;
    int start = p.getInt("quiet_start", 22), end = p.getInt("quiet_end", 8);
    Calendar cal = Calendar.getInstance();
    cal.setTimeInMillis(time);
    if (StudyRules.quiet(cal.get(Calendar.HOUR_OF_DAY), start, end)) {
      if (start > end && cal.get(Calendar.HOUR_OF_DAY) >= start) cal.add(Calendar.DAY_OF_YEAR, 1);
      cal.set(Calendar.HOUR_OF_DAY, end);
      cal.set(Calendar.MINUTE, 0);
      cal.set(Calendar.SECOND, 0);
      cal.set(Calendar.MILLISECOND, 0);
    }
    return cal.getTimeInMillis();
  }

  private static PendingIntent action(Context c, long id, String name) {
    return PendingIntent.getBroadcast(
        c,
        (int) id,
        new Intent(c, StudyReceiver.class)
            .setAction(name + "." + id)
            .putExtra("subject", id)
            .putExtra("control", name),
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  public static long pauseUntil(Context c) {
    return c.getSharedPreferences("settings", 0).getLong("pause_until", 0);
  }

  public static void pauseToday(Context c) {
    Calendar tomorrow = Calendar.getInstance();
    tomorrow.add(Calendar.DAY_OF_YEAR, 1);
    tomorrow.set(Calendar.HOUR_OF_DAY, 0);
    tomorrow.set(Calendar.MINUTE, 0);
    tomorrow.set(Calendar.SECOND, 0);
    tomorrow.set(Calendar.MILLISECOND, 0);
    c.getSharedPreferences("settings", 0)
        .edit()
        .putLong("pause_until", tomorrow.getTimeInMillis())
        .commit();
    try (Store db = new Store(c)) {
      for (Store.Subject s : db.subjects()) cancel(c, s.id);
    }
    restore(c, true);
  }

  public static void resume(Context c) {
    c.getSharedPreferences("settings", 0).edit().remove("pause_until").commit();
    restore(c, true);
  }

  public static void snooze(Context c, long id) {
    try (Store db = new Store(c)) {
      if (db.subject(id) == null) return;
      db.update(id, "snooze_until", System.currentTimeMillis() + 15 * 60000L);
      cancel(c, id);
      schedule(c, db, id, true);
    }
  }

  public static void unsnooze(Context c, long id) {
    try (Store db = new Store(c)) {
      db.update(id, "snooze_until", 0);
      schedule(c, db, id, true);
    }
  }

  public static String status(Context c, Store.Subject s) {
    if (!allowed(c)) return "Notifications are blocked in Android settings.";
    if (s != null && !s.active) return "Reminders are off for this subject.";
    if (pauseUntil(c) > System.currentTimeMillis())
      return "Paused until tomorrow. Reading is still available.";
    if (s != null && s.snooze > System.currentTimeMillis())
      return "Snoozed until "
          + android.text.format.DateFormat.getTimeFormat(c).format(new Date(s.snooze))
          + ".";
    if (outsideQuiet(c, System.currentTimeMillis()) > System.currentTimeMillis() + 1000)
      return "Quiet hours are active. Reminders resume afterward.";
    if (s != null && s.next > 0)
      return "Next reminder around "
          + android.text.format.DateFormat.getDateFormat(c).format(new Date(s.next))
          + " "
          + android.text.format.DateFormat.getTimeFormat(c).format(new Date(s.next))
          + ". Android may delay delivery.";
    return "Notifications are allowed. Enable a subject to start reminders.";
  }

  /** Explicit test bypasses quiet hours/pause, never changes reading progress or schedules. */
  public static boolean test(Context c, long subject) {
    if (!allowed(c)) return false;
    try (Store db = new Store(c)) {
      Store.Subject s = db.subject(subject);
      Store.Chunk x = s == null ? null : db.next(subject);
      Intent open =
          new Intent(c, MainActivity.class)
              .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
      if (x != null) open.putExtra("subject", subject).putExtra("page", x.page);
      PendingIntent tap =
          PendingIntent.getActivity(
              c, -1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
      Notification n =
          new Notification.Builder(c, CHANNEL)
              .setSmallIcon(R.drawable.ic_notification)
              .setContentTitle("StudySnap · Test reminder")
              .setContentText(
                  x == null
                      ? "Your study reminders can appear here. Import notes to get started."
                      : x.preview)
              .setStyle(
                  new Notification.BigTextStyle()
                      .bigText(x == null ? "Your study reminders can appear here." : x.preview))
              .setContentIntent(tap)
              .setAutoCancel(true)
              .build();
      c.getSystemService(NotificationManager.class).notify(-1, n);
      return true;
    }
  }

  public static void restore(Context c, boolean reset) {
    try (Store db = new Store(c)) {
      for (Store.Subject s : db.subjects()) schedule(c, db, s.id, reset);
    }
  }

  public static void deliver(Context c, long id) {
    try (Store db = new Store(c)) {
      Store.Subject s = db.subject(id);
      if (s == null || !s.active) return;
      long now = System.currentTimeMillis();
      if (pauseUntil(c) > now
          || s.snooze > now
          || !allowed(c)
          || outsideQuiet(c, System.currentTimeMillis()) > System.currentTimeMillis() + 1000) {
        schedule(c, db, id, true);
        return;
      }
      Store.Chunk chunk = db.next(id);
      if (chunk == null) return;
      Intent open =
          new Intent(c, MainActivity.class)
              .putExtra("subject", id)
              .putExtra("page", chunk.page)
              .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
      PendingIntent tap =
          PendingIntent.getActivity(
              c, (int) id, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
      Notification n =
          new Notification.Builder(c, CHANNEL)
              .setSmallIcon(com.studysnap.app.R.drawable.ic_notification)
              .setContentTitle(s.title)
              .setContentText(chunk.preview)
              .setStyle(new Notification.BigTextStyle().bigText(chunk.preview))
              .setContentIntent(tap)
              .addAction(
                  new Notification.Action.Builder(null, "Snooze 15 min", action(c, id, "snooze"))
                      .build())
              .addAction(
                  new Notification.Action.Builder(null, "Pause today", action(c, id, "pause"))
                      .build())
              .setAutoCancel(true)
              .setCategory(Notification.CATEGORY_REMINDER)
              .build();
      c.getSystemService(NotificationManager.class).notify((int) id, n);
      // 'Seen' means posted by the app, not proof the user read the notification.
      db.seen(chunk.id);
      schedule(c, db, id, true);
    }
  }
}
