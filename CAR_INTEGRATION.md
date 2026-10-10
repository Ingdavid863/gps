# Navegación en el teléfono y el coche

## Android Auto

La app conserva el paquete y la firma de GPS3D AR David. `GpsCarAppService` usa las plantillas de navegación de Android for Cars; `CarMapSurface` dibuja el mismo mapa WebGL mediante Surface, VirtualDisplay y Presentation. `CarNavigation` comparte la ruta original con el teléfono y devuelve automáticamente los cambios de destino, el avance y la preferencia de casetas. Un servicio de ubicación mantiene el seguimiento cuando la pantalla del teléfono está apagada, con voz sin duplicar las indicaciones del teléfono.

Buscar, terminar, volver al seguimiento y recalcular sin casetas son acciones de la pantalla del coche. El modo AR sigue siendo para el teléfono y no cambia el modo de conducción del auto. No se piden permisos de accesibilidad ni de lectura de notificaciones.

La compilación y las pruebas de Surface/plantillas no garantizan que un host Android Auto físico admita un APK descargado fuera de Google Play. Para probarlo en el coche, hay que distribuirlo por una pista de pruebas de Google Play y habilitar Android Auto en Play Console. La pista interna no requiere la revisión específica para coches; las pruebas abiertas y la publicación en producción sí requieren su aprobación. El Desktop Head Unit permite probar el flujo con el teléfono y no sustituye la prueba en el vehículo.

## iPhone y CarPlay

Ver [ios/README.md](ios/README.md). CarPlay funciona desde un iPhone con la app firmada y el entitlement de navegación concedido por Apple. Un APK Android no funciona en CarPlay.

## Referencia Vela

Se revisó [PimpinPumpkin/Vela](https://github.com/PimpinPumpkin/Vela), que incluye Android Auto. Su organización alrededor de una sesión compartida sirve como referencia. Esta integración utiliza las APIs oficiales de Android for Cars y CarPlay y el mapa existente de GPS3D; no incorpora código de Vela bajo GPL-3.0. Vela no aporta una aplicación iPhone/CarPlay.
