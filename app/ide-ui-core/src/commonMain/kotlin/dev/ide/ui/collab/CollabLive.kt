package dev.ide.ui.collab

import dev.ide.ui.editor.core.EditorSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Connects open editor tabs to live collaboration. The collaboration layer installs [attach] and [detach] when
 * it starts; tabs call [opened] and [closed]. Anything that touches an editor runs on the UI thread through [onUi].
 */
object CollabLive {
    /** Starts live editing of [path] in [session], if that file belongs to a shared project. */
    var attach: ((path: String, session: EditorSession) -> Unit)? = null

    /** Stops live editing of [session]. */
    var detach: ((path: String, session: EditorSession) -> Unit)? = null

    private val ui = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun opened(path: String, session: EditorSession) {
        attach?.invoke(path, session)
    }

    fun closed(path: String, session: EditorSession) {
        detach?.invoke(path, session)
    }

    /** Runs [block] on the UI thread, in the order it was posted. */
    fun onUi(block: () -> Unit) {
        ui.launch { block() }
    }
}
