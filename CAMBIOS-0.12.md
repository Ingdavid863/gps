# GPS3D AR David 0.12.0 beta motion

Basado en el código actual 0.11.0 (bcc5ded5feb4c4a303154554696cc76106f5537b).

- Mapa base TomTom, igual que las rutas. OSM permanece debajo como respaldo.
  La precisión sigue siendo de carretera; no garantiza ubicación por carril.
- Pantalla encendida durante la actividad visible y liberada al salir.
- Alfiler al mantener un dedo durante 1000 ms. Se cancela al soltar antes,
  arrastrar más de 10 px, usar dos dedos o cancelar el gesto.
- Animación de flecha y cámara por fotograma, con interpolación por tiempo.
  Predicción limitada a 600 ms / 18 m, siguiendo las curvas de la ruta;
  no continúa indefinidamente cuando dejan de llegar ubicaciones.
- Sin agrupación de ubicaciones GPS; rechaza ubicaciones atrasadas.
- Aviso de llegada sólo a menos de 25 m; antes indica continuar al destino.

Verificación ejecutada: node tests/navigation-motion.test.cjs, revisión sintáctica
JavaScript y git diff --check. No se ha compilado ni probado el APK en Android.

Para compilar con las mismas credenciales y firma del proyecto, incorpora los
archivos en el repositorio y ejecuta el flujo Build Android APK. El flujo utiliza
ARCORE_API_KEY y TOMTOM_API_KEY ya configuradas como secretos; no las incluyas
ni sustituyas por valores vacíos. La salida esperada es la versión 0.12.0.
La comprobación pendiente incluye suspensión de pantalla, permisos de mapas TomTom,
prueba en teléfono de los gestos y reproducción de ubicaciones de conducción.
