package com.studysnap.app;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class StudyRulesTest {
  @Test
  public void chunkingPreservesEveryWordAndSentence() {
    String sentence = "The cell membrane controls the movement of substances. ";
    String input = sentence.repeat(85);
    List<String> result = StudyRules.chunks(input);
    assertTrue(result.size() > 1);
    assertEquals(input.trim(), String.join(" ", result));
    for (String x : result) {
      assertTrue(x.split("\\s+").length <= 200);
      assertTrue(x.endsWith("."));
    }
  }

  @Test
  public void longSentenceIsNotTruncated() {
    String text = "word ".repeat(240).trim() + ".";
    assertEquals(Collections.singletonList(text), StudyRules.chunks(text));
  }

  @Test
  public void emptyNotesProduceNoPages() {
    assertTrue(StudyRules.chunks(" \n\t ").isEmpty());
  }

  @Test
  public void overnightQuietHours() {
    assertTrue(StudyRules.quiet(23, 22, 8));
    assertTrue(StudyRules.quiet(7, 22, 8));
    assertFalse(StudyRules.quiet(8, 22, 8));
    assertFalse(StudyRules.quiet(12, 22, 8));
    assertFalse(StudyRules.quiet(3, 3, 3));
  }

  @Test
  public void daytimeQuietHours() {
    assertTrue(StudyRules.quiet(12, 10, 16));
    assertFalse(StudyRules.quiet(16, 10, 16));
  }

  @Test
  public void chainedCollisionMovesPastBothTimers() {
    assertEquals(220000, StudyRules.avoidCollision(100001, Arrays.asList(160000L, 100000L)));
  }

  @Test
  public void previewDoesNotSplitEmoji() {
    String text = "a".repeat(79) + "😀" + "bc";
    String preview = StudyRules.preview(text);
    assertTrue(preview.endsWith("😀…"));
  }
}
