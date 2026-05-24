/*
 * Copyright 2025 Google LLC
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

package com.google.ai.edge.gallery.ui.common

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.firebaseAnalytics
import com.google.ai.edge.gallery.ui.theme.customColors

@Composable
fun buildTrackableUrlAnnotatedString(url: String, linkText: String): AnnotatedString {
  val uriHandler = LocalUriHandler.current
  val context = LocalContext.current
  return buildAnnotatedString {
    withLink(
      link =
        LinkAnnotation.Url(
          url = url,
          styles =
            TextLinkStyles(
              style =
                SpanStyle(
                  color = MaterialTheme.customColors.linkColor,
                  textDecoration = TextDecoration.Underline,
                )
            ),
          linkInteractionListener = {
            // openUri() throws IllegalArgumentException -> ActivityNotFoundException when no
            // browser (or any activity that can handle android.intent.action.VIEW on https) is
            // installed/enabled. That used to crash the whole app. Fall back to copying the
            // link to the clipboard so the user still has a path forward.
            try {
              uriHandler.openUri(url)
            } catch (e: Exception) {
              Log.w("ClickableLink", "Failed to open $url: ${e.message}")
              copyToClipboardAndToast(context, url)
            }
            firebaseAnalytics?.logEvent(
              "resource_link_click",
              Bundle().apply { putString("link_destination", url) },
            )
          },
        )
    ) {
      append(linkText)
    }
  }
}

private fun copyToClipboardAndToast(context: Context, url: String) {
  runCatching {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("URL", url))
    Toast.makeText(context, "No browser installed — link copied to clipboard", Toast.LENGTH_LONG)
      .show()
  }
}

@Composable
fun ClickableLink(
  url: String,
  linkText: String,
  modifier: Modifier = Modifier,
  icon: ImageVector? = null,
  textAlign: TextAlign = TextAlign.Center,
) {
  val annotatedText = buildTrackableUrlAnnotatedString(url, linkText)

  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.Center,
    modifier = modifier,
  ) {
    if (icon != null) {
      Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
    }
    Text(
      text = annotatedText,
      textAlign = textAlign,
      style = MaterialTheme.typography.bodyMedium,
      modifier = Modifier.padding(start = if (icon != null) 6.dp else 0.dp),
    )
  }
}
