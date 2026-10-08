/* Time-based navigation motion. No position is extrapolated beyond a fresh-fix horizon. */
(function (root) {
    const mix = (a, b, t) => a.map((v, i) => v + (b[i] - v) * t);
    const bearingMix = (a, b, t) => a + (((b - a + 540) % 360) - 180) * t;
    function distance(a, b) {
        const y = (b[1] - a[1]) * 110540;
        const x = (b[0] - a[0]) * 111320 * Math.cos(a[1] * Math.PI / 180);
        return Math.hypot(x, y);
    }
    function advance(point, meters, bearing, route, index) {
        if (route.length > 1) {
            const start = Math.max(0, Math.min(route.length - 2, index));
            const a = route[start], b = route[start + 1];
            const scale = Math.cos(point[1] * Math.PI / 180);
            const dx = (b[0] - a[0]) * scale, dy = b[1] - a[1];
            const t = Math.max(0, Math.min(1, (((point[0] - a[0]) * scale * dx) + (point[1] - a[1]) * dy) / (dx * dx + dy * dy || 1)));
            let cursor = mix(a, b, t);
            // Only project a vehicle already close to its active route.
            if (distance(cursor, point) <= 12) {
                for (let i = start + 1; i < route.length; i++) {
                    const length = distance(cursor, route[i]);
                    if (meters <= length) return mix(cursor, route[i], length ? meters / length : 0);
                    meters -= length;
                    cursor = route[i];
                }
                return cursor.slice();
            }
        }
        const r = bearing * Math.PI / 180;
        return [point[0] + Math.sin(r) * meters / (111320 * Math.max(0.2, Math.cos(point[1] * Math.PI / 180))), point[1] + Math.cos(r) * meters / 110540];
    }
    function installLongPress(element, select, interacted) {
        let hold = null;
        function cancel() { if (hold) clearTimeout(hold.timer); hold = null; }
        element.addEventListener('pointerdown', e => {
            const hadHold = !!hold;
            cancel();
            interacted();
            if (hadHold || !e.isPrimary || (e.pointerType === 'mouse' && e.button !== 0)) return;
            const rect = element.getBoundingClientRect();
            hold = { id: e.pointerId, x: e.clientX, y: e.clientY };
            hold.timer = setTimeout(() => {
                if (!hold) return;
                const point = [hold.x - rect.left, hold.y - rect.top];
                hold = null;
                select(point);
            }, 1000);
        });
        element.addEventListener('pointermove', e => {
            if (hold && (e.pointerId !== hold.id || Math.hypot(e.clientX - hold.x, e.clientY - hold.y) > 10)) cancel();
        });
        ['pointerup', 'pointercancel', 'pointerleave', 'lostpointercapture'].forEach(type => element.addEventListener(type, cancel));
        element.addEventListener('contextmenu', e => { e.preventDefault(); });
        return cancel;
    }
    const api = { mix, bearingMix, distance, advance, installLongPress };
    if (typeof module !== 'undefined') module.exports = api;
    else root.NavigationMotion = api;
})(typeof window !== 'undefined' ? window : globalThis);
