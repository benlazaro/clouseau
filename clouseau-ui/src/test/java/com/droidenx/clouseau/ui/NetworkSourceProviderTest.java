package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.SourceSetting;
import com.droidenx.clouseau.core.NetworkLogSource;
import com.droidenx.clouseau.core.NetworkLogSource.Listen;
import com.droidenx.clouseau.core.NetworkLogSource.Protocol;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The receiver's form values (UC-20): what they create, and that unclear ones stay local. */
class NetworkSourceProviderTest {

    private final NetworkSourceProvider provider = new NetworkSourceProvider();

    @Test
    void offersTcpOnTheDefaultPortForThisComputerFirst() {
        assertThat(provider.settings()).extracting(SourceSetting::key)
                .containsExactly(NetworkSourceProvider.PROTOCOL, NetworkSourceProvider.PORT, NetworkSourceProvider.LISTEN);
        assertThat(provider.settings().get(0).defaultValue()).isEqualTo("TCP");
        assertThat(provider.settings().get(1).defaultValue()).isEqualTo("5140");
        assertThat(provider.settings().get(2).defaultValue()).isEqualTo(Messages.get("receive.listen.local"));
    }

    @Test
    void createsTheReceiverTheFormDescribes() {
        NetworkLogSource s = (NetworkLogSource) provider.create(Map.of(
                NetworkSourceProvider.PROTOCOL, "UDP",
                NetworkSourceProvider.PORT, " 9999 ",
                NetworkSourceProvider.LISTEN, Messages.get("receive.listen.all")));

        assertThat(s.protocol()).isEqualTo(Protocol.UDP);
        assertThat(s.port()).isEqualTo(9999);
        assertThat(s.listen()).isEqualTo(Listen.ALL);
    }

    @Test
    void anUnknownListenValueMeansThisComputerOnly() {
        assertThat(NetworkSourceProvider.listen(Map.of(NetworkSourceProvider.LISTEN, "Everywhere"))).isEqualTo(Listen.LOCAL);
        assertThat(NetworkSourceProvider.listen(Map.of())).isEqualTo(Listen.LOCAL);
        assertThat(NetworkSourceProvider.listen(Map.of(NetworkSourceProvider.LISTEN, "ALL"))).isEqualTo(Listen.ALL);
    }

    @Test
    void refusesAPortOutOfRangeWithAMessageForTheUser() {
        assertThatThrownBy(() -> provider.create(Map.of(NetworkSourceProvider.PORT, "70000")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(Messages.get("receive.port.range"));
        assertThatThrownBy(() -> provider.create(Map.of(NetworkSourceProvider.PORT, "0")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theTooltipSaysWhoCanSend() {
        assertThat(MainFrame.receiverTooltip(Map.of(NetworkSourceProvider.PORT, "5140")))
                .isEqualTo("Receiving TCP on port 5140 from this computer only");
    }
}
