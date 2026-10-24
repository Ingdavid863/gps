// Real provider geometry, fresh WebGL page, offline travel beyond the old 10 km buffer.
const fs=require('node:fs'),path=require('node:path'),http=require('node:http'),assert=require('node:assert/strict');
const {chromium,request}=require(process.env.GPS3D_QA_NODE_MODULES+'/playwright');
const {PNG}=require(process.env.GPS3D_QA_NODE_MODULES+'/pngjs');
const key=process.env.TOMTOM_API_KEY;assert.ok(key,'service credential required');
const base=path.resolve(__dirname,'../app/src/main/assets'),output=path.resolve('build/map-qa/offline-route');fs.mkdirSync(output,{recursive:true});
// Use the actual vehicle access street. The airport centroid used previously
// snaps to a provider Walkway road; even levels 17/18 have no drivable asphalt there.
const start=[-109.9124,22.8905],end=[-109.71625,23.15763],resources=new Map(),requests=[];
let server,browser,api,phase='route';
const watchdog=setTimeout(()=>{console.error(JSON.stringify({phase,saved:resources.size,error:'Whole-route map QA exceeded six minutes'}));process.exit(1)},360000);
const canonical=url=>url.replace(/https:\/\/[abcd]\.api\.tomtom\.com\/map\//,'https://a.api.tomtom.com/map/');
(async()=>{
 api=await request.newContext();
 const response=await api.get(`https://api.tomtom.com/routing/1/calculateRoute/${start[1]},${start[0]}:${end[1]},${end[0]}/json?key=${encodeURIComponent(key)}&traffic=false&maxAlternatives=0&routeRepresentation=polyline`,{timeout:20000});
 assert.ok(response.ok(),'actual driving route must be available');
 const chosen=(await response.json()).routes[0],points=chosen.legs.flatMap(l=>l.points.map(p=>[p.longitude,p.latitude]));
 assert.ok(chosen.summary.lengthInMeters>20000,'route must exceed the previous 10 km look-ahead');
 let along=0,middle=points[0];
 for(let i=1;i<points.length;i++){
  along+=Math.hypot((points[i][0]-points[i-1][0])*111320*Math.cos(points[i][1]*Math.PI/180),(points[i][1]-points[i-1][1])*110540);
  if(along>chosen.summary.lengthInMeters*.6){middle=points[i];break;}
 }
 const urls=new Set(),tile=(lon,lat,z)=>[Math.floor((lon+180)/360*2**z),Math.floor((1-Math.asinh(Math.tan(lat*Math.PI/180))/Math.PI)/2*2**z)];
 for(let z=3;z<=16;z++)for(const point of points){
  const [x,y]=tile(...point,z),n=2**z;
  const radius=z===16?3:2;
  for(let dx=-radius;dx<=radius;dx++)for(let dy=-radius;dy<=radius;dy++)if(y+dy>=0&&y+dy<n)
   urls.add(`https://a.api.tomtom.com/map/1/tile/basic/main/${z}/${(x+dx+n)%n}/${y+dy}.pbf?key=${encodeURIComponent(key)}`);
 }
 const style='https://api.tomtom.com/style/1',sprite=style+'/sprite/22.2.1-9/sprite';
 for(const suffix of ['.json','.png','@2x.json','@2x.png'])urls.add(`${sprite}${suffix}?map=basic_main&key=${encodeURIComponent(key)}`);
 for(const font of ['NotoSans-MediumItalic','Noto-Regular','Noto-Bold','Noto-Medium'])for(const range of ['0-255','256-511','8192-8447'])
  urls.add(`${style}/glyph/22.2.1-9/${font}/${range}.pbf?key=${encodeURIComponent(key)}`);
 const all=[...urls];
 phase='preparing';console.log('Preparing complete route map',JSON.stringify({resources:all.length}));
 for(let i=0;i<all.length;i+=4)await Promise.all(all.slice(i,i+4).map(async url=>{
  let response;for(let retry=0;retry<3;retry++){response=await api.get(url,{timeout:15000});if(response.ok())break;}
  assert.ok(response.ok(),'complete route preparation failed, HTTP '+response.status());
  resources.set(canonical(url),{status:200,headers:{'content-type':response.headers()['content-type']||'application/octet-stream','access-control-allow-origin':'*'},body:await response.body()});
 }));
 phase='browser';console.log('Complete route map downloaded',resources.size);
 server=http.createServer((req,res)=>res.end());await new Promise(r=>server.listen(0,'127.0.0.1',r));
 browser=await chromium.launch({headless:true,args:['--no-sandbox','--enable-webgl','--use-gl=angle','--use-angle=swiftshader','--enable-unsafe-swiftshader']});
 const context=await browser.newContext({viewport:{width:390,height:840}}),errors=[];
 await context.route('**/*',async route=>{
  const url=new URL(route.request().url());
  if(url.hostname==='127.0.0.1'){
   if(url.pathname==='/traffic-config.js')return route.fulfill({contentType:'text/javascript',body:'window.GPS3D_CONFIG='+JSON.stringify({tomtomApiKey:key})+';'});
   const file=path.join(base,url.pathname);try{return route.fulfill({contentType:file.endsWith('.html')?'text/html':file.endsWith('.css')?'text/css':'text/javascript',body:fs.readFileSync(file)});}catch{return route.abort();}
  }
  const saved=resources.get(canonical(route.request().url()));requests.push({saved:!!saved,path:url.pathname});
  if(saved)return route.fulfill(saved);return route.abort();
 });
 // No browser HTTP or in-memory map tile cache from the preparation stage survives here.
 // Reproduce a phone showing mobile data while only the prepared tile level works.
 await context.setOffline(false);
 const page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));
 await page.addInitScript(()=>{window.AndroidBridge={prepareMapViewport(){},onMapReady(){window.mapReady=true;GPS3D.setTrafficEnabled(false);
  GPS3D.setRouteMapZoom(16);GPS3D.setNetworkAvailable(true);}}});
 await page.goto('http://127.0.0.1:'+server.address().port+'/map3d.html');
 await page.waitForFunction(()=>window.mapReady&&GPS3D.mapState().sourceMaxZoom===16);
 await page.evaluate(points=>{GPS3D.setRoutes(points,[],[]);GPS3D.setViewport(.25,.16);},points);
 const measurements=[];
 const courseAt=point=>{
  const i=points.findIndex(p=>p[0]===point[0]&&p[1]===point[1]),j=Math.max(1,i),a=points[j-1],b=points[j];
  return (Math.atan2((b[0]-a[0])*Math.cos(point[1]*Math.PI/180),b[1]-a[1])*180/Math.PI+360)%360;
 };
 for(const [name,point,zoom,dark] of [['mobile-data',middle,18.65,false],['middle',middle,18.65,false],['destination',points.at(-1),18.65,true],['overview',points.at(-1),12,true]]){
  const online=name==='mobile-data';await context.setOffline(!online);
  await page.evaluate(online=>GPS3D.setNetworkAvailable(online),online);
  assert.equal(await page.evaluate(()=>navigator.onLine),online,'exercise both mobile-data and true offline states');
  phase=name;console.log('Offline route phase',phase);
  const before=requests.length;
  await page.evaluate(({point,zoom,dark,bearing})=>{GPS3D.setDarkTheme(dark);GPS3D.setLocation(...point,bearing,0);GPS3D.follow(...point,bearing,zoom,zoom>16?65:0,1)}, {point,zoom,dark,bearing:courseAt(point)});
  // jumpTo-based camera animation does not set isMoving(). Wait for the actual destination
  // centre as well as zoom, or a same-zoom trip can capture the next viewport before its tiles load.
  await page.waitForFunction(({point,zoom,dark,bearing})=>{const s=GPS3D.mapState(true),c=GPS3D.cameraState(),b=GPS3D.navigationState().cameraBearing;return s.tilesLoaded&&s.sourceMaxZoom===16&&s.dark===dark&&Math.abs(s.zoom-zoom)<.02&&
   Math.abs(c.center[0]-point[0])<1e-6&&Math.abs(c.center[1]-point[1])<1e-6&&Math.abs((b-bearing+540)%360-180)<.1&&
   Math.abs(c.pitch-(zoom>16?65:0))<.02&&(s.roads>0||s.roadData>0)}, {point,zoom,dark,bearing:courseAt(point)},{timeout:10000});
  const state=await page.evaluate(()=>GPS3D.mapState(true));
  assert.ok(state.nativeCacheRequests>0,'vector workers must use the main-thread cache protocol');
  await page.screenshot({path:path.join(output,name+'.png')});
  // A narrow road may be entirely covered by the route highlight. Measure the base map
  // independently, while still requiring the navigation overlay to survive every stage.
  const visible=await page.evaluate(()=>GPS3D.navigationState().remaining);
  assert.ok(visible&&visible.geometry.coordinates.length>1,'saved route guidance remains available');
  await page.evaluate(()=>GPS3D.setRoutes([],[],[]));
  await page.waitForTimeout(180); // Allow the GeoJSON worker to remove the highlight before measuring asphalt.
  const screenshot=await page.screenshot({path:path.join(output,name+'-basemap.png')}),png=PNG.sync.read(screenshot);
  const color=dark?[66,78,90]:[80,89,97];let pixels=0;
  for(let i=0;i<png.data.length;i+=4)if(color.every((c,k)=>Math.abs(c-png.data[i+k])<5))pixels++;
  fs.writeFileSync(path.join(output,'latest.json'),JSON.stringify({name,routeMetres:chosen.summary.lengthInMeters,point,camera:await page.evaluate(()=>GPS3D.cameraState()),state,pixels,requests:requests.slice(before)},null,2));
  assert.ok(pixels>100,'actual streets render offline at '+name+', pixels='+pixels);
  assert.ok(requests.slice(before).filter(r=>/\/map\/1\/tile\//.test(r.path)).every(r=>r.saved),'no uncached detailed map requests during offline travel');
  measurements.push({name,zoom,roads:state.roads,roadData:state.roadData,streetPixels:pixels});
  await page.evaluate(points=>GPS3D.setRoutes(points,[],[]),points);
 }
 assert.deepEqual(errors,[]);fs.writeFileSync(path.join(output,'result.json'),JSON.stringify({routeMetres:chosen.summary.lengthInMeters,resources:resources.size,measurements},null,2));
 console.log('Whole-route offline WebGL passed: Los Cabos mobile-data loss and offline travel',JSON.stringify({routeMetres:chosen.summary.lengthInMeters,resources:resources.size,measurements}));
})().catch(e=>{console.error(String(e).replaceAll(key,'[credential]').replace(/https?:\/\/\S+/g,'[resource]'));process.exitCode=1;})
 .finally(async()=>{if(browser)await browser.close();if(api)await api.dispose();if(server)await new Promise(r=>server.close(r));clearTimeout(watchdog)});
