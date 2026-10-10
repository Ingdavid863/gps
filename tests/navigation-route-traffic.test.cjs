const test=require('node:test'),assert=require('node:assert/strict');
const {create}=require('../app/src/main/assets/navigation-route-traffic.js');
const {COLORS,band,create:overlay,SOURCE,LAYERS,DATA_LAYER}=require('../app/src/main/assets/navigation-traffic.js');
const point=(east,north)=>[-99+east/(111320*Math.cos(19*Math.PI/180)),19+north/110540];
const flow=(a,b,level)=>({type:'Feature',properties:{traffic_level:level,traffic_road_coverage:'one_side'},
 geometry:{type:'LineString',coordinates:[point(...a),point(...b)]}});
test('opposing traffic and crossing streets do not contaminate the route colors',()=>{
 const route=[point(0,0),point(0,100)],matcher=create(route);
 const feed=[flow([0,100],[0,0],.1),flow([-50,50],[50,50],.1),flow([0,0],[0,100],1)];
 const result=matcher.match(feed);
 assert.ok(result.features.length);assert.ok(result.features.every(f=>f.properties.severity==='normal'));
 assert.ok(result.features.every(f=>f.properties.color===COLORS.normal));
 const reverse=create([...route].reverse()).match(feed);
 assert.ok(reverse.features.every(f=>f.properties.severity==='stopped'));
});
test('missing coverage stays unknown and cannot inherit a sample hundreds of meters away',()=>{
 const matcher=create([point(0,0),point(0,1000)]),feed=[flow([0,0],[0,80],.3),flow([0,600],[0,1000],1)];
 const result=matcher.match(feed);
 assert.ok(result.features.some(f=>f.properties.severity==='heavy'));
 assert.ok(result.features.some(f=>f.properties.severity==='normal'));
 assert.ok(result.features.every(f=>f.properties.endAlong<=80.1||f.properties.startAlong>=599.9));
 assert.equal(band({traffic_level:null}),'unknown');assert.equal(band({traffic_level:3}),'unknown');
 assert.equal(matcher.match([flow([20,0],[20,1000],.2)]).features.length,0);
});
test('colors change at measured source boundaries and clip to continuous progress',()=>{
 const matcher=create([point(0,0),point(0,96)]),feed=[flow([0,0],[0,48],.2),flow([0,48],[0,96],.7)];
 const result=matcher.match([...feed,...feed],0,point(0,32));
 assert.equal(result.features.length,2);assert.ok(Math.abs(result.features[0].properties.startAlong-32)<.001);
 assert.ok(Math.abs(result.features[0].properties.endAlong-48)<.001);
 assert.equal(result.features[1].properties.color,COLORS.moderate);
});
test('route mode hides streets while keeping the identical directional source loaded',()=>{
 const sources={},layers={},events={},features=[flow([0,0],[0,100],.2)];let refreshes=0;
 const map={getSource:id=>sources[id],addSource:(id,s)=>sources[id]=s,getLayer:id=>layers[id],
  getStyle:()=>({layers:[]}),addLayer:l=>layers[l.id]=l,moveLayer(){},
  setLayoutProperty(id,k,v){layers[id].layout[k]=v},queryRenderedFeatures:()=>features,
  querySourceFeatures:(id,options)=>{assert.equal(id,SOURCE);assert.equal(options.sourceLayer,'Traffic flow');return features},
  isSourceLoaded:()=>true,getZoom:()=>16,refreshTiles(){refreshes++},on:(n,fn)=>events[n]=fn,off(){}};
 const traffic=overlay(map,{apiKey:'fixture'});traffic.install();traffic.setRouteActive(true);
 for(const id of LAYERS)assert.equal(layers[id].layout.visibility,'none');
 assert.equal(layers[DATA_LAYER].layout.visibility,'visible');assert.deepEqual(traffic.sourceFeatures(),features);
 assert.equal(traffic.state().rendered,0);assert.equal(traffic.state().areaVisible,false);
 traffic.refresh();assert.equal(refreshes,1);
 traffic.setOnline(false);assert.deepEqual(traffic.sourceFeatures(),[]);assert.equal(layers[DATA_LAYER].layout.visibility,'none');
 traffic.setOnline(true);traffic.setRouteActive(false);
 for(const id of LAYERS)assert.equal(layers[id].layout.visibility,'visible');assert.equal(traffic.state().status,'live');
});
