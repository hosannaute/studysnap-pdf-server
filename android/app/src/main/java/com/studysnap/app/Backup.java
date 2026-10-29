package com.studysnap.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.json.*;

/** Portable notes/history only. Restore adds copies, never overwrites existing notes/settings. */
public final class Backup {
  public static final int MAX_BYTES = 25 * 1024 * 1024;

  public static byte[] exportNotes(Context context) throws Exception {
    JSONObject root = new JSONObject().put("app", "StudySnap").put("format", 1);
    JSONArray subjects = new JSONArray();
    try (Store store = new Store(context)) {
      SQLiteDatabase db = store.getWritableDatabase();
      db.beginTransaction();
      try {
        int total = 0, events = 0, characters = 0;
        for (Store.Subject s : store.subjects()) {
          if (subjects.length() >= 500)
            throw new IOException("Backups support up to 500 subjects.");
          JSONObject subject =
              new JSONObject()
                  .put("title", s.title)
                  .put("minutes", s.minutes)
                  .put("reader", s.reader);
          try (Cursor c =
              db.rawQuery("SELECT created_at FROM subjects WHERE id=?", new String[] {"" + s.id})) {
            c.moveToFirst();
            subject.put("created", c.getLong(0));
          }
          JSONArray chunks = new JSONArray();
          try (Cursor c =
              db.rawQuery(
                  "SELECT id,content,seen_at FROM chunks WHERE subject_id=? ORDER BY page",
                  new String[] {"" + s.id})) {
            while (c.moveToNext()) {
              if (++total > 10000 || (characters += c.getString(1).length()) > 8000000)
                throw new IOException(
                    "This library is too large for a single backup (25 MB maximum).");
              JSONObject chunk =
                  new JSONObject()
                      .put("text", c.getString(1))
                      .put("seen", c.isNull(2) ? JSONObject.NULL : c.getLong(2));
              JSONArray days = new JSONArray();
              try (Cursor e =
                  db.rawQuery(
                      "SELECT day FROM events WHERE chunk_id=? ORDER BY id",
                      new String[] {"" + c.getLong(0)})) {
                while (e.moveToNext()) {
                  if (++events > 100000)
                    throw new IOException("Study history is too large for this backup format.");
                  days.put(e.getString(0));
                }
              }
              chunks.put(chunk.put("days", days));
            }
          }
          subjects.put(subject.put("chunks", chunks));
        }
        db.setTransactionSuccessful();
      } finally {
        db.endTransaction();
      }
    }
    byte[] bytes = root.put("subjects", subjects).toString().getBytes(StandardCharsets.UTF_8);
    if (bytes.length > MAX_BYTES)
      throw new IOException("Backup exceeds 25 MB. Reduce the library before exporting.");
    return bytes;
  }

  public static int restore(Context context, InputStream input) throws Exception {
    if (input == null) throw new IOException("Could not open this backup.");
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    byte[] buffer = new byte[16384];
    int n;
    while ((n = input.read(buffer)) != -1) {
      if (bytes.size() + n > MAX_BYTES) throw new IOException("Backup exceeds 25 MB.");
      bytes.write(buffer, 0, n);
    }
    JSONObject root = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
    if (!"StudySnap".equals(root.getString("app")) || root.getInt("format") != 1)
      throw new IOException("Choose a supported StudySnap backup file.");
    JSONArray subjects = root.getJSONArray("subjects");
    if (subjects.length() > 500) throw new IOException("Backup has too many subjects.");
    int total = 0, characters = 0, events = 0;
    // Validate the entire document before touching the database.
    for (int i = 0; i < subjects.length(); i++) {
      JSONObject s = subjects.getJSONObject(i);
      String title = s.getString("title");
      int interval = s.getInt("minutes");
      JSONArray chunks = s.getJSONArray("chunks");
      if (title.trim().isEmpty()
          || title.length() > 200
          || interval < 5
          || interval > 120
          || s.getLong("created") < 0
          || chunks.length() == 0
          || s.getInt("reader") < 0
          || s.getInt("reader") >= chunks.length())
        throw new IOException("Backup contains invalid subject settings.");
      for (int j = 0; j < chunks.length(); j++) {
        JSONObject chunk = chunks.getJSONObject(j);
        String text = chunk.getString("text");
        if (++total > 10000
            || (characters += text.length()) > 8000000
            || text.trim().isEmpty()
            || text.length() > 2000000)
          throw new IOException("Backup contains invalid or oversized notes.");
        if (!chunk.isNull("seen") && chunk.getLong("seen") < 0)
          throw new IOException("Backup contains invalid progress.");
        JSONArray days = chunk.getJSONArray("days");
        for (int k = 0; k < days.length(); k++) {
          if (++events > 100000) throw new IOException("Backup contains too much study history.");
          LocalDate.parse(days.getString(k));
        }
      }
    }
    try (Store store = new Store(context)) {
      SQLiteDatabase db = store.getWritableDatabase();
      db.beginTransaction();
      try {
        for (int i = 0; i < subjects.length(); i++) {
          JSONObject s = subjects.getJSONObject(i);
          ContentValues v = new ContentValues();
          v.put("title", s.getString("title"));
          v.put("created_at", s.getLong("created"));
          v.put("minutes", s.getInt("minutes"));
          v.put("reader_page", s.getInt("reader"));
          long subject = db.insertOrThrow("subjects", null, v);
          JSONArray chunks = s.getJSONArray("chunks");
          for (int j = 0; j < chunks.length(); j++) {
            JSONObject x = chunks.getJSONObject(j);
            v.clear();
            v.put("subject_id", subject);
            v.put("page", j);
            v.put("content", x.getString("text"));
            v.put("preview", StudyRules.preview(x.getString("text")));
            if (!x.isNull("seen")) v.put("seen_at", x.getLong("seen"));
            long id = db.insertOrThrow("chunks", null, v);
            JSONArray days = x.getJSONArray("days");
            for (int k = 0; k < days.length(); k++) {
              v.clear();
              v.put("chunk_id", id);
              v.put("day", days.getString(k));
              db.insertOrThrow("events", null, v);
            }
          }
        }
        db.setTransactionSuccessful();
      } finally {
        db.endTransaction();
      }
    }
    return subjects.length();
  }
}
