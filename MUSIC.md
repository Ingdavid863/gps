# GPS3D 0.31 — reproductor propio y minirreproductor

Al tocar el título o la carátula del área de música se abre **GPS3D Música**, una pantalla de la misma aplicación. El reproductor Media3 pertenece a un servicio independiente de la pantalla: **Atrás** y **Volver al mapa** cierran la pantalla ampliada y mantienen la canción, la posición y la lista. El mapa conserva sus controles de reproducción.

## Escuchar directamente en GPS3D

1. Toca el área de música del mapa.
2. En **Buscar**, elige **Audius** o **SoundCloud**, escribe una canción o artista y toca un resultado público.
3. Consulta título, artista, carátula cuando esté disponible, tiempo y duración; utiliza reproducir/pausar, anterior, siguiente y la barra de posición.
4. Pulsa **Atrás** para minimizar al mapa. **Mi lista** conserva hasta 100 pistas elegidas; **Teléfono** permite añadir archivos y **Radio** ofrece tres emisoras en vivo.

Los catálogos pertenecen a Audius y SoundCloud y requieren internet. Sólo se ofrecen pistas con reproducción pública disponible. Su contenido y disponibilidad dependen del proveedor y del país; no equivalen a las bibliotecas privadas de Spotify o YouTube Music. No se solicitan credenciales, no se descargan canciones y no se eluden pistas privadas, de pago o protegidas. Los archivos elegidos del teléfono sí se pueden escuchar sin internet. La lista se conserva; una URL temporal de SoundCloud se resuelve de nuevo desde su identificador cuando hace falta.

El sonido baja durante las indicaciones de navegación y recupera su volumen al terminarlas. El servicio mantiene la reproducción al volver al mapa o cambiar de pantalla. Los botones de una emisora cambian de emisora; no eligen la canción emitida.

## Otra app musical, opcional

El botón ♫ permite elegir una app instalada, como Spotify o YouTube Music. Para esta opción Android pide activar **Controles de música GPS3D** en **Acceso a notificaciones**. Selecciona una canción en esa app y vuelve al mapa. GPS3D muestra la información y controla su sesión real, con los comandos que publique: título, artista, carátula, estado, posición y duración. La elección se conserva; una app elegida que no tiene sesión nunca redirige órdenes a otra.

Las cuentas, suscripciones, anuncios, conexión y condiciones de segundo plano siguen perteneciendo al proveedor. No se pide su contraseña en GPS3D. Algunas funciones de YouTube Music requieren Premium. Esta modalidad controla la sesión; el catálogo propio de GPS3D es el descrito arriba.

Android exige un NotificationListenerService habilitado por el usuario para consultar sesiones mediante MediaSessionManager. GPS3D no solicita MEDIA_CONTENT_CONTROL, no automatiza el acceso y no utiliza accesibilidad. El servicio no procesa ni guarda contenido de notificaciones; en Android 12+ declara una lista vacía de categorías. Se puede revocar el acceso en Ajustes y el panel elimina los datos de sesión obsoletos. Algunas versiones de Android requieren permitir ajustes restringidos en Información de la app al instalar un APK fuera de la tienda. El reproductor propio funciona sin este acceso.

## Filtro y recursos

El filtro AdGuard DNS por HTTPS se limita a las conexiones del reproductor propio. No modifica mapas, búsquedas, tráfico, el DNS del sistema ni otras aplicaciones. No elimina publicidad insertada dentro del audio o en una app externa. Se puede desactivar desde ♫. Las carátulas se cargan fuera del hilo de interfaz, tienen un tamaño limitado y comparten una caché pequeña. Las búsquedas y la resolución de audio tampoco bloquean el mapa.

## Verificación

Las pruebas Android ejercitan búsquedas públicas reales y la decodificación de audio de cada catálogo, la pantalla ampliada, pausa/reanudación y el regreso al mapa sin reiniciar la canción. Las pruebas de archivos locales y radio comprueban cambios de pista, segundo plano, restauración de lista y atenuación durante indicaciones. Un reproductor de pruebas con otro UID publica metadatos y PCM real para comprobar controles externos, posición, carátula, revocación y selección exacta de una sesión. Ese reproductor no se incluye en el APK. No se usan cuentas reales de Spotify o YouTube Music.

La misma compilación ejecuta las pruebas existentes de GNSS, cámara, navegación, casetas, tráfico, Android Auto y mapa de ruta completa sin conexión.

Referencias: https://docs.audius.org/api/ ; https://github.com/patrickkfkan/soundcloud-fetch ; https://developer.android.com/reference/android/media/session/MediaSessionManager ; https://developer.android.com/reference/android/service/notification/NotificationListenerService ; https://support.google.com/youtubemusic/answer/6313552
