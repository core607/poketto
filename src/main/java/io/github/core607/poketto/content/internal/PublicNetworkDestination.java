package io.github.core607.poketto.content.internal;

import java.io.IOException;
import java.net.InetAddress;

/** Resolves fixed provider hosts before opening a request; private address answers fail closed. */
final class PublicNetworkDestination {
    private PublicNetworkDestination() {}

    static void requirePublic(String host) throws IOException {
        InetAddress[] addresses = InetAddress.getAllByName(host);
        if (addresses.length == 0) {
            throw new IOException("Provider address is unavailable");
        }
        for (InetAddress address : addresses) {
            if (!isPublic(address)) {
                throw new IOException("Provider address is not public");
            }
        }
    }

    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        int first = Byte.toUnsignedInt(bytes[0]);
        int second = Byte.toUnsignedInt(bytes[1]);
        if (bytes.length == 4) {
            return first != 0
                    && first < 224
                    && !(first == 100 && second >= 64 && second <= 127)
                    && !(first == 198 && (second == 18 || second == 19));
        }
        return bytes.length == 16 && (first & 0xe0) == 0x20 && !(first == 0x20 && second == 0x02);
    }
}
