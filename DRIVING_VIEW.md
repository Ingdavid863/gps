# GPS3D 0.27 — vista de conducción 3D

La vista inicial usa la cámara detrás del vehículo, zoom de conducción 19.3 y un carro blanco
con cristal panorámico, luces y sombra. Se conserva el anclaje geográfico elevado,
la corrección a carreteras, el movimiento continuo y el ciclo del ojo: conducción,
recorrido completo y plano. Los botones de zoom siguen disponibles.

La ruta tiene una franja translúcida sobre el suelo y chevrones que siguen su
geometría. Su ancho se expresa en metros para conservar la perspectiva. Sólo se
construyen los primeros 900 m del tramo pendiente; el trazado completo original
permanece visible a distancia. Se eliminan al avanzar, llegar o detener la ruta.
La franja se oculta en mapa plano, vista general y navegación peatonal. Los colores
de tráfico y sus instrucciones conservan su fuente y prioridad.

Los edificios 3D se obtienen de las mismas teselas vectoriales de TomTom que se
guardan con toda la ruta. Se utiliza su atributo `height`; si falta, el volumen
cartográfico tiene una altura de 7 m. La transparencia permite ver el contexto de
las calles. La vista inicial no depende del proveedor adicional de edificios.
En el mapa de respaldo se usa su capa de edificios propia. Los volúmenes se
ocultan en mapa plano y vista general, y pueden desactivarse desde Ajustes.

Las avenidas cuyos estilos antiguos terminaban en zoom 18 conservan su escala al
acercarse. La geometría, accesos, restricciones y orden de puentes no se sustituyen.
Las líneas adicionales de carril sólo aparecen con un atributo `lanes` entero y
un sentido único confirmado. El mapa básico actual de TomTom no incluye ese
atributo: esta versión no ofrece la reproducción exacta de todos los carriles,
cruces peatonales y señales de la referencia. Eso requiere cartografía de ese
detalle. Los chevrones indican el recorrido, no una recomendación de carril.
Las posiciones de semáforos existentes siguen siendo reales y su fase permanece
desconocida cuando no existe información en vivo.

Las teselas, las fuentes, los estilos, el carro y la franja pueden funcionar sin
conexión con el paquete completo de 0.26. El recálculo y el guardado de sesiones
se conservan. Ver `OFFLINE_NAVIGATION.md`.

Fuentes del esquema:

- https://docs.tomtom.com/map-display-api/documentation/tomtom-maps/v1/vector/content
- https://openmaptiles.org/docs/schema/

Comprobaciones: 35 pruebas JavaScript, WebGL con carreteras vectoriales locales y
servicio real, zoom y modos de cámara, restauración Android sin wifi/datos,
tráfico, casetas, búsquedas, enlaces, Android Auto y continuidad AR.
