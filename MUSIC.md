# Música en GPS3D 0.28

Pulsa la nota musical en el panel inferior. Puedes añadir uno o varios archivos de audio desde el selector de Android, ver tu lista, reproducir/pausar y cambiar de canción sin salir del mapa. La lista admite hasta 100 documentos y conserva la canción y la posición. Al volver a abrir el servicio la lista queda en pausa; no comienza a sonar por sí sola.

Los archivos guardados físicamente en el teléfono se reproducen sin internet. Los archivos de un proveedor de documentos en la nube pueden necesitar conexión. Si se mueve o elimina un archivo, hay que volver a seleccionarlo. No se solicita acceso a toda la biblioteca del teléfono.

La reproducción pertenece a un MediaSessionService con ExoPlayer (Media3 1.9.4), no a la actividad del mapa. Continúa al abrir otra pantalla o bloquear el teléfono y ofrece controles de Android/Bluetooth. El usuario puede pausarla desde el mapa o desde los controles del sistema. Al desconectar audífonos se pausa mediante audio becoming noisy. El servicio se mantiene en primer plano mientras reproduce.

Las indicaciones del mapa, la navegación peatonal AR y Android Auto reducen temporalmente el volumen de las canciones propias al 20%. Un token por locutor evita que la terminación atrasada de una indicación restaure el volumen mientras se escucha otra. Se libera al terminar, interrumpir, silenciar o cerrar la navegación. Para el audio de otras apps se conserva AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK de Android; el comportamiento final depende de la app externa.

Spotify y YouTube Music tienen accesos directos, junto con un acceso al reproductor que Android tenga instalado. Se abren en su propia aplicación y se puede regresar al GPS mientras continúan sonando. Estos accesos no son una integración de catálogo/cuenta ni controles remotos de Spotify/YouTube dentro de GPS3D. Los botones del panel controlan únicamente los archivos elegidos en GPS3D; las apps externas conservan sus controles en Android. No se incluyen claves OAuth, SDKs de streaming ni acceso a notificaciones ajenas.

La fila de música está dentro del panel inferior ya medido por setViewport: la cámara y el carro reservan el espacio real de todo el panel. Se mantiene el tema automático, los controles del mapa, las casetas y la ruta completa sin conexión.

Fuentes técnicas: [MediaSessionService](https://developer.android.com/media/media3/session/background-playback), [audio focus](https://developer.android.com/media/optimize/audio-focus), [documentos seleccionados por el usuario](https://developer.android.com/training/data-storage/shared/documents-files).
