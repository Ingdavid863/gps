# GPS3D AR David — Android 0.19.1

## Actualización 0.19.1: recepción normal de destinos
- Se retira completamente el servicio de Accesibilidad que automatizaba los botones de Google Maps. Era un candidato al bloqueo de Play Protect por permisos sensibles; esta versión ya no puede leer ni pulsar la pantalla de otra app.
- Se conserva la recepción de destinos por Compartir de Android, enlaces de Maps/Waze e intents geo/google.navigation, además de la búsqueda propia de GPS3D.
- Ajustes → Recibir destinos desde Maps explica el envío manual. La automatización desde Maps de 0.18 deja de estar disponible; no se solicita desactivar Play Protect ni habilitar ajustes restringidos.
- El APK entregado es de release, no depurable, con el mismo paquete y certificado de las actualizaciones anteriores y versionCode 25.
- Se revisan los permisos del manifiesto combinado y se prueban los intents reales de Android, además de las pruebas de continuidad AR y sincronización entre mapa y cámara.
- No se afirma aprobación de Google ni que el emulador pueda validar el resultado de Play Protect en el teléfono; eso requiere probar la nueva instalación.

## Actualización 0.19: continuidad de la ruta AR
- La cámara muestra hasta 160 m del recorrido por delante y renueva ese tramo conforme avanza el peatón; el recorrido completo se conserva en el mapa.
- Una actualización de ruta conserva la alineación manual. El anclaje cercano se renueva cada 6 m manteniendo posición geográfica y orientación.
- Los nodos visuales usan poses; su eliminación ya no destruye los anclajes que todavía necesita el controlador de la ruta.
- Se resuelve un anclaje de terreno por bloque de 8 m. Si faltan alturas lejanas se usa la altura medida del suelo y se identifica como aproximada, sin ocultar tramos por esperar el siguiente anclaje.
- El mapa y la cámara reutilizan el mismo archivo de ruta peatonal, sin recalcularlo al reabrir la cámara mientras sigue siendo válido.
- La banderita es un indicador del rumbo geográfico del destino en la cámara; muestra la distancia restante y una flecha si queda fuera del campo de visión.
- La flecha de maniobra coincide con izquierda/derecha/llegada; la distancia de giro es más grande.
- Google ARCore Geospatial aporta ubicación y orientación visual cuando está autorizado y tiene cobertura. La ruta peatonal actual se calcula con TomTom: no se afirma acceso a la ruta interna ni al Live View de la app Google Maps.
- Las pruebas automáticas cubren geometría de calles largas, densidad de puntos, giros, proyección del destino y cambios mapa/cámara en Android. La alineación física, altura del pavimento y disponibilidad de VPS requieren prueba con la cámara del teléfono en la calle.

## Actualización histórica 0.18 (automatización retirada en 0.19.1)
- Rutas a pie en AR, mapa de conducción y tráfico TomTom.
- Ajustes → Destinos automáticos desde Maps: asistente optativo de Accesibilidad que ejecuta el envío existente de Compartir indicaciones, con activación inicial, pausa, tiempos límite y prevención de repeticiones. No modifica DiDi ni lee su pantalla.
- El asistente reconoce controles de Maps en español e inglés. Se prueba el servicio real de Android y el selector de compartir con una pantalla de QA que reproduce esos controles; todavía requiere validación con la versión de Google Maps del dispositivo físico.
- Semáforos: ubicaciones cartografiadas por OpenStreetMap/Overpass, caché local, iconos neutros y distancia al cruce próximo a la ruta. Cobertura cartográfica incompleta; no es un inventario de todos los controladores.
- No se simulan colores, ciclos ni segundos. El estado aparece como «sin datos en vivo» hasta disponer de una fuente autorizada y verificable de fases del controlador para la zona.
- La API Traffic de TomTom suministra velocidades/incidentes, no fases de semáforos. TTS/Miovision ofrece datos de estados comerciales, pero no se verificó cobertura para Huehuetoca/Cuautitlán Izcalli ni acceso a su API. No hay conexión activa a ese proveedor.
- Fuente de estados investigada: https://www.traffictechservices.com/personalsignalassistant.html y https://miovision.com/es/v2x/ . Fuente cartográfica: https://wiki.openstreetmap.org/wiki/Tag:highway=traffic_signals .

El servicio de automatización, sus pruebas y la pantalla mapsFixture se eliminaron en 0.19.1.

## MVP histórico

Aplicación Android de navegación experimental sin claves API propietarias.

## Incluye
- MapLibre Native.
- Estilo público OpenFreeMap.
- cámara inclinada tipo navegación 3D.
- ubicación GPS del teléfono.
- mantener pulsado un destino para calcular ruta.
- ruta mediante OSRM público.
- semáforos mediante OpenStreetMap/Overpass.
- distancia al semáforo más cercano.
- ubicaciones de semáforos con estado desconocido cuando falta una fuente en vivo.

## MUY IMPORTANTE
OpenStreetMap aporta la ubicación de los semáforos, no su estado en vivo. El ciclo simulado original fue eliminado. Para fases reales hay que conectar SPaT/V2X o una API de la autoridad vial correspondiente y comprobar cobertura, dirección y caducidad de las observaciones.

## Compilar
Requisitos: JDK 17, Android SDK 35 y Gradle 8.11.1.

En Android Studio: abrir la carpeta y ejecutar `app`.

Por terminal, con Gradle instalado:

```bash
gradle assembleDebug
```

El APK de desarrollo queda en `app/build/outputs/apk/debug/app-debug.apk`. Para una actualización entregable, usar `GPS3D_DEBUG_KEYSTORE` con el certificado existente y `gradle assembleRelease`; queda en `app/build/outputs/apk/release/app-release.apk`.

## Ruta a pie en AR
ARCore Geospatial + Terrain Anchors y anclaje manual a planos permiten dibujar la cinta del recorrido sobre el piso. La alineación y disponibilidad de posicionamiento visual requieren comprobación en el teléfono y en la calle donde se utilice.

