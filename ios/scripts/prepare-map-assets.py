#!/usr/bin/env python3
"""Bundle the identical map renderer used by Android; API keys are supplied at build time."""
from pathlib import Path
import shutil, urllib.request
root = Path(__file__).resolve().parents[2]
source = root / 'app/src/main/assets'
target = root / 'ios/MapAssets'
target.mkdir(parents=True, exist_ok=True)
for item in source.iterdir():
    if item.is_file() and item.suffix in ('.js', '.html', '.json') and item.name != 'traffic-config.js':
        shutil.copyfile(item, target / item.name)
(target / 'traffic-config.js').write_text('window.GPS3D_CONFIG=window.GPS3D_CONFIG||{};\n')
(target / 'vendor').mkdir(exist_ok=True)
for name in ('maplibre-gl.js', 'maplibre-gl.css'):
    dest = target / 'vendor' / name
    if dest.exists() and dest.stat().st_size > 1024:
        continue
    for host in ('https://cdn.jsdelivr.net/npm/', 'https://unpkg.com/'):
        try:
            with urllib.request.urlopen(host + 'maplibre-gl@5.24.0/dist/' + name, timeout=45) as response:
                data = response.read()
            assert len(data) > 1024
            dest.write_bytes(data)
            break
        except Exception:
            if host.endswith('unpkg.com/'):
                raise
print('Bundled shared map renderer and toll catalog')
