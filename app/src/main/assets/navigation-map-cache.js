(function (root) {
    const protocol = 'gps3d-map';
    function cacheable(url) {
        return /^https:\/\/(?:[abcd]\.)?api\.tomtom\.com\/map\/1\/tile\/basic\/[^?#]+\.pbf(?:\?|$)/.test(url) ||
            /^https:\/\/tiles\.openfreemap\.org\/planet[^?#]*\.pbf(?:\?|$)/.test(url);
    }
    function install(engine, bridge, fetcher = root.fetch?.bind(root)) {
        let requests = 0;
        const enabled = !!(bridge && typeof bridge.prepareMapViewport === 'function' && engine.addProtocol && fetcher);
        if (enabled) engine.addProtocol(protocol, async (params, controller) => {
            const url = params.url.replace(protocol + ':', 'https:');
            if (!cacheable(url)) throw new Error('Recurso fuera del mapa guardado');
            requests++;
            // Dedicated-worker HTTP requests can bypass Android's WebViewClient.
            // A main-thread protocol routes these through SafeWebView and the native map cache.
            const response = await fetcher(url, {signal: controller.signal});
            if (!response.ok) throw new Error('Mapa no disponible (' + response.status + ')');
            return {data: await response.arrayBuffer()};
        });
        return {enabled, requestCount: () => requests,
            transformRequest(url, type) {
                return {url: enabled && type === 'Tile' && cacheable(url) ? url.replace('https:', protocol + ':') : url};
            }};
    }
    const api = {install, cacheable};
    if (typeof module !== 'undefined') module.exports = api;
    root.NavigationMapCache = api;
})(typeof window === 'undefined' ? globalThis : window);
