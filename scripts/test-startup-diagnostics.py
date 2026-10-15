#!/usr/bin/env python3
"""nativeを起動せず診断wrapperの転送・段階・終了記録を検証する。"""
import json
import importlib.util
import os
from pathlib import Path
import subprocess
import signal
import time
import tempfile
import threading
import unittest
from unittest.mock import patch

repo=Path(__file__).resolve().parents[1]

class TraceTest(unittest.TestCase):
    def driver_fixture(self,root,cli_body,docker_body):
        distribution=root/'cli'
        for name in ('bin','lib','worker'):(distribution/name).mkdir(parents=True)
        (distribution/'lib/core-fixture.jar').write_bytes(b'fixture')
        (distribution/'worker/worker-agent.jar').write_bytes(b'fixture')
        cli=distribution/'bin/mcicon';cli.write_text('#!/usr/bin/env python3\n'+cli_body);cli.chmod(0o755)
        docker=root/'docker';docker.write_text('#!/usr/bin/env python3\n'+docker_body);docker.chmod(0o755)
        cache=root/'cache';(cache/'versions/1.21.4').mkdir(parents=True)
        (cache/'versions/1.21.4/client.jar').write_bytes(b'fixture')
        spec=importlib.util.spec_from_file_location('startup_driver',repo/'scripts/verify-startup-diagnostics.py')
        module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        argv=['verify-startup-diagnostics.py','--cache',str(cache),'--out',str(root/'out'),'--cli',str(cli),'--runs','1','--delayed-observation']
        return module,argv

    def test_initialization_deadlines_reap_owned_process(self):
        for mode,expected_timeout in [('image',15),('java',10)]:
            with self.subTest(mode=mode),tempfile.TemporaryDirectory(prefix='mcicon-init-test-') as temporary:
                root=Path(temporary);pidfile=root/'hung.pid'
                body="import os,sys,time\nfrom pathlib import Path\nPath(os.environ['FIXTURE_PID']).write_text(str(os.getpid()))\nwhile True:time.sleep(.1)\n"
                module,argv=self.driver_fixture(root,'raise SystemExit(1)\n',body if mode=='image' else "print('fixture-image')\n")
                if mode=='java':
                    java=root/'java';java.write_text('#!/usr/bin/env python3\n'+body);java.chmod(0o755)
                original=module.subprocess.run
                def bounded(*args,**kwargs):
                    command=args[0]
                    if (mode=='image' and 'image' in command) or (mode=='java' and command==['java','-version']):
                        self.assertEqual(expected_timeout,kwargs['timeout']);kwargs['timeout']=2
                    return original(*args,**kwargs)
                env=dict(os.environ,PATH=str(root)+os.pathsep+os.environ['PATH'],FIXTURE_PID=str(pidfile))
                with patch.dict(os.environ,env,clear=True),patch('sys.argv',argv),patch.object(module.subprocess,'run',bounded):
                    with self.assertRaises(subprocess.TimeoutExpired):module.main()
                self.assertEqual('TimeoutExpired',json.loads((root/'out/report.json').read_text())['initializationFailure'])
                with self.assertRaises(ProcessLookupError):os.kill(int(pidfile.read_text()),0)

    def test_driver_waits_for_inflight_monitor_before_reading_results(self):
        with tempfile.TemporaryDirectory(prefix='mcicon-monitor-test-') as temporary:
            root=Path(temporary)
            module,argv=self.driver_fixture(root,"import os,time\nfrom pathlib import Path\nwhile not Path(os.environ['FIXTURE_MONITOR']).exists():time.sleep(.01)\nraise SystemExit(1)\n",
                "import os,sys,time\nfrom pathlib import Path\nif 'ps' in sys.argv:\n Path(os.environ['FIXTURE_MONITOR']).write_text('started')\n time.sleep(.3)\n Path(os.environ['FIXTURE_FINISHED']).write_text('finished')\nelif 'events' in sys.argv:\n while True:time.sleep(.1)\nelse:print('fixture-image')\n")
            env=dict(os.environ,PATH=str(root)+os.pathsep+os.environ['PATH'],FIXTURE_MONITOR=str(root/'monitor.started'),FIXTURE_FINISHED=str(root/'monitor.finished'))
            with patch.dict(os.environ,env,clear=True),patch('sys.argv',argv):
                with self.assertRaises(SystemExit) as error:module.main()
            self.assertEqual(1,error.exception.code)
            self.assertTrue((root/'monitor.finished').exists())
            self.assertEqual('',(root/'out/0/samples.jsonl').read_text())
            self.assertEqual([],json.loads((root/'out/report.json').read_text())['jobs'][0]['monitorErrors'])

    def test_driver_refuses_to_consume_results_while_monitor_is_alive(self):
        with tempfile.TemporaryDirectory(prefix='mcicon-monitor-join-test-') as temporary:
            root=Path(temporary);release=threading.Event();threads=[]
            module,argv=self.driver_fixture(root,'raise SystemExit(1)\n',"import sys,time\nif 'events' in sys.argv:\n while True:time.sleep(.1)\nelse:print('fixture-image')\n")
            def stalled_monitor(process,folder,docker,cache,delayed,stop):
                threads.append(threading.current_thread());stop.wait(5);release.wait(5)
            original=threading.Thread.join
            def short_join(thread,timeout=None):
                self.assertEqual(20,timeout);return original(thread,.05)
            env=dict(os.environ,PATH=str(root)+os.pathsep+os.environ['PATH'])
            try:
                with patch.dict(os.environ,env,clear=True),patch('sys.argv',argv),patch.object(module,'monitor',stalled_monitor),patch.object(threading.Thread,'join',short_join):
                    with self.assertRaisesRegex(RuntimeError,'監視thread'):module.main()
                report=json.loads((root/'out/report.json').read_text())
                self.assertIn('監視thread',report['monitorFailure']);self.assertEqual([],report['jobs'])
            finally:
                release.set()
                for thread in threads:original(thread,2)

    def fixture(self,body):
        temporary=tempfile.TemporaryDirectory(prefix='mcicon-startup-test-');self.addCleanup(temporary.cleanup)
        root=Path(temporary.name);fake=root/'docker';fake.write_text('#!/usr/bin/env python3\n'+body);fake.chmod(0o755)
        env=dict(os.environ,MCICON_REAL_DOCKER=str(fake),MCICON_STARTUP_TRACE=str(root))
        command=['python3',str(repo/'scripts/qa/trace-docker.py'),'--context','synthetic','run','--name','mcicon-worker-synthetic','-v',str(root)+':/mcicon/cache','-v',str(root/'worker/worker-agent.jar')+':/mcicon/worker.jar:ro']
        return root,env,command
    def test_java_diagnostic_is_transparent_without_worker_observation(self):
        cases=[('mcicon-java-diagnostic',False),('mcicon-worker-java-diagnostic',False),('mcicon-java-diagnostic',True),(None,False),('mcicon-doctor-synthetic',False)]
        for name,worker_mount in cases:
            with self.subTest(name=name,worker_mount=worker_mount):
                payload=b'java version\n\x00\xff'
                root,env,command=self.fixture('import sys\nassert sys.stdin.buffer.read(5)==b"input"\nsys.stderr.buffer.write('+repr(b'java diagnostic\n')+');sys.stderr.buffer.flush()\nsys.stdout.buffer.write('+repr(payload)+');sys.stdout.buffer.flush();sys.exit(17)\n')
                if name is None:
                    index=command.index('--name');del command[index:index+2]
                else:command[command.index('--name')+1]=name
                if not worker_mount:del command[-2:]
                command+=['--entrypoint','/opt/java/java21/bin/java','fixture-image','-version']
                result=subprocess.run(command,input=b'input',env=env,capture_output=True,timeout=10)
                self.assertEqual(result.returncode,17,result.stderr);self.assertEqual(result.stdout,payload)
                self.assertEqual(result.stderr,b'java diagnostic\n')
                self.assertFalse((root/'docker-run.json').exists());self.assertFalse((root/'protocol.stdout').exists())
                calls=list(root.glob('docker-call-*.json'));self.assertEqual(len(calls),1)
                record=json.loads(calls[0].read_text());self.assertEqual(record['exitCode'],17)
                self.assertNotIn('runDirectory',record);self.assertNotIn('readyMonotonicNs',record)

    def test_ready_bytes_and_pid_mapping(self):
        root,env,command=self.fixture('''import os,sys,time
assert sys.stdin.buffer.read(5)==b'input'
sys.stderr.write('game initialisation\\n');sys.stderr.flush()
sys.stdout.buffer.write(b'{"type":"rea');sys.stdout.buffer.flush();time.sleep(.05)
sys.stdout.buffer.write(b'dy","protocolVersion":1,"renderer":"fixture"}\\n');sys.stdout.buffer.flush()
''')
        result=subprocess.run(command,input=b'input',env=env,capture_output=True,timeout=10)
        expected=b'{"type":"ready","protocolVersion":1,"renderer":"fixture"}\n'
        self.assertEqual(result.returncode,0,result.stderr);self.assertEqual(result.stdout,expected)
        self.assertEqual((root/'protocol.stdout').read_bytes(),expected)
        self.assertEqual(result.stderr,b'game initialisation\n')
        record=json.loads((root/'docker-run.json').read_text())
        self.assertEqual(record['containerName'],'mcicon-worker-synthetic')
        self.assertEqual(record['runDirectory'],str(root/'worker'))
        self.assertNotEqual(record['dockerPid'],record['wrapperPid'])
        self.assertLessEqual(record['startedMonotonicNs'],record['firstStdoutMonotonicNs'])
        self.assertLessEqual(record['firstStdoutMonotonicNs'],record['readyMonotonicNs'])
        self.assertLessEqual(record['readyMonotonicNs'],record['finishedMonotonicNs'])

    def test_stage_agent_preserves_original_arguments_and_protocol(self):
        payload=b'{"type":"ready","protocolVersion":1,"renderer":"fixture"}\n'
        root,env,command=self.fixture('import sys\nsys.stdout.buffer.write('+repr(payload)+');sys.stdout.buffer.flush()\n')
        (root/'worker').mkdir()
        agent=root/'stage.jar';agent.write_bytes(b'QA agent')
        env['MCICON_STARTUP_STAGE_AGENT']=str(agent)
        command+=['fixture-image','java','-javaagent:/mcicon/worker.jar=/mcicon/cache/worker/agent.json','-cp','original','Main']
        original=command[2:]
        result=subprocess.run(command,env=env,capture_output=True,timeout=10)
        self.assertEqual(result.returncode,0,result.stderr);self.assertEqual(result.stdout,payload)
        record=json.loads((root/'docker-run.json').read_text())
        self.assertEqual(original,record['originalArguments'])
        extra='-javaagent:/mcicon/cache/worker/startup-stage.jar=/mcicon/cache/worker/startup-stages.tsv'
        changed=list(original);changed.insert(original.index('-cp'),extra)
        self.assertEqual(changed,record['arguments'])
        self.assertEqual(b'QA agent',(root/'worker/startup-stage.jar').read_bytes())
        self.assertEqual(payload,(root/'protocol.stdout').read_bytes())

    def test_stage_agent_refuses_ambiguous_original_agent(self):
        root,env,command=self.fixture("raise AssertionError('Dockerを起動してはいけない')\n")
        (root/'worker').mkdir()
        agent=root/'stage.jar';agent.write_bytes(b'QA agent')
        env['MCICON_STARTUP_STAGE_AGENT']=str(agent)
        result=subprocess.run(command,env=env,capture_output=True,timeout=10)
        self.assertNotEqual(0,result.returncode)
        self.assertIn('旧worker agentの引数が一意ではありません',result.stderr.decode())
        self.assertFalse((root/'docker-run.json').exists())
    def test_invalid_protocol_and_exit_are_preserved(self):
        root,env,command=self.fixture("import sys\nsys.stdout.buffer.write(b'invalid protocol\\n');sys.stdout.buffer.flush();sys.exit(7)\n")
        result=subprocess.run(command,input=b'',env=env,capture_output=True,timeout=10)
        self.assertEqual(result.returncode,7);self.assertEqual(result.stdout,b'invalid protocol\n')
        record=json.loads((root/'docker-run.json').read_text())
        self.assertEqual(record['exitCode'],7);self.assertEqual(record['invalidProtocolLines'],1)
        self.assertNotIn('readyMonotonicNs',record)

    def test_non_object_json_bytes_and_exit_are_preserved(self):
        for payload in (b'[]\n',b'42\n',b'null\n',b'"text"\n'):
            with self.subTest(payload=payload):
                root,env,command=self.fixture('import sys\nsys.stdout.buffer.write('+repr(payload)+');sys.stdout.buffer.flush();sys.exit(7)\n')
                result=subprocess.run(command,input=b'',env=env,capture_output=True,timeout=10)
                self.assertEqual(result.returncode,7,result.stderr);self.assertEqual(result.stdout,payload)
                self.assertEqual((root/'protocol.stdout').read_bytes(),payload)
                record=json.loads((root/'docker-run.json').read_text())
                self.assertEqual(record['invalidProtocolLines'],1);self.assertEqual(record['exitCode'],7)
                self.assertNotIn('readyMonotonicNs',record)

    def test_surrogate_renderer_bytes_and_exit_are_preserved(self):
        payload=b'{"type":"ready","protocolVersion":1,"renderer":"\\ud800"}\n'
        root,env,command=self.fixture('import sys\nsys.stdout.buffer.write('+repr(payload)+');sys.stdout.buffer.flush();sys.exit(9)\n')
        result=subprocess.run(command,input=b'',env=env,capture_output=True,timeout=10)
        self.assertEqual(result.returncode,9,result.stderr);self.assertEqual(result.stdout,payload)
        self.assertEqual((root/'protocol.stdout').read_bytes(),payload)
        record=json.loads((root/'docker-run.json').read_text())
        self.assertEqual(record['renderer'],chr(0xd800));self.assertEqual(record['exitCode'],9)
        self.assertIn('readyMonotonicNs',record)

    def test_broken_pipe_child_ignoring_sigterm_is_bounded(self):
        root,env,command=self.fixture("import signal,sys,time\nsignal.signal(signal.SIGTERM,signal.SIG_IGN)\nsignal.signal(signal.SIGUSR1,lambda *_: (sys.stdout.buffer.write(b'again'),sys.stdout.buffer.flush()))\nsys.stdout.buffer.write(b'hello');sys.stdout.buffer.flush()\nwhile True:time.sleep(.1)\n")
        process=subprocess.Popen(command,env=env,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        child=None
        try:
            self.assertEqual(process.stdout.read(5),b'hello')
            child=json.loads((root/'docker-run.json').read_text())['dockerPid']
            process.stdout.close()
            # 次の書込みでBrokenPipeを発生させる。
            os.kill(child,signal.SIGUSR1)
            process.wait(timeout=6)
            self.assertNotEqual(process.returncode,0)
            self.assertTrue(json.loads((root/'docker-run.json').read_text()).get('forwardingBrokenPipe'))
            with self.assertRaises(ProcessLookupError):os.kill(child,0)
        finally:
            if child:
                try:os.kill(child,signal.SIGKILL)
                except ProcessLookupError:pass
            if process.poll() is None:process.kill();process.wait(timeout=3)
            process.stdin.close();process.stderr.close()

    def test_events_child_ignoring_sigterm_is_bounded(self):
        with tempfile.TemporaryDirectory(prefix='mcicon-events-test-') as temporary:
            root=Path(temporary);distribution=root/'cli';(distribution/'bin').mkdir(parents=True);(distribution/'lib').mkdir();(distribution/'worker').mkdir()
            (distribution/'lib/core-fixture.jar').write_bytes(b'fixture');(distribution/'worker/worker-agent.jar').write_bytes(b'fixture')
            cli=distribution/'bin/mcicon';cli.write_text("#!/usr/bin/env python3\nimport os,time\nfrom pathlib import Path\nwhile not Path(os.environ['MCICON_EVENT_PID_FILE']).exists():time.sleep(.01)\nraise SystemExit(1)\n");cli.chmod(0o755)
            fake=root/'docker';fake.write_text("#!/usr/bin/env python3\nimport os,signal,sys,time\nfrom pathlib import Path\nif 'events' in sys.argv:\n signal.signal(signal.SIGTERM,signal.SIG_IGN)\n Path(os.environ['MCICON_EVENT_PID_FILE']).write_text(str(os.getpid()))\n while True:time.sleep(.1)\nelif 'inspect' in sys.argv:print('sha256:'+'0'*64)\n");fake.chmod(0o755)
            cache=root/'cache';(cache/'versions/1.21.4').mkdir(parents=True);(cache/'versions/1.21.4/client.jar').write_bytes(b'fixture')
            pidfile=root/'events.pid';env=dict(os.environ,PATH=str(root)+os.pathsep+os.environ['PATH'],MCICON_EVENT_PID_FILE=str(pidfile))
            child=None
            try:
                result=subprocess.run(['python3',str(repo/'scripts/verify-startup-diagnostics.py'),'--cache',str(cache),'--out',str(root/'out'),'--cli',str(cli),'--runs','1'],env=env,capture_output=True,text=True,timeout=20)
                child=int(pidfile.read_text())
                self.assertEqual(result.returncode,1)
                self.assertNotIn('TimeoutExpired',result.stderr)
                self.assertTrue((root/'out/report.json').exists())
                with self.assertRaises(ProcessLookupError):os.kill(child,0)
            finally:
                if child is None and pidfile.exists():child=int(pidfile.read_text())
                if child:
                    try:os.kill(child,signal.SIGKILL)
                    except ProcessLookupError:pass

    def test_jfr_preserves_original_cause(self):
        spec=importlib.util.spec_from_file_location('diagnostics',repo/'scripts/verify-startup-diagnostics.py')
        module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory(prefix='mcicon-jfr-test-') as temporary:
            root=Path(temporary);settings=root/'exceptions.jfc';settings.write_text(module.JFR_SETTINGS)
            source=root/'CauseFixture.java';source.write_text('public class CauseFixture { public static void main(String[] args) { try { throw new java.io.IOException("元例外"); } catch(java.io.IOException cause) { throw new RuntimeException("外側",cause); } } }')
            subprocess.run(['javac','--release','21','-d',str(root),str(source)],capture_output=True,check=True)
            result=subprocess.run(['java','-XX:StartFlightRecording=filename='+str(root/'host.jfr')+',settings='+str(settings)+',dumponexit=true','-Xlog:jfr+startup=off','-cp',str(root),'CauseFixture'],capture_output=True,timeout=15)
            self.assertEqual(result.returncode,1);self.assertFalse(result.stdout)
            events=json.loads(subprocess.check_output(['jfr','print','--json','--events','jdk.JavaExceptionThrow',str(root/'host.jfr')],timeout=15))['recording']['events']
            causes=[event['values'] for event in events if event['values'].get('message')=='元例外']
            self.assertTrue(causes)
            self.assertTrue(any(frame['method']['type']['name']=='CauseFixture' for cause in causes for frame in cause['stackTrace']['frames']))

    def test_delayed_observation_records_missing_jfr_as_intentional(self):
        with tempfile.TemporaryDirectory(prefix='mcicon-delayed-test-') as temporary:
            root=Path(temporary);distribution=root/'cli'
            for name in ('bin','lib','worker'):(distribution/name).mkdir(parents=True)
            (distribution/'lib/core-fixture.jar').write_bytes(b'fixture')
            (distribution/'worker/worker-agent.jar').write_bytes(b'fixture')
            cli=distribution/'bin/mcicon'
            cli.write_text("#!/usr/bin/env python3\nimport os\nfrom pathlib import Path\nPath(os.environ['MCICON_OPTIONS_FILE']).write_text(os.environ['JAVA_OPTS'])\nraise SystemExit(1)\n");cli.chmod(0o755)
            fake=root/'docker'
            fake.write_text("#!/usr/bin/env python3\nimport sys,time\nif 'events' in sys.argv:\n while True:time.sleep(.1)\nelif 'inspect' in sys.argv:print('sha256:'+'0'*64)\n");fake.chmod(0o755)
            cache=root/'cache';(cache/'versions/1.21.4').mkdir(parents=True)
            (cache/'versions/1.21.4/client.jar').write_bytes(b'fixture')
            env=dict(os.environ,PATH=str(root)+os.pathsep+os.environ['PATH'],JAVA_OPTS='-Dfixture=true',MCICON_OPTIONS_FILE=str(root/'options.txt'))
            result=subprocess.run(['python3',str(repo/'scripts/verify-startup-diagnostics.py'),'--cache',str(cache),'--out',str(root/'out'),'--cli',str(cli),'--runs','1','--delayed-observation'],env=env,capture_output=True,text=True,timeout=20)
            self.assertEqual(result.returncode,1,result.stderr)
            self.assertEqual((root/'options.txt').read_text(),'-Dfixture=true')
            report=json.loads((root/'out/report.json').read_text())
            self.assertFalse(report['hostJfrEnabled']);self.assertEqual(report['threadDumpSeconds'],[45,120,165])
            self.assertEqual(report['jobs'][0]['monitorErrors'],[])
            self.assertFalse((root/'out/0/host.jfr').exists())
            self.assertFalse(list((root/'out/0').glob('*-threads-*.log')))

if __name__=='__main__':unittest.main()
