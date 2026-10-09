(function (root) {
    const clone = value => JSON.parse(JSON.stringify(value));
    function themedStyle(styles, provider, dark, key) {
        const source = provider === 'tomtom' ? (dark ? styles.night : styles.day) : styles.fallback;
        const style = JSON.parse(JSON.stringify(source).replaceAll('{{TOMTOM_API_KEY}}', encodeURIComponent(key || '')));
        if (provider !== 'tomtom' && dark) {
            for (const layer of style.layers) {
                const paint = layer.paint || (layer.paint = {});
                if (layer.type === 'background') paint['background-color'] = '#232b36';
                if (layer.type === 'fill') paint['fill-color'] = /water/.test(layer.id) ? '#263f58' : /park|wood|grass|landcover/.test(layer.id) ? '#283d36' : '#2b3440';
                if (layer.type === 'line') paint['line-color'] = /motorway|trunk|primary/.test(layer.id) ? '#b5a576' : '#637385';
                if (layer.type === 'symbol') { paint['text-color'] = '#e5ecf3'; paint['text-halo-color'] = '#232b36'; }
                if (layer.type === 'raster') paint['raster-opacity'] = 0;
            }
        }
        if (provider === 'tomtom') {
            // Reuse the cached icon atlas across day/night transitions.
            style.sprite = JSON.parse(JSON.stringify(styles.day.sprite).replaceAll('{{TOMTOM_API_KEY}}', encodeURIComponent(key || '')));
            if (dark) for (const layer of style.layers) if (layer.type === 'symbol') {
                layer.paint = {...layer.paint, 'text-color':'#e5ecf3', 'text-halo-color':'#1d2732', 'text-halo-width':1.2};
            }
            // Administrative boundaries are screen decorations, not road widths.
            // Imported legacy stops reached 4096 px at zoom 19 and interpolated to
            // about 1490 px at zoom 12, painting over streets while labels survived.
            for (const layer of style.layers) if (layer.type === 'line' && /border/i.test(layer['source-layer'] || '')) {
                const background = /background/i.test(layer.id);
                layer.paint = {...layer.paint, 'line-width': background
                    ? ['interpolate',['linear'],['zoom'],3,0.8,8,1.5,14,2,19.3,3]
                    : ['interpolate',['linear'],['zoom'],3,0.6,8,1,14,1.2,19.3,1.6]};
            }
            // Browser connection limits should not serialize all visible tiles onto one host.
            style.sources.vectorTiles.tiles = ['a', 'b', 'c', 'd'].map(host =>
                `https://${host}.api.tomtom.com/map/1/tile/basic/main/{z}/{x}/{y}.pbf?key=${encodeURIComponent(key || '')}`);
        }
        return style;
    }
    function preserveNavigation(base, current) {
        const style = clone(base);
        for (const [id, source] of Object.entries(current.sources || {})) if (id.startsWith('gps-')) style.sources[id] = clone(source);
        style.layers.push(...(current.layers || []).filter(layer => layer.id.startsWith('gps-')).map(clone));
        return style;
    }
    const api = {themedStyle, preserveNavigation};
    if (typeof module !== 'undefined') module.exports = api;
    root.NavigationBasemap = api;
})(typeof window === 'undefined' ? globalThis : window);
