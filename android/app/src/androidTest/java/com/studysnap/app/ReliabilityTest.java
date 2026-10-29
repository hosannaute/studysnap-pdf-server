package com.studysnap.app;

import static org.junit.Assert.*;

import android.app.NotificationManager;
import android.content.*;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ReliabilityTest {
  private Context c;

  @Before
  public void setup() {
    c = InstrumentationRegistry.getInstrumentation().getTargetContext();
    c.deleteDatabase("studysnap.db");
    c.getSharedPreferences("settings", 0).edit().clear().commit();
    c.getSystemService(NotificationManager.class).cancelAll();
    com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(c);
  }

  @After
  public void cleanup() {
    try (Store db = new Store(c)) {
      for (Store.Subject s : db.subjects()) Reminders.cancel(c, s.id);
    }
    c.getSystemService(NotificationManager.class).cancelAll();
    c.deleteDatabase("studysnap.db");
  }

  private File fixture(String name) throws Exception {
    File f = File.createTempFile("fixture-", ".pdf", c.getCacheDir());
    try (InputStream in =
            InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(name);
        OutputStream out = new FileOutputStream(f)) {
      byte[] b = new byte[8192];
      int n;
      while ((n = in.read(b)) != -1) out.write(b, 0, n);
    }
    return f;
  }

  @Test
  public void importReportsProgressAndExtractsText() throws Exception {
    File f = fixture("notes.pdf");
    try {
      List<Integer> progress = new ArrayList<>();
      List<String> chunks =
          PdfImporter.read(
              c,
              Uri.fromFile(f),
              new PdfImporter.Cancel(),
              (message, value) -> progress.add(value));
      assertTrue(chunks.get(0).contains("entirely offline"));
      assertEquals(Integer.valueOf(0), progress.get(0));
      assertEquals(Integer.valueOf(95), progress.get(progress.size() - 1));
      for (int i = 1; i < progress.size(); i++) assertTrue(progress.get(i) >= progress.get(i - 1));
    } finally {
      f.delete();
    }
  }

  @Test
  public void unsupportedPdfsHaveActionableErrors() throws Exception {
    String[] files = {"scanned.pdf", "protected.pdf", "too-many-pages.pdf"};
    String[] expected = {"OCR", "password", "100 pages"};
    for (int i = 0; i < files.length; i++) {
      File f = fixture(files[i]);
      try {
        PdfImporter.read(c, Uri.fromFile(f), new PdfImporter.Cancel(), (m, p) -> {});
        fail(files[i]);
      } catch (IOException e) {
        assertTrue(e.getMessage(), e.getMessage().contains(expected[i]));
      } finally {
        f.delete();
      }
    }
  }

  @Test
  public void cancellationDoesNotLeaveTempFilesOrDatabaseRows() throws Exception {
    File f = fixture("notes.pdf");
    Set<String> before = new HashSet<>(Arrays.asList(c.getCacheDir().list()));
    PdfImporter.Cancel token = new PdfImporter.Cancel();
    token.cancel();
    try {
      PdfImporter.read(c, Uri.fromFile(f), token, (m, p) -> {});
      fail("Cancelled import should stop");
    } catch (CancellationException expected) {
    }
    assertEquals(before, new HashSet<>(Arrays.asList(c.getCacheDir().list())));
    f.delete();
    try (Store db = new Store(c)) {
      try {
        db.insert("Cancelled", Arrays.asList("Some study notes"), token);
        fail("No partial writes");
      } catch (CancellationException expected) {
      }
      assertTrue(db.subjects().isEmpty());
    }
  }

  @Test
  public void backupRoundTripPreservesHistoryAndAddsDisabledCopies() throws Exception {
    long id;
    try (Store db = new Store(c)) {
      id = db.insert("Biology", Arrays.asList("The first study section.", "The second section."));
      db.update(id, "active", 1);
      db.update(id, "minutes", 8);
      db.update(id, "reader_page", 1);
      db.seen(db.chunk(id, 0).id);
    }
    byte[] backup = Backup.exportNotes(c);
    assertEquals(1, Backup.restore(c, new ByteArrayInputStream(backup)));
    try (Store db = new Store(c)) {
      assertEquals(2, db.subjects().size());
      Store.Subject restored = null;
      for (Store.Subject s : db.subjects()) if (s.id != id) restored = s;
      assertNotNull(restored);
      assertFalse(restored.active);
      assertTrue(db.subject(id).active);
      assertEquals(0, restored.next);
      assertEquals(1, restored.reader);
      assertEquals(8, restored.minutes);
      assertEquals(1, restored.seen);
      assertEquals("The second section.", db.chunk(restored.id, 1).text);
      assertEquals(2, db.today());
    }
  }

  @Test
  public void malformedBackupNeverPartiallyRestores() throws Exception {
    try (Store db = new Store(c)) {
      db.insert("Original", Arrays.asList("An original note."));
    }
    JSONObject backup =
        new JSONObject(new String(Backup.exportNotes(c), java.nio.charset.StandardCharsets.UTF_8));
    backup.getJSONArray("subjects").put(new JSONObject().put("title", "Broken"));
    try {
      Backup.restore(
          c,
          new ByteArrayInputStream(
              backup.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      fail("Invalid backup must fail");
    } catch (Exception expected) {
    }
    try (Store db = new Store(c)) {
      assertEquals(1, db.subjects().size());
      assertEquals("Original", db.subjects().get(0).title);
    }
  }

  @Test
  public void pauseAndSnoozeDelayAlarmsAndDoNotAdvanceNotes() {
    try (Store db = new Store(c)) {
      long id = db.insert("Biology", Arrays.asList("These are useful study notes for reminders."));
      db.update(id, "active", 1);
      Reminders.schedule(c, db, id, true);
      db.update(id, "minutes", 60);
      Reminders.snooze(c, id);
      assertTrue(db.subject(id).next < System.currentTimeMillis() + 16 * 60000L);
      assertTrue(db.subject(id).next >= db.subject(id).snooze);
      Reminders.deliver(c, id);
      assertEquals(0, db.subject(id).seen);
      assertTrue(Reminders.status(c, db.subject(id)).contains("Snoozed"));
      Reminders.pauseToday(c);
      assertTrue(db.subject(id).next >= Reminders.pauseUntil(c));
      assertTrue(Reminders.status(c, db.subject(id)).contains("Paused"));
      Reminders.deliver(c, id);
      assertEquals(0, db.subject(id).seen);
      db.update(id, "minutes", 5);
      Reminders.unsnooze(c, id);
      Reminders.resume(c);
      assertTrue(db.subject(id).next < System.currentTimeMillis() + 10 * 60000L);
    }
  }

  @Test
  public void testReminderBypassesPauseWithoutChangingProgressOrTimers() {
    try (Store db = new Store(c)) {
      long id =
          db.insert(
              "Biology", Arrays.asList("These notes are only used for the test notification."));
      db.update(id, "active", 1);
      Reminders.pauseToday(c);
      long next = db.subject(id).next;
      assertTrue(Reminders.test(c, id));
      assertEquals(0, db.subject(id).seen);
      assertEquals(next, db.subject(id).next);
      assertEquals(
          1, c.getSystemService(NotificationManager.class).getActiveNotifications().length);
    }
  }

  @Test
  public void versionOneDatabaseMigratesWithoutLosingNotes() {
    SQLiteDatabase legacy =
        SQLiteDatabase.openOrCreateDatabase(c.getDatabasePath("studysnap.db"), null);
    legacy.execSQL(
        "CREATE TABLE subjects(id INTEGER PRIMARY KEY,title TEXT NOT NULL,created_at INTEGER NOT"
            + " NULL,active INTEGER NOT NULL DEFAULT 0,minutes INTEGER NOT NULL DEFAULT 5,next_at"
            + " INTEGER NOT NULL DEFAULT 0,reader_page INTEGER NOT NULL DEFAULT 0)");
    legacy.execSQL(
        "CREATE TABLE chunks(id INTEGER PRIMARY KEY,subject_id INTEGER REFERENCES subjects(id) ON"
            + " DELETE CASCADE,page INTEGER,content TEXT,preview TEXT,seen_at INTEGER)");
    legacy.execSQL(
        "CREATE TABLE events(id INTEGER PRIMARY KEY,chunk_id INTEGER REFERENCES chunks(id) ON"
            + " DELETE CASCADE,day TEXT)");
    legacy.execSQL("INSERT INTO subjects(id,title,created_at) VALUES(1,'Existing notes',1)");
    legacy.execSQL(
        "INSERT INTO chunks(id,subject_id,page,content,preview) VALUES(1,1,0,'Previously saved"
            + " notes.','Previously saved notes.')");
    legacy.setVersion(1);
    legacy.close();
    try (Store db = new Store(c)) {
      assertEquals(0, db.subject(1).snooze);
      assertEquals("Previously saved notes.", db.chunk(1, 0).text);
      assertEquals(2, db.getReadableDatabase().getVersion());
    }
  }
}
