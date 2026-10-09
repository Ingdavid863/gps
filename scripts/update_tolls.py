"""Refresh public toll sources. Each failed source leaves its last valid prices intact."""
import argparse
import concurrent.futures
import hashlib
import io
import json
import re
import struct
import tempfile
import time
import unicodedata
import urllib.request
import zipfile
import zlib
from datetime import datetime, timezone
from html.parser import HTMLParser
from pathlib import Path

from toll_catalog import extract

AGENT = {"User-Agent": "GPS3D-AR-David-catalog/0.14"}
ASSET = Path("app/src/main/assets/mx-tolls.json")

def download(url, headers=None, data=None):
    request=urllib.request.Request(url,data=data,headers=AGENT | (headers or {}))
    with urllib.request.urlopen(request,timeout=60) as response:
        return response.read(), dict(response.headers)

def normalize(text):
    text=''.join(c for c in unicodedata.normalize('NFD',text.upper()) if not unicodedata.combining(c))
    text=re.sub(r'^(?:PLAZA DE COBRO|CASETAS?(?: DE (?:PEAJE|COBRO))?|PEAJE)(?: DE)?\s+','',text)
    return ' '.join(re.sub('[^A-Z0-9 ]',' ',text).split())

def money(text):
    match=re.fullmatch(r'\s*\$?\s*([\d,]+(?:\.\d{1,2})?)\s*',text or '')
    return float(match[1].replace(',','')) if match else None

class Tables(HTMLParser):
    def __init__(self):
        super().__init__();self.tables=[];self.contexts=[];self.before=[];self.table=None;self.row=None;self.cell=None
    def handle_starttag(self,tag,attrs):
        if tag=='table':
            self.contexts.append(' '.join(self.before)[-1000:]);self.before=[];self.table=[]
        elif tag=='tr' and self.table is not None:self.row=[]
        elif tag in ('td','th') and self.row is not None:self.cell=[]
        elif tag=='br' and self.cell is not None:self.cell.append(' ')
    def handle_data(self,text):
        if self.cell is not None:self.cell.append(text)
        elif self.table is None:self.before.append(text)
    def handle_endtag(self,tag):
        if tag in ('td','th') and self.cell is not None:self.row.append(' '.join(''.join(self.cell).split()));self.cell=None
        elif tag=='tr' and self.row is not None:self.table.append(self.row);self.row=None
        elif tag=='table' and self.table is not None:self.tables.append(self.table);self.table=None

def tables(html):
    parser=Tables();parser.feed(html);return parser.tables

def capufe_fares(pdf):
    import pdfplumber
    fares=[]
    with pdfplumber.open(io.BytesIO(pdf)) as document:
        for page in document.pages:
            words=page.extract_words()
            tramo=any(w['text']=='TRAMO' and w['top']<85 for w in words)
            for table in page.find_tables():
                rows=table.extract()
                auto=next((i for i,s in enumerate(rows[0]) if s and 'AUTOS' in s),None)
                if auto is None:continue
                for i,row in enumerate(rows[1:],1):
                    if len(row)<=auto:continue
                    cost=money(row[auto]);date=row[auto-2]
                    if cost is None or not date or not re.search(r'20\d{2}',date):continue
                    cell=table.rows[i].cells[auto]
                    if cell is None:continue
                    if tramo:
                        if row[0] and re.fullmatch(r'\d+',row[0].strip()):continue  # whole-road total is not a plaza fare
                        name=page.crop((35,cell[1],165,cell[3])).extract_text() or ''
                    else:name=row[auto-3] or ''
                    name=' '.join(name.split())
                    if name and not name.isdigit():fares.append({'name':name,'carMxn':cost,'effective':date})
    if len(fares)<180:raise ValueError('CAPUFE table format changed or is incomplete')
    return fares

def apply_capufe(catalog,pdf,url,checked):
    grouped={}
    for fare in capufe_fares(pdf):grouped.setdefault(normalize(fare['name']),[]).append(fare)
    updated=0
    for plaza in catalog['plazas']:
        found=grouped.get(normalize(plaza['name']),[])
        if plaza['operator']!='CAPUFE' or not found or len({f['carMxn'] for f in found})!=1:continue
        for fare in plaza['fares']:
            if fare['entryId']!=plaza['id']:continue  # closed-system rates need the specific entry
            fare.update(carMxn=found[0]['carMxn'],effective=found[0]['effective'],source='CAPUFE',sourceUrl=url)
            updated+=1
    return updated

VB_ALIASES={
    'G BAZ':'GUSTAVO BAZ','GUSTAVO BAZ ORIENTE':'GUSTAVO BAZ','VALLEJO VALLEJO':'VALLEJO',
    '1O DE MAYO':'1 DE MAYO','1 DE MAYO':'1 DE MAYO','1RO DE MAYO':'1 DE MAYO','LOPEZ PORTILLO PERINORTE':'LOPEZ PORTILLO',
    'VIA JOSE LOPEZ PORTILLO':'LOPEZ PORTILLO','L GPE':'LAGO DE GUADALUPE','CIRCUN VALACION':'CIRCUNVALACION',
    'RIO SAN JOAQUIN':'SAN JOAQUIN','ENTRADA TOREO':'TOREO'}

# RNC distinguishes the main plazas from access ramps bearing the same name.
# Only identified plaza IDs are linked to the corresponding published column.
CEM_COLUMNS={
    700:'T1 Tultepec',701:'T1 Tultepec',1610:'T1 Tultepec',
    704:'A31-A32 Ent. Tultepec',705:'A31-A32 Ent. Tultepec',
    706:'T2 ConMex',707:'T2 ConMex',725:'T5 Tultitlán',726:'T5 Tultitlán',
    728:'T0 Jorobas',729:'T0 Jorobas',968:'T7 Texcoco',969:'T7 Texcoco',
    1602:'T4 Chalco',1603:'T4 Chalco'}
GAN_COLUMNS={502:'Amozoc T-1',451:'Cuapiaxtla T-2',452:'Cuapiaxtla A1-A2',
    453:'Cuapiaxtla A1-A2',454:'Cuapiaxtla A1-A2',455:'Cuapiaxtla A1-A2',
    488:'Cantona T-3',489:'Cantona T-3',528:'Lib. Perote',529:'Lib. Perote',1627:'AUDI'}

def classification_fares(parsed):
    fees={}
    for table in parsed:
        if not table or len(table[0])<2:continue
        for row in table[1:]:
            if row and normalize(row[0]) in ('AUTOMOVIL','AUTOMOVILES'):
                for name,value in zip(table[0][1:],row[1:]):
                    cost=money(value)
                    if cost is not None:fees.setdefault(normalize(name),set()).add(cost)
    return fees

def apply_columns(catalog,parsed,columns,source,url,checked):
    fees=classification_fares(parsed)
    if len(fees)<8:raise ValueError('Classification table format changed')
    updated=0
    for plaza in catalog['plazas']:
        costs=fees.get(normalize(columns.get(plaza['id'],'')),set())
        if len(costs)!=1:continue
        for fare in plaza['fares']:
            if fare['entryId']==plaza['id']:
                fare.update(carMxn=next(iter(costs)),effective='Publicado; consultado '+checked,source=source,sourceUrl=url)
                updated+=1
    return updated

def vb_name(text):
    name=normalize(text);return VB_ALIASES.get(name,name)

def apply_televia(catalog,html,url,checked,closed=False):
    parsed=tables(html);updated=0
    if closed:
        pairs={}
        for table in parsed:
            if not table or not table[0] or not table[0][0].startswith('Entrada en '):continue
            origin=re.sub(r'^Entrada en | con salida en$','',table[0][0])
            for row in table[1:]:
                if len(row)==2 and money(row[1]) is not None:pairs.setdefault((vb_name(origin),vb_name(row[0])),set()).add(money(row[1]))
        if len(pairs)<50:raise ValueError('Viaducto table format changed')
        by_id={p['id']:p for p in catalog['plazas']}
        for plaza in catalog['plazas']:
            if not (19.3<plaza['lat']<19.8 and -99.35<plaza['lon']<-99.1):continue
            for fare in plaza['fares']:
                origin=by_id.get(fare['entryId'])
                if not origin or origin['id']==plaza['id']:continue
                pair=(vb_name(origin['name']),vb_name(plaza['name']))
                if len(pairs.get(pair,set()))==1:
                    fare.update(carMxn=next(iter(pairs[pair])),effective='Publicado; consultado '+checked,source='TeleVía / Viaducto Bicentenario',sourceUrl=url)
                    updated+=1
    else:
        updated=apply_columns(catalog,parsed,CEM_COLUMNS,'TeleVía / Circuito Exterior Mexiquense',url,checked)
    return updated

def apply_closed(catalog,html,url,checked,source,bounds,vehicle=None,variable=False):
    pairs={}
    parser=Tables();parser.feed(html)
    for table,context in zip(parser.tables,parser.contexts):
        if not table or not table[0]:continue
        if vehicle and (vehicle not in normalize(context) or 'REMOLQUE' in normalize(context)):continue
        if normalize(table[0][0]) not in ('ENTRADA SALIDA','ACCESO AL LIBRAMIENTO'):continue
        # Desktop matrices include all ordered entry/exit pairs. Mobile duplicates
        # are deliberately ignored, so conflicting values remain unquoted.
        if len(table[0])<3:continue
        for row in table[1:]:
            for destination,value in zip(table[0][1:],row[1:]):
                cost=money(value)
                if cost is not None:
                    origin=normalize(re.sub(r'^Entrada\s+','',row[0]))
                    pairs.setdefault((origin,normalize(destination)),set()).add(cost)
    if len(pairs)<8:raise ValueError('Closed tariff matrix format changed')
    by_id={p['id']:p for p in catalog['plazas']};updated=0
    lowlat,highlat,lowlon,highlon=bounds
    for plaza in catalog['plazas']:
        if not (lowlat<plaza['lat']<highlat and lowlon<plaza['lon']<highlon):continue
        for fare in plaza['fares']:
            origin=by_id.get(fare['entryId'])
            if not origin or origin['id']==plaza['id']:continue
            costs=pairs.get((normalize(origin['name']),normalize(plaza['name'])),set())
            if len(costs)==1 or (variable and len(costs)==2):
                fare.update(carMxn=min(costs),effective='Publicado; consultado '+checked,source=source,sourceUrl=url)
                if len(costs)>1:fare.update(carMxnMax=max(costs),effective='Varía por horario; consultado '+checked)
                else:fare.pop('carMxnMax',None)
                updated+=1
    return updated

class RangeFile(io.RawIOBase):
    def __init__(self,url):
        self.url=url;self.position=0
        _,headers=download(url,{'Range':'bytes=0-0'})
        self.length=int(headers['Content-Range'].split('/')[-1])
    def seekable(self):return True
    def tell(self):return self.position
    def seek(self,offset,whence=0):
        self.position=offset if whence==0 else self.position+offset if whence==1 else self.length+offset
        return self.position
    def read(self,size=-1):
        size=self.length-self.position if size<0 else min(size,self.length-self.position)
        if size<=0:return b''
        data,headers=download(self.url,{'Range':f'bytes={self.position}-{self.position+size-1}'})
        if len(data)!=size or 'Content-Range' not in headers:raise ValueError('Invalid ZIP range response')
        self.position+=size;return data

def new_rnc(catalog,directory):
    payload={k:None for k in ['enti','muni','loca','tema','titg','esca','form','edic','seri','clave','rango','busc','wordag','mkeys','mageo','formIncl','formExcl']}
    payload.update(prog='3221',tipoB=1,adv=False,orden=1,desc=True,pag=0,tam=100)
    body,_=download('https://www.inegi.org.mx/app/api/productos/interna_v2/componente/mapas/lista/resultados/',
        {'Content-Type':'application/json'},json.dumps(payload).encode())
    candidates=[m for m in json.loads(body)['list']['mapas'] if any(f['extension']=='GPKG' for f in m['formatos'])]
    latest=max(candidates,key=lambda m:m['edicion'])
    url='https://www.inegi.org.mx'+next(f['url']['valor'] for f in latest['formatos'] if f['extension']=='GPKG')
    source=next(s for s in catalog['sources'] if s['id']=='rnc')
    with urllib.request.urlopen(urllib.request.Request(url,method='HEAD',headers=AGENT),timeout=30) as response:etag=response.headers.get('ETag')
    if source.get('zipUrl')==url and source.get('etag')==etag:return catalog
    remote=RangeFile(url)
    with zipfile.ZipFile(remote) as archive:
        member=next(info for info in archive.infolist() if info.filename.lower().endswith('.gpkg'))
        remote.seek(member.header_offset);header=remote.read(30);values=struct.unpack('<4sHHHHHIIIHH',header)
        start=member.header_offset+30+values[9]+values[10]
    if member.compress_type!=zipfile.ZIP_DEFLATED:raise ValueError('Unknown RNC archive compression')
    ranges=[(o,min(8*1024*1024,member.compress_size-o)) for o in range(0,member.compress_size,8*1024*1024)]
    def block(part):
        offset,size=part
        data,_=download(url,{'Range':f'bytes={start+offset}-{start+offset+size-1}'})
        if len(data)!=size:raise ValueError('Incomplete RNC download')
        return data
    target=Path(directory)/'national.gpkg';decoder=zlib.decompressobj(-15);length=crc=0
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool,target.open('wb') as output:
        pending={i:pool.submit(block,ranges[i]) for i in range(min(4,len(ranges)))}
        for i in range(len(ranges)):
            data=pending.pop(i).result()
            if i+4<len(ranges):pending[i+4]=pool.submit(block,ranges[i+4])
            plain=decoder.decompress(data);length+=len(plain);crc=zlib.crc32(plain,crc);output.write(plain)
            if length>member.file_size:raise ValueError('Invalid national dataset size')
        plain=decoder.flush();length+=len(plain);crc=zlib.crc32(plain,crc);output.write(plain)
    if length!=member.file_size or crc!=member.CRC or not decoder.eof:raise ValueError('National dataset CRC mismatch')
    next_catalog=extract(target,latest['edicion'],'https://www.inegi.org.mx'+latest['url'])
    next_catalog['sources'][0].update(zipUrl=url,etag=etag)
    # Preserve the last verified operator prices if a source is temporarily down
    # on the day a newer national inventory is published.
    previous={p['id']:p for p in catalog['plazas']}
    for plaza in next_catalog['plazas']:
        old=previous.get(plaza['id'])
        if not old or normalize(old['name'])!=normalize(plaza['name']):continue
        rates={f['entryId']:f for f in old['fares'] if not f['source'].startswith('INEGI')}
        for fare in plaza['fares']:
            if fare['entryId'] in rates:fare.update(rates[fare['entryId']])
    next_catalog['sources']+= [s for s in catalog['sources'] if s['id']!='rnc']
    return next_catalog

def refresh(path=ASSET,cache=None):
    catalog=json.loads(Path(path).read_text());checked=datetime.now(timezone.utc).date().isoformat()
    if cache is None:
        try:
            with tempfile.TemporaryDirectory() as temp:catalog=new_rnc(catalog,temp)
        except Exception as error:print('RNC retained:',type(error).__name__)
    sources=[
        ('capufe','https://iave.capufe.gob.mx/assets/Doc/Tarifas-vigentes-'+checked[:4]+'.pdf','capufe-tarifas-'+checked[:4]+'.pdf',apply_capufe),
        ('cem','https://www.televia.com.mx/cobertura-y-tarifas/circuito-exterior-mexiquense','televia-cem.html',apply_televia),
        ('vb','https://www.televia.com.mx/cobertura-y-tarifas/viaducto-bicentenario','televia-vb.html',lambda c,h,u,d:apply_televia(c,h,u,d,True)),
        ('gan','https://www.televia.com.mx/cobertura-y-tarifas/grupo-autopistas-nacionales','televia-grupo-autopistas-nacionales.html',lambda c,h,u,d:apply_columns(c,tables(h),GAN_COLUMNS,'TeleVía / Grupo Autopistas Nacionales',u,d)),
        ('aun','https://www.televia.com.mx/cobertura-y-tarifas/autopista-urbana-norte','televia-autopista-urbana-norte.html',lambda c,h,u,d:apply_closed(c,h,u,d,'TeleVía / Autopista Urbana Norte',(19.37,19.46,-99.24,-99.16),variable=True)),
        ('lep','https://www.televia.com.mx/cobertura-y-tarifas/libramiento-elevado-puebla','televia-libramiento-elevado-puebla.html',lambda c,h,u,d:apply_closed(c,h,u,d,'TeleVía / Libramiento Elevado Puebla',(19.04,19.12,-98.3,-98.1),vehicle='AUTOMOVIL SENCILLO 2 EJES'))]
    successful=0
    for identity,url,filename,apply in sources:
        try:
            data=(Path(cache)/filename).read_bytes() if cache else download(url)[0]
            updated=apply(catalog,data if identity=='capufe' else data.decode(),url,checked)
            catalog['sources']=[s for s in catalog['sources'] if s['id']!=identity]+[{'id':identity,'url':url,'checked':checked,'sha256':hashlib.sha256(data).hexdigest()}]
            successful+=1;print(identity,updated,'fares verified')
        except Exception as error:print(identity,'retained:',type(error).__name__)
    if not successful:raise RuntimeError('No live tariff source was verified')
    assert len(catalog['plazas'])>=1200 and sum(len(p['fares']) for p in catalog['plazas'])>=2000
    catalog.update(revision=int(time.time()),checked=checked)
    temporary=Path(str(path)+'.tmp');temporary.write_text(json.dumps(catalog,ensure_ascii=False,separators=(',',':'))+'\n');temporary.replace(path)
    return catalog

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--cache');parser.add_argument('--output',default=str(ASSET))
    args=parser.parse_args();refresh(args.output,args.cache)
