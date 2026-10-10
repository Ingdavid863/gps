# GPS3D para iPhone y CarPlay

App nativa de iPhone y escena de navegación CarPlay. Un `NavigationStore` comparte la ruta exacta, posición, avance, instrucciones y preferencias entre el iPhone y el auto. Las dos pantallas utilizan el mismo mapa MapLibre y las mismas capas direccionales de tráfico TomTom que Android. El tráfico general se oculta durante una ruta y los tramos ya recorridos se retiran de la línea. El buscador incluye × y conserva Ir en la ficha del destino. Evitar caseta recalcula la ruta y solo cambia la preferencia si el proveedor devuelve una alternativa sin peaje; se comprueba también el catálogo de casetas mexicano.

## Compilar

En macOS con Xcode y XcodeGen:

```sh
python3 ios/scripts/prepare-map-assets.py
cd ios
xcodegen generate
xcodebuild -project GPS3D.xcodeproj -scheme GPS3D -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO TOMTOM_API_KEY='<clave TomTom>' build
```

Para instalar en iPhone: abrir el proyecto generado, seleccionar el equipo de Apple Developer, configurar la clave TomTom como ajuste `TOMTOM_API_KEY` y firmar con su perfil. La clave no se guarda en el código. Antes de distribuir, limitarla a los servicios y clientes necesarios en el proveedor.

CarPlay requiere que Apple conceda `com.apple.developer.carplay-maps` al identificador de la app y que se incluya en el perfil de distribución. El archivo de entitlements y la escena ya se generan, pero compilar sin firma o abrir el simulador no acredita acceso a un auto real. No hay IPA instalable ni publicación automática. La prueba física debe cubrir la conexión al auto, permisos, voz, cambios de destino y seguimiento en carretera.

## Validación

El flujo `Validate iPhone and CarPlay` compila para simulador y dispositivo y ejecuta pruebas de avance métrico, recorte de ruta, calles paralelas, sentido de las casetas, recalculo sin peaje y búsqueda. Los servicios de rutas se simulan en las pruebas; el mapa real requiere conexión y la clave configurada.

Esta primera versión para iPhone contiene navegación en mapa y CarPlay. La cámara AR continúa en la aplicación Android existente.
