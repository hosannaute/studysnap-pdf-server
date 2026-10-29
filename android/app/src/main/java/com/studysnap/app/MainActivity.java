package com.studysnap.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.view.*;
import android.widget.*;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
  static final int BG = 0xFFF7F8F2,
      INK = 0xFF172D26,
      MUTED = 0xFF64766B,
      GREEN = 0xFF207A59,
      PALE = 0xFFE6EEDF;
  private LinearLayout root, body;
  private Store db;
  private android.content.SharedPreferences prefs;
  private String screen = "home";
  private long subjectId = -1;
  private int page = 0;
  private EditText subjectName;
  private boolean processing = false;
  private String importTitle = "";
  private PdfImporter.Cancel importCancel;
  private TextView importProgress;
  private ProgressBar importBar;
  private Button cancelImport;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();

  public void onCreate(Bundle state) {
    super.onCreate(state);
    if (Build.VERSION.SDK_INT >= 27)
      getWindow()
          .getDecorView()
          .setSystemUiVisibility(
              getWindow().getDecorView().getSystemUiVisibility()
                  | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                  | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    db = new Store(this);
    prefs = getSharedPreferences("settings", 0);
    PDFBoxResourceLoader.init(getApplicationContext());
    Reminders.channel(this);
    if (state != null) {
      screen = state.getString("screen", "home");
      subjectId = state.getLong("subject", -1);
      page = state.getInt("page", 0);
      importTitle = state.getString("importTitle", "");
    }
    if (getIntent().hasExtra("subject")) openIntent(getIntent());
    else if (!prefs.getBoolean("onboarded", false)) welcome();
    else render();
    Reminders.restore(this, false);
  }

  public void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    state.putString("screen", screen);
    state.putLong("subject", subjectId);
    state.putInt("page", page);
    state.putString("importTitle", importTitle);
  }

  protected void onNewIntent(Intent i) {
    super.onNewIntent(i);
    setIntent(i);
    openIntent(i);
  }

  private void openIntent(Intent i) {
    subjectId = i.getLongExtra("subject", -1);
    page = i.getIntExtra("page", 0);
    i.removeExtra("subject");
    i.removeExtra("page");
    reader();
  }

  protected void onResume() {
    super.onResume();
    if (db != null && prefs.getBoolean("onboarded", false)) {
      Reminders.restore(this, false);
      if ("home".equals(screen)) home();
    }
  }

  protected void onDestroy() {
    if (importCancel != null) importCancel.cancel();
    worker.shutdownNow();
    db.close();
    super.onDestroy();
  }

  @Override
  public void onBackPressed() {
    if (processing) {
      cancelImport();
      return;
    }
    if ("home".equals(screen) || "welcome".equals(screen)) super.onBackPressed();
    else home();
  }

  private int d(float n) {
    return (int) (n * getResources().getDisplayMetrics().density + 0.5f);
  }

  private GradientDrawable bg(int color, int radius) {
    GradientDrawable x = new GradientDrawable();
    x.setColor(color);
    x.setCornerRadius(d(radius));
    return x;
  }

  private TextView text(String value, int size, int color, boolean bold) {
    TextView t = new TextView(this);
    t.setText(value);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setLineSpacing(d(3), 1);
    if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    return t;
  }

  private void addText(LinearLayout target, String value, int size, int color, boolean bold) {
    TextView t = text(value, size, color, bold);
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.bottomMargin = d(12);
    target.addView(t, p);
  }

  private LinearLayout vertical() {
    LinearLayout x = new LinearLayout(this);
    x.setOrientation(LinearLayout.VERTICAL);
    return x;
  }

  private void base(String name, String eyebrow, boolean nav) {
    screen = name;
    root = vertical();
    root.setBackgroundColor(BG);
    root.setPadding(d(22), 0, d(22), 0);
    root.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          v.setPadding(
              d(22),
              insets.getSystemWindowInsetTop() + d(14),
              d(22),
              insets.getSystemWindowInsetBottom());
          return insets.consumeSystemWindowInsets();
        });
    setContentView(root);
    root.requestApplyInsets();
    LinearLayout brand = new LinearLayout(this);
    brand.setGravity(Gravity.CENTER_VERTICAL);
    TextView logo = text("▥  StudySnap", 21, GREEN, true);
    brand.addView(logo, new LinearLayout.LayoutParams(0, d(48), 1));
    if (nav) {
      TextView settings = text("Settings", 14, MUTED, true);
      settings.setPadding(d(12), d(12), 0, d(12));
      settings.setOnClickListener(v -> settings());
      brand.addView(settings);
    }
    root.addView(brand);
    addText(root, eyebrow.toUpperCase(Locale.ROOT), 11, MUTED, true);
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    body = vertical();
    body.setPadding(0, d(12), 0, d(20));
    scroll.addView(body);
    if (nav) {
      LinearLayout bar = new LinearLayout(this);
      bar.setPadding(0, d(8), 0, d(8));
      navButton(bar, "Library", () -> home(), "home".equals(name));
      navButton(bar, "＋ Import", () -> upload(), "upload".equals(name));
      navButton(bar, "Progress", () -> progress(), "progress".equals(name));
      root.addView(bar);
    }
  }

  private void navButton(LinearLayout bar, String title, Runnable action, boolean selected) {
    TextView t = text(title, 14, selected ? GREEN : MUTED, true);
    t.setGravity(Gravity.CENTER);
    t.setPadding(0, d(16), 0, d(16));
    if (selected) t.setBackground(bg(PALE, 16));
    bar.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
    t.setOnClickListener(v -> action.run());
  }

  private Button button(LinearLayout target, String title, Runnable action, boolean primary) {
    Button b = new Button(this);
    b.setText(title);
    b.setAllCaps(false);
    b.setTextSize(16);
    b.setTextColor(primary ? Color.WHITE : GREEN);
    b.setBackground(bg(primary ? GREEN : PALE, 16));
    b.setPadding(d(14), d(14), d(14), d(14));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.topMargin = d(10);
    lp.bottomMargin = d(6);
    target.addView(b, lp);
    b.setOnClickListener(v -> action.run());
    return b;
  }

  private LinearLayout card() {
    LinearLayout c = vertical();
    c.setPadding(d(20), d(20), d(20), d(20));
    c.setBackground(bg(Color.WHITE, 22));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.bottomMargin = d(14);
    body.addView(c, lp);
    return c;
  }

  private void title(String value, String subtitle) {
    addText(body, value, 32, INK, true);
    if (!subtitle.isEmpty()) addText(body, subtitle, 16, MUTED, false);
  }

  private void welcome() {
    base("welcome", "A little learning, often", false);
    addText(body, "▥", 80, GREEN, true);
    title("Make your next scroll\na study break.", "Your notes. Small moments. Lasting knowledge.");
    LinearLayout c = card();
    addText(c, "01  Bring your notes", 18, INK, true);
    addText(
        c,
        "Import a typed PDF, and we’ll turn it into short, readable sections.",
        16,
        MUTED,
        false);
    addText(c, "02  Set your rhythm", 18, INK, true);
    addText(
        c, "Receive a little of what you’re learning while you use your phone.", 16, MUTED, false);
    addText(c, "03  Keep it yours", 18, INK, true);
    addText(
        c,
        "Fully offline. No account, AI, or subscription. Your notes stay on this device.",
        16,
        MUTED,
        false);
    button(body, "Get started  →", () -> permission(), true);
  }

  private void permission() {
    base("permission", "Your study rhythm", false);
    title(
        "Let your notes\ncome to you.",
        "Allow notifications to receive snippets while you use other apps. You can change this at"
            + " any time.");
    LinearLayout c = card();
    addText(c, "▥  STUDYSNAP  ·  just now", 12, GREEN, true);
    addText(c, "A little Biology", 19, INK, true);
    addText(
        c,
        "Osmosis is the movement of water through a selectively permeable membrane…",
        16,
        MUTED,
        false);
    addText(
        body,
        "Reminders follow Android’s battery settings and may arrive later than your chosen"
            + " interval.",
        14,
        MUTED,
        false);
    button(
        body,
        "Allow study reminders",
        () -> {
          if (Build.VERSION.SDK_INT >= 33
              && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                  != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 42);
          else completeWelcome();
        },
        true);
    button(body, "Continue without reminders", () -> completeWelcome(), false);
  }

  private void completeWelcome() {
    prefs.edit().putBoolean("onboarded", true).apply();
    home();
  }

  public void onRequestPermissionsResult(int code, String[] names, int[] results) {
    super.onRequestPermissionsResult(code, names, results);
    if (code == 42 || code == 43) prefs.edit().putBoolean("permission_asked", true).apply();
    if (code == 42) completeWelcome();
    else if (code == 43) {
      Reminders.restore(this, false);
      render();
    }
  }

  private void home() {
    base("home", "Your pocket study companion", true);
    title("A little today.\nA lot over time.", "Make room for one more idea.");
    LinearLayout stats = card();
    stats.setBackground(bg(PALE, 22));
    addText(
        stats,
        db.today() + " sections today     ·     " + db.streak() + " day streak",
        18,
        GREEN,
        true);
    if (!Reminders.allowed(this)) {
      button(body, "Enable notifications", () -> notificationSettings(), false);
      addText(
          body,
          "Reading works offline. Turn on notifications to receive study reminders.",
          14,
          MUTED,
          false);
    }
    LinearLayout reminderCard = card();
    addText(reminderCard, "Reminder status", 20, INK, true);
    reminderControls(reminderCard, null);
    addText(body, "Your library", 22, INK, true);
    List<Store.Subject> subjects = db.subjects();
    if (subjects.isEmpty()) {
      LinearLayout c = card();
      addText(c, "Your next idea starts here.", 22, INK, true);
      addText(
          c,
          "Bring a lecture, handout, or chapter. StudySnap will divide it into manageable"
              + " sections.",
          16,
          MUTED,
          false);
      button(c, "＋ Import your first PDF", () -> upload(), true);
    }
    for (Store.Subject s : subjects) {
      LinearLayout c = card();
      addText(c, s.title, 22, INK, true);
      addText(c, s.seen + " of " + s.total + " sections explored", 14, MUTED, false);
      TextView reminderStatus = text(Reminders.status(this, s), 13, MUTED, false);
      c.addView(reminderStatus);
      ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
      progress.setMax(s.total);
      progress.setProgress(s.seen);
      progress.setProgressTintList(android.content.res.ColorStateList.valueOf(GREEN));
      c.addView(progress, new LinearLayout.LayoutParams(-1, d(7)));
      Switch toggle = new Switch(this);
      toggle.setText("Reminders · every " + s.minutes + " min");
      toggle.setTextColor(MUTED);
      toggle.setPadding(0, d(14), 0, d(8));
      toggle.setChecked(s.active);
      toggle.setOnCheckedChangeListener(
          (b, on) -> {
            db.update(s.id, "active", on ? 1 : 0);
            Reminders.schedule(this, db, s.id, true);
            reminderStatus.setText(Reminders.status(this, db.subject(s.id)));
            if (on && !Reminders.allowed(this)) toast("Enable notifications to receive reminders.");
          });
      c.addView(toggle);
      button(
          c,
          "Continue reading  →",
          () -> {
            subjectId = s.id;
            page = s.reader;
            reader();
          },
          true);
      button(
          c,
          "Subject settings",
          () -> {
            subjectId = s.id;
            subjectSettings();
          },
          false);
    }
  }

  private void upload() {
    base("upload", "Bring your own knowledge", true);
    title(
        "Good notes deserve\na second look.",
        "Import a PDF and keep it with you, wherever you go.");
    LinearLayout c = card();
    addText(c, "Subject name", 15, INK, true);
    subjectName = new EditText(this);
    subjectName.setSingleLine();
    subjectName.setTextColor(INK);
    subjectName.setTextSize(18);
    subjectName.setHint("e.g. Cell Biology (optional)");
    c.addView(subjectName, new LinearLayout.LayoutParams(-1, d(56)));
    button(
        c,
        "Choose PDF from device",
        () -> {
          importTitle = subjectName.getText().toString().trim();
          Intent i =
              new Intent(Intent.ACTION_OPEN_DOCUMENT)
                  .setType("application/pdf")
                  .addCategory(Intent.CATEGORY_OPENABLE);
          try {
            startActivityForResult(i, 10);
          } catch (ActivityNotFoundException e) {
            error(
                "No file picker available",
                "Install or enable a document provider on this device.");
          }
        },
        true);
    addText(
        body,
        "PDFs up to 100 pages and 30 MB. Typed text only; scanned pages need OCR first. Processing"
            + " happens on your phone and may take longer for large files.",
        14,
        MUTED,
        false);
    addText(body, "No upload to a server. No internet permission.", 14, GREEN, true);
  }

  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (result == RESULT_OK && data != null && data.getData() != null && request == 20) {
      exportBackup(data.getData());
      return;
    }
    if (result == RESULT_OK && data != null && data.getData() != null && request == 21) {
      restoreBackup(data.getData());
      return;
    }
    if (request == 10 && result == RESULT_OK && data != null && data.getData() != null) {
      Uri uri = data.getData();
      String title = importTitle;
      if (title.isEmpty()) title = fileName(uri);
      importPdf(uri, title);
    }
  }

  private String fileName(Uri uri) {
    try (Cursor c =
        getContentResolver()
            .query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
      if (c != null && c.moveToFirst()) {
        String s = c.getString(0);
        if (s != null) return s.replaceFirst("(?i)\\.pdf$", "");
      }
    } catch (Exception ignored) {
    }
    return "Untitled notes";
  }

  private void cancelImport() {
    if (importCancel != null) {
      importCancel.cancel();
      if (cancelImport != null) {
        cancelImport.setEnabled(false);
        cancelImport.setText("Cancelling…");
      }
    }
  }

  private void importPdf(Uri uri, String name) {
    processing = true;
    importCancel = new PdfImporter.Cancel();
    PdfImporter.Cancel token = importCancel;
    base("processing", "All on your device", false);
    title(
        "Preparing your\nnext small step.", "Keep StudySnap open while your notes are processed.");
    importProgress = text("Opening your PDF…", 18, GREEN, true);
    body.addView(importProgress);
    importBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    importBar.setMax(100);
    body.addView(importBar, new LinearLayout.LayoutParams(-1, d(10)));
    cancelImport = button(body, "Cancel import", () -> cancelImport(), false);
    addText(body, "Nothing is saved if you cancel before processing finishes.", 14, MUTED, false);
    worker.execute(
        () -> {
          try {
            List<String> chunks =
                PdfImporter.read(
                    getApplicationContext(),
                    uri,
                    token,
                    (message, percent) ->
                        runOnUiThread(
                            () -> {
                              if (!isDestroyed() && processing) {
                                importProgress.setText(message);
                                importBar.setProgress(percent);
                              }
                            }));
            token.check();
            runOnUiThread(
                () -> {
                  if (!isDestroyed()) {
                    cancelImport.setEnabled(false);
                    importProgress.setText("Saving your notes…");
                    importBar.setProgress(98);
                  }
                });
            long id;
            try (Store store = new Store(getApplicationContext())) {
              id =
                  store.insert(
                      name.trim().isEmpty()
                          ? "Untitled notes"
                          : (name.length() > 200 ? name.substring(0, 200) : name),
                      chunks,
                      token);
            }
            runOnUiThread(
                () -> {
                  processing = false;
                  importCancel = null;
                  if (!isDestroyed()) {
                    subjectId = id;
                    home();
                    toast("Notes ready — turn on reminders whenever you like.");
                  }
                });
          } catch (CancellationException e) {
            runOnUiThread(
                () -> {
                  processing = false;
                  importCancel = null;
                  if (!isDestroyed()) {
                    upload();
                    toast("Import cancelled. No notes were added.");
                  }
                });
          } catch (Exception e) {
            String message =
                e instanceof IOException
                    ? e.getMessage()
                    : "This file may be damaged or unsupported. Try another typed, unlocked PDF.";
            runOnUiThread(
                () -> {
                  processing = false;
                  importCancel = null;
                  if (!isDestroyed()) {
                    upload();
                    error("Couldn’t read this PDF", message);
                  }
                });
          }
        });
  }

  private void backupPicker(boolean restore) {
    Intent i =
        new Intent(restore ? Intent.ACTION_OPEN_DOCUMENT : Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(restore ? "*/*" : "application/json");
    if (!restore)
      i.putExtra(Intent.EXTRA_TITLE, "StudySnap-backup-" + java.time.LocalDate.now() + ".json");
    try {
      startActivityForResult(i, restore ? 21 : 20);
    } catch (ActivityNotFoundException e) {
      error("File picker unavailable", "Enable a document provider on this phone.");
    }
  }

  private void backupBusy(String message) {
    processing = true;
    base("backup", "Keep your knowledge", false);
    title(message, "Please keep StudySnap open until this finishes.");
    body.addView(new ProgressBar(this));
  }

  private void exportBackup(Uri uri) {
    backupBusy("Creating your backup…");
    worker.execute(
        () -> {
          try {
            byte[] data = Backup.exportNotes(getApplicationContext());
            try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
              if (out == null) throw new IOException("Could not write to this location.");
              out.write(data);
            }
            runOnUiThread(
                () -> {
                  processing = false;
                  if (!isDestroyed()) {
                    settings();
                    toast("Backup saved. Keep this file somewhere safe.");
                  }
                });
          } catch (Exception e) {
            runOnUiThread(
                () -> {
                  processing = false;
                  if (!isDestroyed()) {
                    settings();
                    error(
                        "Backup not saved",
                        "Could not save the backup. Check free space and the chosen file provider,"
                            + " then try again.");
                  }
                });
          }
        });
  }

  private void restoreBackup(Uri uri) {
    backupBusy("Restoring your notes…");
    worker.execute(
        () -> {
          try {
            int count;
            try (InputStream input = getContentResolver().openInputStream(uri)) {
              count = Backup.restore(getApplicationContext(), input);
            }
            runOnUiThread(
                () -> {
                  processing = false;
                  if (!isDestroyed()) {
                    home();
                    toast(
                        count
                            + " subjects restored. Their reminders are off until you enable them.");
                  }
                });
          } catch (Exception e) {
            runOnUiThread(
                () -> {
                  processing = false;
                  if (!isDestroyed()) {
                    settings();
                    error(
                        "Couldn’t restore backup",
                        "Choose a valid StudySnap JSON backup up to 25 MB. Your existing notes have"
                            + " not been changed.");
                  }
                });
          }
        });
  }

  private void reminderControls(LinearLayout target, Store.Subject subject) {
    addText(target, Reminders.status(this, subject), 14, MUTED, false);
    button(
        target,
        "Send test reminder",
        () -> {
          if (Reminders.test(this, subject == null ? -1 : subject.id))
            toast(
                "Test posted. Check your notification shade; Android controls banners and sound.");
          else notificationSettings();
        },
        false);
    addText(
        target,
        "A test sends immediately, even during quiet hours or a pause. It does not change your"
            + " progress.",
        12,
        MUTED,
        false);
    boolean paused = Reminders.pauseUntil(this) > System.currentTimeMillis();
    button(
        target,
        paused ? "Resume reminders" : "Pause for today",
        () -> {
          if (paused) Reminders.resume(this);
          else Reminders.pauseToday(this);
          render();
        },
        false);
    if (subject != null) {
      boolean snoozed = subject.snooze > System.currentTimeMillis();
      button(
          target,
          snoozed ? "End snooze" : "Snooze 15 minutes",
          () -> {
            if (snoozed) Reminders.unsnooze(this, subject.id);
            else Reminders.snooze(this, subject.id);
            render();
          },
          false);
    }
  }

  private void reader() {
    Store.Subject s = db.subject(subjectId);
    if (s == null) {
      home();
      return;
    }
    page = Math.max(0, Math.min(page, s.total - 1));
    Store.Chunk chunk = db.chunk(subjectId, page);
    if (chunk == null) {
      home();
      return;
    }
    db.update(
        subjectId,
        "reader_page",
        page); // Reading and notification delivery both count as exploration.
    // Record only the first manual visit; repeated notification cycles are recorded separately.
    try (Cursor c =
        db.getReadableDatabase()
            .rawQuery("SELECT seen_at FROM chunks WHERE id=?", new String[] {"" + chunk.id})) {
      if (c.moveToFirst() && c.isNull(0)) db.seen(chunk.id);
    }
    base("reader", s.title, false);
    button(body, "← Your library", () -> home(), false);
    addText(body, "SECTION " + (page + 1) + " OF " + s.total, 12, GREEN, true);
    title(s.title, "");
    LinearLayout c = card();
    TextView content = text(chunk.text, 21, INK, false);
    content.setTextIsSelectable(true);
    content.setLineSpacing(d(7), 1);
    c.addView(content);
    final float[] down = {0, 0};
    content.setOnTouchListener(
        (v, e) -> {
          if (e.getAction() == MotionEvent.ACTION_DOWN) {
            down[0] = e.getX();
            down[1] = e.getY();
          } else if (e.getAction() == MotionEvent.ACTION_UP) {
            float dx = e.getX() - down[0], dy = e.getY() - down[1];
            if (Math.abs(dx) > d(80) && Math.abs(dx) > Math.abs(dy) * 1.5) {
              move(dx < 0 ? 1 : -1);
              return true;
            }
          }
          return false;
        });
    addText(body, "Swipe across the text or use the buttons below.", 13, MUTED, false);
    Button previous = button(body, "← Previous section", () -> move(-1), false);
    previous.setEnabled(page > 0);
    Button next =
        button(
            body,
            page == s.total - 1 ? "Back to library ✓" : "Next section →",
            () -> {
              if (page == s.total - 1) home();
              else move(1);
            },
            true);
    button(body, "Timer & subject settings", () -> subjectSettings(), false);
  }

  private void move(int delta) {
    Store.Subject s = db.subject(subjectId);
    if (s != null && page + delta >= 0 && page + delta < s.total) {
      page += delta;
      reader();
    }
  }

  private void subjectSettings() {
    Store.Subject s = db.subject(subjectId);
    if (s == null) {
      home();
      return;
    }
    base("subject", "Your study rhythm", false);
    button(body, "← Your library", () -> home(), false);
    title(s.title, s.seen + " of " + s.total + " sections explored");
    Switch active = new Switch(this);
    active.setText("Include in study reminders");
    active.setChecked(s.active);
    active.setTextColor(INK);
    body.addView(active);
    active.setOnCheckedChangeListener(
        (v, on) -> {
          db.update(s.id, "active", on ? 1 : 0);
          Reminders.schedule(this, db, s.id, true);
          if (on && !Reminders.allowed(this)) notificationSettings();
        });
    reminderControls(body, s);
    final int[] intervals = {5, 8, 10, 15, 20, 30, 45, 60, 90, 120};
    addText(body, "Reminder interval", 20, INK, true);
    TextView label = text("Every " + s.minutes + " minutes", 18, GREEN, true);
    body.addView(label);
    SeekBar seek = new SeekBar(this);
    seek.setMax(intervals.length - 1);
    int current = 0;
    for (int i = 0; i < intervals.length; i++) if (intervals[i] == s.minutes) current = i;
    seek.setProgress(current);
    body.addView(seek);
    seek.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onStartTrackingTouch(SeekBar b) {}

          public void onProgressChanged(SeekBar b, int n, boolean user) {
            label.setText("Every " + intervals[n] + " minutes");
          }

          public void onStopTrackingTouch(SeekBar b) {
            db.update(s.id, "minutes", intervals[b.getProgress()]);
            Reminders.schedule(MainActivity.this, db, s.id, true);
          }
        });
    addText(
        body,
        "Android may delay reminders in battery-saving mode. Quiet hours apply to every subject."
            + " Notification delivery counts as explored, even if you dismiss it.",
        14,
        MUTED,
        false);
    button(
        body,
        "Read these notes",
        () -> {
          page = s.reader;
          reader();
        },
        true);
    button(
        body,
        "Delete subject",
        () ->
            new AlertDialog.Builder(this)
                .setTitle("Delete " + s.title + "?")
                .setMessage(
                    "This removes these notes and their study history from this device. This cannot"
                        + " be undone.")
                .setNegativeButton("Keep notes", null)
                .setPositiveButton(
                    "Delete",
                    (dialog, which) -> {
                      Reminders.cancel(this, s.id);
                      db.delete(s.id);
                      home();
                    })
                .show(),
        false);
  }

  private void settings() {
    base("settings", "Make it work for you", true);
    title("Your rhythm.\nYour rules.", "A calmer way to keep learning.");
    LinearLayout c = card();
    addText(c, "Quiet hours", 22, INK, true);
    addText(c, "Pause study reminders while you rest.", 15, MUTED, false);
    Switch quiet = new Switch(this);
    quiet.setText("Use quiet hours");
    quiet.setChecked(prefs.getBoolean("quiet", false));
    c.addView(quiet);
    quiet.setOnCheckedChangeListener(
        (v, on) -> {
          prefs.edit().putBoolean("quiet", on).apply();
          Reminders.restore(this, true);
        });
    button(
        c,
        "From " + hour(prefs.getInt("quiet_start", 22)),
        () -> pickHour("quiet_start", 22),
        false);
    button(c, "Until " + hour(prefs.getInt("quiet_end", 8)), () -> pickHour("quiet_end", 8), false);
    addText(c, "Matching start and end hours means no quiet period.", 13, MUTED, false);
    button(body, "Android notification settings", () -> notificationSettings(), false);
    LinearLayout backup = card();
    addText(backup, "Backup & restore", 22, INK, true);
    addText(
        backup,
        "Save notes, reading position, and study history to a portable file. Backups are not"
            + " encrypted; anyone with the file can read your notes. A cloud file provider may sync"
            + " the file if you choose one.",
        14,
        MUTED,
        false);
    button(backup, "Export backup", () -> backupPicker(false), false);
    button(
        backup,
        "Restore backup",
        () ->
            new AlertDialog.Builder(this)
                .setTitle("Restore notes as copies?")
                .setMessage(
                    "Your existing notes stay unchanged. Restored subjects are added as separate"
                        + " copies with reminders off. Restoring the same file twice adds"
                        + " duplicates. Global preferences are kept.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Choose backup", (dialog, which) -> backupPicker(true))
                .show(),
        false);
    LinearLayout privacy = card();
    addText(privacy, "Offline by design", 22, INK, true);
    addText(
        privacy,
        "Your notes and progress are stored only on this phone. No account, analytics, AI, or"
            + " internet access. Uninstalling StudySnap deletes its local data.",
        15,
        MUTED,
        false);
    addText(privacy, "StudySnap 1.1.0 · Android MVP", 13, GREEN, true);
    addText(
        privacy,
        "PDF engine: PDFBox-Android (Apache 2.0). A support contact has not been configured for"
            + " this development build.",
        13,
        MUTED,
        false);
  }

  private String hour(int h) {
    return String.format(Locale.getDefault(), "%02d:00", h);
  }

  private void pickHour(String key, int fallback) {
    String[] hours = new String[24];
    for (int i = 0; i < 24; i++) hours[i] = hour(i);
    new AlertDialog.Builder(this)
        .setTitle("Choose an hour")
        .setSingleChoiceItems(
            hours,
            prefs.getInt(key, fallback),
            (dialog, h) -> {
              prefs.edit().putInt(key, h).apply();
              Reminders.restore(this, true);
              dialog.dismiss();
              settings();
            })
        .setNegativeButton("Cancel", null)
        .show();
  }

  private void notificationSettings() {
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
      if (!prefs.getBoolean("permission_asked", false)
          || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))
        requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 43);
      else
        startActivity(
            new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName()));
    } else
      startActivity(
          new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
              .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName()));
  }

  private void progress() {
    base("progress", "Small steps add up", true);
    title("Look how far\nyou’ve come.", "Every section is another moment spent learning.");
    LinearLayout a = card();
    a.setBackground(bg(PALE, 22));
    addText(a, "" + db.streak(), 60, GREEN, true);
    addText(a, "day study streak", 18, INK, true);
    addText(a, "A streak continues if you studied today or yesterday.", 14, MUTED, false);
    LinearLayout b = card();
    addText(b, db.week() + " sections this week", 24, INK, true);
    addText(b, db.today() + " unique sections explored today", 16, MUTED, false);
    addText(b, "Most active subject", 14, MUTED, true);
    addText(b, db.mostActive(), 20, GREEN, true);
    addText(
        body,
        "Explored means opened in the reader or posted as a notification. It does not measure"
            + " whether a notification was read.",
        14,
        MUTED,
        false);
    button(body, "Keep learning →", () -> home(), true);
  }

  private void render() {
    switch (screen) {
      case "reader":
        reader();
        break;
      case "subject":
        subjectSettings();
        break;
      case "settings":
        settings();
        break;
      case "progress":
        progress();
        break;
      case "upload":
        upload();
        break;
      case "permission":
        permission();
        break;
      default:
        home();
    }
  }

  private void toast(String message) {
    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
  }

  private void error(String title, String message) {
    new AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton("OK", null)
        .show();
  }
}
