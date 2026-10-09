import unittest
from update_tolls import apply_televia, apply_closed, normalize

class SourceMappingTest(unittest.TestCase):
    def test_same_name_on_another_road_is_not_overwritten(self):
        headers=['T0 Jorobas','T1 Tultepec','T2 ConMex','T3 Nabor Carrillo destino Puebla',
                 'T3 Nabor Carrillo destino Bordo','T7 Texcoco','T4 Chalco','A31-A32 Ent. Tultepec']
        costs=[162,113,80,50,35,49,116,94]
        html='<table><tr><th>Clasificación</th>'+''.join('<th>'+h+'</th>' for h in headers)+'</tr><tr><td>Automóvil</td>'+''.join('<td>$'+str(c)+'</td>' for c in costs)+'</tr></table>'
        def plaza(id,name,cost):
            return {'id':id,'name':name,'fares':[{'entryId':id,'carMxn':cost}]}
        catalog={'plazas':[plaza(671,'Chalco',25),plaza(1602,'Chalco',116),plaza(704,'Tultepec',94)]}
        self.assertEqual(2,apply_televia(catalog,html,'https://source','2026-10-09'))
        self.assertEqual([25,116,94],[p['fares'][0]['carMxn'] for p in catalog['plazas']])
        self.assertNotIn('source',catalog['plazas'][0]['fares'][0])

    def test_closed_matrix_selects_car_class_and_preserves_scheduled_range(self):
        names=[f'Salida {i}' for i in range(8)]
        def matrix(cost):
            return '<table><tr><td>Acceso al Libramiento</td>'+''.join('<td>'+n+'</td>' for n in names)+'</tr><tr><td>Entrada Entrada norte</td>'+''.join('<td>$'+str(cost)+'</td>' for n in names)+'</tr></table>'
        html='Motos / Eje excedente sencillo'+matrix(20)+'Automóvil sencillo 2 ejes'+matrix(40)
        catalog={'plazas':[{'id':1,'name':'Entrada norte','lat':19.1,'lon':-98.2,'fares':[]},
            {'id':2,'name':'Salida 0','lat':19.1,'lon':-98.2,'fares':[{'entryId':1,'carMxn':1}]}]}
        apply_closed(catalog,html,'https://source','2026-10-09','Concesión',(19,20,-99,-98),vehicle='AUTOMOVIL SENCILLO 2 EJES')
        self.assertEqual(40,catalog['plazas'][1]['fares'][0]['carMxn'])
        html=matrix(40)+matrix(60)
        apply_closed(catalog,html,'https://source','2026-10-09','Concesión',(19,20,-99,-98),variable=True)
        self.assertEqual(60,catalog['plazas'][1]['fares'][0]['carMxnMax'])

if __name__=='__main__': unittest.main()
