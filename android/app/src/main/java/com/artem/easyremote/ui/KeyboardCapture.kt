package com.artem.easyremote.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.artem.easyremote.Proto
import com.artem.easyremote.RemoteController
import kotlin.math.min

/** The field always starts with this character so a Backspace on an "empty" field is still a text change. */
private const val SENTINEL = " "
private const val MAX_BUFFER = 48

/**
 * Invisible text field that receives the phone's normal on-screen keyboard and forwards what is
 * typed to the PC. Works with any IME / language. [request] is incremented by the keyboard button:
 * the first press opens the keyboard, the next hides it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeyboardCapture(c: RemoteController, request: Int) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible
    var buffer by remember { mutableStateOf(TextFieldValue(SENTINEL, TextRange(SENTINEL.length))) }

    LaunchedEffect(request) {
        if (request == 0) return@LaunchedEffect
        if (imeVisible) {
            keyboard?.hide()
            focusManager.clearFocus()
        } else {
            focus.requestFocus()
            keyboard?.show()
        }
    }
    // when the user dismisses the keyboard with Back, drop focus so the next press opens it again
    LaunchedEffect(imeVisible) {
        if (!imeVisible) focusManager.clearFocus()
    }

    BasicTextField(
        value = buffer,
        onValueChange = { nv ->
            val old = buffer.text
            val new = nv.text
            if (old != new) sendDiff(c, old, new)
            buffer = if (new.length < SENTINEL.length || new.length > MAX_BUFFER) {
                TextFieldValue(SENTINEL, TextRange(SENTINEL.length))
            } else {
                nv
            }
        },
        modifier = Modifier.size(1.dp).alpha(0f).focusRequester(focus),
        singleLine = true,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { c.key(Proto.KEY_ENTER) }),
    )
}

/** Translate a text change into Backspaces + typed text (the change is almost always at the end). */
private fun sendDiff(c: RemoteController, old: String, new: String) {
    var p = 0
    val m = min(old.length, new.length)
    while (p < m && old[p] == new[p]) p++
    // never split a surrogate pair
    if (p > 0 && p < old.length && Character.isHighSurrogate(old[p - 1])) p--
    val removed = old.substring(p)
    val inserted = new.substring(p)
    repeat(removed.codePointCount(0, removed.length)) { c.key(Proto.KEY_BACKSPACE) }
    if (inserted.isNotEmpty()) c.text(inserted)
}
