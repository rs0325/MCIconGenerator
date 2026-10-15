#!/usr/bin/env python3
"""ビルド済みCLI・JDK21で代理socketと終了済みCLIのcleanupを合成検証する。"""
import importlib.util
from pathlib import Path
import socket
import tempfile
import os
import subprocess
import unittest

spec=importlib.util.spec_from_file_location('disconnect',Path(__file__).with_name('verify-docker-disconnect.py'))
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)

class ProxyTest(unittest.TestCase):
    def test_cut_keeps_new_connections(self):
        with tempfile.TemporaryDirectory(prefix='mcicon-proxy-test-') as temporary:
            root=Path(temporary)
            server=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM);server.bind(str(root/'server'));server.listen();server.settimeout(3)
            proxy=module.Proxy(root/'proxy',str(root/'server'))
            try:
                client=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM);client.settimeout(3);client.connect(str(root/'proxy'))
                remote,_=server.accept();remote.settimeout(3)
                client.sendall(b'hello');self.assertEqual(remote.recv(5),b'hello')
                remote.sendall(b'ready');self.assertEqual(client.recv(5),b'ready')
                proxy.arm.set();client.sendall(b'{"command":');client.sendall(b'"render"}\n')
                self.assertTrue(proxy.cut.wait(3));self.assertEqual(client.recv(1),b'')
                # render prefixは届いても完全な要求と応答は届かない。
                self.assertNotIn(b'"render"',remote.recv(1024))
                self.assertEqual(remote.recv(1),b'')
                client.close();remote.close()
                cleanup=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM);cleanup.settimeout(3);cleanup.connect(str(root/'proxy'))
                fresh,_=server.accept();fresh.settimeout(3)
                cleanup.sendall(b'DELETE owned');self.assertEqual(fresh.recv(12),b'DELETE owned')
                fresh.sendall(b'204');self.assertEqual(cleanup.recv(3),b'204')
                cleanup.close();fresh.close()
            finally:proxy.close();server.close()

    def test_exited_cli_requests_container_cleanup(self):
        repo=Path(__file__).resolve().parents[1]
        distribution=repo/'cli/build/install/mcicon'
        classpath=os.pathsep.join(str(path) for path in sorted((distribution/'lib').glob('*.jar')))
        with tempfile.TemporaryDirectory(prefix='mcicon-cli-test-') as temporary:
            root=Path(temporary);classes=root/'classes';classes.mkdir();cache=root/'cache';cache.mkdir()
            executable=root/'docker'
            executable.write_text("""#!/usr/bin/env python3
import json,os,sys
from pathlib import Path
if 'inspect' in sys.argv:print('sha256:'+'0'*64)
elif 'run' in sys.argv and '--entrypoint' in sys.argv:print('openjdk version \"21.0.9\"')
elif 'run' in sys.argv:
 Path(os.environ['MCICON_FAKE_CACHE'],'owned').write_text(sys.argv[sys.argv.index('--name')+1])
 print(json.dumps({'type':'ready','protocolVersion':1,'animationFrameZero':True,'contextProfile':'canonical-v1','renderer':'synthetic'}),flush=True)
 sys.stdin.readline()
 sys.exit(1)
elif 'rm' in sys.argv:Path(os.environ['MCICON_FAKE_CACHE'],'removed').write_text(sys.argv[-1])
else:sys.exit(2)
""")
            executable.chmod(0o755)
            subprocess.run(['javac','--release','21','-cp',classpath,'-d',str(classes),str(repo/'scripts/qa/WorkerDisconnectProbe.java')],check=True)
            env=dict(os.environ,PATH=str(root)+os.pathsep+os.environ['PATH'],MCICON_FAKE_CACHE=str(cache))
            result=subprocess.run(['java','-cp',str(classes)+os.pathsep+classpath,'WorkerDisconnectProbe',str(cache),str(distribution/'worker/worker-agent.jar')],env=env,text=True,capture_output=True,timeout=30)
            self.assertEqual(result.returncode,0,result.stderr)

if __name__=='__main__':unittest.main()
