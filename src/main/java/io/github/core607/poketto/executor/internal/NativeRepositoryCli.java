package io.github.core607.poketto.executor.internal;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** The repository CLI is consumer code, carried by Poketto rather than compiled into Sandcrab. */
final class NativeRepositoryCli {
    private static final String SOURCE = """
            import argparse,base64,hashlib,json,subprocess,sys
            parser=argparse.ArgumentParser(prog='poketto')
            commands=parser.add_subparsers(dest='operation',required=True)
            status=commands.add_parser('status',help='Show local acknowledged state; does not inspect Git HEAD')
            status.add_argument('--remote',action='store_true',help='Also fetch current remote main and compare it with the host baseline')
            commands.add_parser('receipt',help='Read a complete retained receipt, including details omitted by status').add_argument('kind',choices=['save','import'])
            commands.add_parser('recover')
            save=commands.add_parser('save')
            save.add_argument('paths',nargs='*')
            save.add_argument('--delete',action='append',default=[])
            sync=commands.add_parser('sync',help='Prepare candidates without replacing working files; edit, then accept explicit hashes')
            syncing=sync.add_subparsers(dest='sync_operation')
            syncing.add_parser('prepare')
            sync.set_defaults(sync_operation='prepare')
            accepted=syncing.add_parser('accept',help='Verify selected working bytes and acknowledge only their remote baselines')
            accepted.add_argument('plan_id')
            accepted.add_argument('--expect',action='append',default=[],metavar='PATH=SHA256')
            accepted.add_argument('--absent',action='append',default=[],metavar='PATH')
            move=commands.add_parser('move',help='Prepare repaired links and index; edit and accept paths before the remote move')
            moving=move.add_subparsers(dest='move_operation',required=True)
            prepared=moving.add_parser('prepare')
            prepared.add_argument('source')
            prepared.add_argument('destination')
            moved=moving.add_parser('accept',help='Record per-path candidate verification; the last acceptance requests the saved remote move')
            moved.add_argument('plan_id')
            moved.add_argument('--expect',action='append',default=[],metavar='PATH=SHA256')
            moved.add_argument('--absent',action='append',default=[],metavar='PATH')
            moving.add_parser('recover',help='Reconcile the same retained remote attempt').add_argument('plan_id')
            plan=commands.add_parser('plan').add_subparsers(dest='plan_operation',required=True)
            shown=plan.add_parser('show',help='Page prepared paths, hashes and candidate locations')
            shown.add_argument('plan_id')
            shown.add_argument('--offset',type=int,default=0)
            shown.add_argument('--limit',type=int,default=64)
            recovered=plan.add_parser('file',help='Recover an immutable original or candidate to an absent output path')
            recovered.add_argument('plan_id')
            recovered.add_argument('path')
            recovered.add_argument('--version',choices=['original','candidate'],required=True)
            recovered.add_argument('--output',required=True)
            released=plan.add_parser('release',help='Delete retained application plan backups; keep working files and accepted baselines')
            released.add_argument('plan_id')
            artifact=commands.add_parser('artifact').add_subparsers(dest='artifact_operation',required=True)
            create=artifact.add_parser('create',help='Capture a temporary file for get_artifact')
            create.add_argument('path')
            create.add_argument('--type',dest='media_type',default='application/octet-stream')
            remove=artifact.add_parser('remove')
            remove.add_argument('artifact_id')
            media=commands.add_parser('media').add_subparsers(dest='media_operation',required=True)
            listing=media.add_parser('list')
            listing.add_argument('--prefix',default='')
            listing.add_argument('--offset',type=int,default=0)
            listing.add_argument('--limit',type=int,default=100)
            listing.add_argument('--index-version')
            listing.add_argument('--commit')
            fetch=media.add_parser('fetch',help='Download an original without replacing different local bytes')
            fetch.add_argument('path')
            fetch.add_argument('--commit')
            fetch.add_argument('--output')
            imported=media.add_parser('import',help='Store an original and propose an index entry; edit the index explicitly')
            imported.add_argument('file')
            imported.add_argument('--path',dest='logical_path',required=True)
            imported.add_argument('--type',dest='media_type',default='application/octet-stream')
            imported.add_argument('--key',required=True)
            imported.add_argument('--replace',action='store_true')
            linked=media.add_parser('link',help='Propose an existing original as an index entry; edit the index explicitly')
            linked.add_argument('path')
            linked.add_argument('--asset',required=True)
            linked.add_argument('--revision',required=True)
            linked.add_argument('--replace',action='store_true')
            args=parser.parse_args()
            arguments={}
            operation=args.operation
            if operation=='status' and args.remote:
                arguments={'remote':True}
            elif args.operation=='save':
                if not args.paths and not args.delete:
                    parser.error('save requires selected files or explicit --delete paths')
                arguments={'writes':args.paths,'deletes':args.delete}
            elif operation in ('sync','move'):
                action=args.sync_operation if operation=='sync' else args.move_operation
                operation=operation+'_'+action
                if action=='accept':
                    expected=[]
                    for value in args.expect:
                        if '=' not in value:
                            parser.error('--expect requires PATH=SHA256')
                        path,digest=value.rsplit('=',1)
                        expected.append({'path':path,'sha256':digest})
                    expected.extend({'path':path,'sha256':'absent'} for path in args.absent)
                    arguments={'planId':args.plan_id,'files':expected}
                elif operation=='move_prepare':
                    arguments={'source':args.source,'destination':args.destination}
                elif operation=='move_recover':
                    arguments={'planId':args.plan_id}
            elif operation=='plan':
                operation='plan_'+args.plan_operation
                arguments={'planId':args.plan_id}
                if args.plan_operation=='show':
                    arguments.update(offset=args.offset,limit=args.limit)
                elif args.plan_operation=='file':
                    arguments.update(path=args.path,version=args.version,output=args.output)
            elif operation=='artifact':
                operation='artifact_'+args.artifact_operation
                arguments=({'path':args.path,'mediaType':args.media_type} if args.artifact_operation=='create'
                           else {'artifactId':args.artifact_id})
            elif operation=='media':
                operation='media_'+args.media_operation
                if args.media_operation=='list':
                    arguments={'prefix':args.prefix,'offset':args.offset,'limit':args.limit,
                               'indexVersion':args.index_version,'commit':args.commit}
                elif args.media_operation=='fetch':
                    arguments={'path':args.path,'commit':args.commit,'output':args.output}
                elif args.media_operation=='import':
                    arguments={'file':args.file,'path':args.logical_path,'mediaType':args.media_type,
                               'key':args.key,'replace':args.replace}
                else:
                    arguments={'path':args.path,'assetId':args.asset,'revision':args.revision,'replace':args.replace}
            def call(operation,arguments):
                request=json.dumps({'operation':operation,'arguments':arguments}).encode()
                if len(request)>65536:
                    parser.error('host request exceeds 64 KiB')
                result=subprocess.run(['sandcrab','call','-'],input=request,stdout=subprocess.PIPE)
                if result.returncode:
                    sys.stdout.buffer.write(result.stdout)
                    sys.exit(result.returncode)
                return result.stdout
            if operation=='receipt':
                content=bytearray()
                digest=None
                while True:
                    raw=call('receipt',{'kind':args.kind,'offset':len(content),'limit':16384,'sha256':digest})
                    reply=json.loads(raw)
                    if reply.get('ok') is not True:
                        sys.stdout.buffer.write(raw)
                        sys.exit(1)
                    page=reply['result']
                    if page['offset']!=len(content) or (digest is not None and digest!=page['sha256']):
                        parser.error('receipt changed during delivery')
                    digest=page['sha256']
                    chunk=base64.b64decode(page['base64'],validate=True)
                    content.extend(chunk)
                    if not chunk or len(content)>65536 or page['bytes']>65536:
                        parser.error('invalid receipt delivery length')
                    if page['nextOffset'] is None:
                        if len(content)!=page['bytes'] or hashlib.sha256(content).hexdigest()!=digest:
                            parser.error('receipt delivery is incomplete')
                        sys.stdout.buffer.write(content+b'\\n')
                        sys.exit(0)
                    if page['nextOffset']!=len(content):
                        parser.error('invalid receipt continuation')
            raw=call(operation,arguments)
            sys.stdout.buffer.write(raw)
            reply=json.loads(raw)
            sys.exit(0 if reply.get('ok') is True else 1)
            """;

    private NativeRepositoryCli() {}

    static String install() {
        String encoded = Base64.getEncoder().encodeToString(SOURCE.getBytes(StandardCharsets.UTF_8));
        String installer = """
                import base64,os,sys,tempfile
                os.umask(0o077)
                directory=tempfile.mkdtemp(prefix='poketto-cli.',dir='/tmp')
                client=os.path.join(directory,'poketto')
                descriptor=os.open(client,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o700)
                with os.fdopen(descriptor,'wb') as output:
                    output.write(('#!'+sys.executable+' -I\\n').encode()+base64.b64decode('%s'))
                print(directory)
                """.formatted(encoded);
        // The source and its imports never come from the user's current directory. PATH is
        // published only after a complete executable exists in this session's private /tmp.
        return "poketto_cli_directory=$(python3 -I -c '" + installer.replace("'", "'\"'\"'")
                + "') && export PATH=\"$poketto_cli_directory${PATH:+:$PATH}\" && unset poketto_cli_directory";
    }
}
