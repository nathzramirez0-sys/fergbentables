package com.blushdesk.app.data.backup

/** The rule for a backup password, shared by the screen and its tests. */
object BackupPassword {
    const val MIN_LENGTH = 6

    /** Why [password] and its [confirmation] can't be used yet, or null when they can. */
    fun problem(password: String, confirmation: String): String? = when {
        password.length < MIN_LENGTH -> "Use at least $MIN_LENGTH characters."
        password.isBlank() -> "The password can't be only spaces."
        password != confirmation -> "The two passwords don't match."
        else -> null
    }
}
