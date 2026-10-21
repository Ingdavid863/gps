// Exercise the real vector service with CI credentials. Diagnostics never include URLs/keys.
const http=require('node:http'),fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {chromium}=require(process.env.GPS3D_QA_NODE_MODULES+'/playwright');
const {PNG}=require(process.env.GPS3D_QA_NODE_MODULES+'/pngjs');
const key=process.env.TOMTOM_API_KEY;
assert.ok(key,'Map service credential configured for rendering QA');
const output=path.resolve(__dirname,'../build/map-qa');fs.mkdirSync(output,{recursive:true});
let browser,server,page,phase='startup';
const measurements=[],statuses={};
(async()=>{
 const base=path.resolve(__dirname,'../app/src/main/assets');
 server=http.createServer((req,res)=>{
  if(req.url==='/traffic-config.js'){res.setHeader('Content-Type','text/javascript');res.end('window.GPS3D_CONFIG='+JSON.stringify({tomtomApiKey:key})+';');return;}
  const file=path.join(base,req.url.split('?')[0]);
  try{res.setHeader('Content-Type',file.endsWith('.html')?'text/html':file.endsWith('.css')?'text/css':'text/javascript');res.end(fs.readFileSync(file));}catch{res.statusCode=404;res.end();}
 });
 await new Promise(r=>server.listen(0,'127.0.0.1',r));
 browser=await chromium.launch({headless:true,args:['--no-sandbox','--enable-webgl','--use-gl=angle','--use-angle=swiftshader','--enable-unsafe-swiftshader']});
 const context=await browser.newContext({viewport:{width:390,height:840}});
 page=await context.newPage();const errors=[],preparedResources=new Map();
 page.on('pageerror',e=>errors.push(e.message.replaceAll(key,'[credential]').replace(/https?:\/\/\S+/g,'[resource]')));
 page.on('response',r=>{if(/\/map\/1\/tile\/basic\//.test(r.url()))statuses[r.status()]=(statuses[r.status()]||0)+1;});
 // Optional 3D/traffic services should not influence the basemap regression.
 await page.route(/^https:\/\//,async r=>{
  const u=new URL(r.request().url());
  if(u.hostname.endsWith('api.tomtom.com')&&(/^\/map\//.test(u.pathname)||/^\/style\//.test(u.pathname))){
   // Model the Android HTTP map cache. Route interception disables Chromium's own
   // HTTP cache, so continuing requests would falsely test only a partial GPU cache.
   if(/^\/map\//.test(u.pathname))u.hostname='a.api.tomtom.com';
   const id=u.toString(),cached=preparedResources.get(id);
   if(cached){await r.fulfill(cached);return;}
   try{
    const response=await r.fetch();
    const headers={...response.headers(),'access-control-allow-origin':'*'};
    delete headers['content-encoding'];delete headers['content-length'];
    const resource={status:response.status(),headers,body:await response.body()};
    if(response.ok())preparedResources.set(id,resource);
    await r.fulfill(resource);
   }catch{await r.abort();}
  }else await r.abort();
 });
 await page.addInitScript(()=>{window.AndroidBridge={onMapReady(){window.mapReady=true}}});
 await page.goto('http://127.0.0.1:'+server.address().port+'/map3d.html');
 await page.waitForFunction(()=>window.mapReady===true);
 await page.evaluate(()=>window.GPS3D.setTrafficEnabled(false));
 let drivingRoad=null;
 for(const zoom of [17.4,16,14,12,10,6,3,18.65]){
  phase='zoom-'+zoom;
  await page.evaluate(z=>{const g=window.GPS3D;g.setLocation(-99.133209,19.432608,0,0);g.follow(-99.133209,19.432608,0,z,0,1)},zoom);
  await page.waitForFunction(z=>{const s=window.GPS3D.mapState();return Math.abs(s.zoom-z)<.02&&s.tilesLoaded&&s.rendered>0},zoom,{timeout:30000});
  const state=await page.evaluate(()=>window.GPS3D.mapState());
  assert.equal(state.provider,'tomtom','primary service must render the actual map');
  assert.equal(state.failures.vectorTiles||0,0,'no missing vector tiles while zooming');
  measurements.push({zoom,rendered:state.rendered,roads:state.roads,areas:state.areas});
  if(zoom===16)drivingRoad=await page.evaluate(()=>GPS3D.mapState(true).roadGeometry);
  if(zoom===14||zoom===3)await page.screenshot({path:path.join(output,`day-zoom-${zoom}.png`)});
 }
 phase='driving';
 // The default reference coordinate lies in a pedestrian plaza. Use an actual
 // rendered drivable line for the behind-car check instead of inventing a road there.
 const road=drivingRoad;
 assert.ok(road&&road.length>1,'a real drivable feature is required for the driving fixture');
 const segment=Math.floor((road.length-2)/2),a=road[segment],b=road[segment+1];
 const drivePoint=[(a[0]+b[0])/2,(a[1]+b[1])/2];
 const driveBearing=Math.atan2((b[0]-a[0])*Math.cos(drivePoint[1]*Math.PI/180),b[1]-a[1])*180/Math.PI;
 await page.evaluate(({point,bearing})=>{GPS3D.setViewMode(1);GPS3D.setLocation(...point,bearing,0);
  GPS3D.follow(...point,bearing,18.65,65,1)}, {point:drivePoint,bearing:driveBearing});
 await page.waitForFunction(()=>{const s=GPS3D.mapState(true);return GPS3D.cameraState().pitch>64&&s.tilesLoaded&&s.roadData>0},null,{timeout:30000});
 const driving=PNG.sync.read(await page.screenshot({path:path.join(output,'day-driving.png')}));
 let drivingRoadPixels=0;
 for(let i=0;i<driving.data.length;i+=4){const [r,g,b]=driving.data.subarray(i,i+3);
  if(Math.abs(r-80)<5&&Math.abs(g-89)<5&&Math.abs(b-97)<5)drivingRoadPixels++;}
 assert.ok(drivingRoadPixels>200,'real asphalt must be visible behind the car; pixels='+drivingRoadPixels);
 phase='night';
 await page.evaluate(()=>{const g=window.GPS3D;g.setLocation(-99.133209,19.432608,0,0);
  g.follow(-99.133209,19.432608,0,14,0,1);g.setDarkTheme(true)});
 await page.waitForFunction(()=>{const s=window.GPS3D.mapState();return s.dark&&s.tilesLoaded&&s.rendered>0&&Math.abs(s.zoom-14)<.02});
 const night=await page.evaluate(()=>window.GPS3D.mapState());
 assert.ok(night.layerIds.includes('Woodland'),'nighttime woodland layer installed');
 assert.ok(!night.layerIds.includes('Earth Cover 9-22'),'daytime land cover removed completely');
 await page.screenshot({path:path.join(output,'night-zoom-14.png')});
 phase='offline';
 await page.evaluate(()=>window.GPS3D.setNetworkAvailable(false));await context.setOffline(true);
 const start=Date.now();
 await page.evaluate(()=>window.GPS3D.follow(-99.133209,19.432608,0,12,0,1));
 await page.waitForFunction(()=>{const s=window.GPS3D.mapState();return Math.abs(s.zoom-12)<.02&&s.tilesLoaded&&s.rendered>0},{},{timeout:5000});
 const cachedMs=Date.now()-start;
 assert.ok(cachedMs<1500,'complete prepared viewport stays responsive offline');
 const offline=await page.evaluate(()=>window.GPS3D.mapState());
 assert.ok(offline.rendered>=measurements.find(m=>m.zoom===12).rendered*.70,'offline viewport must have full coverage, not a single surviving tile');
 await page.waitForTimeout(500);
 const screenshot=await page.screenshot({path:path.join(output,'offline-zoom-12.png')});
 const pixels=PNG.sync.read(screenshot);
 let roadPixels=0;
 for(let i=0;i<pixels.data.length;i+=4){const [r,g,b]=pixels.data.subarray(i,i+3);if(Math.abs(r-66)<8&&Math.abs(g-78)<8&&Math.abs(b-90)<8)roadPixels++;}
 fs.writeFileSync(path.join(output,'result.json'),JSON.stringify({measurements,statuses,cachedMs,roadPixels,offline:{rendered:offline.rendered,roads:offline.roads,areas:offline.areas}},null,2));
 console.log('MAP_QA_DIAGNOSTIC '+JSON.stringify({measurements,statuses,cachedMs,roadPixels,offline:{rendered:offline.rendered,roads:offline.roads,areas:offline.areas}}));
 console.log('MAP_QA_JPEG '+(await page.screenshot({type:'jpeg',quality:65})).toString('base64'));
 assert.ok(roadPixels>300,'streets must be drawn on screen, not just labels on a gray background; pixels='+roadPixels);
 await context.setOffline(false);
 await page.evaluate(()=>{window.GPS3D.setNetworkAvailable(true);window.GPS3D.setDarkTheme(false)});
 await page.waitForFunction(()=>{const s=window.GPS3D.mapState();return !s.dark&&s.layerIds.includes('Earth Cover 9-22')&&s.tilesLoaded&&s.rendered>0});
 assert.deepEqual(errors,[],'no renderer/script errors');
 const report={measurements,statuses,cachedMs,roadPixels,nightLayersCorrect:true};
 fs.writeFileSync(path.join(output,'result.json'),JSON.stringify(report,null,2));
 console.log('Live map QA passed',JSON.stringify(report));
})().catch(async e=>{
 const error=String(e).replaceAll(key,'[credential]').replace(/https?:\/\/\S+/g,'[resource]');
 const state=page?await page.evaluate(()=>{const s=GPS3D.mapState(true);return {camera:GPS3D.cameraState(),
  map:{provider:s.provider,zoom:s.zoom,rendered:s.rendered,roads:s.roads,roadData:s.roadData,tilesLoaded:s.tilesLoaded,failures:s.failures}}}).catch(()=>null):null;
 const report={phase,error,state,measurements,statuses};
 fs.writeFileSync(path.join(output,'failure.json'),JSON.stringify(report,null,2));
 if(page)await page.screenshot({path:path.join(output,'failure.png')}).catch(()=>{});
 console.error(JSON.stringify(report));process.exitCode=1;
})
 .finally(async()=>{if(browser)await browser.close();if(server)server.close()});

