# Minification is disabled for release at this stage (see app/build.gradle.kts).
# Rules are kept here so that turning it on later is a one-line change.

-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keep class com.vlad230596.sesame.data.** { *; }
