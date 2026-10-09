"""Build the small Android catalog from INEGI/IMT's public national GeoPackage."""
import argparse
import json
import math
import sqlite3
import struct
from pathlib import Path

RNC_URL = "https://www.inegi.org.mx/app/biblioteca/ficha.html?upc=794551163030"

def coordinates(blob):
    envelope = (blob[3] >> 1) & 7
    offset = 8 + {0: 0, 1: 32, 2: 48, 3: 48, 4: 64}[envelope]
    endian = "<" if blob[offset] == 1 else ">"
    kind = struct.unpack_from(endian + "I", blob, offset + 1)[0]
    if kind == 1:
        return [struct.unpack_from(endian + "dd", blob, offset + 5)]
    if kind == 2:
        count = struct.unpack_from(endian + "I", blob, offset + 5)[0]
        return [struct.unpack_from(endian + "dd", blob, offset + 9 + i * 16) for i in range(count)]
    raise ValueError("Unexpected official geometry")

def road_directions(connection, lon, lat):
    sx = 111320 * math.cos(math.radians(lat))
    result = []
    rows = connection.execute("""SELECT r.geom FROM red_vial r JOIN rtree_red_vial_geom t ON t.id=r.fid
        WHERE t.minx<? AND t.maxx>? AND t.miny<? AND t.maxy>? AND r.CIRCULA='Un sentido' AND r.PEAJE='Si'""",
        (lon + .0004, lon - .0004, lat + .0004, lat - .0004))
    for row in rows:
        points = coordinates(row[0]); best = None
        for a, b in zip(points, points[1:]):
            dx=(b[0]-a[0])*sx;dy=(b[1]-a[1])*110540
            px=(lon-a[0])*sx;py=(lat-a[1])*110540
            fraction=max(0,min(1,(px*dx+py*dy)/max(1e-8,dx*dx+dy*dy)))
            distance=math.hypot(px-fraction*dx,py-fraction*dy)
            if best is None or distance<best[0]: best=(distance,(math.degrees(math.atan2(dx,dy))+360)%360)
        if best and best[0]<8: result.append(best)
    # Crossings and the opposite carriageway must not determine a plaza's direction.
    if not result: return []
    nearest=min(d for d,_ in result)
    return sorted(set(round(b,1) for d,b in result if d<=nearest+1.5))

def extract(path, edition=2025, source=RNC_URL):
    connection=sqlite3.connect("file:"+str(Path(path).resolve())+"?mode=ro",uri=True)
    connection.row_factory=sqlite3.Row
    rates={}
    for row in connection.execute("SELECT * FROM tarifas"):
        if row['T_AUTO'] is None or not math.isfinite(row['T_AUTO']) or row['T_AUTO']<0: continue
        rates.setdefault(row['ID_PLAZA'],[]).append({"entryId":row['ID_PLAZA_E'],"carMxn":row['T_AUTO'],
            "effective":"Inventario actualizado "+row['FECHA_ACT'][:10],"source":"INEGI / IMT RNC "+str(edition),"sourceUrl":source})
    plazas=[]
    for row in connection.execute("SELECT * FROM plaza_cobro ORDER BY ID_PLAZA"):
        lon,lat=coordinates(row['geom'])[0]
        plazas.append({"id":row['ID_PLAZA'],"name":row['NOMBRE'],"lat":round(lat,7),"lon":round(lon,7),
            "operator":row['ADMINISTRA'],"section":row['SECCION'],"subsection":row['SUBSECCION'],
            "mode":row['MODALIDAD'],"role":row['FUNCIONAL'],"bearings":road_directions(connection,lon,lat),
            "fares":rates.get(row['ID_PLAZA'],[])})
    connection.close()
    assert len(plazas)>1200 and len({p['id'] for p in plazas})==len(plazas)
    return {"schema":2,"revision":1,"sources":[{"id":"rnc","edition":edition,"url":source,
        "zipUrl":"https://www.inegi.org.mx/contenidos/productos/prod_serv/contenidos/espanol/bvinegi/productos/geografia/caminos/2025/794551163030_gpk.zip",
        "etag":'"24a14ddcba6ddc1:0"'}],"vehicle":"Auto de 2 ejes, sin remolque","plazas":plazas}

if __name__=="__main__":
    parser=argparse.ArgumentParser();parser.add_argument("geopackage");parser.add_argument("output")
    args=parser.parse_args();catalog=extract(args.geopackage)
    Path(args.output).write_text(json.dumps(catalog,ensure_ascii=False,separators=(',',':'))+"\n")
    print(len(catalog['plazas']),"national plaza records;",sum(len(p['fares']) for p in catalog['plazas']),"car fares")
