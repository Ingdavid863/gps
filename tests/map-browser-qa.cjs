const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const {chromium} = require(process.env.GPS3D_QA_NODE_MODULES + '/playwright');
(async()=>{
 const base = path.resolve(__dirname, '../app/src/main/assets');
 const server = http.createServer((req,res)=>{
  if (req.url.split('?')[0] === '/traffic-config.js') {res.setHeader('Content-Type','text/javascript'); res.end('window.GPS3D_CONFIG={tomtomApiKey:""};'); return;}
  const file=path.join(base,req.url.split('?')[0]);
  try {res.setHeader('Content-Type',file.endsWith('.html')?'text/html':file.endsWith('.css')?'text/css':'text/javascript'); res.end(fs.readFileSync(file));}catch{res.statusCode=404;res.end();}
 });
 await new Promise(r=>server.listen(0,'127.0.0.1',r));
 const browser=await chromium.launch({headless:true,args:['--no-sandbox','--enable-webgl','--use-gl=angle','--use-angle=swiftshader','--enable-unsafe-swiftshader']});
 const page=await browser.newPage({viewport:{width:390,height:840}});
 const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.route(/^https:\/\//,r=>r.abort());
 await page.addInitScript(()=>{window.AndroidBridge={onMapReady(){window.mapReady=true},onMapError(){}}});
 await page.goto('http://127.0.0.1:'+server.address().port+'/map3d.html');
 await page.waitForFunction(()=>window.mapReady===true);
 const credits=page.locator('.maplibregl-ctrl-attrib');
 await page.waitForFunction(()=>{
  const e=document.querySelector('.maplibregl-ctrl-attrib');
  return e&&!e.classList.contains('maplibregl-attrib-empty');
 });
 assert.equal(await credits.locator('summary').isVisible(),false,'information icon removed');
 assert.equal(await credits.locator('.maplibregl-ctrl-attrib-inner').isVisible(),true,'provider credits remain visible');
 assert.match(await credits.innerText(),/OpenStreetMap/);
 assert.equal(await page.locator('#traffic-status').isVisible(),false);
 assert.equal(await page.locator('#signal-status').isVisible(),false);
 await page.evaluate(()=>{
  const g=window.GPS3D;g.setViewport(.25,.16);g.setLocation(-99.13,19.43,0,0);g.follow(-99.13,19.43,0,17.4,0,1);
 });
 await page.waitForTimeout(600);
 const marker=await page.locator('.vehicle-wrap').boundingBox();
 assert.ok(marker,'marker exists');
 assert.ok(Math.abs((marker.y+marker.height/2)-(840*(1-.16)-48))<3,'arrow anchored above bottom panel');
 const color=await page.locator('.vehicle-arrow').evaluate(e=>getComputedStyle(e).backgroundColor);
 assert.equal(color,'rgb(25, 180, 91)');
 await page.evaluate(()=>{
  const g=window.GPS3D;g.setRoutes([[-99.13,19.43],[-99.135,19.44],[-99.15,19.46]],[],[]);g.setViewMode(2);g.showOverview();
 });
 await page.waitForTimeout(600);
 const overview=await page.evaluate(()=>window.GPS3D.cameraState());
 assert.equal(overview.mode,2);assert.ok(Math.abs(overview.pitch)<.01);
 for(const [x,y] of overview.route) {
  assert.ok(x>=overview.padding.left-1&&x<=390-overview.padding.right+1,'route fits horizontally: '+JSON.stringify(overview));
  assert.ok(y>=overview.padding.top-1&&y<=840-overview.padding.bottom+1,'origin and destination fit vertically: '+JSON.stringify(overview));
 }
 const canvasBefore=await page.locator('canvas.maplibregl-canvas').screenshot();
 await page.evaluate(()=>window.GPS3D.follow(-98.0,20.0,140,19,55,110));
 await page.waitForTimeout(400);
 const canvasAfter=await page.locator('canvas.maplibregl-canvas').screenshot();
 assert.equal(Buffer.compare(canvasBefore,canvasAfter),0,'GPS follow must not override overview');
 await page.setViewportSize({width:412,height:915});
 await page.evaluate(()=>window.GPS3D.setViewport(.30,.22));
 const resized=await page.evaluate(()=>window.GPS3D.cameraState());
 assert.equal(resized.moving,false,'overview must fit immediately after a native panel resize');
 for(const [x,y] of resized.route) {
  assert.ok(x>=resized.padding.left-1&&x<=412-resized.padding.right+1,'resized route fits horizontally: '+JSON.stringify(resized));
  assert.ok(y>=resized.padding.top-1&&y<=915-resized.padding.bottom+1,'resized route fits above footer: '+JSON.stringify(resized));
 }
 await page.evaluate(()=>{window.GPS3D.setViewMode(1);window.GPS3D.follow(-99.13,19.43,0,17.4,55,1);});
 await page.waitForTimeout(500);
 await page.evaluate(()=>{window.GPS3D.setDarkTheme(true);window.GPS3D.setManeuvers([{index:1,maneuver:'TURN_LEFT'}]);});
 assert.equal((await page.evaluate(()=>window.GPS3D.cameraState())).dark,true);
 await page.evaluate(()=>{window.GPS3D.setViewMode(0);window.GPS3D.follow(-99.13,19.43,0,17.4,0,1);});
 await page.waitForTimeout(500);
 assert.ok(Math.abs((await page.evaluate(()=>window.GPS3D.cameraState())).pitch)<.01,'third click restores default view');
 assert.deepEqual(errors,[]);
 console.log('Browser map QA passed: clean navigation UI, green marker anchor, overview lock, isometric switch; no JS errors.');
 await browser.close();server.close();
})().catch(e=>{console.error(e);process.exit(1)});

