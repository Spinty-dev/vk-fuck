# Default Compose rules only keep the *name* of MainDispatcherFactory; the Swing
# implementation is referenced only from META-INF/services and is otherwise
# removed, so Dispatchers.Main stays a "missing" stub in the release uber JAR.
-keep class kotlinx.coroutines.swing.** { *; }
