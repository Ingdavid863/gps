(function (root) {
    // A small local mesh projected into SVG. No model download or extra WebGL context.
    // The marker's ground center remains the same geographic point as the GPS tracker.
    const NS = 'http://www.w3.org/2000/svg';
    function mesh() {
        const faces = [];
        function box(x0,y0,z0,x1,y1,z1,colors) {
            const p=[[x0,y0,z0],[x1,y0,z0],[x1,y1,z0],[x0,y1,z0],
                [x0,y0,z1],[x1,y0,z1],[x1,y1,z1],[x0,y1,z1]];
            [[0,1,5,4],[1,2,6,5],[2,3,7,6],[3,0,4,7],[4,5,6,7]].forEach((v,i)=>
                faces.push({points:v.map(j=>p[j]),color:colors[i] || colors[0]}));
        }
        for (const x of [-1.03,.76]) for (const y of [-1.55,1.1])
            box(x,y,.12,x+.27,y+.73,.65,['#101b20','#202b2e','#0d171a','#1e292d','#354346']);
        box(-.92,-2.13,.35,.92,2.13,.72,['#12834b','#0c7744','#31c879','#179e57','#35d377']);
        // Sloping cabin, front windshield toward +Y; rear glass toward the camera.
        const low=[[-.8,-1.1,.73],[.8,-1.1,.73],[.77,1.2,.73],[-.77,1.2,.73]];
        const high=[[-.64,-.65,1.36],[.64,-.65,1.36],[.62,.55,1.36],[-.62,.55,1.36]];
        [[0,1,'#173b43'],[1,2,'#123b41'],[2,3,'#365d65'],[3,0,'#34666b']].forEach(([a,b,color])=>
            faces.push({points:[low[a],low[b],high[b],high[a]],color}));
        faces.push({points:high,color:'#48dc87'});
        box(-.84,-2.18,.46,.84,-2.12,.67,['#125f3b']);
        for (const x of [-.81,.39]) box(x,-2.195,.54,x+.42,-2.18,.66,['#ff4e59']);
        box(-.32,-2.2,.43,.32,-2.19,.53,['#e3e6d3']);
        for (const x of [-.82,.45]) box(x,2.13,.54,x+.37,2.15,.65,['#ebf7ed']);
        box(-.69,-2.22,.25,.69,-2.14,.36,['#142a27']);
        return faces;
    }
    function project(point, pitch, yaw) {
        const p=pitch*Math.PI/180, a=yaw*Math.PI/180;
        const x=point[0]*Math.cos(a)+point[1]*Math.sin(a);
        const y=point[1]*Math.cos(a)-point[0]*Math.sin(a);
        return {x:40+x*12,y:48-(y*Math.cos(p)+point[2]*Math.sin(p))*12,
            depth:y*Math.sin(p)-point[2]*Math.cos(p)};
    }
    function create(element) {
        element.setAttribute('role','img');element.setAttribute('aria-label','Tu vehículo');
        const svg=document.createElementNS(NS,'svg');
        svg.setAttribute('viewBox','0 0 80 96');svg.setAttribute('class','vehicle-car');
        svg.setAttribute('aria-hidden','true');
        element.appendChild(svg);
        const faces=mesh().map(face=>{
            const node=document.createElementNS(NS,'polygon');
            node.setAttribute('fill',face.color);node.setAttribute('stroke',face.color);
            node.setAttribute('stroke-width','.35');return {...face,node};
        });
        const shadow=document.createElementNS(NS,'ellipse');
        shadow.setAttribute('cx','40');shadow.setAttribute('cy','50');shadow.setAttribute('rx','15');
        shadow.setAttribute('fill','#081c22');shadow.setAttribute('opacity','.34');
        let lastPitch=NaN,lastYaw=NaN;
        function update(pitch,yaw) {
            if(Math.abs(pitch-lastPitch)<.4 && Math.abs(yaw-lastYaw)<.4)return;
            lastPitch=pitch;lastYaw=yaw;
            shadow.setAttribute('ry',String(5+20*Math.cos(pitch*Math.PI/180)));
            const polygons=faces.map(face=>({...face, projected:face.points.map(p=>project(p,pitch,yaw))}));
            polygons.sort((a,b)=>b.projected.reduce((s,p)=>s+p.depth,0)/b.projected.length-
                a.projected.reduce((s,p)=>s+p.depth,0)/a.projected.length);
            const nodes=polygons.map(face=>{
                const polygon=face.node;
                polygon.setAttribute('points',face.projected.map(p=>p.x.toFixed(2)+','+p.y.toFixed(2)).join(' '));
                return polygon;
            });
            svg.replaceChildren(shadow,...nodes);
        }
        update(0,0);
        return {update};
    }
    const api={create,mesh,project};
    if(typeof module!=='undefined')module.exports=api;
    root.NavigationVehicle=api;
})(typeof window==='undefined'?globalThis:window);
