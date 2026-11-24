package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogSource;
import com.droidenx.clouseau.api.LogSourceProvider;
import com.droidenx.clouseau.api.SourceSetting;
import com.droidenx.clouseau.core.NetworkLogSource;
import com.droidenx.clouseau.core.NetworkLogSource.Listen;
import com.droidenx.clouseau.core.NetworkLogSource.Protocol;

import java.util.List;
import java.util.Map;

/**
 * The built-in source behind File → Open TCP/UDP Listener… (UC-20, D-30): a
 * {@link NetworkLogSource} from a protocol, a port and where to listen. A provider like a
 * plugin's, so the settings form, remembered values, Reconnect and sessions work the same
 * way; it isn't a PF4J extension and isn't listed with the plugins' sources.
 *
 * <p>A choice's value is the label shown, as for any source. Where to listen is read back
 * as "any computer" only when it is exactly that label (or {@code ALL}); anything else,
 * such as a label from an older wording, means this computer only, the safe side.
 */
final class NetworkSourceProvider implements LogSourceProvider {

    static final String PROTOCOL = "protocol";
    static final String PORT = "port";
    static final String LISTEN = "listen";
    static final int DEFAULT_PORT = 5140;

    @Override
    public String getName() {
        return Messages.get("receive.name");
    }

    @Override
    public List<SourceSetting> settings() {
        return List.of(
                SourceSetting.choice(PROTOCOL, Messages.get("receive.protocol"), List.of("TCP", "UDP")),
                SourceSetting.number(PORT, Messages.get("receive.port")).withDefault(String.valueOf(DEFAULT_PORT)),
                SourceSetting.choice(LISTEN, Messages.get("receive.listen"),
                        List.of(Messages.get("receive.listen.local"), Messages.get("receive.listen.all"))));
    }

    @Override
    public LogSource create(Map<String, String> values) {
        return new NetworkLogSource(protocol(values), port(values), listen(values));
    }

    static Protocol protocol(Map<String, String> values) {
        return "UDP".equalsIgnoreCase(values.get(PROTOCOL)) ? Protocol.UDP : Protocol.TCP;
    }

    static int port(Map<String, String> values) {
        String text = values.getOrDefault(PORT, String.valueOf(DEFAULT_PORT)).strip();
        int port;
        try {
            port = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            port = -1;
        }
        if (port < 1 || port > 65_535) throw new IllegalArgumentException(Messages.get("receive.port.range"));
        return port;
    }

    /** All interfaces only when asked for by name or by its label; anything else is this computer only. */
    static Listen listen(Map<String, String> values) {
        String v = values.get(LISTEN);
        return Listen.ALL.name().equals(v) || Messages.get("receive.listen.all").equals(v) ? Listen.ALL : Listen.LOCAL;
    }
}
