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
