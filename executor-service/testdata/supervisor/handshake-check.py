"""The handshake route and configured lease bounds against the native reference, including request capacity."""
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
                except Exception:expected={'ok':False,'code':'INVALID_BODY'}
                actual=c.exchange(path,raw);assert actual==expected,(mode,actual,expected)
                cases+=1;return actual
            def expect(method,target,status,code,raw=b''):
                global cases
                assert c.http(path,method,target,raw)==(status,{'ok':False,'code':code}),(method,target)
                cases+=1
            def hello():
                global cases
                status,reply=c.http(path,'GET','/v1/hello')
                assert (status,reply)==(200,python.hello()),(mode,reply)
                cases+=1;return reply
            for _ in range(3):assert hello()['ok']
            # The handshake has its own route; a request body is always a signed envelope.
            for version in (1,True):
                raw=json.dumps({'version':version,'operation':'HELLO'}).encode()
                assert c.exchange(path,raw)=={'ok':False,'code':'INVALID_REQUEST'};cases+=1
            assert check(b'{"version":1,"operation":"HELLO","version":1}')['code']=='INVALID_BODY'
            expect('GET','/v1/hello?x=1',404,'NOT_FOUND')
            expect('POST','/v1/hello',404,'NOT_FOUND',b'{}')
            expect('GET','/v1/requests',404,'NOT_FOUND')
            expect('GET','/v1/hello',400,'INVALID_HTTP',b'{}')
            def signed(seconds):
                payload=c.payload(settings);payload['expiresAt']=payload['issuedAt']+seconds
                return check(c.envelope(key,payload))
            assert signed(9)['ok']
            assert signed(10)['code']=='LEASE_EXPIRED'
            assert signed(9)['ok']
            assert hello()['ok']
            assert signed(9)['code']=='REQUEST_CAPACITY'
        finally:rust.close()
print(json.dumps({'result':'PASS','handshakeAndConfiguredLeaseChecks':cases,'scope':'Handshake route and fields, routes and bodies refused, advertised and enforced nine-second lease, and the handshake outside replay capacity; both threaded and pooled fixture server modes.'}))
