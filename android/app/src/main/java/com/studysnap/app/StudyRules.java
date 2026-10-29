package com.studysnap.app;

import java.text.BreakIterator;
import java.util.*;

public final class StudyRules {
  private StudyRules() {}

  public static List<String> chunks(String text) {
    text = text.replaceAll("\\s+", " ").trim();
    List<String> result = new ArrayList<>();
    if (text.isEmpty()) return result;
    BreakIterator sentences = BreakIterator.getSentenceInstance(Locale.ENGLISH);
    sentences.setText(text);
    StringBuilder chunk = new StringBuilder();
    int words = 0, start = sentences.first();
    for (int end = sentences.next();
        end != BreakIterator.DONE;
        start = end, end = sentences.next()) {
      String sentence = text.substring(start, end).trim();
      int count = sentence.split("\\s+").length;
      if (words > 0 && words + count > 200) {
        result.add(chunk.toString().trim());
        chunk.setLength(0);
        words = 0;
      }
      chunk.append(sentence).append(' ');
      words += count;
    }
    if (chunk.length() > 0) result.add(chunk.toString().trim());
    return result;
  }

  public static String preview(String text) {
    int count = text.codePointCount(0, text.length());
    return count > 80 ? text.substring(0, text.offsetByCodePoints(0, 80)).trim() + "…" : text;
  }

  public static boolean quiet(int hour, int start, int end) {
    if (start == end) return false;
    return start < end ? hour >= start && hour < end : hour >= start || hour < end;
  }

  public static long avoidCollision(long candidate, List<Long> others) {
    List<Long> sorted = new ArrayList<>(others);
    Collections.sort(sorted);
    for (long other : sorted) if (Math.abs(candidate - other) < 60000) candidate = other + 60000;
    return candidate;
  }
}
