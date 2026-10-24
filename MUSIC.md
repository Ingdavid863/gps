# GPS3D 0.31 — minirreproductor en el mapa

La barra enlaza una sesión multimedia real de Spotify, YouTube Music u otro reproductor instalado. Presenta canción, artista, carátula si la app la publica, estado de reproducción, posición y duración. Anterior, reproducir/pausar, siguiente y búsqueda en la canción se envían al controlador elegido, respetando los comandos que esa app ofrece. Los controles deshabilitados no se simulan.

## Conexión inicial

1. Toca ♫ en el panel para ver las apps de audio instaladas con sus iconos.
2. Elige Spotify, YouTube Music u otro reproductor y activa **Controles de música GPS3D** cuando Android abra Acceso a notificaciones.
3. Elige una canción en el reproductor si todavía no tiene sesión y vuelve al GPS. La información y los controles aparecen en el mapa. La selección se conserva al reabrir GPS3D.

El catálogo se selecciona en la aplicación musical. GPS3D muestra y controla su sesión; no incrusta la ventana completa de otra app ni copia su catálogo. Al tocar la información del tema puede abrirse su reproductor. El modo automático elige la sesión activa; elegir una app concreta impide controlar accidentalmente otra.

Android exige habilitar un NotificationListenerService para consultar las sesiones de otras apps mediante MediaSessionManager. GPS3D no solicita el permiso de firma MEDIA_CONTENT_CONTROL, no automatiza la concesión y no usa accesibilidad. Su servicio no procesa ni guarda contenido de notificaciones. En Android 12+ declara una lista de categorías vacía; sólo se usan los datos multimedia que el reproductor publica. El usuario puede revocar el acceso desde Ajustes; el panel borra entonces título, carátula y controles obsoletos.

Algunas versiones de Android restringen este acceso para APK instalados fuera de la tienda. Puede ser necesario permitir ajustes restringidos desde Información de la app antes de activarlo. Android 10 o anteriores en dispositivos declarados de poca RAM pueden impedir los listeners; los perfiles de trabajo también tienen restricciones del sistema.

## Condiciones del reproductor

Spotify y YouTube Music mantienen sus propias cuentas, suscripciones, anuncios, conexión y permisos de reproducción en segundo plano. YouTube Music normalmente requiere Premium para escuchar canciones mientras se utiliza otra app; la integración no elimina esa condición ni sus anuncios. No se pide la contraseña en GPS3D. Si una app no publica metadatos, carátula, duración o un comando, GPS3D indica lo disponible sin inventarlo.

## Música propia conservada

El menú conserva archivos del teléfono, enlaces directos HTTPS MP3/AAC/HLS y Radio Paradise. Se reproducen en Media3 dentro de GPS3D, con hasta 100 entradas, reproducción en segundo plano y atenuación durante indicaciones. La radio requiere internet y anterior/siguiente cambian de emisora. La lista se conserva al cambiar entre el reproductor propio y otra app.

AdGuard DNS por HTTPS se aplica sólo al audio del reproductor propio. No modifica DNS del sistema, mapas, búsquedas, tráfico ni otras aplicaciones y no elimina publicidad integrada en emisiones o de YouTube Music externo. Se puede desactivar desde el menú.

## Verificación

El reproductor de pruebas independiente usa otro UID, reproduce PCM real y publica título, artista, carátula, duración y progreso. Las pruebas comprueban controles, búsqueda real en el decodificador, cambios de canción, reapertura, revocación/reconexión y que una app seleccionada no envíe órdenes a otra sesión. No se utilizan cuentas reales de Spotify o YouTube Music. El reproductor de pruebas no se incluye en el APK entregado.

Referencias: https://developer.android.com/reference/android/media/session/MediaSessionManager y https://developer.android.com/reference/android/service/notification/NotificationListenerService ; condiciones de YouTube Music: https://support.google.com/youtubemusic/answer/6313552
