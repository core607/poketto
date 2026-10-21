"""HELLO and configured lease bounds against the native reference, including request capacity."""
import importlib.util,json,tempfile
from pathlib import Path
spec=importlib.util.spec_from_file_location('comparison',Path(__file__).with_name('compare.py'))
c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
cases=0
for mode in ('threads','pool'):
    key=c.Ed25519PrivateKey.generate();settings=c.fixture(key)
    settings.update(leaseSeconds=9,renewAfterSeconds=3,maxRequests=2)
    python=c.python_service(key,settings);python.config.update(leaseSeconds=9,renewAfterSeconds=3)
    with tempfile.TemporaryDirectory() as directory:
        path=str(Path(directory)/'worker.sock');rust=c.RustServer(mode,path,settings)
        try:
            def check(raw):
                global cases
                try:expected=python.handle(c.json_read(raw))
                except Exception:expected={'ok':False,'code':'INVALID_FRAME'}
                actual=c.exchange(path,raw);assert actual==expected,(mode,actual,expected)
                cases+=1;return actual
            def hello(value):return check(json.dumps(value,separators=(',',':')).encode())
            for _ in range(3):
                for version in (1,True,1.0):assert hello({'version':version,'operation':'HELLO'})['ok']
            for version in (0,2,False,None,'1'):assert hello({'version':version,'operation':'HELLO'})['code']=='INVALID_REQUEST'
            assert hello({'version':1,'operation':'HELLO','extra':True})['code']=='INVALID_REQUEST'
            assert check(b'{"version":1,"operation":"HELLO","version":1}')['code']=='INVALID_FRAME'
            def signed(seconds):
                payload=c.payload(settings);payload['expiresAt']=payload['issuedAt']+seconds
                return check(c.envelope(key,payload))
            assert signed(9)['ok']
            assert signed(10)['code']=='LEASE_EXPIRED'
            assert signed(9)['ok']
            assert hello({'version':1,'operation':'HELLO'})['ok']
            assert signed(9)['code']=='REQUEST_CAPACITY'
        finally:rust.close()
print(json.dumps({'result':'PASS','handshakeAndConfiguredLeaseChecks':cases,'scope':'HELLO fields/version/duplicate framing, advertised and enforced nine-second lease, and HELLO outside replay capacity; both threaded and pooled fixture server modes.'}))
