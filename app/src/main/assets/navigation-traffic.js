(function (root) {
    'use strict';
    const SOURCE = 'gps-area-traffic';
    const LAYERS = ['gps-area-traffic-outline', 'gps-area-traffic-lines'];
    const DATA_LAYER = 'gps-traffic-data';
    const REFRESH_MS = 60000;
    const COLORS = {closed:'#9b1c24', stopped:'#8c1d26', heavy:'#e53935',
        slow:'#ff9234', moderate:'#f8c83d', normal:'#28b56e', unknown:'#087cf0'};
    const ratio = ['coalesce', ['get', 'traffic_level'], 1];
    const valid = ['any', ['has', 'traffic_level'], ['==', ['get', 'road_closure'], true]];
    const width = ['interpolate', ['linear'], ['zoom'], 5, 0.7, 10, 2, 14, 3.5, 16, 5.5, 18, 8, 19.3, 9.5];
    // The geometry follows each travel direction. Draw one_side records on the
    // driving side so opposite directions retain their own congestion colors.
    const side = ['case', ['==', ['get', 'traffic_road_coverage'], 'one_side'],
        ['case', ['==', ['get', 'left_hand_traffic'], true], -1, 1], 0];
    const offset = ['interpolate', ['linear'], ['zoom'],
        5, ['*', side, 0.55], 10, ['*', side, 1.3], 14, ['*', side, 2.25],
        16, ['*', side, 3.25], 18, ['*', side, 4.5], 19.3, ['*', side, 5.25]];
    const colors = ['case', ['==', ['get', 'road_closure'], true], '#9b1c24',
        ['<', ratio, 0.15], '#8c1d26', ['<', ratio, 0.35], '#e53935',
        ['<', ratio, 0.58], '#ff9234', ['<', ratio, 0.80], '#f8c83d', '#28b56e'];

    function band(properties) {
        if (properties.road_closure === true) return 'closed';
        if (properties.traffic_level == null || properties.traffic_level === '') return 'unknown';
        const value = Number(properties.traffic_level);
        if (!Number.isFinite(value) || value < 0 || value > 1) return 'unknown';
        return value < .15 ? 'stopped' : value < .35 ? 'heavy' : value < .58 ? 'slow' : value < .8 ? 'moderate' : 'normal';
    }

    function create(map, options) {
        const key = String(options.apiKey || '').trim();
        let enabled = options.enabled !== false, online = options.online !== false, routeActive = false;
        let updatedAt = 0, failed = false, destroyed = false;
        const notify = options.onState || (() => {});
        const sourceSpec = {type: 'vector', minzoom: 0, maxzoom: 22,
            tiles: ['a', 'b', 'c', 'd'].map(host =>
                `https://${host}.api.tomtom.com/traffic/map/4/tile/flow/relative/{z}/{x}/{y}.pbf?key=${encodeURIComponent(key)}`)};

        function state() {
            const visible = enabled && online && !!key;
            const features = visible && !routeActive && map.getLayer(LAYERS[1])
                ? map.queryRenderedFeatures({layers: [LAYERS[1]]}) : [];
            const bands = {};
            for (const feature of features) {
                const name = band(feature.properties || {});
                bands[name] = (bands[name] || 0) + 1;
            }
            const loaded = !!map.getSource(SOURCE) && map.isSourceLoaded(SOURCE);
            return {enabled, online, loaded, rendered: features.length, bands, updatedAt, routeActive,
                areaVisible:visible && !routeActive,
                status: !enabled ? 'disabled' : !online ? 'offline' : !key ? 'unavailable'
                    : failed ? 'unavailable' : routeActive ? 'route' : features.length > 0 ? 'live'
                    : loaded ? (map.getZoom() < 5 ? 'zoom' : 'no-coverage') : 'loading'};
        }
        function publish() { if (!destroyed) notify(state()); }
        function visibility() {
            const show = enabled && online && !!key && !routeActive;
            for (const id of LAYERS) if (map.getLayer(id))
                map.setLayoutProperty(id, 'visibility', show ? 'visible' : 'none');
            // Keep the same tiles loaded while their street colors are hidden.
            // Source queries, unlike rendered queries, retain travel direction and geometry.
            if (map.getLayer(DATA_LAYER)) map.setLayoutProperty(DATA_LAYER, 'visibility',
                enabled && online && !!key ? 'visible' : 'none');
        }
        function install() {
            if (destroyed || !key) { publish(); return; }
            if (!map.getSource(SOURCE)) map.addSource(SOURCE, sourceSpec);
            // Road arrows occur among road geometry. Insert above all roads but
            // below text labels, rather than before the first symbol layer.
            const before = (map.getStyle().layers || []).find(layer =>
                layer.type === 'symbol' && layer.layout && layer.layout['text-field'] && !layer.id.startsWith('gps-'))?.id;
            const shared = {type: 'line', source: SOURCE, 'source-layer': 'Traffic flow', minzoom: 5,
                filter: valid, layout: {'line-cap': 'round', 'line-join': 'round'}};
            if (!map.getLayer(DATA_LAYER)) map.addLayer({...shared, layout:{...shared.layout}, id:DATA_LAYER,
                paint:{'line-opacity':0,'line-width':1}}, before);
            if (!map.getLayer(LAYERS[0])) map.addLayer({...shared, layout:{...shared.layout}, id: LAYERS[0],
                paint: {'line-color': '#173629', 'line-opacity': .6,
                    'line-width': ['interpolate', ['linear'], ['zoom'], 5, 1.3, 10, 3.2, 14, 5, 16, 7, 18, 9.6, 19.3, 11.1],
                    'line-offset': offset}}, before);
            if (!map.getLayer(LAYERS[1])) map.addLayer({...shared, layout:{...shared.layout}, id: LAYERS[1],
                paint: {'line-color': colors, 'line-opacity': 1, 'line-width': width, 'line-offset': offset}}, before);
            if (before) for (const id of LAYERS) map.moveLayer(id, before);
            visibility(); publish();
        }
        function refresh() {
            if (destroyed || !enabled || !online || !key) return;
            failed = false;
            if (map.getSource(SOURCE) && map.refreshTiles) map.refreshTiles(SOURCE);
            publish();
        }
        function sourceData(event) {
            if (event.sourceId !== SOURCE) return;
            if (event.sourceDataType === 'content') { updatedAt = Date.now(); failed = false; }
            publish();
        }
        function error(event) {
            if (event.sourceId !== SOURCE) return;
            failed = true; publish();
        }
        map.on('sourcedata', sourceData); map.on('error', error); map.on('moveend', publish);
        return {install, refresh, state,
            sourceFeatures() {
                if (!enabled || !online || !key || failed || !map.getSource(SOURCE)) return [];
                return map.querySourceFeatures(SOURCE, {sourceLayer:'Traffic flow'});
            },
            setRouteActive(value) { routeActive=!!value; visibility(); publish(); },
            setEnabled(value) { enabled = !!value; visibility(); if (enabled) refresh(); else publish(); },
            setOnline(value) {
                const resumed = !online && !!value;
                online = !!value; visibility(); if (resumed) refresh(); else publish();
            },
            destroy() {
                destroyed = true;
                map.off('sourcedata', sourceData); map.off('error', error); map.off('moveend', publish);
            }};
    }
    const api = {create, band, COLORS, SOURCE, LAYERS, DATA_LAYER, REFRESH_MS};
    if (typeof module !== 'undefined') module.exports = api;
    root.NavigationTraffic = api;
})(typeof window === 'undefined' ? globalThis : window);
