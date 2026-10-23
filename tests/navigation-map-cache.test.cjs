const test=require('node:test'),assert=require('node:assert/strict');
const cache=require('../app/src/main/assets/navigation-map-cache.js');
const tile='https://c.api.tomtom.com/map/1/tile/basic/main/16/14712/29126.pbf?key=test';
test('native vector requests return to the main thread and preserve cancellation and bytes',async()=>{
 let handler,loaded;const bytes=new Uint8Array([1,2,3]).buffer;
 const engine={addProtocol(name,fn){assert.equal(name,'gps3d-map');handler=fn;}};
 const installed=cache.install(engine,{prepareMapViewport(){}},async(url,options)=>{
  loaded={url,options};return {ok:true,arrayBuffer:async()=>bytes};
 });
 const request=installed.transformRequest(tile,'Tile');assert.match(request.url,/^gps3d-map:/);
 const controller=new AbortController(),result=await handler(request,controller);
 assert.equal(loaded.url,tile);assert.equal(loaded.options.signal,controller.signal);
 assert.equal(result.data,bytes);assert.equal(installed.requestCount(),1);
});
test('traffic, search and unrelated servers keep their existing network path',()=>{
 const installed=cache.install({addProtocol(){}},{prepareMapViewport(){}},async()=>{});
 for(const url of ['https://api.tomtom.com/traffic/map/4/tile/flow/16/1/1.pbf?key=test',
  'https://api.tomtom.com/search/2/geocode/private.json','https://example.com/map/1/tile/basic/16/1/1.pbf'])
  assert.equal(installed.transformRequest(url,'Tile').url,url);
 assert.equal(installed.transformRequest(tile,'Glyphs').url,tile);
 assert.equal(cache.install({addProtocol(){throw Error('browser should remain unchanged');}},null).transformRequest(tile,'Tile').url,tile);
});
test('a missing prepared tile rejects the request instead of reporting a blank success',async()=>{
 let handler;cache.install({addProtocol(name,fn){handler=fn;}},{prepareMapViewport(){}},async()=>({ok:false,status:503}));
 await assert.rejects(handler({url:tile.replace('https:','gps3d-map:')},new AbortController()),/503/);
});
