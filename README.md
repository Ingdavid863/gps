# GPS3D AR David — Android 0.18

## Actualización 0.18
- Rutas a pie en AR, mapa de conducción y tráfico TomTom.
- Ajustes → Destinos automáticos desde Maps: asistente optativo de Accesibilidad que ejecuta el envío existente de Compartir indicaciones, con activación inicial, pausa, tiempos límite y prevención de repeticiones. No modifica DiDi ni lee su pantalla.
- El asistente reconoce controles de Maps en español e inglés. Se prueba el servicio real de Android y el selector de compartir con una pantalla de QA que reproduce esos controles; todavía requiere validación con la versión de Google Maps del dispositivo físico.
- Semáforos: ubicaciones cartografiadas por OpenStreetMap/Overpass, caché local, iconos neutros y distancia al cruce próximo a la ruta. Cobertura cartográfica incompleta; no es un inventario de todos los controladores.
- No se simulan colores, ciclos ni segundos. El estado aparece como «sin datos en vivo» hasta disponer de una fuente autorizada y verificable de fases del controlador para la zona.
- La API Traffic de TomTom suministra velocidades/incidentes, no fases de semáforos. TTS/Miovision ofrece datos de estados comerciales, pero no se verificó cobertura para Huehuetoca/Cuautitlán Izcalli ni acceso a su API. No hay conexión activa a ese proveedor.
- Fuente de estados investigada: https://www.traffictechservices.com/personalsignalassistant.html y https://miovision.com/es/v2x/ . Fuente cartográfica: https://wiki.openstreetmap.org/wiki/Tag:highway=traffic_signals .

El módulo mapsFixture es una pantalla de prueba exclusiva del emulador y se incluye únicamente con GPS3D_AUTO_SHARE_QA=1; nunca se entrega al usuario ni se instala en su teléfono.

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

El APK queda en `app/build/outputs/apk/debug/app-debug.apk`.

## Ruta a pie en AR
ARCore Geospatial + Terrain Anchors y anclaje manual a planos permiten dibujar la cinta del recorrido sobre el piso. La alineación y disponibilidad de posicionamiento visual requieren comprobación en el teléfono y en la calle donde se utilice.

