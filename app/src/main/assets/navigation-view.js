(function (root) {
    function padding(width, height, topFraction, bottomFraction, overview) {
        const top = Math.max(0, Math.min(height * 0.45, topFraction * height));
        const bottom = Math.max(0, Math.min(height * 0.45, bottomFraction * height));
        if (overview) return { top: top + 20, bottom: bottom + 30, left: 30, right: Math.min(82, width * 0.22) };
        // Place the geographical camera target 48 CSS px above the actual native bottom panel.
        const anchor = Math.max(top + 36, height - bottom - 48);
        const paddedTop = Math.max(0, Math.min(height - bottom - 28, 2 * anchor - height + bottom));
        return { top: paddedTop, bottom, left: 0, right: 0 };
    }
    function overviewPoints(route, location, destination) {
        if (Array.isArray(route) && route.length > 1) return route;
        return [location, destination].filter(p => Array.isArray(p) && p.length >= 2 && p.every(Number.isFinite));
    }
    const api = { padding, overviewPoints };
    if (typeof module !== 'undefined') module.exports = api;
    else root.NavigationView = api;
})(typeof window !== 'undefined' ? window : globalThis);
