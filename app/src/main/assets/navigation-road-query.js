(function(root) {
    'use strict';
    const roads=typeof module!=='undefined'?require('./navigation-road-position.js'):root.NavigationRoadPosition;
    const motion=typeof module!=='undefined'?require('./navigation-motion.js'):root.NavigationMotion;
    const sources=['vectorTiles','openmaptiles'];
    function box(point,meters) {
        const sx=111320*Math.max(.01,Math.abs(Math.cos(point[1]*Math.PI/180)));
        return [point[0]-meters/sx,point[1]-meters/110540,point[0]+meters/sx,point[1]+meters/110540];
    }
    const intersects=(a,b)=>a[0]<=b[2]&&a[2]>=b[0]&&a[1]<=b[3]&&a[3]>=b[1];
    function bounds(geometry) {
        const lines=geometry?.type==='LineString'?[geometry.coordinates]:
            geometry?.type==='MultiLineString'?geometry.coordinates:[];
        const result=[Infinity,Infinity,-Infinity,-Infinity];
        for(const line of lines)for(const p of line) {
            if(!Array.isArray(p)||p.length<2||!p.every(Number.isFinite))return null;
            result[0]=Math.min(result[0],p[0]);result[1]=Math.min(result[1],p[1]);
            result[2]=Math.max(result[2],p[0]);result[3]=Math.max(result[3],p[1]);
        }
        return Number.isFinite(result[0])?result:null;
    }
    function create(map) {
        let dirty=true,definitions=null,center=null,cache=[];
        map.on('style.load',()=>{dirty=true;definitions=null;});
        map.on('sourcedata',event=>{if(sources.includes(event.sourceId))dirty=true;});
        function nearby(point) {
            if(!Array.isArray(point)||point.length<2||!point.every(Number.isFinite))return [];
            if(!center||motion.distance(center,point)>120)dirty=true;
            if(dirty) {
                if(!definitions) {
                    const unique=new Map();
                    for(const layer of map.getStyle()?.layers||[]) {
                        const source=layer.source,sourceLayer=layer['source-layer'];
                        if(!sources.includes(source)||!sourceLayer)continue;
                        if(source==='vectorTiles'&&!roads.isRoad({source,sourceLayer}))continue;
                        if(source==='openmaptiles'&&sourceLayer!=='transportation')continue;
                        unique.set(source+':'+sourceLayer,{source,sourceLayer});
                    }
                    definitions=[...unique.values()];
                }
                const coverage=box(point,300),next=[];
                for(const {source,sourceLayer} of definitions) {
                    if(!map.getSource(source))continue;
                    let features=[];
                    try{features=map.querySourceFeatures(source,{sourceLayer});}catch(_){continue;}
                    for(const feature of features) {
                        const normalized={source,sourceLayer,properties:feature.properties||{},geometry:feature.geometry};
                        if(!roads.isRoad(normalized))continue;
                        const extent=bounds(normalized.geometry);
                        if(extent&&intersects(extent,coverage))next.push({feature:normalized,extent});
                    }
                }
                cache=next;center=point.slice();dirty=false;
            }
            const query=box(point,60);
            return cache.filter(record=>intersects(record.extent,query)).map(record=>record.feature);
        }
        return {nearby};
    }
    const api={create};
    if(typeof module!=='undefined')module.exports=api;else root.NavigationRoadQuery=api;
})(typeof window==='undefined'?globalThis:window);
