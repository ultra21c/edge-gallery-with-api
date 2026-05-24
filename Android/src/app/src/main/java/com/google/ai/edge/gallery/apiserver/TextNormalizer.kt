/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.apiserver

/**
 * Strips markdown formatting that LLMs (Gemma included) routinely emit so that the TTS
 * backend reads the underlying prose instead of the syntax characters. Without this, an LLM
 * answer like `대한민국의 수도는 **서울**입니다.` would be voiced as "star star 서울 star star
 * 입니다" by ElevenLabs (it does not auto-strip markdown) and by Android TTS depending on the
 * engine.
 *
 * The cleaner is intentionally conservative — it preserves all original prose, punctuation,
 * and ordering. Code blocks are kept inline (without the fence) on the assumption that the
 * spoken content is still useful; if it is not, callers can pre-process further.
 */
internal object TextNormalizer {

  // Compiled regexes are kept at file scope because every TTS request hits them.
  private val FENCED_CODE = Regex("```[ \\t]*[A-Za-z0-9_-]*[ \\t]*\\r?\\n([\\s\\S]*?)\\r?\\n[ \\t]*```")
  private val INLINE_CODE = Regex("`([^`\\n]+)`")
  private val IMAGE_LINK = Regex("!\\[([^\\]]*)\\]\\([^)]*\\)")
  private val LINK = Regex("\\[([^\\]]+)\\]\\([^)]+\\)")
  private val AUTO_URL = Regex("https?://\\S+")
  private val BOLD_ITALIC = Regex("\\*\\*\\*(.+?)\\*\\*\\*")
  private val BOLD_STAR = Regex("\\*\\*(.+?)\\*\\*")
  private val BOLD_UNDER = Regex("__(.+?)__")
  // Italic is the trickiest: must not match list markers ("*" / "_") at line start, and must
  // not split a multiplication or filename like `path_to_file`. We require a non-word boundary
  // on each side.
  private val ITALIC_STAR = Regex("(?<![A-Za-z0-9_*])\\*([^*\\n]+?)\\*(?![A-Za-z0-9_*])")
  private val ITALIC_UNDER = Regex("(?<![A-Za-z0-9_])_([^_\\n]+?)_(?![A-Za-z0-9_])")
  private val STRIKETHROUGH = Regex("~~(.+?)~~")
  private val HEADING = Regex("(?m)^[ \\t]*#{1,6}[ \\t]+")
  private val BLOCKQUOTE = Regex("(?m)^[ \\t]*>[ \\t]?")
  private val HORIZONTAL_RULE = Regex("(?m)^[ \\t]*([-*_])([ \\t]*\\1){2,}[ \\t]*$")
  private val BULLET = Regex("(?m)^[ \\t]*[-*+][ \\t]+")
  private val NUMBERED = Regex("(?m)^[ \\t]*\\d+[.)][ \\t]+")
  private val HTML_TAG = Regex("<[^>]+>")
  private val WHITESPACE = Regex("[ \\t]+")
  private val NEWLINES = Regex("\\s*\\n\\s*\\n+\\s*")
  private val INLINE_NEWLINE = Regex("[ \\t]*\\n[ \\t]*")

  /**
   * Returns a speech-friendly version of [text]. The result has no markdown syntax characters
   * and only single spaces between words, with sentence breaks preserved as `.` punctuation
   * so TTS engines pause naturally.
   */
  fun forSpeech(text: String): String {
    if (text.isEmpty()) return text

    var s = text
    // 1. Fenced code blocks → drop fence + language, keep body.
    s = FENCED_CODE.replace(s) { it.groupValues[1] }
    // 2. Inline code → just the body.
    s = INLINE_CODE.replace(s, "$1")
    // 3. Image and ordinary links → keep the alt/anchor text only.
    s = IMAGE_LINK.replace(s, "$1")
    s = LINK.replace(s, "$1")
    // 4. Bare URLs aren't useful to voice — drop them.
    s = AUTO_URL.replace(s, " ")
    // 5. Emphasis (order matters: triple → double → single).
    s = BOLD_ITALIC.replace(s, "$1")
    s = BOLD_STAR.replace(s, "$1")
    s = BOLD_UNDER.replace(s, "$1")
    s = ITALIC_STAR.replace(s, "$1")
    s = ITALIC_UNDER.replace(s, "$1")
    s = STRIKETHROUGH.replace(s, "$1")
    // 6. Block elements.
    s = HEADING.replace(s, "")
    s = BLOCKQUOTE.replace(s, "")
    s = HORIZONTAL_RULE.replace(s, "")
    s = BULLET.replace(s, "")
    s = NUMBERED.replace(s, "")
    // 7. Strip leftover HTML.
    s = HTML_TAG.replace(s, " ")
    // 8. Paragraph breaks → ". " so TTS pauses; intra-paragraph newlines → space.
    s = NEWLINES.replace(s, ". ")
    s = INLINE_NEWLINE.replace(s, " ")
    // 9. Collapse runs of spaces / tabs.
    s = WHITESPACE.replace(s, " ")
    // 10. Tidy stray punctuation that lost its context (". ." → ".", " ." → ".").
    s = s.replace(Regex("\\s+([,.!?;:])"), "$1")
    s = s.replace(Regex("([.!?])\\s*([.!?])+"), "$1")

    return s.trim()
  }
}
