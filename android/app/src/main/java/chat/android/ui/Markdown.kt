package chat.android.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink

private val re = Regex("(`[^`\\n]+`|\\*\\*[^*\\n]+\\*\\*|\\*[^*\\n]+\\*|https://[^\\s<>\"']+)")

/** Kleines, sicheres Markdown-Subset: `code`, **fett**, *kursiv*, https-Links. Niemals HTML. */
fun renderInline(text: String): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in re.findAll(text)) {
        if (m.range.first > last) append(text.substring(last, m.range.first))
        val t = m.value
        when {
            t.startsWith("`") -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(t.substring(1, t.length - 1)) }
            t.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(t.substring(2, t.length - 2)) }
            t.startsWith("*") -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(t.substring(1, t.length - 1)) }
            else -> withLink(LinkAnnotation.Url(t)) { withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append(t) } }
        }
        last = m.range.last + 1
    }
    if (last < text.length) append(text.substring(last))
}
