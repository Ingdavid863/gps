(function(root) {
    'use strict';
    const rad=Math.PI/180, empty=()=>({type:'FeatureCollection',features:[]});
    const wrap=x=>((x+180)%360+360)%360-180;
    function routeFloor(route,walking=false) {
        if(walking||!Array.isArray(route)||route.length<2)return empty();
        const origin=route[0];
        if(!Array.isArray(origin)||origin.length<2||!origin.every(Number.isFinite))return empty();
        const sx=111320*Math.max(.01,Math.cos(origin[1]*rad)),sy=110540;
        const xy=[],along=[];
        for(const p of route) {
            if(!Array.isArray(p)||p.length<2||!p.every(Number.isFinite)||Math.abs(p[1])>85||Math.abs(p[0])>180||
                Math.abs(p[0]-origin[0])>180)return empty();
            const next=[wrap(p[0]-origin[0])*sx,(p[1]-origin[1])*sy];
            const previous=xy.at(-1),distance=previous?Math.hypot(next[0]-previous[0],next[1]-previous[1]):0;
            if(previous&&distance<.05)continue;
            const total=(along.at(-1)||0)+distance;
            if(previous&&total>900) {
                const t=(900-along.at(-1))/distance;
                xy.push(previous.map((v,i)=>v+(next[i]-v)*t));along.push(900);break;
            }
            xy.push(next);along.push(total);
            if(xy.length>=320)break;
        }
        if(xy.length<2)return empty();
        const geo=p=>[wrap(origin[0]+p[0]/sx),origin[1]+p[1]/sy];
        const normal=(a,b)=>{const dx=b[0]-a[0],dy=b[1]-a[1],d=Math.hypot(dx,dy);return [-dy/d,dx/d];};
        const normals=xy.slice(1).map((p,i)=>normal(xy[i],p));
        function edge(halfWidth,side) {
            return xy.map((p,i)=>{
                const a=normals[Math.max(0,i-1)],b=normals[Math.min(i,normals.length-1)];
                const sum=[a[0]+b[0],a[1]+b[1]],length=Math.hypot(...sum);
                const n=length>.1?sum.map(v=>v/length):b;
                const miter=Math.min(halfWidth*1.8,halfWidth/Math.max(.55,n[0]*b[0]+n[1]*b[1]));
                return geo([p[0]+side*n[0]*miter,p[1]+side*n[1]*miter]);
            });
        }
        function band(halfWidth,kind) {
            const ring=[...edge(halfWidth,1),...edge(halfWidth,-1).reverse()];ring.push(ring[0]);
            return {type:'Feature',properties:{kind},geometry:{type:'Polygon',coordinates:[ring]}};
        }
        const features=[band(2.6,'glow'),band(1.25,'ribbon')];
        // These chevrons follow the chosen route, not an inferred traffic lane.
        // Their coordinates live on the ground and remain correct when pitched.
        for(let distance=26;distance<Math.min(along.at(-1)-8,650);distance+=65) {
            const i=along.findIndex(a=>a>=distance),a=xy[i-1],b=xy[i],length=along[i]-along[i-1];
            if(length<.1)continue;
            const t=(distance-along[i-1])/length,p=a.map((v,j)=>v+(b[j]-v)*t);
            const forward=[(b[0]-a[0])/length,(b[1]-a[1])/length],n=normals[i-1];
            const local=[[-.85,-1.25],[0,-.25],[.85,-1.25],[.85,-.25],[0,.95],[-.85,-.25]];
            const ring=local.map(([x,y])=>geo([p[0]+n[0]*x+forward[0]*y,p[1]+n[1]*x+forward[1]*y]));
            ring.push(ring[0]);features.push({type:'Feature',properties:{kind:'chevron'},geometry:{type:'Polygon',coordinates:[ring]}});
        }
        return {type:'FeatureCollection',features};
    }
    const api={routeFloor};
    if(typeof module!=='undefined')module.exports=api;else root.NavigationDrivingScene=api;
})(typeof window==='undefined'?globalThis:window);
