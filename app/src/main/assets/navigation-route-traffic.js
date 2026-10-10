(function(root) {
    'use strict';
    const traffic = typeof module !== 'undefined' ? require('./navigation-traffic.js') : root.NavigationTraffic;
    const rad=Math.PI/180, CELL=50, MAX_DISTANCE=12;
    const lerp=(a,b,t)=>[a[0]+(b[0]-a[0])*t,a[1]+(b[1]-a[1])*t];
    function create(route) {
        const origin=route[0]||[0,0], sx=111320*Math.cos(origin[1]*rad), sy=110540;
        const xy=p=>[(p[0]-origin[0])*sx,(p[1]-origin[1])*sy];
        const meters=(a,b)=>Math.hypot((b[0]-a[0])*sx,(b[1]-a[1])*sy);
        const along=[0];
        for(let i=1;i<route.length;i++)along.push(along[i-1]+meters(route[i-1],route[i]));
        function progress(index,point) {
            if(route.length<2)return 0;
            index=Math.max(0,Math.min(route.length-2,index));
            const a=xy(route[index]),b=xy(route[index+1]),p=xy(point||route[index]);
            const dx=b[0]-a[0],dy=b[1]-a[1];
            const t=Math.max(0,Math.min(1,((p[0]-a[0])*dx+(p[1]-a[1])*dy)/(dx*dx+dy*dy||1)));
            return along[index]+t*(along[index+1]-along[index]);
        }
        function match(sourceFeatures, startIndex=0, startPoint=null) {
            const grid=new Map(),records=[],seen=new Set();
            for(const feature of sourceFeatures) {
                const severity=traffic.band(feature.properties||{});
                if(severity==='unknown')continue;
                const geometry=feature.geometry||{};
                const lines=geometry.type==='LineString'?[geometry.coordinates]:
                    geometry.type==='MultiLineString'?geometry.coordinates:[];
                for(const line of lines)for(let i=1;i<line.length;i++) {
                    const a=xy(line[i-1]),b=xy(line[i]),dx=b[0]-a[0],dy=b[1]-a[1],length=Math.hypot(dx,dy);
                    if(length<.1)continue;
                    const id=[...a,...b,severity].map(v=>typeof v==='number'?v.toFixed(2):v).join(':');
                    if(seen.has(id))continue;seen.add(id);
                    const record={a,b,dx,dy,length,severity},index=records.push(record)-1;
                    // Some source features extend beyond the tile boundary. Bound work for long roads.
                    const count=Math.ceil(length/(CELL/2));
                    for(let j=0;j<=count;j++) {
                        const x=Math.floor((a[0]+dx*j/count)/CELL),y=Math.floor((a[1]+dy*j/count)/CELL);
                        const cell=x+':'+y;if(!grid.has(cell))grid.set(cell,new Set());grid.get(cell).add(index);
                    }
                }
            }
            const minimum=progress(startIndex,startPoint),features=[];
            for(let i=Math.max(0,startIndex);i<route.length-1;i++) {
                const a=xy(route[i]),b=xy(route[i+1]),dx=b[0]-a[0],dy=b[1]-a[1],length=Math.hypot(dx,dy);
                if(length<.1||along[i+1]<=minimum)continue;
                const count=Math.max(1,Math.ceil(length/8));
                let current=null;
                for(let j=0;j<count;j++) {
                    const begin=Math.max(j/count,(minimum-along[i])/length),end=(j+1)/count;
                    if(begin>=end)continue;
                    const fraction=(begin+end)/2,p=[a[0]+dx*fraction,a[1]+dy*fraction];
                    const cellX=Math.floor(p[0]/CELL),cellY=Math.floor(p[1]/CELL),candidates=new Set();
                    for(let x=cellX-1;x<=cellX+1;x++)for(let y=cellY-1;y<=cellY+1;y++)
                        for(const index of grid.get(x+':'+y)||[])candidates.add(index);
                    let best=null,bestDistance=MAX_DISTANCE;
                    for(const index of candidates) {
                        const record=records[index];
                        // Opposite travel directions share road coordinates in TomTom tiles.
                        // Never transfer congestion from the opposing lane or a crossing street.
                        if((dx*record.dx+dy*record.dy)/(length*record.length)<Math.cos(40*rad))continue;
                        const t=Math.max(0,Math.min(1,((p[0]-record.a[0])*record.dx+
                            (p[1]-record.a[1])*record.dy)/(record.length*record.length)));
                        const d=Math.hypot(p[0]-record.a[0]-record.dx*t,p[1]-record.a[1]-record.dy*t);
                        // Do not stretch traffic beyond a source segment's end.
                        const dot=((p[0]-record.a[0])*record.dx+(p[1]-record.a[1])*record.dy)/record.length;
                        if(dot<-.5||dot>record.length+.5)continue;
                        if(d<bestDistance){bestDistance=d;best=record;}
                    }
                    if(!best){current=null;continue;}
                    const beginAlong=along[i]+begin*length,endAlong=along[i]+end*length;
                    if(current&&current.properties.severity===best.severity&&
                        Math.abs(current.properties.endAlong-beginAlong)<.01) {
                        current.geometry.coordinates.push(lerp(route[i],route[i+1],end));
                        current.properties.endAlong=endAlong;
                    } else {
                        current={type:'Feature',properties:{severity:best.severity,color:traffic.COLORS[best.severity],
                            index:i,startAlong:beginAlong,endAlong},geometry:{type:'LineString',
                            coordinates:[lerp(route[i],route[i+1],begin),lerp(route[i],route[i+1],end)]}};
                        features.push(current);
                    }
                }
            }
            return {type:'FeatureCollection',features};
        }
        return {match,progress};
    }
    const api={create};
    if(typeof module!=='undefined')module.exports=api;else root.NavigationRouteTraffic=api;
})(typeof window==='undefined'?globalThis:window);
