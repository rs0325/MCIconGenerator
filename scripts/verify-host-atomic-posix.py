#!/usr/bin/env python3
"""POSIXで専用process groupを持つatomic監督Javaを期限内に回収する。"""
import argparse
import json
import os
from pathlib import Path
import signal
import subprocess
import time

parser=argparse.ArgumentParser()
parser.add_argument('--java',required=True)
parser.add_argument('--bundle',type=Path,required=True)
parser.add_argument('--inventory-sha256',required=True)
parser.add_argument('--out',type=Path,required=True)
parser.add_argument('--mode',choices=('normal','outer-deadline','parent-only-exit'),default='normal')
args=parser.parse_args()
if os.name!='posix':parser.error('POSIX専用')
out=args.out.resolve();bundle=args.bundle.resolve()
if out.exists():parser.error('--outは新規directoryが必要')
out.mkdir(parents=True)
result={'passed':False,'mode':args.mode,'inventorySha256':args.inventory_sha256}
process=None
try:
    with (out/'supervisor.stdout.log').open('w') as stdout,(out/'supervisor.stderr.log').open('w') as stderr:
        process=subprocess.Popen([args.java,'-Xmx128m','-cp','classes:lib/*','HostAtomicRecoveryProbe',str(out/'output'),str(out/'evidence'),'75',
                                  'deadline' if args.mode!='normal' else 'normal',args.inventory_sha256],cwd=bundle,stdout=stdout,stderr=stderr,start_new_session=True)
        result['supervisorPid']=process.pid
        if args.mode!='normal':
            ready=out/'evidence/deadline.stdout.log';until=time.monotonic()+10
            while not (ready.exists() and 'READY_FOR_ATOMIC_WRITE' in ready.read_text()):
                if time.monotonic()>=until:raise RuntimeError('外側期限fixtureのREADY未観測')
                time.sleep(.01)
            result['fixtureActorReady']=True
        if args.mode=='parent-only-exit':
            os.kill(process.pid,signal.SIGKILL)
            result['fixtureKilledSupervisorOnly']=True
        until=time.monotonic()+(1 if args.mode=='outer-deadline' else 90)
        while True:
            # poll/waitでreapせず、終了済みの親PIDを回収完了まで確保する。
            state=subprocess.run(['ps','-p',str(process.pid),'-o','stat='],capture_output=True,text=True,timeout=2)
            if state.returncode!=0 or not state.stdout.strip():raise RuntimeError('所有supervisorの状態を確認できない')
            if state.stdout.strip().startswith('Z'):
                result['supervisorExitObservedBeforeReap']=True;break
            if time.monotonic()>=until:raise RuntimeError('OUTER_DEADLINE')
            time.sleep(.02)
        report=json.loads((out/'evidence/report.json').read_text())
        result['passed']=report['passed'] and not report['remainingOwnedActors']
except Exception as error:
    result['failure']=str(error)
finally:
    if process is not None:
        try:
            members=subprocess.run(['ps','-axo','pid=,pgid=,stat='],capture_output=True,text=True,timeout=5)
            if members.returncode!=0:raise RuntimeError('所有groupの状態を確認できない')
            actors=[int(row[0]) for line in members.stdout.splitlines() if len(row:=line.split())==3 and int(row[1])==process.pid and int(row[0])!=process.pid]
            result['ownedActorsBeforeCleanup']=actors
            if not result.get('supervisorExitObservedBeforeReap') or actors:
                os.killpg(process.pid,signal.SIGKILL);result['killedOwnedGroup']=True
        except ProcessLookupError:pass
        except Exception as error:
            result['cleanupFailure']=str(error)
            try:os.killpg(process.pid,signal.SIGKILL);result['killedOwnedGroup']=True
            except ProcessLookupError:pass
        try:
            result['supervisorExit']=process.wait(timeout=5)
            if result['supervisorExit']!=0:result['passed']=False
        except Exception as error:result['cleanupFailure']=str(error)
        until=time.monotonic()+5
        while True:
            try:os.killpg(process.pid,0)
            except ProcessLookupError:
                result['ownedGroupRemaining']=False;break
            if time.monotonic()>=until:
                result['ownedGroupRemaining']=True;result['passed']=False;break
            time.sleep(.01)
    if result.get('killedOwnedGroup') or result.get('cleanupFailure'):result['passed']=False
    (out/'outer-report.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result))
raise SystemExit(0 if result['passed'] else 1)
