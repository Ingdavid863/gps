/* Time-based navigation motion. No position is extrapolated beyond a fresh-fix horizon. */
(function (root) {
    const mix = (a, b, t) => a.map((v, i) => v + (b[i] - v) * t);
    const bearingDelta = (a,b) => ((b-a)%360+540)%360-180;
    const bearingMix = (a, b, t) => a + bearingDelta(a,b)*t;
    // Exact critically damped integration retains velocity when a new GNSS fix arrives.
    // Unlike restarting a short easing on every fix, motion continues on every frame.
    function spring(value,target,velocity,seconds,frequency) {
        const change=value-target, decay=Math.exp(-frequency*seconds);
        const acceleration=velocity+frequency*change;
        return {value:target+(change+acceleration*seconds)*decay,
            velocity:(velocity-frequency*acceleration*seconds)*decay};
    }
    function createTracker() {
        let initialized=false,point=[0,0],bearing=0,bearingVelocity=0;
        let positionVelocity=[0,0],alongState={value:0,velocity:0};
        let anchor=[0,0],anchorAlong=0,targetBearing=0,speed=0,path=null;
        let fixAt=0,frameAt=0,cadence=1000;
        function sample(now) {
            if(!initialized)return null;
            const age=Math.max(0,now-fixAt);
            const horizon=Math.max(600,Math.min(1200,cadence*1.15));
            const meters=Math.min(30,speed*Math.min(age,horizon)/1000);
            const dt=Math.max(0,Math.min(.08,(now-frameAt)/1000));
            frameAt=now;
            if(path) {
                alongState=spring(alongState.value,Math.min(path.total,anchorAlong+meters),alongState.velocity,dt,35);
                alongState.value=Math.max(0,Math.min(path.total,alongState.value));
                point=path.at(alongState.value);
            } else {
                const predicted=advance(anchor,meters,targetBearing,[],0);
                point=point.map((v,i)=>{
                    const next=spring(v,predicted[i],positionVelocity[i],dt,35);
                    positionVelocity[i]=next.velocity;return next.value;
                });
            }
            const rotation=spring(bearing,bearing+bearingDelta(bearing,targetBearing),bearingVelocity,dt,14);
            bearing=rotation.value;bearingVelocity=rotation.velocity;
            return {point:point.slice(),bearing,along:path?alongState.value:null,path};
        }
        function fix(input) {
            const now=input.now, nextPoint=input.point.slice(),nextPath=input.path||null;
            if(initialized)sample(now);
            const reset=!initialized||distance(point,nextPoint)>150;
            if(initialized&&now>fixAt&&now-fixAt<=2000)cadence=now-fixAt;
            if(reset) {
                point=nextPoint.slice();bearing=Number(input.bearing)||0;
                bearingVelocity=0;positionVelocity=[0,0];frameAt=now;
            }
            if(nextPath && (reset||path!==nextPath)) {
                const projected=nextPath.project(nextPath.indexAt(input.along),point);
                alongState={value:distance(point,nextPath.at(projected))<=12?projected:input.along,velocity:0};
            }
            if(!nextPath&&path)positionVelocity=[0,0];
            path=nextPath;anchor=nextPoint;anchorAlong=Number(input.along)||0;
            targetBearing=Number(input.bearing)||0;speed=Math.max(0,Number(input.speed)||0);
            fixAt=now;initialized=true;
            return sample(now);
        }
        return {fix,sample};
    }
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
    const api = { mix, bearingMix, bearingDelta, spring, createTracker, distance, advance, installLongPress };
    if (typeof module !== 'undefined') module.exports = api;
    else root.NavigationMotion = api;
})(typeof window !== 'undefined' ? window : globalThis);

