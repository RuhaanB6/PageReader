package com.pagereader.android.reading

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.dp
import com.pagereader.android.ocr.BlockKind
import com.pagereader.android.ocr.BlockLabels
import com.pagereader.android.ocr.OcrPage
import com.pagereader.android.ocr.TextBlock

/**
 * The screen the app lands on once a page has been read.
 *
 * Two audiences at once, and the design has to serve both without either
 * noticing the other:
 *
 *  - **TalkBack off.** The controls below are the whole interface. Not one of
 *    them requires aim — tap anywhere, long-press anywhere, the volume keys, a
 *    shake. A blind user cannot hit a button, so there are no buttons.
 *  - **TalkBack on.** TalkBack consumes single-finger gestures, so those
 *    controls are unreachable. The same block list is therefore exposed as
 *    semantic nodes with a reading-order traversal index and a "Read from here"
 *    action, and TalkBack's own navigation covers the same ground.
 *
 * The visible text exists for low-vision users and sighted helpers. It is never
 * the only channel: everything drawn here is also spoken.
 */
@Composable
fun ReadingScreen(
    page: OcrPage,
    currentBlockId: Int?,
    onTogglePlay: () -> Unit,
    onRepeatBlock: () -> Unit,
    onNextPage: () -> Unit,
    onReadFrom: (TextBlock) -> Unit,
    modifier: Modifier = Modifier,
) {
    val blocks = BlockLabels.playbackBlocks(page)

    Column(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Tap, double-tap and long-press anywhere. Aim is the thing
                // this user does not have, so the target is the whole screen.
                //
                // All three live in ONE recognizer deliberately. A separate
                // double-tap detector on an ancestor does not compose: both
                // see every touch, so one physical double-tap toggles play
                // twice here AND fires the ancestor, and the user gets an
                // unrequested capture over the top of their page. Declaring
                // onDoubleTap here also makes Compose wait for the second tap
                // before resolving a single one, which is the disambiguation
                // two independent detectors cannot do.
                detectTapGestures(
                    onTap = { onTogglePlay() },
                    onDoubleTap = { onNextPage() },
                    onLongPress = { onRepeatBlock() },
                )
            }
            // One traversal group so TalkBack walks the blocks in reading
            // order rather than in layout order.
            .semantics { isTraversalGroup = true },
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(blocks, key = { it.id }) { block ->
                BlockRow(
                    block = block,
                    isCurrent = block.id == currentBlockId,
                    onReadFrom = { onReadFrom(block) },
                )
            }
        }
    }
}

@Composable
private fun BlockRow(
    block: TextBlock,
    isCurrent: Boolean,
    onReadFrom: () -> Unit,
) {
    val label = BlockLabels.title(block)
    val spoken = if (block.text.isNotBlank()) block.text else label

    Text(
        text = spoken,
        style = if (block.kind == BlockKind.HEADING) {
            MaterialTheme.typography.headlineSmall
        } else {
            MaterialTheme.typography.bodyLarge
        },
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier
            .fillMaxSize()
            .background(
                if (isCurrent) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
            )
            .padding(8.dp)
            .semantics {
                // Reading order, not layout order: on a multi-column page these
                // differ, and TalkBack should follow the former.
                traversalIndex = block.order.toFloat()
                if (block.kind == BlockKind.HEADING) heading()
                customActions = listOf(
                    CustomAccessibilityAction("Read from here") {
                        onReadFrom(); true
                    }
                )
            },
    )
}
