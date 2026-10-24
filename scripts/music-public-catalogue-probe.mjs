import SoundCloud from 'soundcloud-fetch';
const timeout=setTimeout(()=>{console.error('Public catalogue probe timed out');process.exit(1)},180000);
let playable=false;
const fetcher=(url,options={})=>fetch(url,{...options,signal:AbortSignal.timeout(20000)});
try{
 try{
  const response=await fetcher('https://discoveryprovider.audius.co/v1/tracks/search?query=piano&limit=3&app_name=GPS3D');
  console.log('Audius search HTTP',response.status);
  if(response.ok){
   const results=(await response.json()).data||[];
   console.log('Audius results',results.length);
   for(const song of results){
    if(song.is_stream_gated)continue;
    const audio=await fetcher('https://discoveryprovider.audius.co/v1/tracks/'+song.id+'/stream?app_name=GPS3D',{headers:{Range:'bytes=0-1023'}});
    console.log('Audius audio',JSON.stringify({status:audio.status,mime:audio.headers.get('content-type')}));
    if(audio.ok){const reader=audio.body.getReader();const data=await reader.read();await reader.cancel();if(data.value?.length){console.log('Audius audio bytes',data.value.length);playable=true;break;}}
   }
  }
 }catch(error){console.log('Audius error',String(error).replace(/https?:\S+/g,'[resource]').slice(0,250));}
 try{
  const client=new SoundCloud();
  const result=await client.search('Kevin MacLeod',{type:'track',limit:5});
  console.log('SoundCloud collection fields',Object.keys(result));
  const songs=result.items||result.collection||[];
  console.log('SoundCloud result count',songs.length);
  for(const item of songs.slice(0,3)){
   const song=item.mediaInfo?item:await client.getTrack(item.id);
   console.log('SoundCloud track',JSON.stringify({id:song?.id,title:song?.title,mediaFields:Object.keys(song?.mediaInfo||{})}));
   for(const format of song?.mediaInfo?.transcodings||[]){
    if(!format.url||format.preset?.startsWith('abr_sq'))continue;
    const stream=await client.getStreamingData({transcodingUrl:format.url,trackAuthorization:song.mediaInfo.trackAuthorization||undefined});
    if(stream?.licenseAuthToken){console.log('Protected format omitted');continue;}
    if(!stream?.url)continue;
    const audio=await fetcher(stream.url,{headers:{Range:'bytes=0-1023'}});
    console.log('SoundCloud audio',JSON.stringify({protocol:format.protocol,status:audio.status,mime:audio.headers.get('content-type')}));
    if(audio.ok){const reader=audio.body.getReader();const data=await reader.read();await reader.cancel();if(data.value?.length){console.log('SoundCloud audio bytes',data.value.length);playable=true;break;}}
   }
   if(playable)break;
  }
 }catch(error){console.log('SoundCloud error',String(error).replace(/https?:\S+/g,'[resource]').slice(0,250));}
 if(!playable)throw Error('No independently playable public catalogue found');
}finally{clearTimeout(timeout)}
