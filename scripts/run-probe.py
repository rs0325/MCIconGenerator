#!/usr/bin/env python3
"""Run the isolated vanilla client and agent under the local rendering container."""
import argparse
import json
import os
import subprocess
from pathlib import Path

root=Path('/workspace/.local/probe')
parser=argparse.ArgumentParser()
parser.add_argument('version')
parser.add_argument('--java',default='/opt/java/java21/bin/java')
args=parser.parse_args()
base=root/args.version
meta=json.loads((base/'version.json').read_text())
config={'version':args.version}
if args.version.startswith('1.13'):
    config.update(profile='legacy-1.13',hookClass='net.minecraft.client.render.GameRenderer',tickMethod='render',hookDescriptor='(FJZ)V')
elif args.version=='26.3':
    config['profile']='gui-26.3'
if (base/'mappings.txt').exists(): config['mappings']=str(base/'mappings.txt')
(base/'agent.json').write_text(json.dumps(config))
game=base/'game'
game.mkdir(exist_ok=True)
(game/'options.txt').write_text('soundCategory_master:0.0\nguiScale:1\nmaxFps:30\nfullscreen:false\nonboardAccessibility:false\n')
cp=[base/'client.jar']+[root/p for p in json.loads((base/'classpath-linux.json').read_text())]
agent=next(Path('/workspace/worker/build/libs').glob('*-agent.jar'))
command=[args.java,'-Xmx2G','-Djava.awt.headless=true',
 '-Djava.library.path='+str(base/'natives-linux'),
 '-javaagent:'+str(agent)+'='+str(base/'agent.json'),
 '-cp',os.pathsep.join(map(str,cp)),meta['mainClass'],
 '--username','MCIconGenerator','--version',args.version,
 '--gameDir',str(game),'--assetsDir',str(root/'assets'),
 '--assetIndex',meta['assetIndex']['id'],'--accessToken','0',
 '--uuid','00000000000000000000000000000000','--userType','legacy',
 '--width','320','--height','240','--demo','--disableMultiplayer','--disableChat']
raise SystemExit(subprocess.call(command))
