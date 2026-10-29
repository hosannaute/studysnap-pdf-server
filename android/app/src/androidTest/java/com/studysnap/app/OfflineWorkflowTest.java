package com.studysnap.app;

import static org.junit.Assert.*;

import android.content.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class OfflineWorkflowTest {
  private Context context;

  @Before
  public void setup() {
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    context.getSystemService(android.app.NotificationManager.class).cancelAll();
    context.deleteDatabase("studysnap.db");
    context.getSharedPreferences("settings", 0).edit().clear().commit();
    PDFBoxResourceLoader.init(context);
  }

  @After
  public void cleanup() {
    try (Store db = new Store(context)) {
      for (Store.Subject s : db.subjects()) Reminders.cancel(context, s.id);
    }
    context.getSystemService(android.app.NotificationManager.class).cancelAll();
    context.deleteDatabase("studysnap.db");
  }

  @Test
  public void pdfToPersistentReaderAndHistory() throws Exception {
    String text;
    try (PDDocument doc =
        PDDocument.load(
            InstrumentationRegistry.getInstrumentation()
                .getContext()
                .getAssets()
                .open("notes.pdf"))) {
      assertEquals(1, doc.getNumberOfPages());
      text = new PDFTextStripper().getText(doc);
    }
    assertTrue(text.contains("entirely offline"));
    long id;
    try (Store db = new Store(context)) {
      id = db.insert("Biology", StudyRules.chunks(text));
      assertEquals(1, db.subject(id).total);
      assertTrue(db.chunk(id, 0).text.contains("cell membrane"));
      db.seen(db.chunk(id, 0).id);
      assertEquals(1, db.today());
      assertEquals(1, db.streak());
      assertEquals("Biology", db.mostActive());
    }
    try (Store reopened = new Store(context)) {
      assertEquals(1, reopened.subject(id).seen);
      assertTrue(reopened.chunk(id, 0).text.contains("entirely offline"));
      reopened.delete(id);
      assertTrue(reopened.subjects().isEmpty());
      assertEquals(0, reopened.today());
    }
  }

  @Test
  public void unseenFirstThenOldestRotation() {
    try (Store db = new Store(context)) {
      long id =
          db.insert("Physics", Arrays.asList("First section", "Second section", "Third section"));
      for (int i = 0; i < 3; i++) {
        Store.Chunk next = db.next(id);
        assertEquals(i, next.page);
        db.seen(next.id);
      }
      assertEquals(0, db.next(id).page);
    }
  }

  @Test
  public void notificationTapOpensTheMatchingSection() throws Exception {
    android.app.Instrumentation inst = InstrumentationRegistry.getInstrumentation();
    try (Store db = new Store(context)) {
      long id =
          db.insert(
              "Chemistry",
              Arrays.asList(
                  "First chemistry section.",
                  "Second chemistry section opens from a notification."));
      db.seen(db.chunk(id, 0).id);
      db.update(id, "active", 1);
      Reminders.deliver(context, id);
      android.service.notification.StatusBarNotification[] posted =
          context.getSystemService(android.app.NotificationManager.class).getActiveNotifications();
      assertEquals(1, posted.length);
      android.app.Instrumentation.ActivityMonitor monitor =
          inst.addMonitor(MainActivity.class.getName(), null, false);
      android.app.Activity activity = null;
      try {
        posted[0].getNotification().contentIntent.send();
        activity = inst.waitForMonitorWithTimeout(monitor, 30000);
        assertNotNull("Notification must open the reader", activity);
        assertEquals(1, db.subject(id).reader);
      } finally {
        inst.removeMonitor(monitor);
        if (activity != null) {
          android.app.Activity opened = activity;
          inst.runOnMainSync(opened::finish);
        }
      }
    }
  }

  @Test
  public void failedInsertRollsBackSubjectAndChunks() {
    try (Store db = new Store(context)) {
      try {
        db.insert("Broken", Arrays.asList("Valid", null));
        fail("Expected invalid chunk to fail");
      } catch (Exception expected) {
      }
      assertTrue(db.subjects().isEmpty());
    }
  }

  @Test
  public void remindersRespectToggleQuietHoursAndCancellation() {
    assertTrue(Reminders.allowed(context));
    try (Store db = new Store(context)) {
      long id =
          db.insert("Biology", Arrays.asList("An offline study notification with useful content."));
      db.update(id, "active", 1);
      Reminders.schedule(context, db, id, true);
      assertTrue(db.subject(id).next > System.currentTimeMillis());
      Reminders.deliver(context, id);
      assertEquals(1, db.subject(id).seen);
      assertTrue(
          context
                  .getSystemService(android.app.NotificationManager.class)
                  .getActiveNotifications()
                  .length
              > 0);
      db.update(id, "active", 0);
      Reminders.schedule(context, db, id, true);
      assertEquals(0, db.subject(id).next);
      assertEquals(
          0,
          context
              .getSystemService(android.app.NotificationManager.class)
              .getActiveNotifications()
              .length);
      context
          .getSharedPreferences("settings", 0)
          .edit()
          .putBoolean("quiet", true)
          .putInt("quiet_start", 22)
          .putInt("quiet_end", 8)
          .commit();
      Calendar c = Calendar.getInstance();
      c.set(2026, 9, 29, 23, 15, 0);
      long adjusted = Reminders.outsideQuiet(context, c.getTimeInMillis());
      c.setTimeInMillis(adjusted);
      assertEquals(30, c.get(Calendar.DAY_OF_MONTH));
      assertEquals(8, c.get(Calendar.HOUR_OF_DAY));
      assertEquals(0, c.get(Calendar.MINUTE));
    }
  }
}
