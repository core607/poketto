package io.github.core607.poketto.executor.internal;

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
        return "poketto() { python3 -c '" + SOURCE.replace("'", "'\"'\"'") + "' \"$@\"; }; export -f poketto";
    }
}
