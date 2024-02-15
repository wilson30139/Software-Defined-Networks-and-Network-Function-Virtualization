/*
 * Copyright 2024-present Open Networking Foundation
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
package nycu.sdnfv.vrouter;

import static org.onosproject.net.config.NetworkConfigEvent.Type.CONFIG_ADDED;
import static org.onosproject.net.config.NetworkConfigEvent.Type.CONFIG_UPDATED;
import static org.onosproject.net.config.basics.SubjectFactories.APP_SUBJECT_FACTORY;

import org.onlab.packet.IpAddress;
import org.onlab.packet.IpPrefix;
import org.onlab.packet.Ethernet;
import org.onlab.packet.MacAddress;
import org.onlab.packet.IPv4;
import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.FilteredConnectPoint;
import org.onosproject.net.Host;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketService;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.config.ConfigFactory;
import org.onosproject.net.config.NetworkConfigEvent;
import org.onosproject.net.config.NetworkConfigListener;
import org.onosproject.net.config.NetworkConfigRegistry;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.host.HostService;
import org.onosproject.net.intent.IntentService;
import org.onosproject.net.intent.PointToPointIntent;
import org.onosproject.net.intent.MultiPointToSinglePointIntent;
import org.onosproject.net.intf.InterfaceService;
import org.onosproject.net.intf.Interface;
import org.onosproject.routeservice.RouteService;
import org.onosproject.routeservice.ResolvedRoute;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Dictionary;
import java.util.Properties;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;

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

    private ApplicationId appId;
    
    private final VirtualRouterConfigListener virtualRouterConfigListener = new VirtualRouterConfigListener();
    
    private final ConfigFactory<ApplicationId, VirtualRouterConfig> virtualRouterConfigFactory = new ConfigFactory<ApplicationId, VirtualRouterConfig>(APP_SUBJECT_FACTORY, VirtualRouterConfig.class, "router") {
        @Override
        public VirtualRouterConfig createConfig() {
            return new VirtualRouterConfig();
        }
    };
    
    private VirtualRouterPacketProcessor virtualRouterPacketProcessor = new VirtualRouterPacketProcessor();

    private ConnectPoint quaggaConnectedPoint;

    private MacAddress quaggaMacAddress;

    private IpAddress virtualRouterIpAddress;

    private MacAddress virtualRouterMacAddress;

    private List<IpAddress> externalRouterIpAddressList;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ComponentConfigService cfgService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected NetworkConfigRegistry networkConfigRegistry;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected RouteService routeService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected IntentService intentService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected InterfaceService interfaceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected HostService hostService;

    @Activate
    protected void activate() {
        cfgService.registerProperties(getClass());
        appId = coreService.registerApplication("nycu.sdnfv.vrouter");
        networkConfigRegistry.addListener(virtualRouterConfigListener);
        networkConfigRegistry.registerConfigFactory(virtualRouterConfigFactory);
        packetService.addProcessor(virtualRouterPacketProcessor, PacketProcessor.director(6));
        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        cfgService.unregisterProperties(getClass(), false);
        networkConfigRegistry.removeListener(virtualRouterConfigListener);
        networkConfigRegistry.unregisterConfigFactory(virtualRouterConfigFactory);
        packetService.removeProcessor(virtualRouterPacketProcessor);
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

    private class VirtualRouterConfigListener implements NetworkConfigListener {
        @Override
        public void event(NetworkConfigEvent event) {
            if((event.type() == CONFIG_ADDED || event.type() == CONFIG_UPDATED) && event.configClass().equals(VirtualRouterConfig.class)) {
                // Network Config
                GetNetworkConfigInformation();

                // BGP Traffic
                for (IpAddress externalRouterIpAddress : externalRouterIpAddressList) {
                    Interface externalRouterInterface = interfaceService.getMatchingInterface(externalRouterIpAddress);
                    ConnectPoint externalRouterConnectedPoint = externalRouterInterface.connectPoint();
                    IpAddress quaggaIpAddress = externalRouterInterface.ipAddressesList().get(0).ipAddress();
                    // Outgoing
                    InstallIntentForBgpTraffic(quaggaConnectedPoint, externalRouterConnectedPoint, externalRouterIpAddress);
                    // Incoming
                    InstallIntentForBgpTraffic(externalRouterConnectedPoint, quaggaConnectedPoint, quaggaIpAddress);
                }
            }
        }
    }

    private void GetNetworkConfigInformation() {
        VirtualRouterConfig virtualRouterConfig = networkConfigRegistry.getConfig(appId, VirtualRouterConfig.class);

        quaggaConnectedPoint = virtualRouterConfig.GetQuaggaConnectedPoint();
        log.info("Quagga Connected Point: {}", quaggaConnectedPoint.toString());

        quaggaMacAddress = virtualRouterConfig.GetQuaggaMacAddress();
        log.info("Quagga MAC Address: {}", quaggaMacAddress.toString());

        virtualRouterIpAddress = virtualRouterConfig.GetVirtualRouterIpAddress();
        log.info("Virtual Router IP Address: {}", virtualRouterIpAddress.toString());

        virtualRouterMacAddress = virtualRouterConfig.GetVirtualRouterMacAddress();
        log.info("Virtual Router MAC Address: {}", virtualRouterMacAddress.toString());

        externalRouterIpAddressList = virtualRouterConfig.GetExternalRouterIpAddressList();
        log.info("External Router IP Address List: {}", externalRouterIpAddressList.toString());
    }

    private class VirtualRouterPacketProcessor implements PacketProcessor {
        @Override
        public void process(PacketContext packetContext) {
            if (packetContext.isHandled()) {
                return;
            }

            InboundPacket inboundPacket = packetContext.inPacket();
            ConnectPoint senderConnectedPoint = inboundPacket.receivedFrom();
            Ethernet frame = inboundPacket.parsed();

            if (frame.getEtherType() == Ethernet.TYPE_ARP) {
                return;
            }

            IPv4 packet = (IPv4) frame.getPayload();
            IpAddress senderSourceIpAddress = IpAddress.valueOf(packet.getSourceAddress());
            IpAddress senderDestinationIpAddress = IpAddress.valueOf(packet.getDestinationAddress());
            
            /*
            -----------------------------------------------------------------------------------------------------------------------------------------
            |                                         |              Route Table Entry              |                      Host                     |
            |         Virtual Router Workflow         -----------------------------------------------------------------------------------------------
            |                                         | Destination IP Address  | Source IP Address | Destination MAC Address | Source MAC Address  |
            -----------------------------------------------------------------------------------------------------------------------------------------
            | BGP Traffic                             |            X            |         X         |            O            |          O          |
            -----------------------------------------------------------------------------------------------------------------------------------------
            | SDN-External Traffic (SDN => External)  |            O            |         X         |            X            |          O          |
            -----------------------------------------------------------------------------------------------------------------------------------------
            | SDN-External Traffic (External => SDN)  |            X            |         O         |            O            |          X          |
            -----------------------------------------------------------------------------------------------------------------------------------------
            | Transit Traffic                         |            O            |         O         |            X            |          X          |
            -----------------------------------------------------------------------------------------------------------------------------------------
            */
            
            Optional<ResolvedRoute> routeTableEntryInformationForSenderSourceIpAddress = routeService.longestPrefixLookup(senderSourceIpAddress);
            Optional<ResolvedRoute> routeTableEntryInformationForSenderDestinationIpAddress = routeService.longestPrefixLookup(senderDestinationIpAddress);
            if (routeTableEntryInformationForSenderDestinationIpAddress.isPresent()) {
                IpAddress externalRouterIpAddress = routeTableEntryInformationForSenderDestinationIpAddress.get().nextHop();
                Interface externalRouterInterface = interfaceService.getMatchingInterface(externalRouterIpAddress);
                ConnectPoint externalRouterConnectedPoint = externalRouterInterface.connectPoint();

                Set<Host> externalRouterSet = hostService.getHostsByIp(externalRouterIpAddress);
                Host[] externalRouterArray = externalRouterSet.toArray(new Host[0]);
                MacAddress externalRouterMacAddress = externalRouterArray[0].mac();
                
                if (routeTableEntryInformationForSenderSourceIpAddress.isPresent()) {
                    // Transit Traffic
                    InstallIntentForTransitTraffic(externalRouterConnectedPoint, senderDestinationIpAddress, quaggaMacAddress, externalRouterMacAddress);
                } else {
                    // SDN-External Traffic (SDN => External)
                    InstallIntentForSdnExternalTraffic(senderConnectedPoint, externalRouterConnectedPoint, senderDestinationIpAddress, quaggaMacAddress, externalRouterMacAddress);
                }
                packetContext.block();
            }

            Set<Host> sdnHostSet = hostService.getHostsByIp(senderDestinationIpAddress);
            Host[] sdnHostArray = sdnHostSet.toArray(new Host[0]);
            MacAddress sdnHostMacAddress = sdnHostArray[0].mac();
            if (sdnHostMacAddress != null) {
                if (routeTableEntryInformationForSenderSourceIpAddress.isPresent()) {
                    // SDN-External Traffic (External => SDN)
                    ConnectPoint sdnHostConnectedPoint = new ConnectPoint(sdnHostArray[0].location().deviceId(), sdnHostArray[0].location().port());
                    InstallIntentForSdnExternalTraffic(senderConnectedPoint, sdnHostConnectedPoint, senderDestinationIpAddress, virtualRouterMacAddress, sdnHostMacAddress);
                    packetContext.block();
                }
            }
        }
    }

    private void InstallIntentForBgpTraffic(ConnectPoint ingressConnectPoint, ConnectPoint egressConnectPoint, IpAddress destinationIpAddress) {
        FilteredConnectPoint ingressFilteredConnectPoint = new FilteredConnectPoint(ingressConnectPoint);
        FilteredConnectPoint egressFilteredConnectPoint = new FilteredConnectPoint(egressConnectPoint);
        TrafficSelector selector = DefaultTrafficSelector.builder()
        .matchEthType(Ethernet.TYPE_IPV4)
        .matchIPDst(IpPrefix.valueOf(destinationIpAddress, 32))
        .build();
        PointToPointIntent intent = PointToPointIntent.builder()
        .appId(appId)
        .filteredIngressPoint(ingressFilteredConnectPoint)
        .filteredEgressPoint(egressFilteredConnectPoint)
        .selector(selector)
        .build();
        intentService.submit(intent);
    }

    private void InstallIntentForSdnExternalTraffic(ConnectPoint ingressConnectPoint, ConnectPoint egressConnectPoint, IpAddress destinationIpAddress, MacAddress sourceMacAddress, MacAddress destinationMacAddress) {
        FilteredConnectPoint ingressFilteredConnectPoint = new FilteredConnectPoint(ingressConnectPoint);
        FilteredConnectPoint egressFilteredConnectPoint = new FilteredConnectPoint(egressConnectPoint);
        TrafficSelector selector = DefaultTrafficSelector.builder()
        .matchEthType(Ethernet.TYPE_IPV4)
        .matchIPDst(IpPrefix.valueOf(destinationIpAddress, 32))
        .build();
        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
        .setEthSrc(sourceMacAddress)
        .setEthDst(destinationMacAddress)
        .build();
        PointToPointIntent intent = PointToPointIntent.builder()
        .appId(appId)
        .filteredIngressPoint(ingressFilteredConnectPoint)
        .filteredEgressPoint(egressFilteredConnectPoint)
        .selector(selector)
        .treatment(treatment)
        .build();
        intentService.submit(intent);
    }

    private void InstallIntentForTransitTraffic(ConnectPoint egressConnectPoint, IpAddress destinationIpAddress, MacAddress sourceMacAddress, MacAddress destinationMacAddress) {
        Set<FilteredConnectPoint> ingressFilteredConnectPointSet = new HashSet<>();
        for (IpAddress externalRouterIpAddress : externalRouterIpAddressList) {
            Interface externalRouterInterface = interfaceService.getMatchingInterface(externalRouterIpAddress);
            ConnectPoint externalRouterConnectedPoint = externalRouterInterface.connectPoint();
            if (externalRouterConnectedPoint == egressConnectPoint) {
                continue;
            }
            FilteredConnectPoint externalRouterFilteredConnectedPoint = new FilteredConnectPoint(externalRouterConnectedPoint);
            ingressFilteredConnectPointSet.add(externalRouterFilteredConnectedPoint);
        }
        FilteredConnectPoint egressFilteredConnectPoint = new FilteredConnectPoint(egressConnectPoint);
        TrafficSelector selector = DefaultTrafficSelector.builder()
        .matchEthType(Ethernet.TYPE_IPV4)
        .matchIPDst(IpPrefix.valueOf(destinationIpAddress, 24))
        .build();
        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
        .setEthSrc(sourceMacAddress)
        .setEthDst(destinationMacAddress)
        .build();
        MultiPointToSinglePointIntent intent = MultiPointToSinglePointIntent.builder()
        .appId(appId)
        .filteredIngressPoints(ingressFilteredConnectPointSet)
        .filteredEgressPoint(egressFilteredConnectPoint)
        .selector(selector)
        .treatment(treatment)
        .build();
        intentService.submit(intent);
    }
}
