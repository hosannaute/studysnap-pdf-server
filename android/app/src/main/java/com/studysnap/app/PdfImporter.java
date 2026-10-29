package com.studysnap.app;

import android.content.Context;
import android.net.Uri;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import java.io.*;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** All import paths use the same bounded, cancellable on-device pipeline. */
public final class PdfImporter {
  public static final class Failure extends IOException {
    Failure(String message) {
      super(message);
    }
  }

  public interface Progress {
    void update(String message, int percent);
  }

  public static final class Cancel {
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public void cancel() {
      cancelled.set(true);
    }

    public void check() {
      if (cancelled.get() || Thread.currentThread().isInterrupted())
        throw new CancellationException();
    }
  }

  public static List<String> read(Context context, Uri uri, Cancel cancel, Progress progress)
      throws IOException {
    File temp = File.createTempFile("notes-", ".pdf", context.getCacheDir());
    try {
      progress.update("Opening PDF…", 0);
      try (InputStream input = context.getContentResolver().openInputStream(uri);
          OutputStream out = new FileOutputStream(temp)) {
        if (input == null)
          throw new Failure("Could not open this file. Download it to your phone and try again.");
        byte[] buffer = new byte[16384];
        int n;
        long size = 0;
        while ((n = input.read(buffer)) != -1) {
          cancel.check();
          size += n;
          if (size > 30L * 1024 * 1024)
            throw new Failure("This PDF exceeds 30 MB. Split it into smaller files.");
          out.write(buffer, 0, n);
        }
      }
      cancel.check();
      try (PDDocument doc =
          PDDocument.load(
              temp, MemoryUsageSetting.setupTempFileOnly().setTempDir(context.getCacheDir()))) {
        if (doc.isEncrypted() || !doc.getCurrentAccessPermission().canExtractContent())
          throw new Failure(
              "This PDF is protected. Choose an unlocked copy that allows text extraction.");
        int pages = doc.getNumberOfPages();
        if (pages > 100)
          throw new Failure("This PDF has " + pages + " pages. Choose 100 pages or fewer.");
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        StringBuilder text = new StringBuilder();
        for (int page = 1; page <= pages; page++) {
          cancel.check();
          progress.update("Reading page " + page + " of " + pages, page * 90 / Math.max(1, pages));
          stripper.setStartPage(page);
          stripper.setEndPage(page);
          text.append(stripper.getText(doc)).append('\n');
          if (text.length() > 2000000)
            throw new Failure("This PDF contains too much text. Split it into smaller chapters.");
        }
        cancel.check();
        if (text.toString().trim().length() < 20)
          throw new Failure(
              "No readable text found. This may be a scanned PDF. Use OCR to create a searchable"
                  + " PDF, then import it again.");
        progress.update("Organizing your reading sections…", 95);
        return StudyRules.chunks(text.toString());
      } catch (InvalidPasswordException e) {
        throw new Failure("This PDF needs a password. Unlock a copy before importing it.");
      }
    } catch (Failure e) {
      throw e;
    } catch (IOException e) {
      throw new Failure(
          "Could not read this file. It may be damaged or unavailable. Save a fresh typed PDF to"
              + " your phone and try again.");
    } finally {
      temp.delete();
    }
  }
}
