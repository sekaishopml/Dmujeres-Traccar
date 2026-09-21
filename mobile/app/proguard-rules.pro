# Mantener nombres Paho/Retrofit si se habilitan; sin minify en esta fase.
-keep class org.eclipse.paho.** { *; }
# Sentry: que R8 no recorte su init ni el serializado de eventos.
-keep class io.sentry.** { *; }
-dontwarn io.sentry.**
# Modelos y entry points propios que viajan por reflexión/intents.
-keep class com.dmujeres.traccar.DmujeresApp { *; }
-keep class com.dmujeres.traccar.ui.SplashActivity { *; }
-keep class com.dmujeres.traccar.ui.MainActivity { *; }
-keep class com.dmujeres.traccar.BuildConfig { *; }
