package me.rerere.rikkahub.ui.components.message

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.ChainOfThoughtScope

@Composable
fun ChainOfThoughtScope.ChatMessageCommentaryStep(
    text: String,
    onClickCitation: (String) -> Unit = {},
) {
    var expanded by remember { mutableStateOf(true) }
    ControlledChainOfThoughtStep(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        label = {
            Text(
                text = "Commentary",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        },
        contentVisible = expanded,
        content = { MarkdownBlock(content = text, onClickCitation = onClickCitation) },
    )
}
