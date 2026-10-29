package com.studysnap.app;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.time.*;
import java.util.*;

public class Store extends SQLiteOpenHelper {
  public Store(Context context) {
    super(context, "studysnap.db", null, 2);
  }

  public void onCreate(SQLiteDatabase db) {
    db.execSQL(
        "CREATE TABLE subjects (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL,"
            + " created_at INTEGER NOT NULL, active INTEGER NOT NULL DEFAULT 0, minutes INTEGER NOT"
            + " NULL DEFAULT 5, next_at INTEGER NOT NULL DEFAULT 0, reader_page INTEGER NOT NULL"
            + " DEFAULT 0)");
    db.execSQL(
        "CREATE TABLE chunks (id INTEGER PRIMARY KEY AUTOINCREMENT, subject_id INTEGER NOT NULL"
            + " REFERENCES subjects(id) ON DELETE CASCADE, page INTEGER NOT NULL, content TEXT NOT"
            + " NULL, preview TEXT NOT NULL, seen_at INTEGER, UNIQUE(subject_id,page))");
    db.execSQL(
        "CREATE TABLE events (id INTEGER PRIMARY KEY, chunk_id INTEGER REFERENCES chunks(id) ON"
            + " DELETE CASCADE, day TEXT NOT NULL)");
    db.execSQL("ALTER TABLE subjects ADD COLUMN snooze_until INTEGER NOT NULL DEFAULT 0");
    db.execSQL("CREATE INDEX chunks_subject ON chunks(subject_id,page)");
    db.execSQL("CREATE INDEX events_chunk ON events(chunk_id)");
  }

  public void onConfigure(SQLiteDatabase db) {
    db.setForeignKeyConstraintsEnabled(true);
  }

  public void onUpgrade(SQLiteDatabase db, int old, int next) {
    if (old < 2) {
      db.execSQL("ALTER TABLE subjects ADD COLUMN snooze_until INTEGER NOT NULL DEFAULT 0");
      db.execSQL("CREATE INDEX IF NOT EXISTS events_chunk ON events(chunk_id)");
    }
  }

  public static class Subject {
    public long id, next, snooze;
    public String title;
    public int minutes, total, seen, reader;
    public boolean active;
  }

  public static class Chunk {
    public long id;
    public int page;
    public String text, preview;
  }

  public List<Subject> subjects() {
    List<Subject> list = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT"
                    + " s.id,s.title,s.active,s.minutes,s.next_at,s.reader_page,s.snooze_until,COUNT(c.id),SUM(CASE"
                    + " WHEN c.seen_at IS NOT NULL THEN 1 ELSE 0 END) FROM subjects s LEFT JOIN"
                    + " chunks c ON c.subject_id=s.id GROUP BY s.id ORDER BY s.created_at DESC",
                null)) {
      while (c.moveToNext()) {
        Subject s = new Subject();
        s.id = c.getLong(0);
        s.title = c.getString(1);
        s.active = c.getInt(2) == 1;
        s.minutes = c.getInt(3);
        s.next = c.getLong(4);
        s.reader = c.getInt(5);
        s.snooze = c.getLong(6);
        s.total = c.getInt(7);
        s.seen = c.getInt(8);
        list.add(s);
      }
    }
    return list;
  }

  public Subject subject(long id) {
    for (Subject s : subjects()) if (s.id == id) return s;
    return null;
  }

  public long insert(String title, List<String> chunks) {
    return insert(title, chunks, new PdfImporter.Cancel());
  }

  public long insert(String title, List<String> chunks, PdfImporter.Cancel cancel) {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      ContentValues v = new ContentValues();
      v.put("title", title);
      v.put("created_at", System.currentTimeMillis());
      long id = db.insertOrThrow("subjects", null, v);
      for (int i = 0; i < chunks.size(); i++) {
        cancel.check();
        v.clear();
        v.put("subject_id", id);
        v.put("page", i);
        v.put("content", chunks.get(i));
        v.put("preview", StudyRules.preview(chunks.get(i)));
        db.insertOrThrow("chunks", null, v);
      }
      cancel.check();
      db.setTransactionSuccessful();
      return id;
    } finally {
      db.endTransaction();
    }
  }

  private Chunk from(Cursor c) {
    Chunk x = new Chunk();
    x.id = c.getLong(0);
    x.page = c.getInt(1);
    x.text = c.getString(2);
    x.preview = c.getString(3);
    return x;
  }

  public Chunk chunk(long subject, int page) {
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT id,page,content,preview FROM chunks WHERE subject_id=? AND page=?",
                new String[] {"" + subject, "" + page})) {
      return c.moveToFirst() ? from(c) : null;
    }
  }

  public Chunk next(long subject) {
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT id,page,content,preview FROM chunks WHERE subject_id=? ORDER BY CASE WHEN"
                    + " seen_at IS NULL THEN 0 ELSE 1 END,seen_at,page LIMIT 1",
                new String[] {"" + subject})) {
      return c.moveToFirst() ? from(c) : null;
    }
  }

  public void seen(long id) {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      ContentValues v = new ContentValues();
      v.put("seen_at", System.currentTimeMillis());
      db.update("chunks", v, "id=?", new String[] {"" + id});
      v.clear();
      v.put("chunk_id", id);
      v.put("day", LocalDate.now().toString());
      db.insertOrThrow("events", null, v);
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  public void update(long id, String key, long value) {
    ContentValues v = new ContentValues();
    v.put(key, value);
    getWritableDatabase().update("subjects", v, "id=?", new String[] {"" + id});
  }

  public void delete(long id) {
    getWritableDatabase().delete("subjects", "id=?", new String[] {"" + id});
  }

  public int today() {
    return count(
        "SELECT COUNT(DISTINCT chunk_id) FROM events WHERE day=?",
        new String[] {LocalDate.now().toString()});
  }

  public int week() {
    return count(
        "SELECT COUNT(DISTINCT chunk_id) FROM events WHERE day>=?",
        new String[] {LocalDate.now().minusDays(6).toString()});
  }

  private int count(String sql, String[] args) {
    try (Cursor c = getReadableDatabase().rawQuery(sql, args)) {
      c.moveToFirst();
      return c.getInt(0);
    }
  }

  public int streak() {
    Set<String> days = new HashSet<>();
    try (Cursor c = getReadableDatabase().rawQuery("SELECT DISTINCT day FROM events", null)) {
      while (c.moveToNext()) days.add(c.getString(0));
    }
    LocalDate d = LocalDate.now();
    if (!days.contains(d.toString())) d = d.minusDays(1);
    int n = 0;
    while (days.contains(d.toString())) {
      n++;
      d = d.minusDays(1);
    }
    return n;
  }

  public String mostActive() {
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT s.title FROM events e JOIN chunks c ON c.id=e.chunk_id JOIN subjects s ON"
                    + " s.id=c.subject_id GROUP BY s.id ORDER BY COUNT(*) DESC LIMIT 1",
                null)) {
      return c.moveToFirst() ? c.getString(0) : "Start with your first note";
    }
  }
}
