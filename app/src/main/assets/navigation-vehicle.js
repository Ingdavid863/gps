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
        for (const x of [-1.03,.76]) for (const y of [-1.2,1.4]) {
            // Rounded tire profiles instead of rectangular blocks.
            const ring=Array.from({length:12},(_,i)=>{
                const a=i*Math.PI/6;return [y+Math.cos(a)*.38,.43+Math.sin(a)*.35];
            });
            for(let i=0;i<12;i++){
                const a=ring[i],b=ring[(i+1)%12];
                faces.push({points:[[x,...a],[x+.27,...a],[x+.27,...b],[x,...b]],color:i<6?'#273438':'#10191c'});
            }
            for(const side of [x,x+.27])faces.push({points:ring.map(p=>[side,...p]),color:'#172428'});
        }
        const outline=[[-.68,-2.13],[.68,-2.13],[.92,-1.76],[.92,1.62],[.66,2.13],[-.66,2.13],[-.92,1.62],[-.92,-1.76]];
        const lower=outline.map(([x,y])=>[x,y,.34]),upper=outline.map(([x,y])=>[x*.95,y*.98,.72]);
        for(let i=0;i<outline.length;i++)faces.push({points:[lower[i],lower[(i+1)%8],upper[(i+1)%8],upper[i]],
            color:['#a8bac9','#e8eff4','#b4c3cf','#f8fbff','#e7eff7','#cedce7','#a9bbc9','#dbe6ef'][i]});
        faces.push({points:upper,color:'#eff6fc'});
        // Sloping cabin, front windshield toward +Y; rear glass toward the camera.
        const low=[[-.8,-1.1,.73],[.8,-1.1,.73],[.77,1.2,.73],[-.77,1.2,.73]];
        const high=[[-.64,-.65,1.36],[.64,-.65,1.36],[.62,.55,1.36],[-.62,.55,1.36]];
        [[0,1,'#173b43'],[1,2,'#123b41'],[2,3,'#365d65'],[3,0,'#34666b']].forEach(([a,b,color])=>
            faces.push({points:[low[a],low[b],high[b],high[a]],color}));
        faces.push({points:high,color:'#152a3c'});
        // White roof rails and a small rear spoiler frame the panoramic glass.
        for(const x of [-.67,.57])box(x,-.67,1.36,x+.1,.59,1.4,['#f3f8fd']);
        box(-.67,-.76,1.32,.67,-.62,1.4,['#edf5fc']);
        box(-.84,-2.18,.46,.84,-2.12,.67,['#acbfce']);
        for (const x of [-.81,.39]) box(x,-2.195,.54,x+.42,-2.18,.66,['#ff4e59']);
        box(-.32,-2.2,.43,.32,-2.19,.53,['#e3e6d3']);
        for (const x of [-.82,.45]) box(x,2.13,.54,x+.37,2.15,.65,['#ebf7ed']);
        // Light strips are visible in the flat map as well as from behind.
        for(const x of [-.65,.31]) {
            box(x,-2.08,.72,x+.34,-1.98,.74,['#ff515e']);
            box(x,1.93,.72,x+.34,2.03,.74,['#e6fff4']);
        }
        for(const x of [-1.05,.82])box(x,.35,.76,x+.23,.64,.87,['#c9dae7','#96aebe','#deebf4','#f0f7fc','#fcfeff']);
        box(-.69,-2.22,.25,.69,-2.14,.36,['#142a27']);
        return faces;
    }
    function projector(pitch, yaw) {
        const p=pitch*Math.PI/180, a=yaw*Math.PI/180;
        const ca=Math.cos(a),sa=Math.sin(a),cp=Math.cos(p),sp=Math.sin(p);
        return point=>{
            const x=point[0]*ca+point[1]*sa,y=point[1]*ca-point[0]*sa;
            return {x:40+x*16,y:48-(y*cp+point[2]*sp)*16,depth:y*sp-point[2]*cp};
        };
    }
    const project=(point,pitch,yaw)=>projector(pitch,yaw)(point);
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
        shadow.setAttribute('cx','40');shadow.setAttribute('cy','50');shadow.setAttribute('rx','20');
        shadow.setAttribute('fill','#081c22');shadow.setAttribute('opacity','.34');
        let lastPitch=NaN,lastYaw=NaN;
        function update(pitch,yaw) {
            if(Math.abs(pitch-lastPitch)<.4 && Math.abs(yaw-lastYaw)<.4)return;
            lastPitch=pitch;lastYaw=yaw;
            shadow.setAttribute('ry',String(5+20*Math.cos(pitch*Math.PI/180)));
            const projectPoint=projector(pitch,yaw);
            const polygons=faces.map(face=>({...face, projected:face.points.map(projectPoint)}));
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
