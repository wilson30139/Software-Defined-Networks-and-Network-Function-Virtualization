/*
 * Copyright 2023-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nctu.winlab.unicastdhcp;

import static org.onosproject.net.config.NetworkConfigEvent.Type.CONFIG_ADDED;
import static org.onosproject.net.config.NetworkConfigEvent.Type.CONFIG_UPDATED;
import static org.onosproject.net.config.basics.SubjectFactories.APP_SUBJECT_FACTORY;

import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.net.FilteredConnectPoint;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.intent.IntentService;
import org.onosproject.net.intent.PointToPointIntent;
import org.onosproject.net.intent.Intent;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketService;
import org.onosproject.net.packet.PacketPriority;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.config.ConfigFactory;
import org.onosproject.net.config.NetworkConfigEvent;
import org.onosproject.net.config.NetworkConfigListener;
import org.onosproject.net.config.NetworkConfigRegistry;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.onlab.packet.Ethernet;
import org.onlab.packet.MacAddress;
import org.onlab.packet.IPv4;
import org.onlab.packet.UDP;
import org.onlab.packet.TpPort;

import java.util.Dictionary;
import java.util.Properties;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.List;

import static org.onlab.util.Tools.get;

/**
 * Skeletal ONOS application component.
 */
@Component(immediate = true,
           service = {SomeInterface.class},
           property = {
               "someProperty=Some Default String Value",
           })
public class AppComponent implements SomeInterface {

    private final Logger log = LoggerFactory.getLogger(getClass());

    /** Some configurable property. */
    private String someProperty;

    private final DhcpServerConfigListener dhcpServerConfigListener = new DhcpServerConfigListener();

    private final ConfigFactory<ApplicationId, DhcpConfig> dhcpServerConfigFactory =
            new ConfigFactory<ApplicationId, DhcpConfig>(
            APP_SUBJECT_FACTORY, DhcpConfig.class, "UnicastDhcpConfig") {
        @Override
        public DhcpConfig createConfig() {
            return new DhcpConfig();
        }
    };

    private ApplicationId appId;

    private DeviceId dhcpServerConnectedSwitchID;

    private PortNumber dhcpServerConnectedPortNumber;

    private DhcpUnicastPacketProcessor dhcpUnicastPacketProcessor = new DhcpUnicastPacketProcessor();

    private List<PointToPointIntent> installedIntents = new ArrayList<>();

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ComponentConfigService cfgService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected NetworkConfigRegistry networkConfigRegistry;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected IntentService intentService;

    @Activate
    protected void activate() {
        cfgService.registerProperties(getClass());
        appId = coreService.registerApplication("nctu.winlab.unicastdhcp");
        networkConfigRegistry.addListener(dhcpServerConfigListener);
        networkConfigRegistry.registerConfigFactory(dhcpServerConfigFactory);
        packetService.addProcessor(dhcpUnicastPacketProcessor, PacketProcessor.director(2));
        requestIntercepts();
        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        cfgService.unregisterProperties(getClass(), false);
        networkConfigRegistry.removeListener(dhcpServerConfigListener);
        networkConfigRegistry.unregisterConfigFactory(dhcpServerConfigFactory);
        packetService.removeProcessor(dhcpUnicastPacketProcessor);
        withdrawIntercepts();
        cleanAllIntents();
        log.info("Stopped");
    }

    @Modified
    public void modified(ComponentContext context) {
        Dictionary<?, ?> properties = context != null ? context.getProperties() : new Properties();
        if (context != null) {
            someProperty = get(properties, "someProperty");
        }
        log.info("Reconfigured");
    }

    @Override
    public void someMethod() {
        log.info("Invoked");
    }

    private class DhcpServerConfigListener implements NetworkConfigListener {
        @Override
        public void event(NetworkConfigEvent event) {
            if ((event.type() == CONFIG_ADDED || event.type() == CONFIG_UPDATED)
              && event.configClass().equals(DhcpConfig.class)) {
                DhcpConfig dhcpConfig = networkConfigRegistry.getConfig(appId, DhcpConfig.class);
                if (dhcpConfig != null) {
                    String[] dhcpServerLocation = dhcpConfig.name().split("/");
                    dhcpServerConnectedSwitchID = DeviceId.deviceId(dhcpServerLocation[0]);
                    dhcpServerConnectedPortNumber = PortNumber.portNumber(dhcpServerLocation[1]);
                    log.info("DHCP server is connected to `{}`, port `{}`",
                      dhcpServerConnectedSwitchID, dhcpServerConnectedPortNumber);
                }
            }
        }
    }

    private class DhcpUnicastPacketProcessor implements PacketProcessor {
        @Override
        public void process(PacketContext packetContext) {

            if (packetContext.isHandled()) {
                return;
            }

            InboundPacket inboundPacket = packetContext.inPacket();
            DeviceId sourceSwitchID = inboundPacket.receivedFrom().deviceId();
            PortNumber inPortNumber = inboundPacket.receivedFrom().port();

            Ethernet frame = inboundPacket.parsed();
            MacAddress sourceMacAddress = frame.getSourceMAC();
            MacAddress destinationMacAddress = frame.getDestinationMAC();

            if (!(sourceSwitchID.equals(dhcpServerConnectedSwitchID) &&
              inPortNumber.equals(dhcpServerConnectedPortNumber))) {
                ConnectPoint ingressConnectPoint = new ConnectPoint(sourceSwitchID, inPortNumber);
                ConnectPoint egressConnectPoint =
                new ConnectPoint(dhcpServerConnectedSwitchID, dhcpServerConnectedPortNumber);

                FilteredConnectPoint ingressFilteredConnectPoint = new FilteredConnectPoint(ingressConnectPoint);
                FilteredConnectPoint egressFilteredConnectPoint = new FilteredConnectPoint(egressConnectPoint);

                TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
                selector.matchEthSrc(sourceMacAddress);
                selector.matchEthType(Ethernet.TYPE_IPV4);
                selector.matchIPProtocol(IPv4.PROTOCOL_UDP);
                selector.matchUdpSrc(TpPort.tpPort(UDP.DHCP_CLIENT_PORT));
                selector.matchUdpDst(TpPort.tpPort(UDP.DHCP_SERVER_PORT));

                PointToPointIntent intent = PointToPointIntent.builder()
                  .appId(appId)
                  .filteredIngressPoint(ingressFilteredConnectPoint)
                  .filteredEgressPoint(egressFilteredConnectPoint)
                  .selector(selector.build())
                  .build();

                if (!installedIntents.contains(intent)) {
                    installedIntents.add(intent);
                    intentService.submit(intent);
                    log.info("Intent `{}`, port `{}` => `{}`, port `{}` is submitted.",
                    intent.filteredIngressPoint().connectPoint().deviceId(),
                    intent.filteredIngressPoint().connectPoint().port(),
                    intent.filteredEgressPoint().connectPoint().deviceId(),
                    intent.filteredEgressPoint().connectPoint().port());
                }

                ingressConnectPoint = new ConnectPoint(dhcpServerConnectedSwitchID, dhcpServerConnectedPortNumber);
                egressConnectPoint = new ConnectPoint(sourceSwitchID, inPortNumber);

                ingressFilteredConnectPoint = new FilteredConnectPoint(ingressConnectPoint);
                egressFilteredConnectPoint = new FilteredConnectPoint(egressConnectPoint);

                selector = DefaultTrafficSelector.builder();
                selector.matchEthDst(sourceMacAddress);
                selector.matchEthType(Ethernet.TYPE_IPV4);
                selector.matchIPProtocol(IPv4.PROTOCOL_UDP);
                selector.matchUdpSrc(TpPort.tpPort(UDP.DHCP_SERVER_PORT));
                selector.matchUdpDst(TpPort.tpPort(UDP.DHCP_CLIENT_PORT));

                intent = PointToPointIntent.builder()
                  .appId(appId)
                  .filteredIngressPoint(ingressFilteredConnectPoint)
                  .filteredEgressPoint(egressFilteredConnectPoint)
                  .selector(selector.build())
                  .build();

                if (!installedIntents.contains(intent)) {
                    installedIntents.add(intent);
                    intentService.submit(intent);
                    log.info("Intent `{}`, port `{}` => `{}`, port `{}` is submitted.",
                      intent.filteredIngressPoint().connectPoint().deviceId(),
                      intent.filteredIngressPoint().connectPoint().port(),
                      intent.filteredEgressPoint().connectPoint().deviceId(),
                      intent.filteredEgressPoint().connectPoint().port());
                }
            }
        }
    }

    private void requestIntercepts() {
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        selector.matchEthType(Ethernet.TYPE_IPV4)
          .matchIPProtocol(IPv4.PROTOCOL_UDP)
          .matchUdpSrc(TpPort.tpPort(UDP.DHCP_CLIENT_PORT))
          .matchUdpDst(TpPort.tpPort(UDP.DHCP_SERVER_PORT));
        packetService.requestPackets(selector.build(), PacketPriority.REACTIVE, appId);

        selector = DefaultTrafficSelector.builder();
        selector.matchEthType(Ethernet.TYPE_IPV4)
          .matchIPProtocol(IPv4.PROTOCOL_UDP)
          .matchUdpSrc(TpPort.tpPort(UDP.DHCP_SERVER_PORT))
          .matchUdpDst(TpPort.tpPort(UDP.DHCP_CLIENT_PORT));
        packetService.requestPackets(selector.build(), PacketPriority.REACTIVE, appId);
    }

    private void withdrawIntercepts() {
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        selector.matchEthType(Ethernet.TYPE_IPV4)
          .matchIPProtocol(IPv4.PROTOCOL_UDP)
          .matchUdpSrc(TpPort.tpPort(UDP.DHCP_CLIENT_PORT))
          .matchUdpDst(TpPort.tpPort(UDP.DHCP_SERVER_PORT));
        packetService.requestPackets(selector.build(), PacketPriority.REACTIVE, appId);

        selector = DefaultTrafficSelector.builder();
        selector.matchEthType(Ethernet.TYPE_IPV4)
          .matchIPProtocol(IPv4.PROTOCOL_UDP)
          .matchUdpSrc(TpPort.tpPort(UDP.DHCP_SERVER_PORT))
          .matchUdpDst(TpPort.tpPort(UDP.DHCP_CLIENT_PORT));
        packetService.requestPackets(selector.build(), PacketPriority.REACTIVE, appId);
    }

    private void cleanAllIntents() {
        Iterable<Intent> allIntents = intentService.getIntents();
        Iterator<Intent> iteratorAllIntents = allIntents.iterator();
        Intent intent;
        while (iteratorAllIntents.hasNext()) {
            intent = iteratorAllIntents.next();
            intentService.withdraw(intent);
        }

        iteratorAllIntents = allIntents.iterator();
        while (iteratorAllIntents.hasNext()) {
            intent = iteratorAllIntents.next();
            intentService.purge(intent);
        }
    }
}
