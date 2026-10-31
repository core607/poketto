package io.github.core607.poketto.executor.internal;

/** The repository CLI is consumer code, carried by Poketto rather than compiled into Sandcrab. */
final class NativeRepositoryCli {
    private static final String SOURCE = """
            import argparse,json,subprocess,sys
            parser=argparse.ArgumentParser(prog='poketto')
            commands=parser.add_subparsers(dest='operation',required=True)
            commands.add_parser('status')
            commands.add_parser('recover')
            save=commands.add_parser('save')
            save.add_argument('paths',nargs='*')
            save.add_argument('--delete',action='append',default=[])
            args=parser.parse_args()
            arguments={}
            if args.operation=='save':
                if not args.paths and not args.delete:
                    parser.error('save requires selected files or explicit --delete paths')
                arguments={'writes':args.paths,'deletes':args.delete}
            request=json.dumps({'operation':args.operation,'arguments':arguments}).encode()
            result=subprocess.run(['sandcrab','call','-'],input=request,stdout=subprocess.PIPE)
            sys.stdout.buffer.write(result.stdout)
            if result.returncode:
                sys.exit(result.returncode)
            reply=json.loads(result.stdout)
            sys.exit(0 if reply.get('ok') is True else 1)
            """;

    private NativeRepositoryCli() {}

    static String install() {
        return "poketto() { python3 -c '" + SOURCE.replace("'", "'\"'\"'") + "' \"$@\"; }; export -f poketto";
    }
}
