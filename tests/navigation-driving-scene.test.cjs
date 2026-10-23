const test=require('node:test'),assert=require('node:assert/strict');
const {routeFloor}=require('../app/src/main/assets/navigation-driving-scene');
const {create}=require('../app/src/main/assets/navigation-route-progress');
test('ground ribbon starts at remaining progress and never restores passed geometry',()=>{
    const path=create([[0,0],[0,.003],[.01,.003]]),remaining=path.remaining(390);
    const scene=routeFloor(remaining),band=scene.features.find(f=>f.properties.kind==='ribbon');
    assert.ok(scene.features.some(f=>f.properties.kind==='chevron'));
    for(const [lon,lat] of band.geometry.coordinates[0]){
        assert.ok(lon>=remaining[0][0]-.00003,'no trail behind the car');assert.ok(Math.abs(lat-.003)<.00003);
    }
    assert.deepEqual(routeFloor(path.remaining(path.total)).features,[]);
});
test('ribbon is bounded to the foreground, retains metre widths and does not mutate route points',()=>{
    const route=[[0,0],[0,1]],before=JSON.stringify(route),scene=routeFloor(route);
    assert.equal(JSON.stringify(route),before);
    for(const feature of scene.features)for(const p of feature.geometry.coordinates[0]){
        assert.ok(p.every(Number.isFinite));assert.ok(p[1]*110540<=900.001);
        assert.ok(Math.abs(p[0]*111320)<=2.601);
    }
    assert.ok(scene.features.length<=12,'bounded per-frame rendering work');
    const band=scene.features[1].geometry.coordinates[0];
    assert.ok(Math.abs(Math.abs(band[0][0]-band.at(-2)[0])*111320-2.5)<.001);
});
test('walking, missing geometry, invalid points and date-line crossings keep the original guidance alone',()=>{
    for(const route of [[],[[0,0]],[null,[0,0]],[[0,0],[NaN,1]],[[0,0],[0,89]],[[179.999,0],[-179.999,0]]])
        assert.deepEqual(routeFloor(route).features,[]);
    assert.deepEqual(routeFloor([[0,0],[0,.01]],true).features,[]);
    for(const route of [[[0,0],[0,0],[0,.01]],[[0,0],[0,.001],[0,0]],[[0,0],[0,.001],[.0001,.0001]]])
        assert.ok(routeFloor(route).features.every(f=>f.geometry.coordinates[0].every(p=>p.every(Number.isFinite))));
});
