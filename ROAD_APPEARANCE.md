# GPS3D 0.25 road presentation

Driving now starts with the perspective camera (mode 1, 65 degrees), a closer view,
and a green car. The eye cycles driving, full route, 2D, and driving. Recenter
returns to driving. The car is a local polygon model projected into an SVG;
its ground center is the existing geographic marker anchor. It rotates relative
to the camera, including when the map is panned or rotated manually. It does not
change the GNSS fix, road matcher, route progress, camera spring, or prediction.

Road styling uses the existing TomTom v1 or OpenMapTiles geometry, including
TomTom Motorway and Toll motorway classes that were previously omitted. Asphalt,
shoulders and close-range center dividers retain the provider's layer filters
and drawing order, including tunnels, bridges, construction and pedestrian
exclusions. Extra lane counts are not inferred for one-way roads. Markings and
widths are cartographic approximations; they are not a surveyed lane-level map.
Live traffic retains its source and severity colors above the route ribbon.
All assets run locally; there is no extra imagery/model request per GPS fix.

Legacy ground-road filters now accept both an absent `z_level` and an explicit
zero. The old absent-only filter hid close-range roads when tiles supplied zero;
the fix keeps the existing separate filters for bridges, tunnels and construction.

The supplied Facebook ad is a visual reference, not a geographic dataset.
This build does not download Google Maps/Street View photographs or claim to
reconstruct every avenue from them. Street View's official API returns static
panoramas and requires a configured Google Cloud project, key and billing:
https://developers.google.com/maps/documentation/streetview/overview
The current TomTom v1 road schema documents direction and vertical level but
does not supply surveyed lane count or road width:
https://docs.tomtom.com/map-display-api/documentation/tomtom-maps/v1/vector/content

Validation covers the local car projection, provider styles and bridge order,
both camera views and full-route locking, real WebGL geographic anchors,
opposing carriageway matching, and the Android navigation regression suite.
Physical GNSS performance still needs an on-device road trial.
