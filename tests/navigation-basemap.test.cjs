const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const basemap = require('../app/src/main/assets/navigation-basemap.js');
const context = {window:{}}; vm.createContext(context);
vm.runInContext(fs.readFileSync(require('node:path').join(__dirname,'../app/src/main/assets/navigation-styles.js'),'utf8'), context);
const styles = context.window.NavigationStyles;
test('offline route overscales prepared vectors and preserves all guidance on theme changes',()=>{
 const day=basemap.themedStyle(styles,'tomtom',false,'test');
 const offline=basemap.forConnection(day,false,16);
 assert.equal(offline.sources.vectorTiles.maxzoom,16);
 assert.equal(day.sources.vectorTiles.maxzoom,18,'online detail remains intact');
 assert.equal(basemap.forConnection(day,true,16).sources.vectorTiles.maxzoom,18);
 assert.equal(basemap.forConnection(day,false,null).sources.vectorTiles.maxzoom,18,'browsing cache still supports its prepared zooms');
 const night=basemap.forConnection(basemap.themedStyle(styles,'tomtom',true,'test'),false,16);
 assert.equal(night.sources.vectorTiles.maxzoom,16);
 assert.equal(offline.sprite,night.sprite);
});
test('TomTom ground roads accept absent or explicit zero levels without including bridges', () => {
 for(const dark of [false,true]) {
  const styled=basemap.themedStyle(styles,'tomtom',dark,'test');
  const ground=styled.layers.find(l=>l.id==='Major road - level0');
  assert.deepEqual(ground.filter,['all',['any',['!has','z_level'],['==','z_level',0]],['!=','under_construction',true]]);
  const raised=styled.layers.find(l=>l.id==='Major road - level+1');
  assert.deepEqual(raised.filter,['all',['==','z_level',1],['!=','under_construction',true]]);
 }
});
test('full night style removes daytime land cover and keeps route and traffic data', () => {
 const day = basemap.themedStyle(styles,'tomtom',false,'test');
 const night = basemap.themedStyle(styles,'tomtom',true,'test');
 assert.equal(day.sprite,night.sprite,'theme switches reuse the cached icon atlas');
 assert.ok(night.layers.filter(l=>l.type==='symbol').every(l=>l.paint['text-color']==='#e5ecf3'),'night labels have sufficient contrast');
 assert.ok(day.layers.some(l=>l.id==='Earth Cover 0-4'));
 const data={type:'Feature',geometry:{type:'LineString',coordinates:[[-99.13,19.43],[-99.14,19.44]]}};
 const current={sources:{...day.sources,'gps-route-active':{type:'geojson',data}}, layers:[...day.layers,{id:'gps-route-active-layer',type:'line',source:'gps-route-active'}]};
 const changed=basemap.preserveNavigation(night,current);
 assert.ok(!changed.layers.some(l=>l.id==='Earth Cover 0-4'),'daytime fills must disappear at night');
 assert.ok(changed.layers.some(l=>l.id==='Woodland'),'night layers must actually be added');
 assert.deepEqual(changed.sources['gps-route-active'].data,data);
 assert.equal(changed.sources.vectorTiles.tiles.length,4);
 assert.equal(changed.layers.at(-1).id,'gps-route-active-layer');
 const restored=basemap.preserveNavigation(day,changed);
 assert.ok(restored.layers.some(l=>l.id==='Earth Cover 0-4'));
 assert.ok(!restored.layers.some(l=>l.id==='Woodland'));
 assert.deepEqual(restored.sources['gps-route-active'].data,data);
});
