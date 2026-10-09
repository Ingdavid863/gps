const test = require('node:test'), assert = require('node:assert/strict');
const {create, SOURCE, LAYERS, band} = require('../app/src/main/assets/navigation-traffic.js');
function mapMock() {
 const sources={},layers=[{id:'road',type:'line'},{id:'street-name',type:'symbol',layout:{'text-field':'name'}},{id:'gps-route-active-layer',type:'line'}],events={};
 return {sources,layers,features:[],loaded:false,refreshes:0,
  getSource:id=>sources[id],addSource:(id,s)=>sources[id]=s,
  getLayer:id=>layers.find(l=>l.id===id),getStyle:()=>({layers}),
  addLayer(l,before){layers.splice(before?layers.findIndex(x=>x.id===before):layers.length,0,l)},
  moveLayer(id,before){const i=layers.findIndex(l=>l.id===id),[l]=layers.splice(i,1);layers.splice(layers.findIndex(x=>x.id===before),0,l)},
  setLayoutProperty(id,key,value){const l=this.getLayer(id);l.layout={...l.layout,[key]:value}},
  queryRenderedFeatures(){return this.features},isSourceLoaded(){return this.loaded},getZoom:()=>16,
  refreshTiles(){this.refreshes++},on(name,fn){(events[name]||=[]).push(fn)},off(name,fn){events[name]=events[name].filter(x=>x!==fn)},
  fire(name,event){for(const f of events[name]||[])f(event)}};
}
test('traffic is available with no route and never labels missing coverage as free flow',()=>{
 const map=mapMock(),overlay=create(map,{apiKey:'test',online:true});overlay.install();
 assert.ok(map.getSource(SOURCE));assert.equal(overlay.state().status,'loading');
 map.loaded=true;assert.equal(overlay.state().status,'no-coverage');
 map.features=[{properties:{traffic_level:.2}},{properties:{traffic_level:1}}];
 assert.equal(overlay.state().status,'live');assert.deepEqual(overlay.state().bands,{heavy:1,normal:1});
 assert.equal(band({}),'unknown');assert.equal(band({road_closure:true}),'closed');
});
test('offline and disabled traffic hide all old colors and reconnect requests fresh tiles',()=>{
 const map=mapMock(),overlay=create(map,{apiKey:'test'});overlay.install();
 map.features=[{properties:{traffic_level:.5}}];overlay.setOnline(false);
 assert.equal(overlay.state().status,'offline');assert.equal(overlay.state().rendered,0);
 for(const id of LAYERS)assert.equal(map.getLayer(id).layout.visibility,'none');
 overlay.setOnline(true);assert.equal(map.refreshes,1);
 overlay.setEnabled(false);overlay.refresh();assert.equal(map.refreshes,1);
 overlay.setEnabled(true);assert.equal(map.refreshes,2);
});
test('night-style reinstall retains traffic below labels and routes and reports service failure',()=>{
 const map=mapMock(),states=[],overlay=create(map,{apiKey:'test',onState:s=>states.push(s)});overlay.install();
 const nav=map.layers.filter(l=>LAYERS.includes(l.id));map.layers.splice(map.layers.findIndex(l=>LAYERS.includes(l.id)),nav.length);map.layers.push(...nav);
 overlay.install();
 const name=map.layers.findIndex(l=>l.id==='street-name'),route=map.layers.findIndex(l=>l.id==='gps-route-active-layer');
 for(const id of LAYERS){const i=map.layers.findIndex(l=>l.id===id);assert.ok(i<name&&i<route)}
 map.fire('error',{sourceId:SOURCE});assert.equal(states.at(-1).status,'unavailable');
 map.features=[{properties:{traffic_level:.9}}];map.fire('sourcedata',{sourceId:SOURCE,sourceDataType:'content'});
 assert.equal(states.at(-1).status,'live');assert.ok(states.at(-1).updatedAt>0);
});
