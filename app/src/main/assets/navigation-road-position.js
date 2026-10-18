/* Display-only road alignment for driving without an active route. */
(function(root) {
    'use strict';
    const motion=typeof module!=='undefined'?require('./navigation-motion.js'):root.NavigationMotion;
    const paths=typeof module!=='undefined'?require('./navigation-route-progress.js'):root.NavigationRouteProgress;
    const rad=Math.PI/180;
    function isRoad(feature) {
        const layer=feature.sourceLayer||feature.layer?.['source-layer']||'';
        if(feature.source==='vectorTiles') return /road(?: tunnel)?$/i.test(layer) &&
            !/pedestrian|walkway|ferry|parking|non public/i.test(layer);
        if(feature.source==='openmaptiles' && layer==='transportation')
            return /^(motorway|trunk|primary|secondary|tertiary|minor|service)$/.test(feature.properties?.class||'');
        return false;
    }
    function create() {
        const cache=new Map();
        let previous=null;
        function reset() {previous=null;cache.clear();}
        function match(input,features) {
            const {point,bearing,speed,accuracy,now}=input;
            // An uncertain fix must never be disguised as a precise road position.
            if(!Array.isArray(point)||!point.every(Number.isFinite)||!Number.isFinite(bearing)||
                input.headingReliable===false||!Number.isFinite(accuracy)||accuracy<=0||accuracy>40||speed<2.5) {
                previous=null;return null;
            }
            const limit=Math.min(45,Math.max(10,accuracy*1.6+3));
            const sx=111320*Math.cos(point[1]*rad),sy=110540;
            const continuity=previous && now>=previous.now && now-previous.now<2500 ?
                previous.path.at(previous.along+speed*(now-previous.now)/1000):null;
            const candidates=[],seen=new Set();
            for(const feature of features) {
                if(!isRoad(feature)||feature.properties?.under_construction===true)continue;
                const geometry=feature.geometry||{},p=feature.properties||{};
                const lines=geometry.type==='LineString'?[geometry.coordinates]:
                    geometry.type==='MultiLineString'?geometry.coordinates:[];
                // TomTom Map Display v1: -1 = along geometry, +1 = against it.
                // OpenMapTiles uses oneway=1 for along geometry.
                const direction=feature.source==='vectorTiles' ?
                    (Number(p.direction)===-1?1:Number(p.direction)===1?-1:0):
                    (p.oneway===1||p.oneway==='1'||p.oneway===true?1:p.oneway===-1?-1:0);
                for(const line of lines) {
                    if(!Array.isArray(line)||line.length<2||line.some(p=>!Array.isArray(p)||!p.every(Number.isFinite)))continue;
                    const key=feature.source+':'+(feature.sourceLayer||feature.layer?.['source-layer'])+':'+JSON.stringify(line);
                    if(seen.has(key))continue;seen.add(key);
                    let best=null;
                    for(let i=1;i<line.length;i++) {
                        const a=line[i-1],b=line[i],dx=(b[0]-a[0])*sx,dy=(b[1]-a[1])*sy;
                        const length=Math.hypot(dx,dy);if(length<.1)continue;
                        const dot=(-(a[0]-point[0])*sx*dx-(a[1]-point[1])*sy*dy)/(length*length);
                        // Do not pull a vehicle back to an expired tile/road endpoint.
                        if(dot*length < -3 || (dot-1)*length > 3)continue;
                        const t=Math.max(0,Math.min(1,dot)),projected=motion.mix(a,b,t);
                        const distance=motion.distance(point,projected);if(distance>limit)continue;
                        const forward=(Math.atan2(dx,dy)/rad+360)%360;
                        const reverse=direction===-1 || (direction===0 &&
                            Math.abs(motion.bearingDelta(bearing,forward+180))<Math.abs(motion.bearingDelta(bearing,forward)));
                        const heading=(forward+(reverse?180:0))%360;
                        const difference=Math.abs(motion.bearingDelta(bearing,heading));
                        if(difference>40)continue;
                        const score=distance+difference*.18+(continuity?Math.min(8,motion.distance(continuity,projected)*.12):0);
                        if(!best||score<best.score)best={key,line,reverse,segment:i-1,point:projected,bearing:heading,distance,score};
                    }
                    if(best)candidates.push(best);
                }
            }
            candidates.sort((a,b)=>a.score-b.score);
            const best=candidates[0];
            if(!best){previous=null;return null;}
            // Parallel roads without usable direction/continuity remain ambiguous.
            const alternative=candidates.find(c=>motion.distance(c.point,best.point)>6);
            if(alternative && alternative.score-best.score<3 && best.distance>6) {previous=null;return null;}
            const key=best.key+':'+best.reverse;
            if(!cache.has(key)) {
                if(cache.size>=128)cache.clear();
                cache.set(key,paths.create(best.reverse?best.line.slice().reverse():best.line));
            }
            const path=cache.get(key),segment=best.reverse?best.line.length-2-best.segment:best.segment;
            const result={point:best.point,bearing:best.bearing,path,along:path.project(segment,best.point),
                distance:best.distance,now};
            previous=result;return result;
        }
        return {match,reset};
    }
    const api={create,isRoad};
    if(typeof module!=='undefined')module.exports=api;else root.NavigationRoadPosition=api;
})(typeof window==='undefined'?globalThis:window);
