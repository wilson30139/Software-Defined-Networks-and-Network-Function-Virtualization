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
package nctu.winlab.ProxyArp;

import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.edge.EdgePortService;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.packet.DefaultOutboundPacket;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.OutboundPacket;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketPriority;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.onlab.packet.Ip4Address;
import org.onlab.packet.MacAddress;
import org.onlab.packet.ARP;
import org.onlab.packet.Ethernet;

import java.nio.ByteBuffer;
import java.util.Dictionary;
import java.util.Properties;
import java.util.Map;
import java.util.HashMap;

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

    private ProxyArpPacketProcessor processor = new ProxyArpPacketProcessor();

    private Map<Ip4Address, MacAddress> arpTable = new HashMap<>();

    private Map<Ip4Address, ConnectPoint> hostInformation = new HashMap<>();

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ComponentConfigService cfgService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected EdgePortService edgePortService;

    @Activate
    protected void activate() {
        cfgService.registerProperties(getClass());
        appId = coreService.registerApplication("nctu.winlab.ProxyArp");
        packetService.addProcessor(processor, PacketProcessor.director(2));
        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        cfgService.unregisterProperties(getClass(), false);
        packetService.removeProcessor(processor);
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

    private class ProxyArpPacketProcessor implements PacketProcessor {
        @Override
        public void process(PacketContext packetContext) {
            if (packetContext.isHandled()) {
                return;
            }

            InboundPacket inboundPacket = packetContext.inPacket();
            ConnectPoint senderConnectPoint = inboundPacket.receivedFrom();
            Ethernet requestFrame = inboundPacket.parsed();
            ARP arpRequestPacket = (ARP) requestFrame.getPayload();

            Ip4Address senderProtocolAddress = Ip4Address.valueOf(arpRequestPacket.getSenderProtocolAddress());
            MacAddress senderHardwareAddress = MacAddress.valueOf(arpRequestPacket.getSenderHardwareAddress());
            Ip4Address targetProtocolAddress = Ip4Address.valueOf(arpRequestPacket.getTargetProtocolAddress());
            MacAddress targetHardwareAddress = MacAddress.valueOf(arpRequestPacket.getTargetHardwareAddress());
            if (!arpTable.containsKey(senderProtocolAddress)) {
                arpTable.put(senderProtocolAddress, senderHardwareAddress);
            }
            if (!hostInformation.containsKey(senderProtocolAddress)) {
                hostInformation.put(senderProtocolAddress, senderConnectPoint);
            }

            short operationCode = arpRequestPacket.getOpCode();
            if (operationCode == ARP.OP_REQUEST) {
                if (arpTable.containsKey(targetProtocolAddress)) {
                    TrafficTreatment treatment = DefaultTrafficTreatment.builder().setOutput(senderConnectPoint.port()).build();
                    targetHardwareAddress = arpTable.get(targetProtocolAddress);
                    log.info("TABLE HIT. Requested MAC = {}", targetHardwareAddress);
                    Ethernet replyFrame = ARP.buildArpReply(targetProtocolAddress, targetHardwareAddress, requestFrame);
                    OutboundPacket outboundPacket = new DefaultOutboundPacket(senderConnectPoint.deviceId(), treatment, ByteBuffer.wrap(replyFrame.serialize()));
                    packetService.emit(outboundPacket);
                } else {
                    log.info("TABLE MISS. Send request to edge ports");
                    for (ConnectPoint everyConnectPoint: edgePortService.getEdgePoints()) {
                        if (everyConnectPoint.equals(senderConnectPoint)) {
                            continue;
                        }
                        TrafficTreatment treatment = DefaultTrafficTreatment.builder().setOutput(everyConnectPoint.port()).build();
                        OutboundPacket outboundPacket = new DefaultOutboundPacket(everyConnectPoint.deviceId(), treatment, ByteBuffer.wrap(requestFrame.serialize()));
                        packetService.emit(outboundPacket);
                    }
                }
            } else if (operationCode == ARP.OP_REPLY) {
                log.info("RECV REPLY. Requested MAC = {}", senderHardwareAddress);
                ConnectPoint receiverConnectPoint = hostInformation.get(targetProtocolAddress);
                TrafficTreatment treatment = DefaultTrafficTreatment.builder().setOutput(receiverConnectPoint.port()).build();
                OutboundPacket outboundPacket = new DefaultOutboundPacket(receiverConnectPoint.deviceId(), treatment, ByteBuffer.wrap(requestFrame.serialize()));
                packetService.emit(outboundPacket);
            }
        }
    }
}
