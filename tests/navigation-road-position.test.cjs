const {test}=require('node:test');
const assert=require('node:assert/strict');
const {create,isRoad}=require('../app/src/main/assets/navigation-road-position.js');
const {distance,createTracker}=require('../app/src/main/assets/navigation-motion.js');
const origin=[-110.0,23.1],sx=111320*Math.cos(origin[1]*Math.PI/180);
const at=(x,y)=>[origin[0]+x/sx,origin[1]+y/110540];
const road=(points,properties={},source='vectorTiles')=>({source,sourceLayer:source==='vectorTiles'?'Major road':'transportation',
    properties,geometry:{type:'LineString',coordinates:points.map(p=>at(...p))}});
const fix=(x,y,extra={})=>({point:at(x,y),bearing:0,speed:26,accuracy:15,headingReliable:true,now:1000,...extra});

test('no-route highway fixes align to the actual road and interpolate along its bend',()=>{
    const matcher=create(),features=[road([[0,-100],[0,100],[100,200]],{direction:-1})];
    const matched=matcher.match(fix(18,80),features);
    assert.ok(matched&&distance(matched.point,at(0,80))<.01);
    assert.ok(Math.abs(matched.distance-18)<.01);
    const tracker=createTracker();tracker.fix({...matched,speed:26,now:1000});
    let last;
    for(let now=1016;now<=2100;now+=16) {
        last=tracker.sample(now);
        assert.ok(distance(last.point,matched.path.at(last.along))<.01,'prediction stays on road geometry');
    }
    assert.ok(last.point[0]>origin[0],'prediction follows the curve instead of cutting through terrain');
});
test('TomTom v1 direction selects the correct carriageway even when the opposing lane is nearer',()=>{
    const features=[road([[0,-100],[0,200]],{direction:-1}),road([[20,-100],[20,200]],{direction:1})];
    const match=create().match(fix(16,40),features);
    assert.ok(match&&distance(match.point,at(0,40))<.01);
    const south=create().match(fix(4,40,{bearing:180}),features);
    assert.ok(south&&distance(south.point,at(20,40))<.01);
    assert.equal(south.bearing,180);
});
test('TomTom motorway, toll motorway and tunnel classes retain directional road alignment',()=>{
    for(const sourceLayer of ['Motorway','Toll motorway','Motorway tunnel','Toll motorway tunnel']) {
        const north={...road([[0,-100],[0,200]],{direction:-1}),sourceLayer};
        const south={...road([[20,-100],[20,200]],{direction:1}),sourceLayer};
        const match=create().match(fix(16,40),[north,south]);
        assert.ok(match&&distance(match.point,at(0,40))<.01,sourceLayer);
        assert.equal(create().match(fix(16,40,{accuracy:70}),[north,south]),null);
    }
});
test('crossing streets, traffic offsets and pedestrian geometry cannot pull a driving marker away',()=>{
    const main=road([[0,-100],[0,200]],{direction:-1});
    const crossing=road([[-100,40],[100,40]],{direction:-1});
    const traffic={...road([[16,-100],[16,200]]),source:'gps-area-traffic',sourceLayer:'Traffic flow'};
    const footpath={...road([[17,-100],[17,200]]),sourceLayer:'Walkway road'};
    const match=create().match(fix(18,40),[crossing,traffic,footpath,main]);
    assert.ok(match&&distance(match.point,at(0,40))<.01);
});
test('ambiguous parallel roads and imprecise or unreliable GNSS keep the true fix unmodified',()=>{
    const features=[road([[0,-100],[0,200]]),road([[20,-100],[20,200]])];
    assert.equal(create().match(fix(10,40),features),null);
    for(const extra of [{accuracy:65},{accuracy:0},{accuracy:NaN},{headingReliable:false},{speed:1}])
        assert.equal(create().match(fix(10,40,extra),features),null);
    assert.equal(create().match(fix(100,40),features),null,'no forced snapping from a distant road');
    assert.equal(create().match(fix(10,40),[]),null,'no road data is a normal GPS fallback');
});
test('duplicate tile/layer features are not mistaken for competing roads and path identity is stable',()=>{
    const matcher=create(),main=road([[0,-100],[0,200]],{direction:-1});
    const first=matcher.match(fix(18,40),[main,JSON.parse(JSON.stringify(main))]);
    const second=matcher.match(fix(14,66,{now:2000}),[main]);
    assert.ok(first&&second);assert.equal(first.path,second.path);
    assert.ok(second.along>first.along);
    assert.equal(matcher.match(fix(18,240,{now:3000}),[main]),null,'do not pin to the end of a loaded road');
});
test('OpenMapTiles fallback accepts drivable classes and respects its own one-way convention',()=>{
    const main=road([[0,-100],[0,200]],{class:'trunk',oneway:1},'openmaptiles');
    assert.ok(isRoad(main));assert.ok(create().match(fix(15,40),[main]));
    assert.equal(create().match(fix(15,40,{bearing:180}),[main]),null);
    assert.equal(isRoad({...main,properties:{class:'path'}}),false);
});
