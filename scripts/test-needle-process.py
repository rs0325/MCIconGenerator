#!/usr/bin/env python3
"""針QAの期限・Docker例外・所有回収と非所有保持を合成で反証する。"""
import copy
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
from unittest.mock import patch
from qa import needle_process as process


def fixture(mode):
    with tempfile.TemporaryDirectory() as temporary:
        root=Path(temporary);cache=root/'cache';cache.mkdir();worker=root/'worker.jar';worker.write_bytes(b'qa-worker')
        folder=root/'result';folder.mkdir();directory=cache/'worker-owned';active=True;calls=[];handles=[];event_output=None
        container={'Id':'owned','Name':'/mcicon-worker-uuid','Image':'sha256:image','Config':{'Cmd':['-javaagent:/mcicon/worker.jar=/mcicon/cache/worker-owned/agent.json']},'Mounts':[{'Destination':'/mcicon/cache','Source':str(cache),'RW':True},{'Destination':'/mcicon/worker.jar','Source':str(directory/'worker-agent.jar'),'RW':False}]}
        foreign=copy.deepcopy(container);foreign.update(Id='foreign');foreign['Mounts'][0]['Source']=str(root/'other-cache')
        class Handle:
            def __init__(self,event):self.event=event;self.returncode=None;self.pid=1;self.killed=False;self.terminated=False;self.waits=0
            def poll(self):return self.returncode
            def terminate(self):
                self.terminated=True
                if mode!='events_ignore_term' or not self.event:self.returncode=-15
            def kill(self):self.killed=True;self.returncode=-9
            def wait(self,timeout):
                nonlocal active
                calls.append(('wait',timeout));self.waits+=1
                if self.returncode is None and not self.event and mode not in ('deadline','docker_timeout','inspect_exception') and (mode!='diagnostic_missing' or self.waits>1):
                    self.returncode=1 if mode=='natural_exit_one' else 0
                    if mode in ('natural_exit_one','natural_exit_zero_remaining'):return self.returncode
                    active=False
                    event_output.write((json.dumps({'Actor':{'ID':'owned','Attributes':{'exitCode':'0'}},'Action':'die'})+'\n').encode());event_output.flush()
                if self.returncode is None:raise subprocess.TimeoutExpired('mock',timeout)
                return self.returncode
        def popen(command,**kwargs):
            nonlocal event_output
            event='events' in command
            if event:event_output=kwargs['stdout']
            else:directory.mkdir();(directory/'worker-agent.jar').write_bytes(worker.read_bytes())
            handle=Handle(event);handles.append(handle);return handle
        failures=0
        def run(command,**kwargs):
            nonlocal active,failures
            calls.append((command,kwargs['timeout']));assert 0<kwargs['timeout']<=10
            if 'ps' in command:
                if mode=='docker_timeout' and failures==0:failures+=1;raise subprocess.TimeoutExpired(command,kwargs['timeout'])
                return subprocess.CompletedProcess(command,0,('owned\nforeign\n' if active else 'foreign\n'),'')
            if 'inspect' in command:
                if mode=='inspect_exception' and failures==0:failures+=1;raise ValueError('破損inspect')
                ids=command[command.index('inspect')+1:]
                selected=[c for c in [container,foreign] if c['Id'] in ids and (c['Id']!='owned' or active)]
                return subprocess.CompletedProcess(command,0 if selected else 1,json.dumps(selected),'')
            if 'rm' in command:
                assert command[-1]=='owned';active=False
                return subprocess.CompletedProcess(command,0,'','')
            raise AssertionError(command)
        clock=iter([0,241] if mode=='deadline' else [0,1,2,3,50,*range(51,100)] if mode=='diagnostic_missing' else range(100))
        with patch.object(process.time,'monotonic',side_effect=lambda:next(clock,250)):
            result=process.collect(['cli'],['docker'],cache,worker,'sha256:image',folder,'fixture',diagnostic=root/'missing-diagnostic.py' if mode=='diagnostic_missing' else None,run=run,popen=popen)
        assert all(h.poll() is not None for h in handles)
        assert not active
        if mode in ('deadline','docker_timeout','inspect_exception','natural_exit_one','natural_exit_zero_remaining'):
            assert 'failure' in result and result['removedOwnedContainers']==['owned']
        else:assert 'failure' not in result,result
        if mode=='diagnostic_missing':assert 'failure' in result['stallDiagnostic'] and 'failure' not in result
        if mode=='deadline':assert result['supervisorTimedOut']
        if mode=='events_ignore_term':assert handles[0].killed and result['eventsCleanup']['killed']
        return result


for mode in ['normal','deadline','docker_timeout','inspect_exception','events_ignore_term','diagnostic_missing','natural_exit_one','natural_exit_zero_remaining']:
    fixture(mode);print(mode,'PASS')
with tempfile.TemporaryDirectory() as temporary:
    root=Path(temporary);directory=root/'worker-owned';directory.mkdir();snapshot=directory/'worker-agent.jar';snapshot.write_bytes(b'worker')
    container={'Id':'owned','Name':'/mcicon-worker-uuid','Image':'sha256:image','Config':{'Cmd':['-javaagent:/mcicon/worker.jar=/mcicon/cache/worker-owned/agent.json']},'Mounts':[{'Destination':'/mcicon/cache','Source':str(root),'RW':True},{'Destination':'/mcicon/worker.jar','Source':str(snapshot),'RW':False}]}
    digest=hashlib.sha256(snapshot.read_bytes()).hexdigest()
    assert process.owned(container,root,digest,'sha256:image',{directory},set())
    for field in ['cache_mount','snapshot_rw','snapshot_parent','agent','image','hash']:
        changed=copy.deepcopy(container);expected=digest
        if field=='cache_mount':changed['Mounts'][0]['Source']=str(root/'foreign')
        if field=='snapshot_rw':changed['Mounts'][1]['RW']=True
        if field=='snapshot_parent':changed['Mounts'][1]['Source']=str(root/'worker-foreign'/'worker-agent.jar')
        if field=='agent':changed['Config']['Cmd']=['-javaagent:/mcicon/worker.jar=/mcicon/cache/worker-foreign/agent.json']
        if field=='image':changed['Image']='sha256:foreign'
        if field=='hash':expected='wrong'
        assert not process.owned(changed,root,expected,'sha256:image',{directory},set())
        print(field,'PASS')
child=subprocess.Popen([sys.executable,'-u','-c','import signal,time;signal.signal(signal.SIGTERM,signal.SIG_IGN);print("ready",flush=True);time.sleep(120)'],stdout=subprocess.PIPE,text=True)
try:
    assert child.stdout.readline().strip()=='ready'
    result=process.stop(child);assert result['killed'] and child.poll() is not None
finally:
    if child.poll() is None:child.kill();child.wait(timeout=5)
print('posix_term_ignore','PASS')
