(function(root) {
    const R = 6371000, rad = Math.PI / 180;
    function distance(a,b) {
        return Math.hypot((b[0]-a[0])*rad*R*Math.cos((a[1]+b[1])*rad/2),(b[1]-a[1])*rad*R);
    }
    function advance(a,b,meters) {
        const d=distance(a,b), t=d>0?Math.min(1,meters/d):0;
        return [a[0]+(b[0]-a[0])*t,a[1]+(b[1]-a[1])*t];
    }
    function offset(p,east,north) {
        return [p[0]+east/(R*rad*Math.cos(p[1]*rad)),p[1]+north/(R*rad)];
    }
    /** The arrow follows the provider's actual geometry through the maneuver vertex. */
    function features(route,steps,progress,overview) {
        const out=[];
        if(!Array.isArray(route)||route.length<2) return {type:'FeatureCollection',features:out};
        for(const step of steps||[]) {
            const i=Number(step.index), kind=String(step.maneuver||'');
            if(!Number.isInteger(i)||i<1||i>=route.length-1||i<progress||kind.startsWith('DEPART')||kind.startsWith('ARRIVE')) continue;
            let ahead=0;
            for(let j=progress;j<i&&j<route.length-1;j++) ahead+=distance(route[j],route[j+1]);
            if(!overview&&ahead>1800) continue;
            let before=[], left=24;
            for(let j=i;j>0&&left>0;j--) {
                const d=distance(route[j],route[j-1]);
                before.unshift(advance(route[j],route[j-1],left)); left-=d;
            }
            const path=[...before,route[i]]; left=28;
            for(let j=i;j<route.length-1&&left>0;j++) {
                const d=distance(route[j],route[j+1]);
                path.push(advance(route[j],route[j+1],left)); left-=d;
            }
            if(path.length<3) continue;
            const tip=path[path.length-1], tail=path[path.length-2];
            const dx=(tip[0]-tail[0])*Math.cos(tip[1]*rad),dy=tip[1]-tail[1],n=Math.hypot(dx,dy);
            if(n<1e-12) continue;
            const ux=dx/n,uy=dy/n;
            const base=offset(tip,-ux*10,-uy*10);
            const triangle=[tip,offset(base,-uy*5,ux*5),offset(base,uy*5,-ux*5),tip];
            out.push({type:'Feature',properties:{kind:'shaft',index:i},geometry:{type:'LineString',coordinates:path}});
            out.push({type:'Feature',properties:{kind:'head',index:i},geometry:{type:'Polygon',coordinates:[triangle]}});
        }
        return {type:'FeatureCollection',features:out};
    }
    function severityAt(intervals,index) {
        const interval=(intervals||[]).find(x=>index>=x.start&&index<x.end);
        return interval ? interval.severity : 'normal';
    }
    const api={features,distance,severityAt};
    if(typeof module!=='undefined') module.exports=api; else root.NavigationManeuvers=api;
})(typeof window!=='undefined'?window:globalThis);
