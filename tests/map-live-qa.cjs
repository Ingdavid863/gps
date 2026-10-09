// Exercise the real vector service with CI credentials. Diagnostics never include URLs/keys.
const http=require('node:http'),fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {chromium}=require(process.env.GPS3D_QA_NODE_MODULES+'/playwright');
const {PNG}=require(process.env.GPS3D_QA_NODE_MODULES+'/pngjs');
const key=process.env.TOMTOM_API_KEY;
assert.ok(key,'Map service credential configured for rendering QA');
const output=path.resolve(__dirname,'../build/map-qa');fs.mkdirSync(output,{recursive:true});
let browser,server;
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
 const page=await context.newPage(),errors=[],statuses={},preparedResources=new Map();
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
 const measurements=[];
 for(const zoom of [17.4,16,14,12,10,6,3,18.65]){
  await page.evaluate(z=>{const g=window.GPS3D;g.setLocation(-99.133209,19.432608,0,0);g.follow(-99.133209,19.432608,0,z,0,1)},zoom);
  await page.waitForFunction(z=>{const s=window.GPS3D.mapState();return Math.abs(s.zoom-z)<.02&&s.tilesLoaded&&s.rendered>0},zoom,{timeout:30000});
  const state=await page.evaluate(()=>window.GPS3D.mapState());
  assert.equal(state.provider,'tomtom','primary service must render the actual map');
  assert.equal(state.failures.vectorTiles||0,0,'no missing vector tiles while zooming');
  measurements.push({zoom,rendered:state.rendered,roads:state.roads,areas:state.areas});
  if(zoom===14||zoom===3)await page.screenshot({path:path.join(output,`day-zoom-${zoom}.png`)});
 }
 await page.evaluate(()=>{const g=window.GPS3D;g.follow(-99.133209,19.432608,0,14,0,1);g.setDarkTheme(true)});
 await page.waitForFunction(()=>{const s=window.GPS3D.mapState();return s.dark&&s.tilesLoaded&&s.rendered>0&&Math.abs(s.zoom-14)<.02});
 const night=await page.evaluate(()=>window.GPS3D.mapState());
 assert.ok(night.layerIds.includes('Woodland'),'nighttime woodland layer installed');
 assert.ok(!night.layerIds.includes('Earth Cover 9-22'),'daytime land cover removed completely');
 await page.screenshot({path:path.join(output,'night-zoom-14.png')});
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
 for(let i=0;i<pixels.data.length;i+=4){const [r,g,b]=pixels.data.subarray(i,i+3);if(r>105&&r<190&&g>80&&g<155&&b>55&&b<110&&r>g+10&&g>b+10)roadPixels++;}
 assert.ok(roadPixels>300,'streets must be drawn on screen, not just labels on a gray background; pixels='+roadPixels);
 await context.setOffline(false);
 await page.evaluate(()=>{window.GPS3D.setNetworkAvailable(true);window.GPS3D.setDarkTheme(false)});
 await page.waitForFunction(()=>{const s=window.GPS3D.mapState();return !s.dark&&s.layerIds.includes('Earth Cover 9-22')&&s.tilesLoaded&&s.rendered>0});
 assert.deepEqual(errors,[],'no renderer/script errors');
 const report={measurements,statuses,cachedMs,roadPixels,nightLayersCorrect:true};
 fs.writeFileSync(path.join(output,'result.json'),JSON.stringify(report,null,2));
 console.log('Live map QA passed',JSON.stringify(report));
})().catch(e=>{console.error(String(e).replaceAll(key,'[credential]').replace(/https?:\/\/\S+/g,'[resource]'));process.exitCode=1})
 .finally(async()=>{if(browser)await browser.close();if(server)server.close()});
