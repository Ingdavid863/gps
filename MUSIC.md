# Música en GPS3D 0.30

El modo inicial y la primera apertura tras actualizar usan el reproductor propio. El botón de reproducir abre «Escuchar dentro de GPS3D» si no hay una lista. Se puede seleccionar una emisora de Radio Paradise, añadir enlaces directos de audio HTTPS (incluido HLS) o elegir archivos con el selector de Android. La reproducción la realiza Media3 en el servicio de GPS3D: no se abre otra aplicación para esas fuentes. Las tres emisoras iniciales conservan los enlaces y canciones que ya existían en la lista. Su sitio y condiciones se enlazan desde «Acerca de la radio».

La radio requiere internet. Anterior y siguiente cambian la emisora o entrada de la lista, no seleccionan canciones dentro de la programación de una emisora. Los archivos físicos elegidos en el teléfono funcionan sin internet; los proveedores de documentos en la nube pueden requerir conexión. La lista guarda hasta 100 entradas y conserva tipo de fuente, título y posición. Las emisiones en vivo vuelven al directo sin intentar recuperar una posición antigua. Restaurar un servicio deja la lista en pausa, sin reproducción automática.

Los botones del mapa, la sesión de Android/Bluetooth y el servicio de reproducción funcionan también cuando se recrea la actividad. Las indicaciones del mapa, AR peatonal y Android Auto conservan la prioridad de audio y reducen el volumen del reproductor propio mientras se habla. La migración de la lista anterior mantiene los documentos y posiciones guardados.

## Filtro de anuncios

El cliente HTTP de música utiliza el DNS público de AdGuard por HTTPS, activado inicialmente. El menú «Bloqueo de anuncios en audio» permite desactivarlo y explica el alcance. Las consultas de dominios de audio se envían a ese proveedor. El filtro se aplica a las nuevas conexiones; para reabrir una conexión en curso hay que parar/cambiar la fuente. Las respuestas nulas o de loopback no se convierten en conexiones locales.

El filtro no cambia el DNS del teléfono, no instala una VPN y no interviene en las conexiones del mapa, búsqueda, tráfico o cálculo de rutas. Bloquea dominios de publicidad/rastreo que identifique el proveedor. No elimina publicidad que venga insertada en el mismo audio, ni los anuncios de las apps externas. Si el proveedor DNS no está disponible puede impedir abrir una fuente; se puede desactivar desde el menú. No se promete bloquear toda publicidad.

## YouTube Music y Spotify

Siguen disponibles como opciones externas y conservan los controles multimedia de Android de 0.29. Su catálogo no se extrae ni se presenta como audio propio. Las páginas de esas plataformas no son enlaces directos de audio. YouTube Music vincula música en segundo plano y escucha sin anuncios a sus requisitos de suscripción; los botones de GPS3D no cambian esos requisitos. RethinkDNS reconoce que DNS no elimina de forma fiable los anuncios de YouTube, por lo que una VPN DNS adicional no resolvería esa restricción.

## Validación

La validación incluye reproducción HTTPS real dentro del servicio de GPS3D, botones del mapa, persistencia de fuentes/listas, continuidad tras recrear la actividad y en segundo plano. Se comprueba el DNS filtrado con emisoras accesibles y un dominio publicitario bloqueado. También se mantienen las pruebas de archivos locales, prioridad de voz y control de un reproductor Android independiente. Las pruebas no sustituyen comprobar la recepción GNSS y la red en el teléfono de David.

Fuentes: [Media3](https://developer.android.com/media/media3/session/background-playback), [Radio Paradise: enlaces para reproductores](https://radioparadise.com/listen/stream-links), [AdGuard DNS público](https://adguard-dns.io/es/public-dns.html), [YouTube Music en segundo plano](https://support.google.com/youtubemusic/answer/6313552), [límites de RethinkDNS](https://rethinkdns.com/faq).
