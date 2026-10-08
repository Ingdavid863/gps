# GPS3D AR David 0.13.0

Las ubicaciones recibidas por `geo:` ya no acceden a parámetros de una URI opaca, lo que causaba un cierre de Android. El destino se conserva hasta que el mapa y un GPS aceptado están listos. Se aceptan coordenadas, direcciones, enlaces de Maps/Waze y texto compartido; los enlaces cortos se resuelven sin cargar páginas externas en el WebView.

La consulta real al servicio confirmó que Search rechaza `es-MX` (HTTP 400). Las peticiones usan `es-ES`, manteniendo la interfaz y moneda mexicanas. La búsqueda combina geocodificación de direcciones y búsqueda de lugares TomTom, conserva el municipio en los resultados y permite recorrer la lista. Se cancelan y descartan respuestas antiguas. Una dirección ambigua requiere seleccionar el resultado. La búsqueda pública de respaldo se utiliza únicamente al enviar la consulta, no para autocompletar.

El botón de ojo cambia entre vista isométrica (55°), vista completa de origen/destino y navegación habitual. La vista completa permanece estable durante las actualizaciones de GPS. La flecha verde apunta a la posición geográfica del vehículo y se encuadra 48 píxeles CSS sobre el panel inferior según el tamaño real de ese panel.

El panel inferior muestra casetas pendientes detectadas, la opción Evitar caseta y el importe estimado en MXN. Evitar caseta recalcula mediante TomTom `avoid=tollRoads`; se rechaza una respuesta que conserve tramos TOLL. Se guardan las preferencias y se aplican también al recálculo automático. Si la nueva ruta falla se conserva la configuración de la ruta vigente.

Las casetas se buscan en el corredor de toda la ruta elegida, se proyectan sobre sus segmentos y se agrupan los registros de varios carriles de una misma plaza. No se toma el número de tramos de cuota como número de casetas. Las tarifas para auto de dos ejes sin remolque provienen de la tabla CAPUFE 2026, descargada el 8 de octubre, páginas 1–8. Solo se aplican coincidencias inequívocas de nombre y tarifa. No se adivinan tarifas de concesiones, ni cobros distintos por acceso/salida. Se indica "tarifa pendiente" o un subtotal con tarifas faltantes. El número de plazas se etiqueta como detectadas porque OpenStreetMap puede tener registros incompletos. Tocar la cantidad o el importe abre el detalle y fecha de tarifas.

Se conserva la interpolación de movimiento, el tiempo de pulsación de un segundo y la pantalla encendida mientras el navegador está visible. Se añaden pruebas JVM para ubicaciones opacas/malformadas y casetas, además de pruebas JavaScript para el anclaje de la cámara y el encuadre completo. La compilación ejecuta las pruebas antes de producir el APK.

## Fuentes

- [Intents Android](https://developer.android.com/guide/components/intents-common)
- [TomTom Search](https://docs.tomtom.com/search-api/documentation/search-service/fuzzy-search)
- [TomTom Calculate Route](https://docs.tomtom.com/routing-api/documentation/tomtom-maps/v1/calculate-route)
- [Tarifas CAPUFE 2026](https://iave.capufe.gob.mx/assets/Doc/Tarifas-vigentes-2026.pdf)

Los cambios se compilan en una rama independiente. El caché de la clave debug estabiliza la firma a partir de esta versión; no recupera la clave privada de un APK anterior generado en otro runner.
