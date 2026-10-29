package com.studysnap.app;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class NavigationTest {
  private TextView find(View view, String text) {
    if (view instanceof TextView && text.contentEquals(((TextView) view).getText()))
      return (TextView) view;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
        TextView result = find(((ViewGroup) view).getChildAt(i), text);
        if (result != null) return result;
      }
    return null;
  }

  @Test
  public void onboardingLibraryImportAndSettingsRender() {
    Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    Context c = instrumentation.getTargetContext();
    c.getSharedPreferences("settings", 0).edit().clear().commit();
    Activity activity =
        instrumentation.startActivitySync(
            new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    try {
      instrumentation.runOnMainSync(
          () -> {
            View root = activity.getWindow().getDecorView();
            assertNotNull(find(root, "Make your next scroll\na study break."));
            find(root, "Get started  →").performClick();
            assertNotNull(find(root, "Let your notes\ncome to you."));
            find(root, "Continue without reminders").performClick();
            assertNotNull(find(root, "Your library"));
            assertNotNull(find(root, "Reminder status"));
            find(root, "Pause for today").performClick();
            assertTrue(Reminders.pauseUntil(c) > System.currentTimeMillis());
            find(root, "Resume reminders").performClick();
            assertEquals(0, Reminders.pauseUntil(c));
            find(root, "Send test reminder").performClick();
            assertEquals(
                1,
                c.getSystemService(android.app.NotificationManager.class)
                    .getActiveNotifications()
                    .length);
            find(root, "＋ Import").performClick();
            assertNotNull(find(root, "Choose PDF from device"));
            find(root, "Settings").performClick();
            assertNotNull(find(root, "Quiet hours"));
            assertNotNull(find(root, "Export backup"));
            assertNotNull(find(root, "Restore backup"));
            find(root, "Progress").performClick();
            assertNotNull(find(root, "Look how far\nyou’ve come."));
          });
    } finally {
      instrumentation.runOnMainSync(activity::finish);
      c.getSystemService(android.app.NotificationManager.class).cancelAll();
    }
  }
}
