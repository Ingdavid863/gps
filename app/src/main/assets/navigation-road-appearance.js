(function (root) {
    // Presentation only: keep provider geometry, access restrictions and bridge ordering.
    // These are cartographic road markings, not surveyed lane-level driving guidance.
    const clone = value => JSON.parse(JSON.stringify(value));
    function roadLayer(layer) {
        if (layer.type !== 'line') return false;
        const source = layer['source-layer'] || '';
        if (/(?:road|motorway)(?: tunnel)?$/i.test(source)) return !/ferry|walkway|pedestrian|non public/i.test(source);
        return source === 'transportation' && /^(road|bridge|tunnel)_/.test(layer.id) &&
            !/rail|path|pedestrian|service_track/.test(layer.id);
    }
    function widthAt(value, zoom) {
        if (typeof value === 'number') return value;
        let stops, base = 1, step = false;
        if (value && value.stops) { stops = value.stops; base = value.base || 1; }
        else if (Array.isArray(value) && value[0] === 'interpolate') {
            stops = []; base = value[1][0] === 'exponential' ? value[1][1] : 1;
            for (let i = 3; i < value.length; i += 2) stops.push([value[i], value[i + 1]]);
        } else if (Array.isArray(value) && value[0] === 'step') {
            stops = [[0, value[2]]]; step = true;
            for (let i = 3; i < value.length; i += 2) stops.push([value[i], value[i + 1]]);
        }
        if (!stops || stops.some(s => typeof s[1] !== 'number')) return null;
        if (zoom <= stops[0][0]) return stops[0][1];
        for (let i = 1; i < stops.length; i++) if (zoom < stops[i][0]) {
            const [a, x] = stops[i - 1], [b, y] = stops[i];
            const t = base === 1 ? (zoom - a) / (b - a) : (base ** (zoom - a) - 1) / (base ** (b - a) - 1);
            return step ? x : x + (y - x) * t;
        }
        // Legacy non-toll roads often stop at zoom 18, while the matching toll
        // geometry continues doubling its width. Keep the close view at the same
        // ground scale instead of freezing ordinary avenues into thin map strokes.
        const last = stops.at(-1);
        return last[1] * (last[0] >= 16 ? 2 ** Math.max(0, zoom - last[0]) : 1);
    }
    function width(value, extra = 0, fraction = 1) {
        const expression = ['interpolate', ['exponential', 1.3], ['zoom']];
        const originalStops = value?.stops?.map(s => s[0]) ||
            (Array.isArray(value) ? value.filter((v, i) => i >= 3 && i % 2 === 1 && typeof v === 'number') : []);
        const stops = [...new Set([...originalStops, 3, 12, 15, 16, 17, 18, 19.3, 22])].sort((a, b) => a - b);
        for (const zoom of stops) {
            const w = widthAt(value, zoom);
            if (w === null) return value;
            const scale = zoom <= 16 ? 1 : Math.min(1.65, 1 + (zoom - 16) * .26);
            const pixels = Math.max(0, w * scale + extra);
            expression.push(zoom, typeof fraction === 'number' ? pixels * fraction : ['*', pixels, fraction]);
        }
        return expression;
    }
    function decorate(style, dark) {
        const layers = [];
        for (const original of style.layers) {
            const layer = clone(original);
            layers.push(layer);
            // Footprints and height come from the vector tiles already saved with
            // the route. No second building provider is needed in the driving view.
            if (layer.type === 'fill' && !/shadow/i.test(layer.id) &&
                /^(?:Other|School|Factory|Hospital|Hotel) building$/.test(layer['source-layer'] || '')) {
                layers.push({id:'scene-building-' + layer.id, type:'fill-extrusion', source:layer.source,
                    'source-layer':layer['source-layer'], minzoom:16,
                    ...(layer.filter ? {filter:clone(layer.filter)} : {}),
                    paint:{'fill-extrusion-color':dark ? '#43546c' : '#b8cbd4',
                        'fill-extrusion-height':['max',0,['min',160,['to-number',['get','height'],7]]],
                        'fill-extrusion-base':0, 'fill-extrusion-opacity':.66,
                        'fill-extrusion-vertical-gradient':true}});
            }
            if (!roadLayer(layer) || /under construction/i.test(layer.id)) continue;
            const casing = /outline|casing| t$/.test(layer.id), paint = layer.paint || (layer.paint = {});
            paint['line-color'] = casing ? (dark ? '#71808a' : '#b8b7ad') : (dark ? '#424e5a' : '#505961');
            paint['line-width'] = width(original.paint?.['line-width'] ?? 1);
            if (casing || /link|minor/.test(layer.id.toLowerCase())) continue;
            // Detail appears only close to the road; the overview stays legible.
            const base = {type:'line', source:layer.source, 'source-layer':layer['source-layer'],
                minzoom:Math.max(17, layer.minzoom || 0), layout:{'line-cap':'butt','line-join':'round'},
                ...(layer.maxzoom !== undefined ? {maxzoom:layer.maxzoom} : {}),
                ...(layer.filter ? {filter:clone(layer.filter)} : {})};
            layers.push({...base, id:'road-detail-edge-' + layer.id, paint:{
                'line-color': dark ? '#c7d1d5' : '#f1efe1',
                'line-opacity':['interpolate',['linear'],['zoom'],17,0,18,.72,19.3,.88],
                'line-width':['interpolate',['linear'],['zoom'],17,.5,19.3,1.3],
                'line-gap-width':width(original.paint?.['line-width'] ?? 1, -3)
            }});
            // A center divider expresses two-way circulation. Do not infer extra lanes
            // on a one-way carriageway when the tile has no surveyed lane count.
            const oneWay = layer['source-layer'] === 'transportation'
                ? ['in',['to-string',['coalesce',['get','oneway'],0]],['literal',['1','-1','yes','true']]]
                : ['has','direction'];
            layers.push({...base, id:'road-detail-divider-' + layer.id, paint:{
                'line-color':dark ? '#e5c875' : '#efd277',
                'line-opacity':['case',oneWay,0,.8],
                'line-width':['interpolate',['linear'],['zoom'],17,.5,19.3,1.1],
                'line-dasharray':[7,6]
            }});
            // Surveyed lane counts are optional. Never infer three/four lanes from
            // a road class, and never turn these markings into a lane recommendation.
            // Add them only at ground level, leaving bridge/tunnel ordering intact.
            if (/- level|tunnel|bridge/.test(layer.id)) continue;
            const count=['to-number',['get','lanes'],0];
            const known=['all',oneWay,['>=',count,2],['<=',count,6],['==',['%',count,1],0]];
            const ink=condition=>['case',['all',known,condition],.82,0];
            layers.push({...base,id:'road-detail-lane-center-'+layer.id,paint:{
                'line-color':'#f0f5f5','line-width':1.05,'line-dasharray':[7,6],
                'line-opacity':ink(['==',['%',count,2],0])}});
            for(let i=0;i<2;i++) {
                const numerator=['case',['==',['%',count,2],0],2*(i+1),2*i+1];
                const fraction=['/',numerator,['max',count,1]];
                layers.push({...base,id:'road-detail-lane-pair-'+i+'-'+layer.id,paint:{
                    'line-color':'#f0f5f5','line-width':1.05,'line-dasharray':[7,6],
                    'line-gap-width':width(original.paint?.['line-width'] ?? 1,-3,fraction),
                    'line-opacity':ink(['<',numerator,count])}});
            }
        }
        style.layers = layers;
        return style;
    }
    const api = {decorate, roadLayer, widthAt};
    if (typeof module !== 'undefined') module.exports = api;
    root.NavigationRoadAppearance = api;
})(typeof window === 'undefined' ? globalThis : window);
