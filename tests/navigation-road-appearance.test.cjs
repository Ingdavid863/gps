const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const appearance=require('../app/src/main/assets/navigation-road-appearance');
const vehicle=require('../app/src/main/assets/navigation-vehicle');
const context={window:{}};vm.createContext(context);
vm.runInContext(fs.readFileSync(require('node:path').join(__dirname,'../app/src/main/assets/navigation-styles.js'),'utf8'),context);
test('road presentation preserves provider geometry, access filters and bridge drawing order in both providers',()=>{
    for(const name of ['day','night','fallback']) {
        const original=JSON.parse(JSON.stringify(context.window.NavigationStyles[name]));
        const unchanged=JSON.stringify(original),styled=appearance.decorate(JSON.parse(unchanged),name==='night');
        assert.deepEqual(styled.sources,original.sources,'no fabricated or new road sources');
        const detail=styled.layers.filter(l=>l.id.startsWith('road-detail-'));
        assert.ok(detail.length>0);assert.ok(detail.length<180,'bounded layer count');
        assert.equal(new Set(styled.layers.map(l=>l.id)).size,styled.layers.length);
        assert.deepEqual(styled.layers.filter(l=>!l.id.startsWith('road-detail-')).map(l=>l.id),original.layers.map(l=>l.id));
        for(const layer of original.layers) {
            const changed=styled.layers.find(l=>l.id===layer.id);
            assert.deepEqual(changed.filter,layer.filter,'preserve road level and access conditions');
            if(/motorway/i.test(layer['source-layer']||'') && layer.type==='line' &&
                !/outline|casing| t$|under construction/i.test(layer.id))
                assert.equal(changed.paint['line-color'],name==='night'?'#424e5a':'#505961','motorways use the same asphalt treatment');
            if(/under construction|rail|pedestrian|ferry/i.test(layer.id))assert.deepEqual(changed,layer);
        }
        for(const layer of detail){
            assert.ok(layer.minzoom>=17,'distant and overview maps avoid dense markings');
            const id=layer.id.replace(/^road-detail-(edge|divider)-/,'');
            const road=original.layers.find(l=>l.id===id);
            assert.equal(layer['source-layer'],road['source-layer']);
            assert.deepEqual(layer.filter,road.filter);
            const index=styled.layers.findIndex(l=>l.id===id);
            assert.ok(styled.layers.indexOf(layer)<=index+2,'details stay with their road, below later bridge levels');
        }
    }
});
test('local car projection keeps ground position fixed as camera and bearing change',()=>{
    assert.ok(vehicle.mesh().length>30);
    for(const pitch of [0,45,65])for(const yaw of [0,90,180,359]) {
        const center=vehicle.project([0,0,0],pitch,yaw);
        assert.equal(center.x,40);assert.equal(center.y,48);
        for(const face of vehicle.mesh())for(const p of face.points){
            const projected=vehicle.project(p,pitch,yaw);
            assert.ok(Number.isFinite(projected.x)&&Number.isFinite(projected.y));
            assert.ok(projected.x>0&&projected.x<80&&projected.y>0&&projected.y<96);
        }
    }
    assert.ok(vehicle.project([0,1,0],65,0).y<48,'car nose points forward');
    assert.ok(vehicle.project([0,1,0],65,90).x>40,'nose turns right with eastbound heading');
});
