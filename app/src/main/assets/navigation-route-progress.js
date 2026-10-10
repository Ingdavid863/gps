(function(root) {
    'use strict';
    function create(points) {
        const route=points.map(p=>p.slice()), origin=route[0]||[0,0];
        const sx=111320*Math.cos(origin[1]*Math.PI/180), sy=110540;
        const along=[0];
        for(let i=1;i<route.length;i++) along.push(along[i-1]+Math.hypot(
            (route[i][0]-route[i-1][0])*sx,(route[i][1]-route[i-1][1])*sy));
        const total=along[along.length-1]||0;
        function indexAt(meters) {
            let lo=0,hi=Math.max(0,route.length-2);
            while(lo<hi) {
                const mid=Math.ceil((lo+hi)/2);
                if(along[mid]<=meters)lo=mid;else hi=mid-1;
            }
            return lo;
        }
        function project(index,point) {
            if(route.length<2)return 0;
            index=Math.max(0,Math.min(route.length-2,index));
            const a=route[index],b=route[index+1],p=point||a;
            const dx=(b[0]-a[0])*sx,dy=(b[1]-a[1])*sy;
            const t=Math.max(0,Math.min(1,((p[0]-a[0])*sx*dx+(p[1]-a[1])*sy*dy)/(dx*dx+dy*dy||1)));
            return along[index]+t*(along[index+1]-along[index]);
        }
        function at(meters) {
            if(!route.length)return [0,0];
            if(route.length===1||meters>=total)return route[route.length-1].slice();
            const i=indexAt(Math.max(0,meters)),length=along[i+1]-along[i];
            const t=length?Math.max(0,Math.min(1,(meters-along[i])/length)):0;
            return route[i].map((v,j)=>v+(route[i+1][j]-v)*t);
        }
        function remaining(meters) {
            if(route.length<2||meters>=total)return [];
            const i=indexAt(Math.max(0,meters));
            return [at(meters),...route.slice(i+1)];
        }
        return {project,indexAt,at,remaining,total};
    }
    const api={create};
    if(typeof module!=='undefined')module.exports=api;else root.NavigationRouteProgress=api;
})(typeof window==='undefined'?globalThis:window);
