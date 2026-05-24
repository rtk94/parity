# Default ProGuard rules; minification is disabled in release for Phase 6.
# Keep kotlinx.serialization metadata if minification is enabled later.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
