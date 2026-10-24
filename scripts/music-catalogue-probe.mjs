import * as YT from 'youtubei.js';
import vm from 'node:vm';
const platform=YT.Platform||YT.Utils?.Platform;
if(!platform)throw Error('Platform evaluator unavailable');
platform.shim.eval=async(data,env)=>vm.runInNewContext(data.output,{...env},{timeout:5000});
const watchdog=setTimeout(()=>{console.error('Catalogue probe exceeded three minutes');process.exit(1)},180000);
const nativeFetch=globalThis.fetch;
const fetcher=(input,init={})=>nativeFetch(input,{...init,signal:AbortSignal.timeout(20000)});
try{
 const yt=await YT.Innertube.create({lang:'en',location:'US',generate_session_locally:true,fetch:fetcher});
 console.log('Catalogue session created');
 let songs=[];
 try{
  const found=await yt.music.search('Kevin MacLeod Carefree',{type:'song'});
  songs=(found.contents||[]).flatMap(s=>s.contents||[]).filter(s=>s.id).map(s=>({id:s.id,title:String(s.title||''),artist:(s.artists||[]).map(a=>a.name).join(', ')}));
  console.log('Music search',JSON.stringify({count:songs.length,first:songs.slice(0,2)}));
 }catch(error){console.log('Music search unavailable',String(error).replace(/https?:\/\/\S+/g,'[resource]').slice(0,300));}
 if(!songs.length){
  const found=await yt.search('Kevin MacLeod Carefree',{type:'video'});
  songs=found.videos.map(v=>({id:v.id||v.content_id,title:String(v.title||v.metadata?.title||''),artist:v.author?.name||''})).filter(v=>v.id);
  console.log('Video search',JSON.stringify({count:songs.length,first:songs.slice(0,2)}));
 }
 if(!songs.length)throw Error('No actual catalogue results');
 let success=false;
 for(const client of ['ANDROID_VR','IOS','WEB']){
  try{
   const format=await yt.getStreamingData(songs[0].id,{type:'audio',quality:'best',client});
   if(!format.url)throw Error('No audio URL');
   const controller=new AbortController();
   const response=await nativeFetch(format.url,{headers:{Range:'bytes=0-1023'},signal:AbortSignal.any([controller.signal,AbortSignal.timeout(20000)])});
   if(!response.ok)throw Error('Audio HTTP '+response.status);
   const reader=response.body.getReader();const part=await reader.read();controller.abort();
   if(!part.value?.length)throw Error('Empty audio');
   console.log('Actual catalogue audio received',JSON.stringify({client,status:response.status,mime:response.headers.get('content-type'),bytes:part.value.length}));
   success=true;break;
  }catch(error){console.log('Client result',client,String(error).replace(/https?:\/\/\S+/g,'[resource]').slice(0,360));}
 }
 if(!success)throw Error('Catalogue audio not playable on this network');
}finally{clearTimeout(watchdog)}
