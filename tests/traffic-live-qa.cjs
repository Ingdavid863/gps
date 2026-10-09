// Live traffic regression: no destination/route, real vector responses, visible colored pixels.
const http=require('node:http'),fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {chromium}=require(process.env.GPS3D_QA_NODE_MODULES+'/playwright');
const {PNG}=require(process.env.GPS3D_QA_NODE_MODULES+'/pngjs');
const key=process.env.TOMTOM_API_KEY;
assert.ok(key,'Traffic service credential configured');
const output=path.resolve(__dirname,'../build/traffic-qa');fs.mkdirSync(output,{recursive:true});
let browser,server;
function pixels(buffer) {
 const png=PNG.sync.read(buffer),counts={green:0,congested:0};
 for(let i=0;i<png.data.length;i+=4){const [r,g,b]=png.data.subarray(i,i+3);
  if(g>100&&g>r*1.45&&g>b*1.15&&r<90)counts.green++;
  if(r>150&&r>b*1.8&&g<220&&(r>g*1.35||(r>210&&g>120)))counts.congested++;
 }return counts;
}
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
 const page=await context.newPage(),errors=[],statuses={};let trafficRequests=0;
 page.on('pageerror',e=>errors.push(e.message.replaceAll(key,'[credential]').replace(/https?:\/\/\S+/g,'[resource]')));
 await page.route(/^https:\/\//,async route=>{
  const u=new URL(route.request().url());
  const traffic=u.pathname.startsWith('/traffic/map/4/tile/flow/');
  if(u.hostname.endsWith('api.tomtom.com')&&(traffic||u.pathname.startsWith('/map/')||u.pathname.startsWith('/style/'))){
   try{
    const response=await route.fetch();
    if(traffic){trafficRequests++;statuses[response.status()]=(statuses[response.status()]||0)+1;}
    const headers={...response.headers(),'access-control-allow-origin':'*'};
    delete headers['content-encoding'];delete headers['content-length'];
    await route.fulfill({status:response.status(),headers,body:await response.body()});
   }catch{await route.abort();}
  }else await route.abort();
 });
 await page.addInitScript(()=>{window.AndroidBridge={onMapReady(){window.mapReady=true}}});
 await page.goto('http://127.0.0.1:'+server.address().port+'/map3d.html');
 await page.waitForFunction(()=>window.mapReady===true);
 const results=[];
 for(const [name,lon,lat,zoom] of [['tepotzotlan',-99.219,19.709,15.8],['cdmx',-99.133209,19.432608,15.8]]){
  await page.evaluate(({lon,lat,zoom})=>{const g=window.GPS3D;g.setLocation(lon,lat,0,0);g.follow(lon,lat,0,zoom,0,1)},{lon,lat,zoom});
  await page.waitForFunction(()=>{const s=window.GPS3D.trafficState();return s.loaded&&s.rendered>3&&s.status==='live'},null,{timeout:45000});
  const day=await page.evaluate(()=>window.GPS3D.trafficState());
  assert.equal(day.routeActive,false,'street traffic must render without navigation');
  assert.ok(day.bands.normal>0,'actual free-flow segments shown in green');
  await page.waitForTimeout(300);
  const screenshot=await page.screenshot({path:path.join(output,name+'-day.png')});
  const visible=pixels(screenshot);assert.ok(visible.green>100,'traffic must produce visible green road pixels');
  await page.evaluate(()=>window.GPS3D.setDarkTheme(true));
  await page.waitForFunction(()=>window.GPS3D.trafficState().status==='live'&&window.GPS3D.trafficState().rendered>3,null,{timeout:30000});
  await page.waitForTimeout(300);
  const night=await page.evaluate(()=>window.GPS3D.trafficState());
  await page.screenshot({path:path.join(output,name+'-night.png')});
  const before=trafficRequests;
  await page.evaluate(()=>window.GPS3D.refreshTraffic());
  await page.waitForFunction(()=>window.GPS3D.trafficState().loaded,null,{timeout:30000});
  await page.waitForTimeout(1000);
  assert.ok(trafficRequests>before,'refresh must request current traffic tiles');
  await page.evaluate(()=>window.GPS3D.setTrafficEnabled(false));
  const disabled=await page.evaluate(()=>window.GPS3D.trafficState());
  assert.equal(disabled.rendered,0);assert.equal(disabled.status,'disabled');
  await page.evaluate(()=>{window.GPS3D.setTrafficEnabled(true);window.GPS3D.setNetworkAvailable(false)});
  const offline=await page.evaluate(()=>window.GPS3D.trafficState());
  assert.equal(offline.rendered,0);assert.equal(offline.status,'offline','old traffic cannot be presented as live offline');
  await page.evaluate(()=>{window.GPS3D.setNetworkAvailable(true);window.GPS3D.setDarkTheme(false)});
  await page.waitForFunction(()=>window.GPS3D.trafficState().status==='live',null,{timeout:30000});
  results.push({name,day,night,visible,refreshRequests:trafficRequests-before});
 }
 assert.deepEqual(errors,[],'no traffic renderer/script errors');
 assert.ok(statuses[200]>0,'real traffic vector service responded');
 assert.equal(statuses[403]||0,0,'traffic credentials must authorize the vector feed');
 assert.equal(statuses[429]||0,0,'traffic requests must stay below provider limits');
 const report={results,statuses,trafficRequests};
 fs.writeFileSync(path.join(output,'result.json'),JSON.stringify(report,null,2));
 console.log('LIVE_TRAFFIC_QA passed '+JSON.stringify(report));
})().catch(e=>{console.error(String(e).replaceAll(key,'[credential]').replace(/https?:\/\/\S+/g,'[resource]'));process.exitCode=1})
 .finally(async()=>{if(browser)await browser.close();if(server)server.close()});
