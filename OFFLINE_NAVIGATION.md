# GPS3D 0.26 — ruta guardada y recálculo

Al activar una ruta, GPS3D guarda la polilínea completa, los giros, instrucciones, destino,
duración y preferencias de casetas. La sesión se restaura al volver a abrir el mapa,
sin consultar nuevamente el servicio. «Detener» elimina la sesión activa.

La descarga prepara todos los segmentos hasta el destino, incluyendo calles cercanas y
todos los niveles 3–16. Ya no existe el recorte a 10 km/320 mosaicos. El detalle vectorial
16 se amplía en las vistas cercanas sin conexión; al reconectar se recupera el detalle
online 18. No cambia el zoom de la cámara, la posición del carro ni la geometría de la ruta.
Se incluyen los iconos y fuentes latinas de los estilos de día/noche.

`SafeWebView` conserva el interceptor de mapas junto al cargador de recursos seguros.
Anteriormente, `loadUrl` sustituía el `WebViewClient` de la actividad: las descargas se
guardaban, pero las peticiones del mapa no llegaban a esa caché. La prueba de Android con
wifi y datos apagados, caché HTTP eliminada y WebView nuevo cubre esta integración.
Los mosaicos vectoriales pasan por un protocolo de MapLibre ejecutado en el hilo principal,
porque sus peticiones desde workers pueden evitar el interceptor del WebView. El protocolo
conserva la cancelación y no interviene en las consultas de tráfico o búsqueda.

El paquete de la ruta se guarda fuera de la caché HTTP de exploración: acercarse, alejarse
o mirar otra zona no lo desaloja. Una nueva ruta sustituye el corredor anterior y reutiliza
los recursos compartidos. Hay tres descargas simultáneas con prioridad para el mapa visible.
Los archivos se reemplazan atómicamente; una interrupción reanuda sólo lo pendiente.
Un fallo aislado no abandona el resto del recorrido. El estado informa porcentaje, falta
de internet o de espacio y permite reintentar tocándolo. Sólo muestra «Ruta y mapa disponibles
sin conexión» después de comprobar todos los recursos requeridos.

El mapa descargado incluye calles, no imágenes satelitales, relieve remoto ni tráfico en
vivo. Conviene esperar el aviso de disponibilidad antes de entrar en una zona sin datos.
La geometría y las instrucciones se guardan desde el principio, aunque la descarga del
mapa todavía esté en curso. La descarga continúa mientras viva la actividad y se retoma
al volver a abrirla si Android terminó su proceso.

## Recálculo

Los desvíos se confirman con posiciones GPS distintas, recientes y precisas, en unos
300–1000 ms según distancia, rumbo y velocidad. La animación/predicción del carro no puede
provocar un recálculo. Se elimina el bloqueo fijo de 6.5 segundos. El servicio recibe la
posición actual, el rumbo fiable, las restricciones de casetas y una sola alternativa;
la consulta automática tiene un límite total de seis segundos. Una respuesta que ya no
incluye la posición del conductor se sustituye por una consulta nueva.

Un corte durante la consulta conserva la ruta y reanuda el recálculo al reconectar, siempre
que el GPS siga confirmando el desvío. Volver a la ruta cancela esa necesidad. El tiempo
de respuesta final depende del proveedor y de la conexión. Calcular un camino nuevo
requiere internet: el paquete descargado no contiene un motor ni una red vial de rutas
offline. La navegación por la ruta guardada sí continúa con el GPS del teléfono.

## Comprobaciones

- Cobertura de todos los kilómetros y niveles, incluso segmentos largos y cruce de fecha.
- Continuación tras fallos, reanudación, rotación de clave y persistencia independiente
  de la caché HTTP; cancelación al elegir otra ruta.
- Restauración de todos los puntos, giros, casetas y modalidad peatonal.
- Confirmación rápida de desvíos, rechazo de ruido/fixes repetidos y recuperación de internet.
- WebGL con mapas reales descargados previamente, página nueva sin conexión, tramo intermedio,
  destino a más de 20 km, acercamiento 18.65, vista general y cambio día/noche.
- Android: restauración real de la actividad, envío de recálculo, corte durante la consulta,
  reanudación inmediata y conservación de la preferencia sin casetas.
- Suite existente de precisión, carro, controles, tráfico, casetas, enlaces compartidos,
  búsqueda, Android Auto y continuidad AR.

## Corrección 0.30: datos móviles presentes con descargas fallidas

El primer seguimiento o un recentrado a más de 250 m aplica posición, rumbo y zoom de inmediato. La cámara no recorre puntos intermedios entre el centro inicial y la posición GNSS, evitando descargas de lugares ajenos al corredor preparado. Los movimientos normales del vehículo mantienen la interpolación fluida.

Mientras hay una ruta activa, la fuente vectorial usa el nivel 16 del mismo paquete protegido incluso si Android indica conexión. Antes, la vista en línea pedía niveles 17/18 que no estaban en ese paquete: un fallo parcial podía dejar calles vacías con el mensaje de mapa guardado. La navegación ya no cambia a un proveedor sin preparar cuando falla una tesela de la ruta. La preparación especulativa respeta el mismo nivel. La cámara, carreteras y edificios siguen siendo vectoriales, con su escala en metros; fuera de navegación se conserva el detalle en línea hasta nivel 18.

La comprobación WebGL utiliza Cabo San Lucas–Aeropuerto de Los Cabos, simula datos móviles con sólo el nivel preparado disponible y luego recorre el mapa sin conexión. El GPS descarta una degradación aislada que desplazaría una posición reciente precisa, sin ampliar la tolerancia de ajuste a carretera. Permite recuperar lecturas menos precisas después de 2.5 segundos. El rumbo de la cámara sólo usa curso GNSS fiable o el tramo al que la posición ya está ajustada; al detenerse conserva orientación. La exactitud física sigue dependiendo de la señal GNSS y de la cartografía del proveedor.

Los accesos que el proveedor marca como no públicos (por ejemplo, una terminal) conservan su asfalto a la misma escala que el resto del mapa. Se mantienen sus puntos de restricción y filtros; no se usan para atraer automáticamente el GPS a una calle restringida.
