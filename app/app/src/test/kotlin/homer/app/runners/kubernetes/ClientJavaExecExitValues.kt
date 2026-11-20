package homer.app.runners.kubernetes

/** client-java's exec exit value after a websocket failure. */
internal const val EXEC_NO_EXIT_STATUS_SENTINEL = -1975219

/** client-java's exec exit value when the channel ended with no exit code: no `v1.Status`, or one without an exit code. */
internal const val EXEC_ENDED_WITHOUT_EXIT_CODE = -1
