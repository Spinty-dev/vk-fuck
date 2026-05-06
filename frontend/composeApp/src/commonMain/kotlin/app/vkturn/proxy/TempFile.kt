package app.vkturn.proxy

/**
 * Writes [content] to a platform-appropriate temporary location and
 * returns the absolute path. Used by [SingBoxRunner] to stage the
 * on-the-fly sing-box config file before handing it to `-c`.
 *
 * The returned path is stable for the same [name]; callers can rely on
 * it to overwrite previous generations on restart.
 */
expect fun writeTempFile(name: String, content: String): String
