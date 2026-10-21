// Real MapLibre rendering with deterministic local vector roads; no provider credentials.
const http=require('node:http'),fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {chromium}=require(process.env.GPS3D_QA_NODE_MODULES+'/playwright');
const origin=[-110,23.1],sx=111320*Math.cos(origin[1]*Math.PI/180),at=(x,y)=>[origin[0]+x/sx,origin[1]+y/110540];
const roads=[{points:[at(0,-150),at(0,100),at(100,200),at(100,400)],direction:-1},
    {points:[at(20,-150),at(20,100),at(120,200),at(120,400)],direction:1}];
function varint(n){const bytes=[];while(n>127){bytes.push(n%128+128);n=Math.floor(n/128);}return Buffer.from([...bytes,n]);}
const field=(number,bytes)=>Buffer.concat([varint(number*8+2),varint(bytes.length),bytes]);
const integer=(number,n)=>Buffer.concat([varint(number*8),varint(n)]),zig=n=>n<0?-n*2-1:n*2;
function tile(z,x,y) {
    const size=2**z;
    const encoded=roads.map((road,index)=>{
        const points=road.points.map(([lon,lat])=>[Math.round(((lon+180)/360*size-x)*4096),
            Math.round(((1-Math.asinh(Math.tan(lat*Math.PI/180))/Math.PI)/2*size-y)*4096)]);
        if(Math.max(...points.map(p=>p[0]))<0||Math.min(...points.map(p=>p[0]))>4096||
            Math.max(...points.map(p=>p[1]))<0||Math.min(...points.map(p=>p[1]))>4096)return null;
        const geometry=[9,zig(points[0][0]),zig(points[0][1]),(points.length-1)*8+2];
        for(let i=1;i<points.length;i++)geometry.push(zig(points[i][0]-points[i-1][0]),zig(points[i][1]-points[i-1][1]));
        return field(2,Buffer.concat([integer(1,index+1),field(2,Buffer.concat([varint(0),varint(index),varint(1),varint(2)])),integer(3,2),
            field(4,Buffer.concat(geometry.map(varint)))]));
    }).filter(Boolean);
    const layer=Buffer.concat([field(1,Buffer.from('Major road')),...encoded,field(3,Buffer.from('direction')),field(3,Buffer.from('z_level')),
        ...roads.map(r=>field(4,integer(6,zig(r.direction)))),field(4,integer(6,0)),integer(5,4096),integer(15,2)]);
    return field(3,layer);
}
let server,browser;
(async()=>{
    const base=path.resolve(__dirname,'../app/src/main/assets');
    server=http.createServer((req,res)=>{
        const url=req.url.split('?')[0],road=/^\/roads\/(\d+)\/(\d+)\/(\d+)\.pbf$/.exec(url);
        if(road){res.setHeader('Content-Type','application/x-protobuf');res.end(tile(...road.slice(1).map(Number)));return;}
        if(url==='/traffic-config.js'){res.end('window.GPS3D_CONFIG={tomtomApiKey:""};');return;}
        if(url==='/navigation-styles.js'){
            const style={version:8,sources:{vectorTiles:{type:'vector',tiles:[`http://127.0.0.1:${server.address().port}/roads/{z}/{x}/{y}.pbf`],maxzoom:18}},
                layers:[{id:'background',type:'background',paint:{'background-color':'#2b3440'}},
                    {id:'fixture-road',type:'line',source:'vectorTiles','source-layer':'Major road',filter:['!has','z_level'],
                        paint:{'line-color':'#b5a576','line-width':14}}]};
            res.setHeader('Content-Type','text/javascript');res.end('window.NavigationStyles='+JSON.stringify({fallback:style})+';');return;
        }
        const file=path.join(base,url);
        try{res.setHeader('Content-Type',file.endsWith('.html')?'text/html':file.endsWith('.css')?'text/css':'text/javascript');res.end(fs.readFileSync(file));}
        catch{res.statusCode=404;res.end();}
    });
    await new Promise(r=>server.listen(0,'127.0.0.1',r));
    browser=await chromium.launch({headless:true,args:['--no-sandbox','--enable-webgl','--use-gl=angle','--use-angle=swiftshader','--enable-unsafe-swiftshader']});
    const page=await browser.newPage({viewport:{width:390,height:840}}),errors=[],styleErrors=[];
    page.on('console',m=>{if(/layers\[|paint\.|Invalid.*expression/i.test(m.text()))styleErrors.push(m.text());});
    page.on('pageerror',e=>errors.push(e.message));await page.route(/^https:\/\//,r=>r.abort());
    await page.addInitScript(()=>{window.AndroidBridge={onMapReady(){window.mapReady=true},onMapError(){}}});
    await page.goto('http://127.0.0.1:'+server.address().port+'/map3d.html');
    await page.waitForFunction(()=>window.mapReady===true);
    await page.evaluate(p=>{GPS3D.setViewport(.25,.16);GPS3D.setLocation(...p,0,26,false,15,true);GPS3D.follow(...p,0,17.4,0,1);},at(18,40));
    await page.waitForFunction(()=>GPS3D.mapState().tilesLoaded&&GPS3D.mapState().roads>0);
    await page.evaluate(p=>{GPS3D.setLocation(...p,0,26,false,15,true);GPS3D.follow(...p,0,17.4,0,1);},at(18,40));
    await page.waitForFunction(()=>GPS3D.navigationState().roadMatched);
    await page.waitForTimeout(600);
    const matched=await page.evaluate(()=>GPS3D.navigationState());
    assert.ok(Math.abs(matched.roadCorrectionMeters-18)<1,'GNSS offset corrected using road geometry');
    assert.ok(Math.abs((matched.vehicle[0]-origin[0])*sx)<1,'northbound position must not jump to nearer opposing carriageway');
    const marker=await page.locator('.vehicle-wrap').boundingBox();
    assert.ok(Math.abs(marker.x+marker.width/2-195)<2,'marker remains horizontally centered');
    assert.ok(Math.abs(marker.y+marker.height/2-(840*.84-60))<3,'map and marker share the raised anchor');
    await page.evaluate(p=>{GPS3D.setLocation(...p,0,26,false,70,true);},at(18,65));
    assert.equal((await page.evaluate(()=>GPS3D.navigationState())).roadMatched,false,'uncertain fixes cannot be forced onto a road');
    await page.evaluate(points=>{
        GPS3D.setRoutes(points,[],[]);GPS3D.setLocation(...points[0],0,26,true,15,true);GPS3D.follow(...points[0],0,17.4,0,1);
    },[at(50,40),at(50,200)]);
    await page.waitForTimeout(400);
    const routed=await page.evaluate(()=>GPS3D.navigationState());
    assert.equal(routed.roadMatched,false,'existing active-route matching takes precedence');
    assert.ok(Math.abs((routed.vehicle[0]-origin[0])*sx-50)<1,'new no-route logic cannot override the active route');
    await page.evaluate(()=>{GPS3D.setViewMode(1);GPS3D.setDarkTheme(true)});
    await page.setViewportSize({width:412,height:915});
    await page.evaluate(p=>{GPS3D.setViewport(.30,.22);GPS3D.follow(...p,0,18.3,65,1);},at(50,40));
    try { await page.waitForFunction(()=>GPS3D.cameraState().pitch>64,null,{timeout:10000}); }
    catch(error) { throw new Error('Driving camera did not settle: '+JSON.stringify(await page.evaluate(()=>GPS3D.cameraState()))+'; '+error.message); }
    const tilted=await page.locator('.vehicle-wrap').boundingBox();
    assert.ok(Math.abs(tilted.y+tilted.height/2-(915*.78-60))<3,'raised anchor remains accurate after pitch and native-panel resize');
    assert.ok((await page.evaluate(()=>GPS3D.cameraState())).pitch>64,'driving camera stays behind the car');
    assert.ok(await page.locator('.vehicle-car polygon').count()>30,'car survives theme and mode switches');
    await page.evaluate(points=>{
        GPS3D.setRoutes(points,[],[]);GPS3D.setLocation(...points[0],0,0,true,8,true);
        GPS3D.follow(...points[0],0,18.7,65,1);
    },[at(0,40),at(0,100),at(100,200),at(100,400)]);
    await page.waitForTimeout(600);
    const captures=path.resolve(__dirname,'../app/build/ar-qa');fs.mkdirSync(captures,{recursive:true});
    await page.screenshot({path:path.join(captures,'roads-driving-night.png')});
    await page.evaluate(()=>GPS3D.setDarkTheme(false));
    await page.waitForTimeout(300);
    await page.screenshot({path:path.join(captures,'roads-driving-day.png')});
    assert.deepEqual(errors,[]);
    assert.deepEqual(styleErrors,[],'road style expressions must render without validation errors');
    console.log('Precision WebGL QA passed: raw GNSS correction, opposing roads, route isolation, raised anchor, pitch and resize.');
})().catch(e=>{console.error(e);process.exitCode=1})
    .finally(async()=>{if(browser)await browser.close();if(server)server.close()});
