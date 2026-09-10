# GPS3D AR David — Android MVP 0.2

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
- interfaz visual de color + cuenta regresiva.

## MUY IMPORTANTE
La fase y los segundos de los semáforos del MVP son **SIMULADOS** y aparecen etiquetados así. OpenStreetMap aporta la ubicación de los semáforos, no su estado en vivo. Para datos reales hay que conectar SPaT/V2X o una API de la autoridad vial correspondiente.

## Compilar
Requisitos: JDK 17, Android SDK 35 y Gradle 8.11.1.

En Android Studio: abrir la carpeta y ejecutar `app`.

Por terminal, con Gradle instalado:

```bash
gradle assembleDebug
```

El APK queda en `app/build/outputs/apk/debug/app-debug.apk`.

## Fase siguiente
ARCore Geospatial + Terrain Anchors para dibujar una cinta 3D animada aparentemente pegada al pavimento. El render AR debe limitarse a los siguientes ~80–150 m y reciclar anclas/segmentos a medida que avanza el usuario.
