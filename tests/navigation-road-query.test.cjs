const {test}=require('node:test'),assert=require('node:assert/strict');
const {create}=require('../app/src/main/assets/navigation-road-query');
const positions=require('../app/src/main/assets/navigation-road-position');
const {distance}=require('../app/src/main/assets/navigation-motion');
const origin=[-110,23.1],sx=111320*Math.cos(origin[1]*Math.PI/180);
const at=(x,y)=>[origin[0]+x/sx,origin[1]+y/110540];
const line=(x,direction)=>({properties:{direction},geometry:{type:'LineString',coordinates:[at(x,-100),at(x,200)]}});
test('loaded road geometry supports pitched alignment and caches queries between fixes',()=>{
 const events={},layers=[{source:'vectorTiles','source-layer':'Major road'},{source:'vectorTiles','source-layer':'Major road'},
  {source:'gps-traffic','source-layer':'Traffic flow'},{source:'vectorTiles','source-layer':'Pedestrian road'}];
 let calls=0,feed=[line(0,-1),line(20,1),line(1000,-1)];
 const query=create({on:(event,fn)=>events[event]=fn,getStyle:()=>({layers}),getSource:()=>({}),
  querySourceFeatures:(source,options)=>{calls++;assert.equal(source,'vectorTiles');assert.equal(options.sourceLayer,'Major road');return feed;}});
 const features=query.nearby(at(18,40));assert.equal(features.length,2);assert.equal(calls,1);
 const match=positions.create().match({point:at(18,40),bearing:0,speed:26,accuracy:15,headingReliable:true,now:1000},features);
 assert.ok(match&&distance(match.point,at(0,40))<.01,'direction excludes the nearer opposing carriageway');
 query.nearby(at(18,65));assert.equal(calls,1,'camera redraws do not re-query vector tiles');
 events.sourcedata({sourceId:'gps-traffic'});query.nearby(at(18,65));assert.equal(calls,1);
 feed=[line(10,-1)];events.sourcedata({sourceId:'vectorTiles'});
 assert.equal(query.nearby(at(18,65)).length,1);assert.equal(calls,2,'new road tiles invalidate the cache');
 assert.equal(query.nearby(at(1000,40)).length,0,'a distant GPS fix cannot inherit stale local roads');
});
test('style changes replace cached source definitions and fallback excludes walking and railway classes',()=>{
 const events={};let source='vectorTiles';
 const query=create({on:(event,fn)=>events[event]=fn,getSource:()=>({}),
  getStyle:()=>({layers:[{source,'source-layer':source==='vectorTiles'?'Motorway':'transportation'}]}),
  querySourceFeatures:()=>source==='vectorTiles'?[line(0,-1)]:['motorway','path','rail'].map(type=>({
   ...line(0,-1),properties:{class:type,oneway:1}}))});
 assert.equal(query.nearby(at(10,40))[0].source,'vectorTiles');
 source='openmaptiles';events['style.load']();
 const features=query.nearby(at(10,40));assert.equal(features.length,1);
 assert.equal(features[0].source,'openmaptiles');assert.equal(features[0].properties.class,'motorway');
 assert.deepEqual(query.nearby([NaN,23]),[]);
});
